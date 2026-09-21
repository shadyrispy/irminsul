//! JNI bridge between the Android app and the `irminsul` core crate.
//!
//! Built with `cargo ndk -t arm64-v8a build --release` from this directory;
//! the resulting `libirminsul.so` is copied into the `capture` module's
//! `src/main/jniLibs`.

pub mod achievements;
mod known_bodies;
pub mod uiaf;

use std::collections::HashMap;
use std::collections::VecDeque;
use std::path::Path;
use std::sync::Once;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::Mutex;

use anyhow::Result;
use auto_artifactarium::{
    GameCommand, GamePacket, GameSniffer, PacketDirection, matches_achievement_packet,
    matches_avatar_packet, matches_item_packet,
};
use base64::Engine;
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong, jstring};

use crate::achievements::{AchievementExport, AchievementFormat};
use crate::known_bodies::KnownBodyStore;
use irminsul::player_data::{ExportSettings, PlayerData};

// ---------------------------------------------------------------------------
// Global state (thread-safe via Mutex)
// ---------------------------------------------------------------------------

struct SnifferState {
    sniffer: GameSniffer,
    player_data: PlayerData,
    /// Where the sniffer's known-plaintext samples are kept between runs.
    known_bodies: KnownBodyStore,
    has_items: bool,
    has_avatars: bool,
    has_achievements: bool,
    /// Guards the completion callback: the three flags above are sticky, so
    /// without this every packet after completion would re-notify Kotlin.
    completion_notified: bool,
}

static GLOBAL_STATE: Mutex<Option<SnifferState>> = Mutex::new(None);
static JAVA_VM: Mutex<Option<jni::JavaVM>> = Mutex::new(None);

// ---------------------------------------------------------------------------
// Decoded-command cache for the packet detail view
// ---------------------------------------------------------------------------
//
// `nativeProcessPacket` returns only lightweight command summaries; the full
// proto body JSON is produced on demand by `nativeCommandBody` from the raw
// bytes cached here, keyed by the packet id assigned at processing time.
// The sniffer is stateful (session keys, stream reassembly), so packets
// cannot be re-decoded later — caching raw bytes is the only way to defer
// full serialization. Eviction is by total cached proto bytes.

const CMD_CACHE_MAX_BYTES: usize = 64 * 1024 * 1024;

struct CachedCommand {
    command_id: u16,
    header_len: u16,
    direction: PacketDirection,
    proto_data: Vec<u8>,
}

struct CachedPacket {
    id: u64,
    commands: Vec<CachedCommand>,
}

static CMD_CACHE: Mutex<VecDeque<CachedPacket>> = Mutex::new(VecDeque::new());
static CMD_CACHE_BYTES: AtomicUsize = AtomicUsize::new(0);
static NEXT_PACKET_ID: AtomicU64 = AtomicU64::new(0);

fn cached_bytes(commands: &[CachedCommand]) -> usize {
    commands.iter().map(|c| c.proto_data.len()).sum()
}

fn push_cached_packet(packet: CachedPacket) {
    let mut cache = match CMD_CACHE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    CMD_CACHE_BYTES.fetch_add(cached_bytes(&packet.commands), Ordering::Relaxed);
    cache.push_back(packet);
    while CMD_CACHE_BYTES.load(Ordering::Relaxed) > CMD_CACHE_MAX_BYTES {
        let Some(evicted) = cache.pop_front() else {
            break;
        };
        CMD_CACHE_BYTES.fetch_sub(cached_bytes(&evicted.commands), Ordering::Relaxed);
    }
}

fn clear_cached_packets() {
    let mut cache = match CMD_CACHE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    cache.clear();
    CMD_CACHE_BYTES.store(0, Ordering::Relaxed);
}

const PCAPDROID_TRAILER_SIZE: usize = 32;

// Lock helpers that never panic in the JNI boundary. A poisoned mutex (a
// panic occurred while holding it elsewhere) or a contended lock must not
// crash the whole Android process; return None instead.
fn lock_java_vm() -> Option<std::sync::MutexGuard<'static, Option<jni::JavaVM>>> {
    match JAVA_VM.lock() {
        Ok(guard) => Some(guard),
        Err(poisoned) => Some(poisoned.into_inner()),
    }
}

fn lock_global_state() -> Option<std::sync::MutexGuard<'static, Option<SnifferState>>> {
    match GLOBAL_STATE.lock() {
        Ok(guard) => Some(guard),
        Err(poisoned) => Some(poisoned.into_inner()),
    }
}

