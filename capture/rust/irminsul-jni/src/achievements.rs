//! Achievement export formats.
//!
//! These live in the app repo (not in `core`) because the target apps
//! (Cocogoat, Snap Genshin, Xunkong, Teyvat Guide, Seelie) are an app level
//! concern. `core` only exposes the raw achievements via
//! [`PlayerData::achievements`].

use anyhow::Result;
use chrono::Utc;
use irminsul::player_data::PlayerData;
use serde::{Deserialize, Serialize};

use crate::uiaf::{SeelieAchievement, SeelieRoot, UiafAchievement, UiafInfo, UiafRoot};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum AchievementFormat {
    Uiaf,
    Seelie,
    Cocogoat,
    SnapGenshin,
    Xunkong,
    TeyvatGuide,
    Csv,
}

pub trait AchievementExport {
    fn export_achievements(&self, format: AchievementFormat) -> Result<String>;
}

impl AchievementExport for PlayerData {
    fn export_achievements(&self, format: AchievementFormat) -> Result<String> {
        match format {
            AchievementFormat::Uiaf
            | AchievementFormat::Cocogoat
            | AchievementFormat::SnapGenshin
            | AchievementFormat::Xunkong
            | AchievementFormat::TeyvatGuide => export_uiaf(self),
            AchievementFormat::Seelie => export_seelie(self),
            AchievementFormat::Csv => export_csv(self),
        }
    }
}

fn export_uiaf(player_data: &PlayerData) -> Result<String> {
    let achievement_list = player_data
        .achievements()
        .iter()
        .map(|achievement| UiafAchievement {
            id: achievement.id,
            current: 0,
            status: achievement.status,
            timestamp: achievement.finish_timestamp.unwrap_or(0),
        })
        .collect();

    let root = UiafRoot {
        info: UiafInfo {
            export_app: "Irminsul".to_string(),
            export_app_version: env!("CARGO_PKG_VERSION").to_string(),
            uiaf_version: "v1.1".to_string(),
            export_timestamp: Utc::now().timestamp(),
        },
        list: achievement_list,
    };

    Ok(serde_json::to_string(&root)?)
}

fn export_seelie(player_data: &PlayerData) -> Result<String> {
    let achievements = player_data
        .achievements()
        .iter()
        .filter(|achievement| achievement.status >= 2)
        .map(|achievement| (achievement.id, SeelieAchievement { done: true }))
        .collect();

    let root = SeelieRoot { achievements };
    Ok(serde_json::to_string(&root)?)
}

fn export_csv(player_data: &PlayerData) -> Result<String> {
    use std::fmt::Write;

    let mut csv = String::new();
    writeln!(csv, "ID,Status,Current,Timestamp")?;

    for achievement in player_data.achievements() {
        writeln!(
            csv,
            "{},{},{},{}",
            achievement.id,
            achievement.status,
            0,
            achievement.finish_timestamp.unwrap_or(0)
        )?;
    }

    Ok(csv)
}
