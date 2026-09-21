//! One decoding session: the sniffer, the collected player data, and what a
//! frame produced.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use anyhow::Result;
use auto_artifactarium::{
    GameCommand, GamePacket, GameSniffer, KeyOrigin, matches_achievement_packet,
    matches_avatar_packet, matches_item_packet,
};
use base64::Engine;
use irminsul::player_data::{ExportSettings, PlayerData};
use tracing::info;

use crate::achievements::{AchievementExport, AchievementFormat};
use crate::cache::DecodedCache;
use crate::samples::SampleStore;
use crate::source::prepare_frame;
use crate::status::{StatusPayload, command_summary, status_json};

/// What one frame produced. A frame with no game commands in it produces nothing.
pub struct PacketOutcome {
    /// Addresses the packet's commands in the decoded cache.
    pub id: u64,
    /// The status JSON contract (see [`crate::status`]).
    pub status: serde_json::Value,
    /// Set on exactly one frame: the first in which all three data categories
    /// have been seen. A host reports completion from this, not from the counts.
    pub completed: Option<CompletionCounts>,
}

/// The counts to report when a collection completes.
pub struct CompletionCounts {
    pub artifacts: usize,
    pub weapons: usize,
    pub materials: usize,
    pub characters: usize,
    pub achievements: usize,
}

/// Which of the three collectible categories have been seen this session.
#[derive(Default)]
struct Collected {
    items: bool,
    avatars: bool,
    achievements: bool,
}

impl Collected {
    /// Fold one command into the tally, storing its payload if it is a data dump.
    fn absorb(&mut self, command: &GameCommand, player_data: &mut PlayerData) {
        if let Some(items) = matches_item_packet(command) {
            info!(count = items.len(), "matched item packet");
            player_data.process_items(&items);
            self.items = true;
        } else if let Some(avatars) = matches_avatar_packet(command) {
            info!(count = avatars.len(), "matched avatar packet");
            player_data.process_characters(&avatars);
            self.avatars = true;
        } else if let Some(achievements) = matches_achievement_packet(command) {
            info!(count = achievements.len(), "matched achievement packet");
            player_data.process_achievements(&achievements);
            self.achievements = true;
        }
    }

    fn complete(&self) -> bool {
        self.items && self.avatars && self.achievements
    }
}

pub struct Session {
    sniffer: GameSniffer,
    player_data: PlayerData,
    samples: SampleStore,
    cache: DecodedCache,
    collected: Collected,
    completion_notified: bool,
}

impl Session {
    /// Start a session, reloading any known-body samples kept under `samples_dir`.
    ///
    /// Those samples are what let a session whose handshake we never saw be
    /// decrypted at all, so passing the directory is what lets a front end open a
    /// later, blind session after an openable one.
    pub fn new(samples_dir: Option<&Path>) -> Result<Self> {
        let mut sniffer = GameSniffer::new().set_initial_keys(dispatch_keys()?);
        let mut samples = SampleStore::open(samples_dir);
        for body in samples.load() {
            sniffer = sniffer.add_known_body(body);
        }

        Ok(Self {
            sniffer,
            player_data: PlayerData::new(irminsul::game_data()?),
            samples,
            cache: DecodedCache::new(),
            collected: Collected::default(),
            completion_notified: false,
        })
    }

    /// Clear collection progress without discarding collected data, so a second
    /// capture in the same process can complete on its own terms.
    pub fn reset(&mut self) {
        self.collected = Collected::default();
        self.completion_notified = false;
    }

    /// How the key currently decrypting this session was obtained, or `None`
    /// when nothing has opened it yet.
    ///
    /// Session state rather than a field of the per-packet payload: a frame that
    /// decodes nothing produces no payload at all, and "the client re-logged in
    /// and we lost the key" is precisely such a frame. Reading it after feeding
    /// each packet is therefore the only way to notice a session go blind.
    pub fn key_origin(&self) -> Option<KeyOrigin> {
        self.sniffer.key_origin()
    }

    /// Copy this session's persisted known-body samples into `dest_dir` and
    /// report where the copy landed, or `None` when there are none yet.
    ///
    /// A host that cannot read the app's own files dir (`run-as` refused) still
    /// gets to move the samples somewhere pullable — which is what makes a blind
    /// capture from another device, or another day, decodable here.
    pub fn export_known_bodies(&self, dest_dir: &Path) -> Result<Option<PathBuf>> {
        Ok(self.samples.export_to(dest_dir)?)
    }