// ---------------------------------------------------------------------------
// Sniffer tracing → logcat
// ---------------------------------------------------------------------------

/// The vendored sniffer logs through `tracing`, which had no subscriber in this
/// process — every `warn!`/`error!` about handshakes, dispatch keys and failed
/// bruteforce vanished, leaving live capture undiagnosable. Route those into the
/// same channel as the JNI's own logs.
///
/// INFO and above only, and truncated: `trace!` carries a multi-kilobyte hex dump
/// per packet, and forwarding it exhausts the Java heap — an `OutOfMemoryError`
/// left pending, and the next JNI call turned a lost log into `SIGABRT` in the
/// decode thread. INFO is where the key decisions are reported (handshake reset,
/// seed recovered, session key deduced), so it stays.
///
/// Note `Level`'s `Ord` is by severity with the *least* severe largest, so
/// "this level or more severe" reads `<=`, not `>=`.
static TRACING_INIT: Once = Once::new();

const MAX_SNIFF_LOG_LEN: usize = 300;

fn init_sniffer_tracing() {
    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::Layer;

    TRACING_INIT.call_once(|| {
        let layer = LogcatLayer.with_filter(tracing_subscriber::filter::filter_fn(|meta| {
            meta.target().starts_with("auto_artifactarium") && *meta.level() <= tracing::Level::INFO
        }));
        let subscriber = tracing_subscriber::registry().with(layer);
        let _ = tracing::subscriber::set_global_default(subscriber);
    });
}

struct LogcatLayer;

impl<S: tracing::Subscriber> tracing_subscriber::layer::Layer<S> for LogcatLayer {
    fn on_event(&self, event: &tracing::Event<'_>, _ctx: tracing_subscriber::layer::Context<'_, S>) {
        let mut visitor = LogcatVisitor::default();
        event.record(&mut visitor);
        let mut line = format!("{}: {}", event.metadata().target(), visitor.finish());
        if line.len() > MAX_SNIFF_LOG_LEN {
            let mut end = MAX_SNIFF_LOG_LEN;
            while !line.is_char_boundary(end) {
                end -= 1;
            }
            line.truncate(end);
            line.push('…');
        }
        log_to_android("SNIFF", &line);
    }
}

#[derive(Default)]
struct LogcatVisitor {
    message: String,
    fields: String,
}

impl LogcatVisitor {
    fn finish(&self) -> String {
        if self.fields.is_empty() {
            self.message.clone()
        } else {
            format!("{}{}", self.message, self.fields)
        }
    }
}

impl tracing::field::Visit for LogcatVisitor {
    fn record_str(&mut self, field: &tracing::field::Field, value: &str) {
        self.record(field, &value);
    }

    fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn std::fmt::Debug) {
        self.record(field, &format!("{:?}", value));
    }
}

impl LogcatVisitor {
    fn record(&mut self, field: &tracing::field::Field, value: &dyn std::fmt::Display) {
        if field.name() == "message" {
            self.message = value.to_string();
        } else {
            self.fields.push_str(&format!(" {}={}", field.name(), value));
        }
    }
}

// ---------------------------------------------------------------------------
// Logging helper
// ---------------------------------------------------------------------------

