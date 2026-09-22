# irminsul-android

Android 前端（独立仓库）。本仓库包含：JNI 桥、Kotlin/Compose UI、VPN 抓包 C 代码，
以及从 `konkers/irminsul` 裁剪出的跨平台数据解析 crate。

```
irminsul-android/
├── capture/                  # 可嵌入的抓包 AAR（对外接口 com.esc.irminsul.capture）
│   ├── rust/irminsul-core/   # 从 konkers/irminsul 裁剪出的 lib crate（good/player_data/keys/build.rs）
│   ├── rust/irminsul-decode/ # 共享解码核心：pipeline / 缓存 / known bodies / JSON 契约（无 JNI、无 GUI）
│   ├── rust/irminsul-jni/    # JNI 桥 crate：Java_com_esc_irminsul_capture_internal_NativeLib_* → libirminsul.so
│   ├── rust/pcap-check/      # 离线回放验证工具（基线 3919 命令 / 0 解析错误；`--status` 打每包状态，`--good` 打这个会话会交给宿主的 GOOD 导出）
│   ├── rust/irminsul-viewer/ # 桌面查看器：pcap / 管道 / libpcap 实时 → 本地网页（含自写前端页面）
│   ├── src/                  # Kotlin：门面 + VPN 服务 + 解码线程
│   └── testdata/             # summary_status.json：Kotlin/Rust 两端契约夹具
├── app/                      # Android 宿主（Kotlin + CMake 的 C VPN；adb 测试钩子在 src/debug）
├── capture-sample/           # 消费 AAR 的示例宿主
└── .github/workflows/android.yml
```

桌面调试用 `irminsul-viewer`：`cargo run --release -- --file /tmp/e2e.pcap` 后浏览器开
`http://127.0.0.1:1984/`，收到的 JSON 与手机同源同形（`--dump-jsonl` 可与 `pcap-check --status`
逐字节 diff，见 `docs/plan-shared-core-and-macos.md`）。三种源走同一条解码路：
`--file <pcap>`、`--file -`（标准输入，所以抓包的权限和看网页的权限可以是两个用户）、
`--live [iface] [--bpf <expr>]`（走 libpcap，与 konkers 桌面版同一条路；macOS 上开设备要
root 或 `access_bpf` 组，接口名写错时它把真有的接口列出来）。`live` 是默认开启的 cargo
feature，关掉它仍能回放文件与管道，`--live` 则回答为什么不可用。

## 关于 rust/ 的位置

C 代码在 `capture/src/main/c/`，因为它由 `:capture` 的 externalNativeBuild（CMake）构建，
必须挂在 Android 源码集下；Rust 由 cargo 独立构建（gradle 里只有一个 Exec 任务桥接），没有 AGP
源码集概念，按 cargo-in-gradle 的惯例放在 `capture/rust/` 下。两者都由 `:capture` 打进 AAR，
`app/` 与 `capture-sample/` 都只是消费它的宿主。

## 上游同步

上游 `konkers/irminsul` 是桌面 binary crate（没有 `[lib]`），无法直接作为依赖。本仓库把
用到的跨平台部分（共 3 个源文件：`good.rs` / `lib.rs` / `player_data.rs` + `build.rs` + keys）
vendor 成 `capture/rust/irminsul-core` crate。上游有值得同步的改动时，diff 对应文件手工搬即可，
冲突面很小。

## 构建

```bash
export JAVA_HOME=<jdk 17>
export ANDROID_HOME=<android sdk>
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.0.12077973

# Rust 侧（可选，gradle 会自动跑）
cargo ndk -t arm64-v8a -o ../capture/src/main/jniLibs build --release
#   在 capture/rust/irminsul-jni 目录下执行

# APK
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease
./gradlew :app:testDebugUnitTest
```

### 数据源

游戏数据在 `capture/rust/irminsul-core/build.rs` 里下载并打包进 so，来源：

1. `https://raw.githubusercontent.com/DimbreathBot/AnimeGameData`（正式）
2. `https://cdn.jsdelivr.net/gh/DimbreathBot/AnimeGameData`（CDN 兜底）
3. `https://api.github.com/repos/DimbreathBot/AnimeGameData/contents/...`（代理兜底）

设置 `GITHUB_TOKEN` / `GH_TOKEN` 可提高 GitHub API 速率限制。下载失败且 `capture/rust/irminsul-core/target/game_data.json`
无缓存时构建直接 panic，避免产出数据为空的包。

### 依赖 patch

两个上游 git 依赖（konkers 原仓库的 rev）由 `.cargo/config.toml` patch 到 shadyrispy
fork 的 git 分支（V70 protos、启发式包匹配、反射式 proto JSON、network/CDN 数据源）：

* `shadyrispy/anime-game-data@main`
* `shadyrispy/auto-artifactarium@feat/heuristic-matching`

改动这两个 fork 后，在 `capture/rust/irminsul-jni` 下跑 `cargo update -p auto-artifactarium -p anime-game-data`
刷新 lockfile 即可。
