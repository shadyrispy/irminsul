use std::fs::File;
use std::path::Path;
use std::{env, io};

use flate2::Compression;
use flate2::write::GzEncoder;

// NOTE: build scripts run on the host, so network access is available even when
// cross-compiling to Android. Game data is downloaded here and embedded into the
// library (see `src/lib.rs`).
#[tokio::main]
async fn main() -> io::Result<()> {
    let out_dir = env::var_os("OUT_DIR").unwrap();
    let out_dir = Path::new(&out_dir);
    // Cache lives in the crate `target/` dir so every target triple reuses it.
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    std::fs::create_dir_all(manifest_dir.join("target")).ok();
    let cache_path = manifest_dir.join("target").join("game_data.json");
    let out_path = out_dir.join("game_data.gz");

    let mut db = anime_game_data::AnimeGameData::new_with_cache(&cache_path);
    match db.needs_update().await {
        Ok(true) => match db.update().await {
            Ok(_) => {
                eprintln!("Game data updated successfully");
            }
            Err(e) => {
                eprintln!("Failed to update game data: {}", e);
                eprintln!("Using cached data instead");
            }
        },
        Ok(false) => {
            eprintln!("Game data is up to date");
        }
        Err(e) => {
            eprintln!("Failed to check for updates: {}", e);
            eprintln!("Using cached data instead");
        }
    }

    if db.has_data() {
        let f = File::create(&out_path).unwrap();
        let writer = GzEncoder::new(f, Compression::best());
        db.save_to_writer(writer).unwrap();
    } else {
        panic!(
            "No game data available and the download failed. Game data is required to build; \
             check network access to github.com/DimbreathBot/AnimeGameData and retry."
        );
    }

    Ok(())
}