fn log_to_android(level: &str, message: &str) {
    let guard = match lock_java_vm() {
        Some(g) => g,
        None => return,
    };
    if let Some(vm) = guard.as_ref() {
        if let Ok(mut env) = vm.attach_current_thread() {
            // A pending Java exception — an OOM raised by the log path itself, for
            // instance — makes the *next* JNI call abort the process. Clear it and
            // drop the log line; a lost log must never take the capture with it.
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
                return;
            }
            let msg_str = format!("[{}] {}", level, message);
            if let Ok(msg) = env.new_string(&msg_str) {
                if let Ok(class) = env.find_class("com/esc/irminsul/capture/internal/NativeLib") {
                    if env
                        .call_static_method(
                            class,
                            "log",
                            "(Ljava/lang/String;)V",
                            &[(&msg).into()],
                        )
                        .is_err()
                    {
                        let _ = env.exception_clear();
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Key loading
// ---------------------------------------------------------------------------

fn load_keys() -> Result<HashMap<u16, Vec<u8>>> {
    let keys_json: HashMap<u16, String> = serde_json::from_slice(include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../irminsul-core/keys/gi.json"
    )))?;
    keys_json
        .into_iter()
        .map(|(k, v)| {
            let decoded = base64::engine::general_purpose::STANDARD
                .decode(&v)
                .map_err(|e| anyhow::anyhow!("Failed to decode base64 key {}: {}", k, e))?;
            Ok((k, decoded))
        })
        .collect()
}

// ---------------------------------------------------------------------------
// Packet preparation (same logic as irminsul-android)
// ---------------------------------------------------------------------------

fn extract_and_prepare_packet(data: &[u8]) -> Option<Vec<u8>> {
    if data.is_empty() {
        return None;
    }

    let mut packet_data = data;

    // Strip PCAPdroid trailer
    if packet_data.len() >= PCAPDROID_TRAILER_SIZE {
        let trailer_start = packet_data.len() - PCAPDROID_TRAILER_SIZE;
        let trailer = &packet_data[trailer_start..];
        if trailer[0] == 0x01 && trailer[1] == 0x00 {
            packet_data = &packet_data[..trailer_start];
        }
    }

    if packet_data.is_empty() {
        return None;
    }

    let first_byte = packet_data[0];

    // IPv4 — prepend fake Ethernet header with EtherType 0x0800
    if (first_byte >> 4) == 0x04 {
        let mut fake_eth = Vec::with_capacity(14 + packet_data.len());
        fake_eth.extend_from_slice(&[0x00; 6]); // dst MAC
        fake_eth.extend_from_slice(&[0x00; 6]); // src MAC
        fake_eth.extend_from_slice(&[0x08, 0x00]); // EtherType: IPv4
        fake_eth.extend_from_slice(packet_data);
        Some(fake_eth)
    }
    // IPv6 — prepend fake Ethernet header with EtherType 0x86DD
    else if (first_byte & 0xF0) == 0x60 {
        let mut fake_eth = Vec::with_capacity(14 + packet_data.len());
        fake_eth.extend_from_slice(&[0x00; 6]);
        fake_eth.extend_from_slice(&[0x00; 6]);
        fake_eth.extend_from_slice(&[0x86, 0xDD]); // EtherType: IPv6
        fake_eth.extend_from_slice(packet_data);
        Some(fake_eth)
    } else {
        // Already has link-layer header
        Some(packet_data.to_vec())
    }
}

// ---------------------------------------------------------------------------
// Database initialization
// ---------------------------------------------------------------------------

fn init_player_data() -> Result<PlayerData> {
    // Game data is bundled by `core`'s build script.
    Ok(PlayerData::new(irminsul::game_data()?))
}

// ---------------------------------------------------------------------------
// ExportSettings parsing from JSON
// ---------------------------------------------------------------------------

fn parse_export_settings(json: &str) -> ExportSettings {
    match serde_json::from_str(json) {
        Ok(settings) => settings,
        Err(e) => {
            log_to_android(
                "WARN",
                &format!("Failed to parse export settings, using defaults: {}", e),
            );
            ExportSettings::default()
        }
    }
}

// ---------------------------------------------------------------------------
// JNI functions
// ---------------------------------------------------------------------------

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeInitLogging(
    env: JNIEnv,
    _class: JClass,
) {
    let vm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            log_to_android("ERROR", &format!("Failed to get JavaVM: {}", e));
            return;
        }
    };
    if let Some(mut guard) = lock_java_vm() {
        *guard = Some(vm);
    }
    log_to_android("INFO", "Native library initialized");
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeCreateSniffer(
    mut env: JNIEnv,
    _class: JClass,
    storage_dir: JString,
) -> jint {
    let dir = match env.get_string(&storage_dir) {
        Ok(dir) => Path::new(&String::from(dir)).to_path_buf(),
        Err(_) => {
            log_to_android("ERROR", "nativeCreateSniffer needs a usable storage dir");
            return -4;
        }
    };

    let mut state = match lock_global_state() {
        Some(s) => s,
        None => {
            log_to_android("ERROR", "Failed to lock GLOBAL_STATE");
            return -3;
        }
    };

    init_sniffer_tracing();

    let keys = match load_keys() {
        Ok(k) => k,
        Err(e) => {
            log_to_android("ERROR", &format!("Failed to load keys: {}", e));
            return -1;
        }
    };

    // Samples from earlier runs are what let a session whose seed we never saw
    // be opened at all, so they are loaded before the first packet.
    let mut known_bodies = KnownBodyStore::open(&dir);
    let samples = known_bodies.load();
    let mut sniffer = GameSniffer::new().set_initial_keys(keys);
    for body in samples {
        sniffer = sniffer.add_known_body(body);
    }

    let player_data = match init_player_data() {
        Ok(pd) => pd,
        Err(e) => {
            log_to_android("ERROR", &format!("Failed to init player data: {}", e));
            return -2;
        }
    };

    *state = Some(SnifferState {
        sniffer,
        player_data,
        known_bodies,
        has_items: false,
        has_avatars: false,
        has_achievements: false,
        completion_notified: false,
    });

    log_to_android("INFO", "Sniffer created successfully");
    0
}

/// One captured packet's report for the Kotlin side: collection progress plus a
/// lightweight summary of every command it carried.
///
/// The JSON keys produced below are a contract with `StatusDecoder` in the
/// Kotlin half of this module. `capture/testdata/summary_status.json` is the
/// single fixture both halves test against, so a rename on either side fails a
/// test instead of silently reading back as zero.
struct StatusPayload {
    packet_id: u64,
    has_items: bool,
    has_avatars: bool,
    has_achievements: bool,
    artifact_count: usize,
    weapon_count: usize,
    material_count: usize,
    character_count: usize,
    achievement_count: usize,
    commands: Vec<serde_json::Value>,
}

fn status_json(payload: &StatusPayload) -> serde_json::Value {
    serde_json::json!({
        "packet_id": payload.packet_id,
        "has_items": payload.has_items,
        "has_avatars": payload.has_avatars,
        "has_achievements": payload.has_achievements,
        "artifact_count": payload.artifact_count,
        "weapon_count": payload.weapon_count,
        "material_count": payload.material_count,
        "character_count": payload.character_count,
        "achievement_count": payload.achievement_count,
        "commands": payload.commands,
    })
}

/// Reset the per-session flags without touching collected player data.
///
/// Called by the facade at the start of every capture session. The three
/// `has_*` flags and `completion_notified` are sticky, so without this a
/// second session in the same process would inherit the first one's
/// completion and never report its own.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeResetSession(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(mut guard) = lock_global_state() {
        if let Some(state) = guard.as_mut() {
            state.has_items = false;
            state.has_avatars = false;
            state.has_achievements = false;
            state.completion_notified = false;
        }
    }
}

