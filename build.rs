use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use serde::Deserialize;

// ---------------------------------------------------------------------------
// Constants for fetching material data from the Dimbreath AnimeGameData repo
// hosted on GitLab (mirrors the upstream anime-game-data crate's source).
// ---------------------------------------------------------------------------

const COMMITS_API_URL: &str = "https://gitlab.com/api/v4/projects/83871005/repository/commits";
const REPO_BASE_URL: &str = "https://gitlab.com/Dimbreath/animegamedata2/-/raw";

// ---------------------------------------------------------------------------
// Struct definitions mirroring the relevant ExcelBinOutput entries. Only the
// fields we actually need are declared.
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct GitLabCommitEntry {
    id: String,
}

#[derive(Debug, Deserialize)]
#[allow(dead_code)]
struct MaterialExcelConfigDataEntry {
    id: u32,
    #[serde(rename = "nameTextMapHash")]
    name_text_map_hash: u32,
}

type TextMap = HashMap<String, String>;

// ---------------------------------------------------------------------------
// Data fetching helpers (blocking reqwest, works for both desktop & Android
// build hosts).
// ---------------------------------------------------------------------------

fn build_http_client() -> Result<reqwest::blocking::Client> {
    reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_secs(60))
        .build()
        .context("Failed to build reqwest blocking client")
}

fn fetch_latest_hash(client: &reqwest::blocking::Client) -> Result<String> {
    let response = client
        .get(COMMITS_API_URL)
        .send()
        .context("Failed to fetch commits from GitLab")?
        .error_for_status()
        .context("GitLab commits API returned error")?;
    let commits: Vec<GitLabCommitEntry> = serde_json::from_reader(response)
        .context("Failed to parse GitLab commits response")?;
    commits
        .first()
        .map(|c| c.id.clone())
        .ok_or_else(|| anyhow::anyhow!("GitLab returned no commits"))
}

fn fetch_json<T: serde::de::DeserializeOwned>(
    client: &reqwest::blocking::Client,
    git_ref: &str,
    path: &str,
) -> Result<T> {
    let url = format!("{REPO_BASE_URL}/{git_ref}/{path}");
    let response = client
        .get(&url)
        .send()
        .with_context(|| format!("Failed to send request for {url}"))?
        .error_for_status()
        .with_context(|| format!("HTTP error for {url}"))?;
    serde_json::from_reader(response).with_context(|| format!("Failed to parse {url}"))
}

// ---------------------------------------------------------------------------
// Material map generation
// ---------------------------------------------------------------------------

/// Fetches `MaterialExcelConfigData.json` and `TextMap_MediumEN.json` from the
/// Dimbreath repo, and produces a `{id: name}` map serialized as JSON.
fn generate_material_map_bytes(client: &reqwest::blocking::Client) -> Result<Vec<u8>> {
    let git_ref = fetch_latest_hash(client)?;
    eprintln!("Generating material_map from git ref {git_ref}");

    let text_map: TextMap =
        fetch_json(client, &git_ref, "TextMap/TextMap_MediumEN.json")
            .context("Failed to fetch TextMap")?;
    let materials: Vec<MaterialExcelConfigDataEntry> =
        fetch_json(client, &git_ref, "ExcelBinOutput/MaterialExcelConfigData.json")
            .context("Failed to fetch MaterialExcelConfigData")?;

    let mut material_map: HashMap<u32, String> = HashMap::new();
    for entry in &materials {
        if let Some(name) = text_map.get(&entry.name_text_map_hash.to_string()) {
            material_map.insert(entry.id, name.clone());
        }
    }

    let bytes = serde_json::to_vec(&material_map)?;
    eprintln!("Generated material_map with {} entries", material_map.len());
    Ok(bytes)
}

/// Writes `material_map.json` to `out_path` (typically `$OUT_DIR/material_map.json`)
/// so it can be embedded with
/// `include_bytes!(concat!(env!("OUT_DIR"), "/material_map.json"))`.
fn write_material_map_to_out_dir(bytes: &[u8], out_path: &Path) -> Result<()> {
    if let Some(parent) = out_path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let mut file = fs::File::create(out_path)
        .with_context(|| format!("Failed to create {:?}", out_path))?;
    file.write_all(bytes)
        .with_context(|| format!("Failed to write {:?}", out_path))?;
    eprintln!("Wrote material_map to {:?}", out_path);
    Ok(())
}

