# Irminsul - 原神游戏数据解析 Android 应用

![Build Status](https://github.com/shadyrispy/irminsul/actions/workflows/build.yml/badge.svg)

原神游戏数据解析 Android 应用，基于 VPN 流量捕获技术解析游戏通信数据。

## 功能特性

- 🎮 **流量捕获**: 基于 VpnService 实现无 root 流量捕获
- 📊 **数据解析**: Rust 高性能解析引擎，支持游戏协议解析
- 💾 **本地存储**: Room 数据库存储解析结果
- 📱 **Material Design**: 现代化 Jetpack Compose UI

## 技术架构

```
┌─────────────────────────────────────────┐
│              Presentation               │
│  ┌─────────────┐    ┌────────────────┐  │
│  │ MainActivity │◄──►│  MainViewModel │  │
│  └─────────────┘    └────────────────┘  │
└─────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────┐
│               Data Layer                 │
│  ┌─────────────┐    ┌────────────────┐  │
│  │ Repository  │◄──►│  Room Database │  │
│  └─────────────┘    └────────────────┘  │
└─────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────┐
│              Native Layer               │
│  ┌─────────────────────────────────┐   │
│  │  Rust Parser (libirminsul_parser) │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

## 技术栈

| 组件 | 技术 |
|------|------|
| UI | Jetpack Compose (BOM 2024.02.02) |
| 架构 | MVVM + Clean Architecture |
| 依赖注入 | Dagger Hilt 2.48 |
| 数据库 | Room 2.6.1 |
| 网络捕获 | VpnService |
| 解析引擎 | Rust (JNI) |
| 异步处理 | Kotlin Coroutines + Flow |

## 项目结构

```
irminsul/
├── app/
│   └── src/main/
│       ├── java/com/irminsul/
│       │   ├── data/           # 数据层
│       │   ├── di/             # 依赖注入
│       │   ├── domain/         # 业务逻辑
│       │   ├── jni/            # JNI 桥接
│       │   ├── presentation/   # UI 层
│       │   └── service/        # VPN 服务
│       ├── jniLibs/            # Rust 编译产物
│       └── assets/             # 游戏数据
├── rustlib/                    # Rust 解析库
│   ├── src/
│   └── Cargo.toml
└── build.gradle.kts
```

## 构建要求

- JDK 17+
- Android SDK 34
- Android NDK 26.1.10909125
- Rust (stable)

## 本地构建

### 1. 克隆项目

```bash
git clone https://github.com/shadyrispy/irminsul.git
cd irminsul
```

### 2. 编译 Rust 库

```bash
# 安装 Rust 目标
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android

# 安装 cargo-ndk
cargo install cargo-ndk

# 编译
cd rustlib
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 -o ../app/src/main/jniLibs build --release
```

### 3. 构建 APK

```bash
cd ..
./gradlew assembleDebug
```

## 参考

- [irminsul](https://github.com/konkers/irminsul) - 原项目 UI 参考
- [auto-artifactarium](https://github.com/konkers/auto-artifactarium) - 游戏数据解析参考
- [PCAPdroid](https://github.com/emanuele-f/PCAPdroid) - Android 流量镜像参考
- [AnimeGameData](https://gitlab.com/Dimbreath/AnimeGameData) - 游戏基础数据

## License

MIT License
