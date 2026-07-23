//! Runtime data cache replacing `anime-game-data`.
//!
//! Fetches `data_cache.json` from `https://ggartifact.com/good/data_cache.json`
//! at runtime (with local file caching) so the data can be updated without
//! requiring an app update. Material names are generated at build time by
//! `build.rs` and embedded via `include_bytes!` (see `player_data.rs`).

use std::collections::HashMap;
use std::fs;
use std::io::{BufReader, Read};
use std::path::Path;

use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize};

/// URL of the remotely maintained `data_cache.json`.
pub const DATA_CACHE_URL: &str = "https://ggartifact.com/good/data_cache.json";

// ---------------------------------------------------------------------------
// Enum and struct definitions mirrored from `anime-game-data::types`.
// These match the serialization format of `data_cache.json`.
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, Deserialize, Eq, Hash, PartialEq, Serialize)]
pub enum Property {
    Hp,
    HpPercent,
    Attack,
    AttackPercent,
    Defense,
    DefensePercent,
    ElementalMastery,
    EnergyRecharge,
    Healing,
    CritRate,
    CritDamage,
    PhysicalDamage,
    AnemoDamage,
    GeoDamage,
    ElectroDamage,
    HydroDamage,
    PyroDamage,
    CryoDamage,
    DendroDamage,
}

impl Property {
    pub fn good_name(&self) -> &'static str {
        match self {
            Property::Hp => "hp",
            Property::HpPercent => "hp_",
            Property::Attack => "atk",
            Property::AttackPercent => "atk_",
            Property::Defense => "def",
            Property::DefensePercent => "def_",
            Property::ElementalMastery => "eleMas",
            Property::EnergyRecharge => "enerRech_",
            Property::Healing => "heal_",
            Property::CritRate => "critRate_",
            Property::CritDamage => "critDMG_",
            Property::PhysicalDamage => "physical_dmg_",
            Property::AnemoDamage => "anemo_dmg_",
            Property::GeoDamage => "geo_dmg_",
            Property::ElectroDamage => "electro_dmg_",
            Property::HydroDamage => "hydro_dmg_",
            Property::PyroDamage => "pyro_dmg_",
            Property::CryoDamage => "cryo_dmg_",
            Property::DendroDamage => "dendro_dmg_",
        }
    }

    pub fn is_percentage(&self) -> bool {
        matches!(
            self,
            Property::HpPercent
                | Property::AttackPercent
                | Property::DefensePercent
                | Property::EnergyRecharge
                | Property::Healing
                | Property::CritRate
                | Property::CritDamage
                | Property::PhysicalDamage
                | Property::AnemoDamage
                | Property::GeoDamage
                | Property::ElectroDamage
                | Property::HydroDamage
                | Property::PyroDamage
                | Property::CryoDamage
                | Property::DendroDamage
        )
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, Hash, PartialEq, Serialize)]
pub enum ArtifactSlot {
    Flower,
    Plume,
    Sands,
    Goblet,
    Circlet,
}

impl ArtifactSlot {
    pub fn from_game_data_name(name: &str) -> Option<Self> {
        match name {
            "EQUIP_BRACER" => Some(Self::Flower),
            "EQUIP_NECKLACE" => Some(Self::Plume),
            "EQUIP_SHOES" => Some(Self::Sands),
            "EQUIP_RING" => Some(Self::Goblet),
            "EQUIP_DRESS" => Some(Self::Circlet),
            _ => None,
        }
    }

