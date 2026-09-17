# irminsul-android

Android 前端（独立仓库）。Rust 侧代码全部来自 `core/` 子模块（`konkers/irminsul` 的 core 化 fork），
本仓库只包含 Android 相关的东西：JNI 桥、Kotlin/Compose UI、VPN 抓包 C 代码。

```
irminsul-android/
├── core/                 # git submodule → irminsul core（good/player_data/keys/build.rs）
├── rust/irminsul-jni/    # JNI 桥 crate：依赖 core（path），产物 libirminsul.so
│   ├── src/lib.rs        # Java_com_esc_irminsul_NativeLib_* 符号
│   ├── src/uiaf.rs       # UIAF / Seelie 数据结构
│   └── src/achievements.rs  # 成就导出格式（Cocogoat / Snap Genshin / Xunkong / …）
├── app/                  # Android 工程（Kotlin + CMake 的 C VPN）
└── .github/workflows/android.yml
```

## 为什么要拆

上游 `konkers/irminsul` 是桌面 binary crate（没有 `[lib]`），Android 分支只能自己加 `[lib]` 并把
`src/jni.rs` 塞进上游源码树，导致每次同步上游都冲突。拆分后：

* 上游同步 = bump `core/` 子模块指针，Android 侧代码零改动；
* `core/` 只保留跨平台部分（数据解析 + 导出 GOOD），平台相关代码全部在消费者侧；
* 成就导出格式（Cocogoat / Snap Genshin / Xunkong / Teyvat Guide）属于 App 需求，放在本仓库。

## 构建

```bash
# 首次克隆
git clone --recurse-submodules <this repo>

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

游戏数据在 `core/build.rs` 里下载并打包进 so，来源：

1. `https://raw.githubusercontent.com/DimbreathBot/AnimeGameData`（正式）
2. `https://cdn.jsdelivr.net/gh/DimbreathBot/AnimeGameData`（CDN 兜底）
3. `https://api.github.com/repos/DimbreathBot/AnimeGameData/contents/...`（代理兜底）

设置 `GITHUB_TOKEN` / `GH_TOKEN` 可提高 GitHub API 速率限制。下载失败且 `core/target/game_data.json`
无缓存时构建直接 panic，避免产出数据为空的包。

### 依赖 patch

`core/Cargo.toml` 依赖的是 konkers 原仓库的两个 rev，本仓库用 `.cargo/config.toml` 把它们
patch 到 shadyrispy fork 的 git 分支（V70 protos、启发式包匹配、反射式 proto JSON、
network/CDN 数据源）：

* `shadyrispy/anime-game-data@main`
* `shadyrispy/auto-artifactarium@feat/heuristic-matching`

改动这两个 fork 后，在 `rust/irminsul-jni` 下跑 `cargo update -p auto-artifactarium -p anime-game-data`
刷新 lockfile 即可。

### submodule 地址

`core/` 目前指向本地相对路径 `../irminsul-core`（本机的 core 仓库）。推送到远端前改成真实 URL：

```bash
git submodule set-url -- core https://github.com/<you>/irminsul-core
```

`core` 本身是 `konkers/irminsul` 的 fork，只多了「加 `[lib]` + 裁剪桌面代码」两类提交，
上游发版时 `git -C core fetch upstream && git -C core merge <tag>` 即可，冲突面很小。