/// Copies the generated `material_map.json` into the Android assets directory
/// so it is bundled into the APK (per project requirement: "加入assets").
#[cfg(target_os = "android")]
fn copy_material_map_to_android_assets(bytes: &[u8]) -> Result<()> {
    // When cross-compiling for Android, the build runs on the host machine.
    // CARGO_MANIFEST_DIR points to the crate root, so we can locate the
    // Android assets directory relative to it.
    let manifest_dir = std::env::var_os("CARGO_MANIFEST_DIR")
        .context("CARGO_MANIFEST_DIR not set")?;
    let assets_dir = PathBuf::from(manifest_dir).join("android/app/src/main/assets");
    if assets_dir.exists() {
        let dest = assets_dir.join("material_map.json");
        fs::write(&dest, bytes)
            .with_context(|| format!("Failed to write {:?}", dest))?;
        eprintln!("Copied material_map to Android assets: {:?}", dest);
    } else {
        eprintln!(
            "Android assets directory {:?} does not exist, skipping copy",
            assets_dir
        );
    }
    Ok(())
}

#[cfg(not(target_os = "android"))]
fn copy_material_map_to_android_assets(_bytes: &[u8]) -> Result<()> {
    // Desktop builds don't need to populate the Android assets directory.
    Ok(())
}

// ---------------------------------------------------------------------------
// Build script entry points
// ---------------------------------------------------------------------------

fn try_generate_material_map() {
    // Always ensure a material_map.json exists in OUT_DIR so that
    // `include_bytes!(concat!(env!("OUT_DIR"), "/material_map.json"))` in
    // player_data.rs compiles even if network access is unavailable.
    let out_dir = std::env::var_os("OUT_DIR")
        .context("OUT_DIR environment variable not set");
    let out_dir = match out_dir {
        Ok(d) => PathBuf::from(d),
        Err(e) => {
            eprintln!("WARNING: {e}");
            return;
        }
    };
    let out_path = out_dir.join("material_map.json");

    let client = match build_http_client() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("WARNING: Failed to build HTTP client: {e}");
            ensure_fallback_material_map(&out_path);
            return;
        }
    };

    match generate_material_map_bytes(&client) {
        Ok(bytes) => {
            if let Err(e) = write_material_map_to_out_dir(&bytes, &out_path) {
                eprintln!("WARNING: Failed to write material_map to OUT_DIR: {e}");
            }
            if let Err(e) = copy_material_map_to_android_assets(&bytes) {
                eprintln!("WARNING: Failed to copy material_map to Android assets: {e}");
            }
        }
        Err(e) => {
            eprintln!("WARNING: Failed to generate material_map: {e}");
            ensure_fallback_material_map(&out_path);
        }
    }
}

/// Ensures `material_map.json` exists at `out_path`. If the file is already
/// present (from a previous build), it is left in place; otherwise an empty
/// `{}` is written so that `include_bytes!` succeeds.
fn ensure_fallback_material_map(out_path: &Path) {
    if out_path.exists() {
        eprintln!("Using existing material_map.json at {:?}", out_path);
        return;
    }
    if let Some(parent) = out_path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Err(e) = fs::write(out_path, b"{}") {
        eprintln!("WARNING: Failed to write fallback material_map.json: {e}");
    } else {
        eprintln!("Wrote empty fallback material_map.json to {:?}", out_path);
    }
}

#[cfg(windows)]
fn compile_windows_icon() {
    if let Err(e) = winresource::WindowsResource::new()
        .set_icon("assets/icon.ico")
        .compile()
    {
        eprintln!("WARNING: Failed to compile Windows icon: {e}");
    }
}

#[cfg(not(windows))]
fn compile_windows_icon() {}

fn main() {
    try_generate_material_map();
    compile_windows_icon();
}
