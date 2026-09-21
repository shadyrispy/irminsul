//! Persistence for the command bodies the sniffer keeps as known plaintexts.
//!
//! Those bodies are what allow a later session — one whose key cannot be derived
//! from a seed, because the client re-authenticated inside a process we did not
//! see start — to be decrypted at all. They stay valid until the game changes
//! them, which is roughly once per version, so they have to survive the app.
//!
//! The file is rewritten only when the sniffer notes a body it did not have,
//! which is a handful of times per game version rather than per packet.

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

const MAGIC: &[u8; 8] = b"IRMSKBDY";
const FORMAT_VERSION: u32 = 1;
/// A body longer than this means the file is not ours.
const MAX_BODY_LEN: u32 = 4 * 1024 * 1024;
const MAX_BODIES: usize = 16;

pub struct KnownBodyStore {
    path: PathBuf,
    /// Body lengths as they are on disk right now, so an unchanged sniffer does
    /// not rewrite the file on every packet.
    written: Vec<usize>,
}

impl KnownBodyStore {
    pub fn open(dir: &Path) -> Self {
        Self {
            path: dir.join("known_bodies.bin"),
            written: Vec::new(),
        }
    }

    /// The bodies saved by an earlier run. A file that fails to parse is not an
    /// error worth surfacing: a sniffer with no samples still works for every
    /// session it can seed, so the worst case is falling back to that.
    pub fn load(&mut self) -> Vec<Vec<u8>> {
        let Ok(bytes) = fs::read(&self.path) else {
            return Vec::new();
        };
        let Some(bodies) = parse(&bytes) else {
            crate::log_to_android(
                "WARN",
                &format!("Ignoring unreadable {}", self.path.display()),
            );
            return Vec::new();
        };
        self.written = bodies.iter().map(Vec::len).collect();
        if !bodies.is_empty() {
            crate::log_to_android(
                "INFO",
                &format!(
                    "Loaded {} known body sample(s): {}",
                    bodies.len(),
                    self.written
                        .iter()
                        .map(usize::to_string)
                        .collect::<Vec<_>>()
                        .join(", ")
                ),
            );
        }
        bodies
    }

    /// Write `bodies` out if they differ from what is already on disk.
    pub fn store_if_changed(&mut self, bodies: &[Vec<u8>]) {
        let lengths: Vec<usize> = bodies.iter().map(Vec::len).collect();
        if lengths == self.written {
            return;
        }
        let mut bytes: Vec<u8> = Vec::with_capacity(16 + bodies.iter().map(Vec::len).sum::<usize>());
        bytes.extend_from_slice(MAGIC);
        bytes.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        bytes.extend_from_slice(&(bodies.len() as u32).to_le_bytes());
        for body in bodies {
            bytes.extend_from_slice(&(body.len() as u32).to_le_bytes());
            bytes.extend_from_slice(body);
        }

        // Written through a sibling name so a process killed mid-write cannot
        // leave a half file where last version's samples used to be.
        let tmp = self.path.with_extension("bin.tmp");
        let outcome = fs::File::create(&tmp).and_then(|mut file| {
            file.write_all(&bytes)?;
            file.sync_all()?;
            fs::rename(&tmp, &self.path)
        });

        match outcome {
            Ok(()) => {
                self.written = lengths;
                crate::log_to_android(
                    "INFO",
                    &format!("Saved {} known body sample(s)", bodies.len()),
                );
            }
            Err(e) => crate::log_to_android("WARN", &format!("Could not save known bodies: {e}")),
        }
    }
}

fn parse(bytes: &[u8]) -> Option<Vec<Vec<u8>>> {
    if bytes.len() < 16 || &bytes[..8] != MAGIC {
        return None;
    }
    if u32::from_le_bytes(bytes[8..12].try_into().ok()?) != FORMAT_VERSION {
        return None;
    }
    let count = u32::from_le_bytes(bytes[12..16].try_into().ok()?) as usize;
    if count > MAX_BODIES {
        return None;
    }

    let mut cursor = Cursor { data: bytes, at: 16 };
    let mut bodies = Vec::with_capacity(count);
    for _ in 0..count {
        let len = cursor.u32()? as usize;
        if len as u32 > MAX_BODY_LEN || len > cursor.remaining() {
            return None;
        }
        bodies.push(cursor.take(len)?);
    }
    Some(bodies)
}

struct Cursor<'a> {
    data: &'a [u8],
    at: usize,
}

impl Cursor<'_> {
    fn remaining(&self) -> usize {
        self.data.len() - self.at
    }

    fn u32(&mut self) -> Option<u32> {
        Some(u32::from_le_bytes(self.take(4)?.try_into().ok()?))
    }

    fn take(&mut self, len: usize) -> Option<Vec<u8>> {
        if len > self.remaining() {
            return None;
        }
        let out = self.data[self.at..self.at + len].to_vec();
        self.at += len;
        Some(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A temp dir unique to the calling test, since these run in parallel.
    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("irminsul-known-{name}"));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn bodies_survive_a_reopen() {
        let dir = scratch("round_trip");
        let bodies = vec![vec![7u8; 4096], vec![9u8; 100]];

        KnownBodyStore::open(&dir).store_if_changed(&bodies);

        let mut reopened = KnownBodyStore::open(&dir);
        assert_eq!(reopened.load(), bodies);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_unchanged_sniffer_does_not_rewrite_the_file() {
        let dir = scratch("no_rewrite");
        let bodies = vec![vec![1u8; 64]];
        let mut store = KnownBodyStore::open(&dir);
        store.store_if_changed(&bodies);
        let stamp = fs::metadata(dir.join("known_bodies.bin")).unwrap().modified().unwrap();

        store.store_if_changed(&bodies);

        let after = fs::metadata(dir.join("known_bodies.bin")).unwrap().modified().unwrap();
        assert_eq!(stamp, after);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_missing_file_is_empty_samples_not_a_failure() {
        let dir = scratch("missing");
        let mut store = KnownBodyStore::open(&dir);
        assert!(store.load().is_empty());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_truncated_body_is_rejected() {
        let mut bytes: Vec<u8> = Vec::new();
        bytes.extend_from_slice(MAGIC);
        bytes.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        bytes.extend_from_slice(&1u32.to_le_bytes());
        bytes.extend_from_slice(&512u32.to_le_bytes());
        bytes.extend_from_slice(&[0u8; 8]);

        assert_eq!(parse(&bytes), None);
    }

    #[test]
    fn a_foreign_file_is_rejected() {
        assert_eq!(parse(&[0u8; 32]), None);
    }
}
