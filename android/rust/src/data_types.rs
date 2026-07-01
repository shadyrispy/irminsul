//! Schema for `data_cache.json` downloaded from `https://ggartifact.com/good/data_cache.json`.
//!
//! Each map keys an internal game `u32` id to its human-readable metadata.
//! The schema is mirrored from GOODScanner to keep lookup behaviour aligned
//! with the desktop GOOD-mapping community.

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

/// 圣遗物主/副词条属性 (GOOD 名空间)。
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq, Deserialize, Serialize)]
pub enum Property {
    Hp,
    HpPercent,
    Attack,
    AttackPercent,
    Defense,
    DefensePercent,
    ElementalMastery,
    CritRate,
    CritDamage,
    HealingBonus,
    IncomingHealingBonus,
    EnergyRecharge,
    PyroBonus,
    ElectroBonus,
    HydroBonus,
    DendroBonus,
    AnemoBonus,
    GeoBonus,
    CryoBonus,
    PhysicalBonus,
}

impl Property {
    /// 还原 JSON 字符串中的字段命名。
    pub fn from_name(s: &str) -> Option<Self> {
        Some(match s {
            "Hp" => Self::Hp,
            "HpPercent" => Self::HpPercent,
            "Attack" => Self::Attack,
            "AttackPercent" => Self::AttackPercent,
            "Defense" => Self::Defense,
            "DefensePercent" => Self::DefensePercent,
            "ElementalMastery" => Self::ElementalMastery,
            "CritRate" => Self::CritRate,
            "CritDamage" => Self::CritDamage,
            "HealingBonus" => Self::HealingBonus,
            "IncomingHealingBonus" => Self::IncomingHealingBonus,
            "EnergyRecharge" => Self::EnergyRecharge,
            "PyroBonus" => Self::PyroBonus,
            "ElectroBonus" => Self::ElectroBonus,
            "HydroBonus" => Self::HydroBonus,
            "DendroBonus" => Self::DendroBonus,
            "AnemoBonus" => Self::AnemoBonus,
            "GeoBonus" => Self::GeoBonus,
            "CryoBonus" => Self::CryoBonus,
            "PhysicalBonus" => Self::PhysicalBonus,
            _ => return None,
        })
    }

    /// 映射为 GOOD v3 文档中的 key (`hp`, `hp_`, `atk`, ...)。
    pub fn good_name(&self) -> &'static str {
        match self {
            Self::Hp => "hp",
            Self::HpPercent => "hp_",
            Self::Attack => "atk",
            Self::AttackPercent => "atk_",
            Self::Defense => "def",
            Self::DefensePercent => "def_",
            Self::ElementalMastery => "eleMas",
            Self::CritRate => "critRate_",
            Self::CritDamage => "critDMG_",
            Self::HealingBonus => "heal_",
            Self::IncomingHealingBonus => "incomingHeal_",
            Self::EnergyRecharge => "enerRech_",
            Self::PyroBonus => "pyro_dmg_",
            Self::ElectroBonus => "electro_dmg_",
            Self::HydroBonus => "hydro_dmg_",
            Self::DendroBonus => "dendro_dmg_",
            Self::AnemoBonus => "anemo_dmg_",
            Self::GeoBonus => "geo_dmg_",
            Self::CryoBonus => "cryo_dmg_",
            Self::PhysicalBonus => "physical_dmg_",
        }
    }

    pub fn is_percentage(&self) -> bool {
        !matches!(self, Self::Hp | Self::Attack | Self::Defense | Self::ElementalMastery)
    }
}

impl Default for Property {
    fn default() -> Self {
        Self::Hp
    }
}

/// 角色技能类型 (auto / skill / burst)。
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq, Deserialize, Serialize)]
pub enum SkillType {
    Auto,
    Skill,
    Burst,
}

impl SkillType {
    pub fn from_name(s: &str) -> Option<Self> {
        Some(match s {
            "Auto" => Self::Auto,
            "Skill" => Self::Skill,
            "Burst" => Self::Burst,
            _ => return None,
        })
    }
}

impl Default for SkillType {
    fn default() -> Self {
        Self::Auto
    }
}

/// 圣遗物 slot。GOOD 名空间。
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq, Deserialize, Serialize)]
pub enum SlotKey {
    Flower,
    Plume,
    Sands,
    Goblet,
    Circlet,
}

impl SlotKey {
    pub fn good_name(&self) -> &'static str {
        match self {
            Self::Flower => "flower",
            Self::Plume => "plume",
            Self::Sands => "sands",
            Self::Goblet => "goblet",
            Self::Circlet => "circlet",
        }
    }
}

impl Default for SlotKey {
    fn default() -> Self {
        Self::Flower
    }
}

/// 圣遗物套装数据 (来自 ggartifact.com 的 artifact_map)。
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct ArtifactData {
    pub set: String,
    pub slot: SlotKey,
    pub rarity: u32,
}

/// 武器数据 (来自 ggartifact.com 的 weapon_map)。
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct WeaponData {
    pub name: String,
    pub rarity: u32,
}

/// 词条 (来自 ggartifact.com 的 affix_map)。
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct AffixData {
    pub property: String,
    pub value: f32,
}

/// 顶层数据结构,与 https://ggartifact.com/good/data_cache.json 的 schema 1:1 对齐。
///
/// 加载后即可作为 `AnimeGameData` 的替代品,供 `PlayerData` 进行 ID -> 名称 / 元数据查找。
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct DataCache {
    #[serde(default)]
    pub version: u32,
    #[serde(default)]
    pub git_hash: String,

    #[serde(default)]
    pub affix_map: HashMap<u32, AffixData>,
    #[serde(default)]
    pub artifact_map: HashMap<u32, ArtifactData>,
    #[serde(default)]
    pub character_map: HashMap<u32, String>,
    #[serde(default)]
    pub material_map: HashMap<u32, String>,
    #[serde(default)]
    pub property_map: HashMap<u32, String>,
    #[serde(default)]
    pub set_map: HashMap<u32, String>,
    #[serde(default)]
    pub skill_type_map: HashMap<u32, String>,
    #[serde(default)]
    pub weapon_map: HashMap<u32, WeaponData>,
}

impl DataCache {
    pub fn get_character(&self, id: u32) -> Option<&str> {
        self.character_map.get(&id).map(|s| s.as_str())
    }

    pub fn get_material(&self, id: u32) -> Option<&str> {
        self.material_map.get(&id).map(|s| s.as_str())
    }

    pub fn get_set(&self, id: u32) -> Option<&str> {
        self.set_map.get(&id).map(|s| s.as_str())
    }

    pub fn get_artifact(&self, id: u32) -> Option<&ArtifactData> {
        self.artifact_map.get(&id)
    }

    pub fn get_weapon(&self, id: u32) -> Option<&WeaponData> {
        self.weapon_map.get(&id)
    }

    pub fn get_affix(&self, id: u32) -> Option<&AffixData> {
        self.affix_map.get(&id)
    }

    /// 解析 prop id (主词条) -> `Property`。
    pub fn get_property(&self, id: u32) -> Option<Property> {
        self.property_map
            .get(&id)
            .and_then(|s| Property::from_name(s))
    }

    /// 解析 talent id -> `SkillType`。
    pub fn get_skill_type(&self, id: u32) -> Option<SkillType> {
        self.skill_type_map
            .get(&id)
            .and_then(|s| SkillType::from_name(s))
    }
}
