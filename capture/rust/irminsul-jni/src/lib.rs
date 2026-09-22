//! JNI bridge between the Android capture module and `irminsul-decode`.
//!
//! This is the whole Android-specific half of the pipeline and nothing else:
//! converting Java types, holding the single [`Session`] behind a mutex, routing
//! `tracing` into logcat, and calling Kotlin back. Decoding, caching, key
//! recovery and the payload contract all live in `irminsul-decode`, so the
//! desktop viewer and this bridge cannot drift apart.
//!
//! Built with `cargo ndk -t arm64-v8a build --release` from this directory;
//! the resulting `libirminsul.so` is copied into the `capture` module's
//! `src/main/jniLibs`.

use std::sync::Once;
use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};
use std::sync::atomic::{AtomicI64, Ordering};

use irminsul_decode::{AchievementFormat, ExportSettings, PcapFrames, Session, key_origin_name};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JLongArray, JString};
use jni::sys::{JNI_VERSION_1_6, jbyteArray, jint, jlong, jlongArray, jstring};

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------

/// One session at a time, matching the one VPN tunnel the module runs.
static GLOBAL_STATE: Mutex<Option<Session>> = Mutex::new(None);
static JAVA_VM: Mutex<Option<jni::JavaVM>> = Mutex::new(None);

/// pcap files opened for replay, keyed by the handle Kotlin was given. The file
/// format is parsed in exactly one place — the decode core's reader — so a
/// Kotlin-side and a Rust-side understanding of pcap cannot drift apart.
static PCAP_READERS: LazyLock<Mutex<HashMap<i64, PcapFrames>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));
static PCAP_NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

// Lock helpers that never panic at the JNI boundary. A poisoned mutex (a panic
// occurred while holding it elsewhere) or a contended lock must not crash the
// whole Android process; return None instead.
fn lock_java_vm() -> Option<std::sync::MutexGuard<'static, Option<jni::JavaVM>>> {
    match JAVA_VM.lock() {
        Ok(guard) => Some(guard),
        Err(poisoned) => Some(poisoned.into_inner()),
    }
}

fn lock_global_state() -> Option<std::sync::MutexGuard<'static, Option<Session>>> {
    match GLOBAL_STATE.lock() {
        Ok(guard) => Some(guard),
        Err(poisoned) => Some(poisoned.into_inner()),
    }
}

// ---------------------------------------------------------------------------
// tracing → logcat
// ---------------------------------------------------------------------------

/// The decode core and the vendored sniffer log through `tracing`, which has no
/// subscriber of its own in this process — every message about handshakes,
/// recovered keys and failed searches would vanish, leaving live capture
/// undiagnosable. Route them into the same channel as this bridge's own logs.
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

/// Crates whose logs are worth showing, and the tag each is filed under.
const BRIDGED_TARGETS: &[(&str, &str)] = &[
    ("auto_artifactarium", "SNIFF"),
    ("irminsul_decode", "CORE"),
];

fn init_sniffer_tracing() {
    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::Layer;

    TRACING_INIT.call_once(|| {
        let layer = LogcatLayer.with_filter(tracing_subscriber::filter::filter_fn(|meta| {
            *meta.level() <= tracing::Level::INFO && tag_for(meta.target()) != ""
        }));
        let subscriber = tracing_subscriber::registry().with(layer);
        let _ = tracing::subscriber::set_global_default(subscriber);
    });
}

