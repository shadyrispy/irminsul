//! Loads and caches `data_cache.json` from `https://ggartifact.com/good/data_cache.json`.
//!
//! Behaviour mirrors GOODScanner's `data_cache.rs`:
//! - On first run, downloads the JSON to `<cache_dir>/data_cache.json`.
//! - Refreshes if the cache is older than `DATA_CACHE_TTL_SECS` (24h) or missing.
//! - Tracks last fetch time in `<cache_dir>/data_cache_meta.json`.
//! - If download fails, falls back to a bundled snapshot supplied by the
//!   caller (typically read from `assets/data_cache.json` on Android).

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

use crate::data_types::DataCache;

const DATA_CACHE_URL: &str = "https://ggartifact.com/good/data_cache.json";
const DATA_CACHE_FILENAME: &str = "data_cache.json";
const DATA_CACHE_META_FILENAME: &str = "data_cache_meta.json";
/// Cache TTL: 24h.
const DATA_CACHE_TTL_SECS: u64 = 24 * 3600;
/// Max time we are willing to wait for the remote response before falling back.
const REMOTE_FETCH_TIMEOUT_SECS: u64 = 8;

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
struct CacheMeta {
    #[serde(rename = "lastFetchTime")]
    last_fetch_time: u64,
}

#[derive(Debug, Clone, Default)]
pub struct LoadResult {
    pub source: LoadSource,
    pub version: u32,
    pub git_hash: String,
}

#[derive(Debug, Clone, Copy, Eq, PartialEq)]
pub enum LoadSource {
    /// Loaded from fresh remote data.
    Remote,
    /// Loaded from local cache (still within TTL).
    LocalCache,
    /// Loaded from local cache (expired) — could not refresh.
    StaleCache,
    /// Loaded from bundled assets fallback (no usable cache).
    Bundled,
    /// Empty cache — every lookup will return None.
    Empty,
}

impl Default for LoadSource {
    fn default() -> Self {
        Self::Empty
    }
}

impl LoadSource {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Remote => "remote",
            Self::LocalCache => "local_cache",
            Self::StaleCache => "stale_cache",
            Self::Bundled => "bundled",
            Self::Empty => "empty",
        }
    }
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn is_cache_fresh(last_fetch_time: u64, ttl_secs: u64) -> bool {
    last_fetch_time > 0 && now_secs().saturating_sub(last_fetch_time) < ttl_secs
}

/// Force a refresh: delete the local cache and re-download on next call.
pub fn force_refresh(cache_dir: &Path) -> Result<()> {
    let _ = fs::remove_file(cache_dir.join(DATA_CACHE_META_FILENAME));
    let _ = fs::remove_file(cache_dir.join(DATA_CACHE_FILENAME));
    Ok(())
}

