//! Platform agnostic core of Irminsul: game data lookup, network packet
//! interpretation and export to the GOOD / UIAF formats.
//!
//! This crate is consumed by the desktop app (as part of the upstream binary
//! crate) and by the Android app (through the `irminsul-jni` bridge crate).
//! Anything platform specific belongs in the consumer, not here.

pub mod good;
pub mod player_data;

pub use crate::player_data::*;

use anime_game_data::AnimeGameData;
use anyhow::Result;
use flate2::read::GzDecoder;

/// Game data bundled at build time by `build.rs`.
pub const GAME_DATA_GZ: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/game_data.gz"));

/// Load the bundled game data.
pub fn game_data() -> Result<AnimeGameData> {
    Ok(AnimeGameData::new_from_reader(GzDecoder::new(
        GAME_DATA_GZ,
    ))?)
}