/// The JVM's own hook, run by `System.loadLibrary` — the first moment a `JavaVM`
/// exists, and the last moment before any host code has called anything.
///
/// Wiring the log bridge here rather than only in `nativeInitLogging` is what keeps
/// a native line from being dropped on the floor: the bridge needs a `JavaVM` to
/// call back through and the tracing layer to route a crate's own lines, and both
/// used to appear only once a host had run its startup call. A process that reached
/// the library another way — a broadcast that started a pcap replay, say — then
/// logged nothing at all, including the reason the file could not be opened, which
/// is exactly what was measured on a device. `init_sniffer_tracing` stays called
/// from the sniffer's own entry point too; it is a `Once` either way.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn JNI_OnLoad(
    vm: jni::JavaVM,
    _reserved: *mut std::ffi::c_void,
) -> jint {
    if let Some(mut guard) = lock_java_vm() {
        if guard.is_none() {
            *guard = Some(vm);
        }
    }
    init_sniffer_tracing();
    JNI_VERSION_1_6
}

/// The logcat tag for a target, or "" when the crate is not bridged.
fn tag_for(target: &str) -> &'static str {
    BRIDGED_TARGETS
        .iter()
        .find(|(prefix, _)| target.starts_with(prefix))
        .map(|(_, tag)| *tag)
        .unwrap_or("")
}

struct LogcatLayer;

