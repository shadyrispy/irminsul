//! The decode pipeline behind every Irminsul front end.
//!
//! One [`Session`] turns raw IP frames into game commands plus structured status,
//! and it is the only place that knows how the game's session key is recovered and
//! what a decoded command looks like on the wire. Nothing here is platform
//! specific: the Android JNI shim and the desktop viewer both sit on top of it, so
//! a parse or key fix lands on every front end at once instead of in one of them.
//!
//! Logging goes through `tracing` and never at a front end directly. The Android
//! shim installs a subscriber that routes INFO and above into logcat; the desktop
//! viewer keeps the libraries' logs off its console entirely and surfaces what
//! matters as stream events instead.

pub mod achievements;
pub mod uiaf;

mod cache;
mod samples;
mod session;
mod source;
mod status;

pub use achievements::AchievementFormat;
pub use auto_artifactarium::KeyOrigin;
pub use irminsul::player_data::{ExportSettings, PlayerData};
pub use session::{PacketOutcome, Session, dispatch_keys};
pub use source::{AnyFrames, PcapFrames, prepare_frame};
pub use status::{StatusPayload, command_summary, key_origin_name, status_json};
