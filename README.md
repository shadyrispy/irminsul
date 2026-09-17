# irminsul-android

Android 前端（独立仓库）。本仓库包含：JNI 桥、Kotlin/Compose UI、VPN 抓包 C 代码，
以及从 `konkers/irminsul` 裁剪出的跨平台数据解析 crate。

```
irminsul-android/
├── rust/irminsul-core/   # 从 konkers/irminsul 裁剪出的 lib crate（good/player_data/keys/build.rs）
├── rust/irminsul-jni/    # JNI 桥 crate：依赖 irminsul-core（path），产物 libirminsul.so
│   ├── src/lib.rs        # Java_com_esc_irminsul_NativeLib_* 符号
│   ├── src/uiaf.rs       # UIAF / Seelie 数据结构
│   └── src/achievements.rs  # 成就导出格式（Cocogoat / Snap Genshin / Xunkong / …）
├── app/                  # Android 工程（Kotlin + CMake 的 C VPN）
└── .github/workflows/android.yml
```

## 关于 rust/ 的位置

C 代码在 `app/src/main/c/`，因为它由 AGP 的 externalNativeBuild（CMake）构建，必须挂在
Android 源码集下；Rust 由 cargo 独立构建（gradle 里只有一个 Exec 任务桥接），没有 AGP
源码集概念，按 cargo-in-gradle 的惯例放在仓库根目录的 `rust/` 下。

## 上游同步

上游 `konkers/irminsul` 是桌面 binary crate（没有 `[lib]`），无法直接作为依赖。本仓库把
用到的跨平台部分（共 3 个源文件：`good.rs` / `lib.rs` / `player_data.rs` + `build.rs` + keys）
vendor 成 `rust/irminsul-core` crate。上游有值得同步的改动时，diff 对应文件手工搬即可，
冲突面很小。

## 构建

```bash
export JAVA_HOME=<jdk 17>
export ANDROID_HOME=<android sdk>
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.0.12077973

# Rust 侧（可选，gradle 会自动跑）
cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --release
#   在 rust/irminsul-jni 目录下执行

# APK
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease
./gradlew :app:testDebugUnitTest
```

### 数据源

游戏数据在 `rust/irminsul-core/build.rs` 里下载并打包进 so，来源：

1. `https://raw.githubusercontent.com/DimbreathBot/AnimeGameData`（正式）
2. `https://cdn.jsdelivr.net/gh/DimbreathBot/AnimeGameData`（CDN 兜底）
3. `https://api.github.com/repos/DimbreathBot/AnimeGameData/contents/...`（代理兜底）

设置 `GITHUB_TOKEN` / `GH_TOKEN` 可提高 GitHub API 速率限制。下载失败且 `rust/irminsul-core/target/game_data.json`
无缓存时构建直接 panic，避免产出数据为空的包。

### 依赖 patch

两个上游 git 依赖（konkers 原仓库的 rev）由 `.cargo/config.toml` patch 到 shadyrispy
fork 的 git 分支（V70 protos、启发式包匹配、反射式 proto JSON、network/CDN 数据源）：

* `shadyrispy/anime-game-data@main`
* `shadyrispy/auto-artifactarium@feat/heuristic-matching`

改动这两个 fork 后，在 `rust/irminsul-jni` 下跑 `cargo update -p auto-artifactarium -p anime-game-data`
刷新 lockfile 即可。