impl<S: tracing::Subscriber> tracing_subscriber::layer::Layer<S> for LogcatLayer {
    fn on_event(&self, event: &tracing::Event<'_>, _ctx: tracing_subscriber::layer::Context<'_, S>) {
        let mut visitor = LogcatVisitor::default();
        event.record(&mut visitor);
        let target = event.metadata().target();
        let mut line = format!("{}: {}", target, visitor.finish());
        if line.len() > MAX_SNIFF_LOG_LEN {
            let mut end = MAX_SNIFF_LOG_LEN;
            while !line.is_char_boundary(end) {
                end -= 1;
            }
            line.truncate(end);
            line.push('…');
        }
        log_to_android(tag_for(target), &line);
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

    fn record(&mut self, field: &tracing::field::Field, value: &dyn std::fmt::Display) {
        if field.name() == "message" {
            self.message = value.to_string();
        } else {
            self.fields.push_str(&format!(" {}={}", field.name(), value));
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

/// Create the session.
///
/// `storage_dir` is where the known-plaintext samples live: they are what lets a
/// session whose handshake was never observed be decrypted, so they have to
/// outlive the process. Non-zero returns reach `CaptureError.SnifferInitFailed`.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeCreateSniffer(
    mut env: JNIEnv,
    _class: JClass,
    storage_dir: JString,
) -> jint {
    let dir = match env.get_string(&storage_dir) {
        Ok(dir) => std::path::PathBuf::from(String::from(dir)),
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

    match Session::new(Some(&dir)) {
        Ok(session) => {
            *state = Some(session);
            log_to_android("INFO", "Sniffer created successfully");
            0
        }
        Err(e) => {
            log_to_android("ERROR", &format!("Failed to create session: {}", e));
            -1
        }
    }
}

/// Reset the per-session collection flags without touching collected player data.
///
/// Called by the facade at the start of every capture session. The category flags
/// and the completion edge are sticky, so without this a second session in the
/// same process would inherit the first one's completion and never report its own.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeResetSession(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(mut guard) = lock_global_state() {
        if let Some(session) = guard.as_mut() {
            session.reset();
        }
    }
}

/// Process one captured IP packet from the VPN.
///
/// Returns the packet's status JSON (see `irminsul_decode::status`), or null when
/// the packet carried no game commands. On the one packet where all three data
/// categories have arrived for the first time, also notifies Kotlin once via
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

    let mut state_guard = match lock_global_state() {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };
    let Some(session) = state_guard.as_mut() else {
        return std::ptr::null_mut();
    };

    let Some(outcome) = session.feed(&bytes) else {
        return std::ptr::null_mut();
    };
    let status = outcome.status.to_string();
    let completion = outcome.completed;

    // Release the lock before calling back into Java.
    drop(state_guard);

    if let Some(counts) = completion {
        notify_data_complete(
            &mut env,
            counts.artifacts,
            counts.weapons,
            counts.materials,
            counts.characters,
            counts.achievements,
        );
    }

    match env.new_string(status) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// The full body JSON of one cached command. `command_index` is its position
/// within that packet's summary array. Returns null when the packet has been
/// evicted from the cache or the index is out of range.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeCommandBody(
    env: JNIEnv,
    _class: JClass,
    packet_id: jlong,
    command_index: jint,
) -> jstring {
    let state_guard = match lock_global_state() {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };
    let json = state_guard
        .as_ref()
        .and_then(|session| session.command_body(packet_id as u64, command_index as usize));
    drop(state_guard);

    match json.map(|value| value.to_string()) {
        Some(text) => match env.new_string(text) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

/// How the key currently decrypting the session was obtained: "dispatch",
/// "known_body" or "time_search". Null when no session key exists yet — which
/// includes a client that just re-logged in, so a caller learns about going blind
/// only if it asks after every packet.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeKeyOrigin(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let state_guard = match lock_global_state() {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };
    let origin = state_guard.as_ref().and_then(|session| session.key_origin());
    drop(state_guard);

    match origin.map(key_origin_name) {
        Some(name) => match env.new_string(name) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

/// Copies this session's known-body samples into `dest_dir` and returns where the
/// copy landed, or null when there are none yet or the copy failed (logged).
///
/// A host that cannot read the app's own files dir — `run-as` is refused on some
/// images — can still pull the external storage directory, and these bytes decide
/// whether a blind capture is readable later.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeExportKnownBodies(
    mut env: JNIEnv,
    _class: JClass,
    dest_dir: JString,
) -> jstring {
    let dir = match env.get_string(&dest_dir) {
        Ok(dir) => std::path::PathBuf::from(String::from(dir)),
        Err(_) => return std::ptr::null_mut(),
    };

    let exported = {
        let state = match lock_global_state() {
            Some(state) => state,
            None => return std::ptr::null_mut(),
        };
        let Some(session) = state.as_ref() else {
            return std::ptr::null_mut();
        };
        session.export_known_bodies(&dir)
    };

    let path = match exported {
        Ok(Some(path)) => path,
        Ok(None) => return std::ptr::null_mut(),
        Err(e) => {
            log_to_android("ERROR", &format!("Failed to export known bodies: {}", e));
            return std::ptr::null_mut();
        }
    };

    match env.new_string(path.display().to_string()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Opens `path` for replay and hands back the handle `nativePcapNext` reads under.
/// Returns -1 when the file is not a readable pcap; the reason is on the log
/// stream the host already watches, so it is not repeated through the return value.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativePcapOpen(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    let path = match env.get_string(&path) {
        Ok(path) => std::path::PathBuf::from(String::from(path)),
        Err(_) => return -1,
    };
    let display = path.display().to_string();

    match PcapFrames::open(&path) {
        Ok(frames) => {
            let handle = PCAP_NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
            if let Some(mut readers) = PCAP_READERS.lock().ok() {
                readers.insert(handle, frames);
            }
            log_to_android("INFO", &format!("pcap replay opened {}", display));
            handle
        }
        Err(e) => {
            log_to_android("ERROR", &format!("Cannot open {}: {}", display, e));
            -1
        }
    }
}

/// The next frame, or null at the end of the file (or after a read error, which
/// is logged). `timestamp_out[0]` receives the file's own timestamp for the frame
/// in milliseconds, so a replay keeps the capture's clock instead of the wall
/// clock it is replayed on.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativePcapNext(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    timestamp_out: jlongArray,
) -> jbyteArray {
    /// What one pull from the reader produced. Naming "the file ended" separately
    /// matters: without it a completed replay and a bad handle look identical, and
    /// the message written for the wrong one sends whoever is debugging somewhere
    /// they do not need to go.
    enum Pulled {
        Frame(Vec<u8>, u64),
        Ended,
        Stopped(String),
        UnknownHandle,
    }

    // Take the lock, pull one frame, drop it: no upcall into Java happens while
    // the reader map is held.
    let pulled = PCAP_READERS.lock().ok().map_or(Pulled::UnknownHandle, |mut readers| {
        let Some(reader) = readers.get_mut(&handle) else {
            return Pulled::UnknownHandle;
        };
        match reader.next() {
            None => Pulled::Ended,
            Some(Ok((bytes, timestamp_ms))) => Pulled::Frame(bytes, timestamp_ms),
            Some(Err(e)) => Pulled::Stopped(e.to_string()),
        }
    });

    let (bytes, timestamp_ms) = match pulled {
        Pulled::Frame(bytes, timestamp_ms) => (bytes, timestamp_ms),
        Pulled::Ended => return std::ptr::null_mut(),
        // A short read ends the replay rather than failing it: the file gave what
        // it had, and the reason belongs on the log stream the host watches.
        Pulled::Stopped(reason) => {
            log_to_android("WARN", &format!("pcap replay stopped: {}", reason));
            return std::ptr::null_mut();
        }
        Pulled::UnknownHandle => {
            log_to_android("ERROR", &format!("unknown pcap handle {}", handle));
            return std::ptr::null_mut();
        }
    };

    let out = unsafe { JLongArray::from_raw(timestamp_out) };
    if let Err(e) = env.set_long_array_region(&out, 0, &[timestamp_ms as jlong]) {
        log_to_android("ERROR", &format!("cannot report pcap timestamp: {}", e));
        return std::ptr::null_mut();
    }
    match env.byte_array_from_slice(&bytes) {
        Ok(array) => array.into_raw(),
        Err(e) => {
            log_to_android("ERROR", &format!("cannot hand over pcap frame: {}", e));
            std::ptr::null_mut()
        }
    }
}

/// Ends a replay. Kotlin must call this even when the loop was interrupted, or
/// the file handle stays parked in the map for the life of the process.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativePcapClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Ok(mut readers) = PCAP_READERS.lock() {
        readers.remove(&handle);
    }
}