    pub fn good_name(&self) -> &'static str {
        match self {
            ArtifactSlot::Flower => "flower",
            ArtifactSlot::Plume => "plume",
            ArtifactSlot::Sands => "sands",
            ArtifactSlot::Goblet => "goblet",
            ArtifactSlot::Circlet => "circlet",
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub enum SkillType {
    Auto,
    Skill,
    Burst,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct Affix {
    pub property: Property,
    pub value: f64,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct Artifact {
    pub set: String,
    pub slot: ArtifactSlot,
    pub rarity: u32,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct Weapon {
    pub name: String,
    pub rarity: u32,
}

// ---------------------------------------------------------------------------
// DataCache
// ---------------------------------------------------------------------------

/// Schema version of the on-disk `data_cache.json`. Must match the value
/// embedded in the downloaded file.
const DATABASE_VERSION: u32 = 1;

/// Runtime replacement for `anime_game_data::AnimeGameData`.
///
/// `material_map` is intentionally absent — materials are bundled at build
/// time (see `build.rs` and `PlayerData::MATERIAL_MAP`).
#[derive(Debug, Deserialize, Serialize)]
pub struct DataCache {
    pub version: u32,
    pub git_hash: String,
    pub affix_map: HashMap<u32, Affix>,
    pub artifact_map: HashMap<u32, Artifact>,
    pub character_map: HashMap<u32, String>,
    pub property_map: HashMap<u32, Property>,
    pub set_map: HashMap<u32, String>,
    pub skill_type_map: HashMap<u32, SkillType>,
    pub weapon_map: HashMap<u32, Weapon>,
}

impl DataCache {
    pub fn load_from_bytes(bytes: &[u8]) -> Result<Self> {
        let cache: Self = serde_json::from_slice(bytes)
            .context("Failed to deserialize data_cache.json")?;
        if cache.version != DATABASE_VERSION {
            return Err(anyhow!(
                "data_cache version mismatch: expected {}, got {}",
                DATABASE_VERSION,
                cache.version
            ));
        }
        Ok(cache)
    }

    pub fn load_from_reader<R: Read>(reader: R) -> Result<Self> {
        let cache: Self = serde_json::from_reader(reader)
            .context("Failed to deserialize data_cache.json")?;
        if cache.version != DATABASE_VERSION {
            return Err(anyhow!(
                "data_cache version mismatch: expected {}, got {}",
                DATABASE_VERSION,
                cache.version
            ));
        }
        Ok(cache)
    }

    pub fn load_from_path<P: AsRef<Path>>(path: P) -> Result<Self> {
        let file = fs::File::open(path.as_ref())
            .with_context(|| format!("Failed to open data_cache at {:?}", path.as_ref()))?;
        Self::load_from_reader(BufReader::new(file))
    }

    /// Fetch the raw `data_cache.json` bytes from the remote host using a
    /// blocking HTTP client. Used on Android (no tokio runtime available).
    #[cfg(target_os = "android")]
    pub fn fetch_remote_blocking() -> Result<Vec<u8>> {
        let client = reqwest::blocking::Client::builder()
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .context("Failed to build reqwest blocking client")?;
        let bytes = client
            .get(DATA_CACHE_URL)
            .send()
            .context("Failed to send data_cache request")?
            .error_for_status()
            .context("data_cache HTTP error")?
            .bytes()
            .context("Failed to read data_cache response")?;
        Ok(bytes.to_vec())
    }

    /// Fetch the raw `data_cache.json` bytes asynchronously (desktop path).
    #[cfg(not(target_os = "android"))]
    pub async fn fetch_remote_async() -> Result<Vec<u8>> {
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .context("Failed to build reqwest async client")?;
        let bytes = client
            .get(DATA_CACHE_URL)
            .send()
            .await
            .context("Failed to send data_cache request")?
            .error_for_status()
            .context("data_cache HTTP error")?
            .bytes()
            .await
            .context("Failed to read data_cache response")?;
        Ok(bytes.to_vec())
    }

    /// Load from `cache_path` if present; otherwise fetch from the remote
    /// URL, persist to `cache_path`, and return the new cache. Used on
    /// Android.
    ///
    /// If `cache_path` is `None`, the remote is always fetched and the result
    /// is not persisted.
    #[cfg(target_os = "android")]
    pub fn load_or_fetch_blocking(cache_path: Option<&Path>) -> Result<Self> {
        if let Some(path) = cache_path {
            if let Ok(cache) = Self::load_from_path(path) {
                return Ok(cache);
            }
        }
        let bytes = Self::fetch_remote_blocking()
            .context("Failed to fetch data_cache.json from remote")?;
        if let Some(path) = cache_path {
            if let Some(parent) = path.parent() {
                let _ = fs::create_dir_all(parent);
            }
            let _ = fs::write(path, &bytes);
        }
        Self::load_from_bytes(&bytes)
    }

    /// Async variant for the desktop path. Tries `cache_path` first, then
    /// falls back to fetching from the remote URL.
    #[cfg(not(target_os = "android"))]
    pub async fn load_or_fetch_async(cache_path: Option<&Path>) -> Result<Self> {
        if let Some(path) = cache_path {
            if let Ok(cache) = Self::load_from_path(path) {
                return Ok(cache);
            }
        }
        let bytes = Self::fetch_remote_async()
            .await
            .context("Failed to fetch data_cache.json from remote")?;
        if let Some(path) = cache_path {
            if let Some(parent) = path.parent() {
                let _ = fs::create_dir_all(parent);
            }
            let _ = fs::write(path, &bytes);
        }
        Self::load_from_bytes(&bytes)
    }

    // -----------------------------------------------------------------------
    // Accessors mirroring the old `AnimeGameData` API (minus materials).
    // -----------------------------------------------------------------------

    pub fn get_affix(&self, id: u32) -> Result<&Affix> {
        self.affix_map
            .get(&id)
            .ok_or_else(|| anyhow!("Unable to fetch affix {id}"))
    }

    pub fn get_artifact(&self, id: u32) -> Result<&Artifact> {
        self.artifact_map
            .get(&id)
            .ok_or_else(|| anyhow!("Unable to fetch artifact {id}"))
    }

    pub fn get_character(&self, id: u32) -> Result<&String> {
        self.character_map
            .get(&id)
            .ok_or_else(|| anyhow!("Unable to fetch character {id}"))
    }

    pub fn get_property(&self, id: u32) -> Result<&Property> {
        self.property_map
            .get(&id)
            .ok_or_else(|| anyhow!("Unable to fetch property {id}"))
    }

    pub fn get_set(&self, id: u32) -> Result<&String> {
        self.set_map
            .get(&id)
            .ok_or_else(|| anyhow!("Unable to fetch set {id}"))
    }

    pub fn get_skill_type(&self, id: u32) -> Result<&SkillType> {
        self.skill_type_map
            .get(&id)
            .ok_or_else(|| anyhow!("Unable to fetch skill type {id}"))
    }

    pub fn get_weapon(&self, id: u32) -> Result<&Weapon> {
        self.weapon_map
            .get(&id)
            .ok_or_else(|| anyhow!("Unable to fetch weapon {id}"))
    }
}
