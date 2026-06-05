
pub mod good;
pub mod player_data;
pub mod uiaf;

#[cfg(target_os = "android")]
pub mod jni;

use serde::{Deserialize, Serialize};

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

pub use crate::player_data::*;
pub use crate::uiaf::*;
