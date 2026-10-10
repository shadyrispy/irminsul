use std::collections::HashMap;

use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Substat {
    pub key: String,
    pub value: f32,
    pub initial_value: f32,
    /// The individual upgrade values this line sums, in the order the game applied
    /// them and unrounded — so the first entry rounds to `initial_value`, and the
    /// entries can sum to a value one rounding step off the rounded `value`. Kept
    /// because `value` alone cannot say whether it came from one big roll or
    /// several small ones, which is what an optimizer scores future potential on.
    #[serde(default)]
    pub rolls: Vec<f32>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Artifact {
    pub set_key: String,
    pub slot_key: String,
    pub level: u32,
    pub rarity: u32,
    pub main_stat_key: String,
    pub location: String,
    pub lock: bool,
    pub substats: Vec<Substat>,

    // GOOD v3 fields.
    pub total_rolls: u32,
    pub astral_mark: bool,
    pub elixer_crafted: bool,
    pub unactivated_substats: Vec<Substat>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Weapon {
    pub key: String,
    /// The game's own star count, which the export already filtered on and a host
    /// would otherwise have to look up by name — and could not get right for a
    /// weapon whose name it does not know.
    pub rarity: u32,
    pub level: u32,
    pub ascension: u32,
    pub refinement: u32,
    pub location: String,
    pub lock: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct TalentLevel {
    pub auto: u32,
    pub skill: u32,
    pub burst: u32,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Character {
    pub key: String,
    pub level: u32,
    pub constellation: u32,
    pub ascension: u32,
    pub talent: TalentLevel,
    /// Read off the burst, the only skill that carries an element, so this is null
    /// for a character whose burst the client never sent. A host that maps keys
    /// through its own dictionary does not need it; one showing what it actually
    /// captured would otherwise be guessing from a name.
    pub element: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Good {
    pub format: String,
    pub version: u32,
    pub source: String,
    pub characters: Vec<Character>,
    pub artifacts: Vec<Artifact>,
    pub weapons: Vec<Weapon>,
    pub materials: HashMap<String, u32>,
}

/// GOOD key for the Traveler before their element is appended.
pub const TRAVELER_KEY: &str = "Traveler";

pub fn to_good_key(value: &str) -> String {
    let mut result = String::new();
    let mut capitalize_next = true;

    for c in value.chars() {
        if c.is_ascii_alphanumeric() {
            if capitalize_next {
                result.extend(c.to_uppercase());
                capitalize_next = false;
            } else {
                result.push(c);
            }
        } else if c == ' ' {
            capitalize_next = true;
        }
    }

    result
}

pub fn fake_uninitialized_4th_line(artifacts: Vec<Artifact>) -> Vec<Artifact> {
    artifacts
        .into_iter()
        .map(|mut arti| {
            if arti.unactivated_substats.is_empty() || arti.rarity != 5 {
                return arti;
            }
            arti.substats.push(arti.unactivated_substats.pop().unwrap());
            Artifact {
                level: 4,
                total_rolls: 4,
                ..arti
            }
        })
        .collect()
}