/// Load the data cache, refreshing from the network if necessary.
///
/// `bundled_snapshot` is the bytes of an `assets/data_cache.json` shipped with the
/// APK; it is only used when there is no usable local cache and the network
/// refresh fails.
pub fn load_data_cache(
    cache_dir: &Path,
    bundled_snapshot: Option<&[u8]>,
) -> Result<(DataCache, LoadResult)> {
    fs::create_dir_all(cache_dir)
        .with_context(|| format!("failed to create cache dir {}", cache_dir.display()))?;

    let cache_path = cache_dir.join(DATA_CACHE_FILENAME);
    let meta_path = cache_dir.join(DATA_CACHE_META_FILENAME);
    let meta = read_meta(&meta_path);

    let cache_fresh = cache_path.exists() && is_cache_fresh(meta.last_fetch_time, DATA_CACHE_TTL_SECS);

    if !cache_fresh {
        match fetch_remote() {
            Ok(body) => {
                // Validate before persisting.
                match serde_json::from_str::<DataCache>(&body) {
                    Ok(parsed) => {
                        let (version, git_hash) = (parsed.version, parsed.git_hash.clone());
                        write_atomic(&cache_path, body.as_bytes())?;
                        write_meta(
                            &meta_path,
                            &CacheMeta {
                                last_fetch_time: now_secs(),
                            },
                        )?;
                        return Ok((
                            parsed,
                            LoadResult {
                                source: LoadSource::Remote,
                                version,
                                git_hash,
                            },
                        ));
                    }
                    Err(e) => {
                        tracing::warn!(
                            "data_cache.json failed validation ({}); falling back to local",
                            e
                        );
                    }
                }
            }
            Err(e) => {
                tracing::warn!(
                    "failed to fetch data_cache.json ({}); falling back to local cache",
                    e
                );
            }
        }
    }

    if cache_path.exists() {
        match fs::read_to_string(&cache_path)
            .context("failed to read local data_cache.json")
            .and_then(|s| {
                serde_json::from_str::<DataCache>(&s).context("failed to parse local data_cache.json")
            }) {
            Ok(cache) => {
                let source = if cache_fresh {
                    LoadSource::LocalCache
                } else {
                    LoadSource::StaleCache
                };
                let (version, git_hash) = (cache.version, cache.git_hash.clone());
                return Ok((
                    cache,
                    LoadResult {
                        source,
                        version,
                        git_hash,
                    },
                ));
            }
            Err(e) => {
                tracing::warn!("local cache unusable ({}); trying bundled snapshot", e);
            }
        }
    }

    if let Some(snapshot) = bundled_snapshot {
        if let Ok(cache) = serde_json::from_slice::<DataCache>(snapshot) {
            // Best-effort persist the bundled snapshot so subsequent loads can
            // avoid the parse cost.
            let _ = write_atomic(&cache_path, snapshot);
            let (version, git_hash) = (cache.version, cache.git_hash.clone());
            return Ok((
                cache,
                LoadResult {
                    source: LoadSource::Bundled,
                    version,
                    git_hash,
                },
            ));
        }
    }

    // Last resort: empty cache. The app will still work; it just won't have
    // human-readable names for IDs.
    Ok((
        DataCache::default(),
        LoadResult {
            source: LoadSource::Empty,
            version: 0,
            git_hash: String::new(),
        },
    ))
}

fn read_meta(path: &Path) -> CacheMeta {
    fs::read_to_string(path)
        .ok()
        .and_then(|s| serde_json::from_str::<CacheMeta>(&s).ok())
        .unwrap_or_default()
}

fn write_meta(path: &Path, meta: &CacheMeta) -> Result<()> {
    let body = serde_json::to_string(meta).context("failed to serialise data_cache_meta")?;
    write_atomic(path, body.as_bytes())
}

fn write_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    let tmp = path.with_extension("tmp");
    {
        let mut f = fs::File::create(&tmp)
            .with_context(|| format!("failed to create {}", tmp.display()))?;
        f.write_all(bytes)?;
        f.sync_all().ok();
    }
    fs::rename(&tmp, path)
        .with_context(|| format!("failed to rename {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}

fn fetch_remote() -> Result<String> {
    use std::io::Read;

    let agent = ureq::AgentBuilder::new()
        .timeout(Duration::from_secs(REMOTE_FETCH_TIMEOUT_SECS))
        .build();

    let resp = agent
        .get(DATA_CACHE_URL)
        .set("User-Agent", "irminsul-android/0.1.17")
        .call()
        .context("HTTP request to ggartifact.com failed")?;

    let mut reader = resp.into_reader();
    let mut buf = String::new();
    reader
        .read_to_string(&mut buf)
        .context("failed to read data_cache.json body")?;

    Ok(buf)
}

/// Resolves the on-disk path of the cache directory. Helper for JNI bindings.
pub fn ensure_cache_dir(path: &str) -> Result<PathBuf> {
    let p = PathBuf::from(path);
    fs::create_dir_all(&p)
        .with_context(|| format!("failed to create cache dir {}", p.display()))?;
    Ok(p)
}