/// Export GOOD v3 JSON. If `settings_json` is null, uses default settings.
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
    let Some(session) = state_guard.as_ref() else {
        return std::ptr::null_mut();
    };

    let settings = if settings_json.is_null() {
        ExportSettings::default()
    } else {
        let jstr = JString::from(unsafe { jni::objects::JObject::from_raw(settings_json) });
        match env.get_string(&jstr) {
            Ok(java_str) => parse_export_settings(&String::from(java_str)),
            Err(_) => ExportSettings::default(),
        }
    };

    let exported = session.export_good(&settings);
    drop(state_guard);

    match exported {
        Ok(json) => match env.new_string(json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(e) => {
            log_to_android("ERROR", &format!("Export GOOD failed: {}", e));
            std::ptr::null_mut()
        }
    }
}

/// Export achievements. `format_code`: 0 = UIAF, 1 = Seelie, 2 = CSV.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeExportAchievements(
    env: JNIEnv,
    _class: JClass,
    format_code: jint,
) -> jstring {
    let format = match format_code {
        0 => AchievementFormat::Uiaf,
        1 => AchievementFormat::Seelie,
        2 => AchievementFormat::Csv,
        other => {
            log_to_android("ERROR", &format!("Unknown achievement format: {}", other));
            return std::ptr::null_mut();
        }
    };

    let state_guard = match lock_global_state() {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };
    let exported = state_guard
        .as_ref()
        .map(|session| session.export_achievements(format));
    drop(state_guard);

    let Some(exported) = exported else {
        return std::ptr::null_mut();
    };

    match exported {
        Ok(json) => match env.new_string(json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(e) => {
            log_to_android("ERROR", &format!("Export achievements failed: {}", e));
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_esc_irminsul_capture_internal_NativeLib_nativeDestroySniffer(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(mut state) = lock_global_state() {
        *state = None;
    }
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

/// A bad settings blob is not worth failing an export over, but it is worth
/// saying out loud: the exporter would otherwise quietly use defaults.
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