/// Process a raw IP packet from the VPN capture.
///
/// Returns the packet's status JSON (see [`status_json`]), or null when the
/// packet carried no game commands. When the three data categories have all
/// been seen for the first time, also notifies Kotlin once via
/// `NativeLib.onDataComplete(artifactCount, weaponCount, materialCount,
///                            characterCount, achievementCount)`.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeProcessPacket(
    mut env: JNIEnv,
    _class: JClass,
    packet_data: JByteArray,
) -> jstring {
    let bytes = match env.convert_byte_array(&packet_data) {
        Ok(data) => data,
        Err(_) => return std::ptr::null_mut(),
    };

    let prepared_packet = match extract_and_prepare_packet(&bytes) {
        Some(p) => p,
        None => return std::ptr::null_mut(),
    };

    let mut state_guard = match lock_global_state() {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };
    let state = match state_guard.as_mut() {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };

    let Some(GamePacket::Commands(commands)) = state.sniffer.receive_packet(prepared_packet) else {
        return std::ptr::null_mut();
    };

    // This packet may have handed the sniffer a body it can use to open a later
    // session it cannot seed. That sample is only worth keeping if it survives
    // the app, and rewriting the file is free until the set actually changes.
    state
        .known_bodies
        .store_if_changed(state.sniffer.known_bodies());

    let packet_id = NEXT_PACKET_ID.fetch_add(1, Ordering::Relaxed);

    let mut commands_json: Vec<serde_json::Value> = Vec::with_capacity(commands.len());
    let mut cached_commands: Vec<CachedCommand> = Vec::with_capacity(commands.len());
    for command in &commands {
        // Lightweight summary only — full body JSON is produced on demand by
        // nativeCommandBody from the cached raw bytes.
        commands_json.push(command.summary_json());
        cached_commands.push(CachedCommand {
            command_id: command.command_id,
            header_len: command.header_len,
            direction: command.direction,
            proto_data: command.proto_data.clone(),
        });

        if let Some(items) = matches_item_packet(command) {
            log_to_android(
                "INFO",
                &format!("Matched item packet: {} items", items.len()),
            );
            state.player_data.process_items(&items);
            state.has_items = true;
        } else if let Some(avatars) = matches_avatar_packet(command) {
            log_to_android(
                "INFO",
                &format!("Matched avatar packet: {} avatars", avatars.len()),
            );
            state.player_data.process_characters(&avatars);
            state.has_avatars = true;
        } else if let Some(achievements) = matches_achievement_packet(command) {
            log_to_android(
                "INFO",
                &format!(
                    "Matched achievement packet: {} achievements",
                    achievements.len()
                ),
            );
            state.player_data.process_achievements(&achievements);
            state.has_achievements = true;
        }
    }

    // Build status JSON with granular counts
    let artifact_count = state.player_data.artifact_count();
    let weapon_count = state.player_data.weapon_count();
    let material_count = state.player_data.material_count();
    let character_count = state.player_data.character_count();
    let achievement_count = state.player_data.achievement_count();

    let status_json = status_json(&StatusPayload {
        packet_id,
        has_items: state.has_items,
        has_avatars: state.has_avatars,
        has_achievements: state.has_achievements,
        artifact_count,
        weapon_count,
        material_count,
        character_count,
        achievement_count,
        commands: commands_json,
    });

    let status_str = status_json.to_string();

    push_cached_packet(CachedPacket {
        id: packet_id,
        commands: cached_commands,
    });

    // Edge-triggered: the three flags are sticky, so without the guard every
    // later packet would re-notify and the host would re-post the notification.
    let just_completed = !state.completion_notified
        && state.has_items
        && state.has_avatars
        && state.has_achievements;
    if just_completed {
        state.completion_notified = true;
    }

    // Release the lock before calling back into Java
    drop(state_guard);

    if just_completed {
        notify_data_complete(
            &mut env,
            artifact_count,
            weapon_count,
            material_count,
            character_count,
            achievement_count,
        );
    }

    match env.new_string(&status_str) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Return the full JSON (including the decoded proto body) of a single
/// cached command, produced on demand for the packet detail view.
/// `packet_id` is the id assigned in `nativeProcessPacket`; `command_index`
/// is the command's position within that packet's summary array. Returns
/// null when the packet has been evicted from the cache or the index is out
/// of range.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeCommandBody(
    env: JNIEnv,
    _class: JClass,
    packet_id: jlong,
    command_index: jint,
) -> jstring {
    let cache_guard = match CMD_CACHE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    let Some(entry) = cache_guard.iter().find(|p| p.id == packet_id as u64) else {
        return std::ptr::null_mut();
    };
    let Some(cached) = entry.commands.get(command_index as usize) else {
        return std::ptr::null_mut();
    };
    let command = GameCommand {
        command_id: cached.command_id,
        header_len: cached.header_len,
        data_len: cached.proto_data.len() as u32,
        ext_header: Vec::new(),
        proto_data: cached.proto_data.clone(),
        direction: cached.direction,
    };
    drop(cache_guard);

    let Some(json) = command.to_json() else {
        return std::ptr::null_mut();
    };
    match env.new_string(json.to_string()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Export GOOD v3 JSON with optional settings.
/// If `settings_json` is null, uses default settings.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeExportGood(
    mut env: JNIEnv,
    _class: JClass,
    settings_json: jstring,
) -> jstring {
    let state_guard = match lock_global_state() {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };
    let state = match state_guard.as_ref() {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };

    let settings = if settings_json.is_null() {
        ExportSettings::default()
    } else {
        let jstr =
            jni::objects::JString::from(unsafe { jni::objects::JObject::from_raw(settings_json) });
        match env.get_string(&jstr) {
            Ok(java_str) => {
                let json_str: String = java_str.into();
                parse_export_settings(&json_str)
            }
            Err(_) => ExportSettings::default(),
        }
    };

    let json = match state.player_data.export_genshin_optimizer(&settings) {
        Ok(j) => j,
        Err(e) => {
            log_to_android("ERROR", &format!("Export GOOD failed: {}", e));
            return std::ptr::null_mut();
        }
    };

    match env.new_string(&json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Export achievements in the specified format.
/// `format_code`: 0 = UIAF, 1 = Seelie, 2 = CSV
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeExportAchievements(
    env: JNIEnv,
    _class: JClass,
    format_code: jint,
) -> jstring {
    let state_guard = match lock_global_state() {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };
    let state = match state_guard.as_ref() {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };

    let format = match format_code {
        0 => AchievementFormat::Uiaf,
        1 => AchievementFormat::Seelie,
        2 => AchievementFormat::Csv,
        _ => {
            log_to_android(
                "ERROR",
                &format!("Unknown achievement format: {}", format_code),
            );
            return std::ptr::null_mut();
        }
    };

    let json = match state.player_data.export_achievements(format) {
        Ok(j) => j,
        Err(e) => {
            log_to_android("ERROR", &format!("Export achievements failed: {}", e));
            return std::ptr::null_mut();
        }
    };

    match env.new_string(&json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeDestroySniffer(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut state = match lock_global_state() {
        Some(s) => s,
        None => return,
    };
    *state = None;
    drop(state);
    clear_cached_packets();
    log_to_android("INFO", "Sniffer destroyed");
}

// ---------------------------------------------------------------------------
// Callback: notify Kotlin that all data is collected
// ---------------------------------------------------------------------------

fn notify_data_complete(
    env: &mut JNIEnv,
    artifact_count: usize,
    weapon_count: usize,
    material_count: usize,
    character_count: usize,
    achievement_count: usize,
) {
    if let Ok(class) = env.find_class("com/esc/irminsul/capture/internal/NativeLib") {
        let _ = env.call_static_method(
            class,
            "onDataComplete",
            "(IIIII)V",
            &[
                (artifact_count as jint).into(),
                (weapon_count as jint).into(),
                (material_count as jint).into(),
                (character_count as jint).into(),
                (achievement_count as jint).into(),
            ],
        );
    }
}

// ---------------------------------------------------------------------------
// Payload contract test
// ---------------------------------------------------------------------------

#[cfg(test)]
mod contract_tests {
    use super::*;

    /// Shared with the Kotlin `StatusDecoderTest`: whoever changes the payload
    /// must update this one file, and both halves then re-verify against it.
    const FIXTURE: &str = include_str!("../../../testdata/summary_status.json");

    fn sample_payload() -> StatusPayload {
        StatusPayload {
            packet_id: 7,
            has_items: true,
            has_avatars: true,
            has_achievements: false,
            artifact_count: 1200,
            weapon_count: 150,
            material_count: 2307,
            character_count: 90,
            achievement_count: 1712,
            commands: vec![],
        }
    }

    fn keys(value: &serde_json::Value) -> Vec<String> {
        value
            .as_object()
            .unwrap_or_else(|| panic!("expected a JSON object, got {value}"))
            .keys()
            .cloned()
            .collect()
    }

    fn sorted(mut values: Vec<String>) -> Vec<String> {
        values.sort();
        values
    }

    #[test]
    fn status_json_emits_exactly_the_fixture_keys() {
        let fixture: serde_json::Value = serde_json::from_str(FIXTURE).expect("fixture parses");
        let produced = status_json(&sample_payload());
        assert_eq!(
            sorted(keys(&produced)),
            sorted(keys(&fixture)),
            "top-level payload keys drifted from capture/testdata/summary_status.json"
        );
    }

    #[test]
    fn command_summary_has_the_fixture_command_keys() {
        let fixture: serde_json::Value = serde_json::from_str(FIXTURE).expect("fixture parses");
        // An unresolvable command id takes the producer's early-return path, so
        // its key set is the stable base the decoder relies on.
        let command = GameCommand {
            command_id: 0,
            header_len: 10,
            data_len: 0,
            ext_header: vec![],
            proto_data: vec![],
            direction: PacketDirection::Received,
        };
        assert_eq!(
            sorted(keys(&command.summary_json())),
            sorted(keys(&fixture["commands"][0])),
            "command summary keys drifted from the fixture"
        );
    }

    #[test]
    fn fixture_values_have_the_types_the_decoder_reads() {
        let fixture: serde_json::Value = serde_json::from_str(FIXTURE).expect("fixture parses");
        assert!(fixture["packet_id"].is_number());
        for flag in ["has_items", "has_avatars", "has_achievements"] {
            assert!(fixture[flag].is_boolean(), "{flag} must be a bool");
        }
        for count in [
            "artifact_count",
            "weapon_count",
            "material_count",
            "character_count",
            "achievement_count",
        ] {
            assert!(fixture[count].is_number(), "{count} must be a number");
        }
        let command = &fixture["commands"][0];
        assert!(command["cmd_id"].is_number());
        assert!(command["size"].is_number());
        assert!(command["brief_keys"].is_array());
        assert_eq!(command["direction"], "received");
    }
}