    /// Feed one captured IP frame — from a live tunnel or a replayed file.
    pub fn feed(&mut self, raw_frame: &[u8]) -> Option<PacketOutcome> {
        let prepared = prepare_frame(raw_frame)?;
        let Some(GamePacket::Commands(commands)) = self.sniffer.receive_packet(prepared) else {
            return None;
        };

        // This frame may have handed us a body that opens a later session we
        // cannot seed. Rewriting the file is free until the set actually changes.
        self.samples
            .store_if_changed(self.sniffer.known_bodies());

        let flat = flatten(&commands);

        let mut summaries = Vec::with_capacity(flat.len());
        for (command, parent) in &flat {
            // Only the summary is produced here; the full body is serialized on
            // demand from the cached command.
            summaries.push(command_summary(command, *parent));
            self.collected.absorb(command, &mut self.player_data);
        }
        let cached: Vec<GameCommand> = flat.into_iter().map(|(command, _)| command).collect();
        let packet_id = self.cache.push(cached);

        let counts = CompletionCounts {
            artifacts: self.player_data.artifact_count(),
            weapons: self.player_data.weapon_count(),
            materials: self.player_data.material_count(),
            characters: self.player_data.character_count(),
            achievements: self.player_data.achievement_count(),
        };
        let status = status_json(&StatusPayload {
            packet_id,
            has_items: self.collected.items,
            has_avatars: self.collected.avatars,
            has_achievements: self.collected.achievements,
            artifact_count: counts.artifacts,
            weapon_count: counts.weapons,
            material_count: counts.materials,
            character_count: counts.characters,
            achievement_count: counts.achievements,
            commands: &summaries,
        });

        // Sticky flags would otherwise re-notify on every later frame.
        let completed = if self.completion_notified || !self.collected.complete() {
            None
        } else {
            self.completion_notified = true;
            Some(counts)
        };

        Some(PacketOutcome {
            id: packet_id,
            status,
            completed,
        })
    }

    /// The full body JSON of a cached command, or `None` once it has been evicted.
    /// `index` is its position in that packet's `commands` array.
    pub fn command_body(&self, id: u64, index: usize) -> Option<serde_json::Value> {
        self.cache.command(id, index).map(GameCommand::to_json)
    }

    /// The undecoded proto bytes of a cached command, base64'd.
    ///
    /// For whoever wants to compare one command's body across game versions — the
    /// bytes are the thing that changed, and re-serializing them through a schema
    /// would hide that.
    pub fn command_body_base64(&self, id: u64, index: usize) -> Option<String> {
        self.cache
            .command(id, index)
            .map(|command| base64::engine::general_purpose::STANDARD.encode(&command.proto_data))
    }

    pub fn player_data(&self) -> &PlayerData {
        &self.player_data
    }

    pub fn export_good(&self, settings: &ExportSettings) -> Result<String> {
        self.player_data.export_genshin_optimizer(settings)
    }

    pub fn export_achievements(&self, format: AchievementFormat) -> Result<String> {
        self.player_data.export_achievements(format)
    }
}

/// Unroll batch envelopes into the list a front end shows.
///
/// Depth-first, so each command is immediately followed by the ones it carried and
/// an index identifies a parent unambiguously. Worth doing because batches are
/// not rare: on the reference captures more commands arrive inside
/// `UnionCmdNotify` than outside it.
fn flatten(commands: &[GameCommand]) -> Vec<(GameCommand, Option<usize>)> {
    let mut flat = Vec::with_capacity(commands.len());
    flatten_into(commands, None, &mut flat);
    flat
}

fn flatten_into(
    commands: &[GameCommand],
    parent: Option<usize>,
    flat: &mut Vec<(GameCommand, Option<usize>)>,
) {
    for command in commands {
        let index = flat.len();
        flat.push((command.clone(), parent));
        let children = command.children();
        if !children.is_empty() {
            flatten_into(&children, Some(index), flat);
        }
    }
}

/// The per-version dispatch keys the handshake is wrapped in.
///
/// Public because `pcap-check` drives [`GameSniffer`] directly and needs the same
/// map the [`Session`] builds from; baking a second copy into that harness is how
/// the two first drifted apart.
pub fn dispatch_keys() -> Result<HashMap<u16, Vec<u8>>> {
    let keys_json: HashMap<u16, String> = serde_json::from_slice(include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../irminsul-core/keys/gi.json"
    )))?;
    keys_json
        .into_iter()
        .map(|(id, key)| {
            let decoded = base64::engine::general_purpose::STANDARD
                .decode(&key)
                .map_err(|e| anyhow::anyhow!("failed to decode dispatch key {id}: {e}"))?;
            Ok((id, decoded))
        })
        .collect()
}
