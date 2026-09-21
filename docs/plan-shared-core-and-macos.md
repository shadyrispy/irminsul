# 共享解码核心 + macOS 调试前端 — 方案 v2

状态：**提案，P0 已完成（2026-09-21）**。全部数字都是实测的，不是推断。
上下文被压缩后请以本文为准；重跑方法见最后一节。

P0 落地情况：新 crate `capture/rust/irminsul-decode`（session / cache / samples /
source / status + achievements、uiaf 一并搬入），JNI 从 901 行降到 482 行、只剩
Java 类型转换 + 一个 mutex + logcat 桥 + 回调。回归：1071/0、5285/0、盲态 2848（无样本仍 4）、
上一版写的 known_bodies.bin 这一版照读、19 个 core 测试、符号仍 12、BlueStacks 实况仍全量收。
遗留：Kotlin 侧 `readPcapFile` 与 core 的 `PcapFrames` 仍是两份 pcap 解析，
P2/P3 决定 viewer 之后再把 Android 的文件源切过去。

---

## 1. 目标

1. 一份解码核心，两个前端：macOS 桌面（调试用）与 Android（生产用）行为一致。
2. macOS 上**用 pcap 文件回放**驱动，不依赖抓包权限，改一行代码就能重跑同一份流量。
3. 桌面有数据包展示界面（列表 + 单包全文），和 Android 已有的 inspector 同构。
4. 解析结果有稳定的标准格式（JSON），跨语言、可被第三方消费。

不在此目标内：mac 上 live 抓 PC 客户端（协议时序与移动端不同，单独评估）。

---

## 2. 现状事实（本轮测量所得）

### 2.1 同一条流水线目前有 3.5 份实现

| 位置 | 职责 | 备注 |
|---|---|---|
| `irminsul-android/capture/rust/irminsul-jni/src/lib.rs` (901 行) | 喂帧→命令环形缓存→摘要/全文 JSON→known bodies→完成边沿 | **只有 JNI 能用** |
| `irminsul-android/capture/rust/irminsul-jni/src/known_bodies.rs` (216 行) | 已知明文样本落盘/回灌 | 同上 |
| `irminsul/src/monitor.rs` (247 行) | 又一份喂帧→`matches_*`→player_data | 桌面自己那份 |
| `irminsul-android/capture/rust/pcap-check/src/main.rs` | 又一份 pcap 解析 + PCAPdroid trailer strip + 假以太网头 | 回归工具 |
| `irminsul-core/` ↔ `irminsul-android/capture/rust/irminsul-core/` | 两份 player_data/good | 已漂移：仅差 artifact 取整语义（`irminsul-core/src/player_data.rs:238-242` epsilon half-up vs Android 侧 `.round()`） |

### 2.2 桌面端已有的接缝与缺口

- 抓包抽象是干净的：`irminsul/src/capture.rs:44` `trait CaptureBackend { async fn next_packet() }`，
  `BackendType{Pktmon,Pcap}` + `create_capture()`。**加一个 File 后端是小改动。**
- 但今天**没有任何 pcap 文件模式**：`capture/pcap_backend.rs:101` 只有 `Capture::from_device(...).open()`，
  CLI（`main.rs:102-114`）只有 `--no-admin` 和 `--capture-backend`。
- UI 只有聚合视图（`app.rs:506-569`：三个 tick 行 + 导出 + 抽卡 + 成就），`app.rs:944` 是 "coming soon"。
  **没有命令列表、没有单包 JSON 视图。**
- 桌面**不依赖** `irminsul-core`，用的是自己那份 `player_data.rs`(490)/`good.rs`(107)。
- 桌面只把 `command.proto_data` 原始字节落到 `packet_log/`（`monitor.rs:221 log_command()`），没有 JSON。

### 2.3 Android 端已经有的（可以直接抬到 core）

- `CaptureSource{Vpn, File}` 已经把"来源"收敛成一个概念（`capture/` 公开接口）。
- 命令列表 UI（`PacketListScreen` + `ProtoJsonTree` + `PacketDetailScreen`）、摘要/全文两入口、
  64MB 环形缓存、`SessionPhase`/`CaptureTraffic`。
- **JSON 契约已经有双向门禁**：`capture/testdata/summary_status.json` 一份 fixture，
  Rust 侧 `contract_tests` + Kotlin 侧 `StatusDecoderTest` 同时校验（改名即失败）。
- 已知明文取密钥（本轮新做，fork `9a7e58e` + app `ed0d6c3`）。

### 2.4 关键实测数字（用于回归验收）

| 输入 | 结果 |
|---|---|
| `/tmp/capture.pcap` | 1071 命令 / 0 解析错误 / unknown 172 |
| `/tmp/reconnect.pcap` | 5285 / 0 / unknown 703 |
| `/tmp/full.pcap`（模拟器盲态 re-auth，无样本） | 4 命令 |
| `/tmp/full.pcap` + 一份 known body | 2707~2848 / 0 |
| `/tmp/e2e.pcap`（首登+盲重连，不注入任何样本） | 3919 / 0 |
| 实况 BlueStacks（新进程从磁盘读 4 个样本，无 seed 无握手） | `recovered session key from a known command body` → 1045/217/1231/94/1845 |

密钥侧结论（写进 `docs/adr/0004`，此处只留结论）：可解密的只有**进程首次登录**（`client_sequence_id = 1`）；
re-auth 的 seed 时刻在 `sent_ms` 前 26 小时内**无解**（±3h 108M 次 + 再 468M 次均无命中）；
REQ 的 `clientRandKey` 是 RSA-2048 但我们没有对应私钥（`Err` vs Rsp 的 `Ok(8)`）。

---

## 3. 新发现：批量信封 `UnionCmdNotify` 目前不可检视（v1 方案里没有）

证据：

- 它是我们要害数据：`/tmp/capture.pcap` 里 **80 条**（命名命令里排第 2，仅次
  `GetScenePointReq` 92，前面还有 172 条 `unknown`）、`/tmp/reconnect.pcap` 里 **923 条**
  —— 那里它是**第一名**，比 `unknown`(703) 还多。
- fork 里 **`grep -i union src/ → 0 命中**：完全没有特殊处理。
- 渲染结果（`pcap-check /tmp/capture.pcap 7516`）：
  `cmd 7516 (UnionCmdNotify): cmdList: [20]`，`field_count=1` —— 一条**装了 20 个子命令**的批次
  在列表里只算 1 条记录，`field_count=1`。
- 子命令的 `body` 是 proto `bytes` 字段，而 `irminsul-deps/aa/src/proto_json.rs:165-166` 把 `Bytes`
  渲染成 **base64 字符串**。→ 批次内部对 UI 是黑盒。

结论与边界（别过度解读）：

- **确定损失的是可检视性**：923 个批次里的内层命令，今天在包列表里看不到、点不开。
- **不影响今天的采集完成度**：items/avatars/achievements 的全量 notify 是顶层命令，实况已收满。
  但这一点在没做拆包之前**无法证伪**——如果某些版本/某些 notify 走批次，我们的 `matches_*` 会漏。
  拆包之后才能测量"内层还有没有别的 item/avatar 载荷"。
- 也不影响 known body：内层 body 不会以独立命令出现，所以样本集少了几种可能的大 body。

stove-helper 的对应做法（`pkg/helper/sniffer_packet.go:95-106`）值得抄：把 `cmd_list` 里每一项的
`message_id` 再查一次命令表，用它解出的类型**替换**掉 base64，输出真正的树。
它用的是外部 `cmdIdMap`（CSV）；我们不需要——命令 id 到类型的映射我们已有（`proto_json`/描述符 +
`summary_json` 的 name），只需补一层"信封→子命令"的展开。

---

## 4. 参考项目借鉴清单（stove-helper / Iridium-NG）

### 4.1 Iridium-NG（`github.com/Akka0/Iridium-NG`）—— 前端参考

> ⚠️ **许可：该仓库没有任何 LICENSE 文件**（GitHub API `license: null`，源码里也无版权声明头，
> `frontend/README.md` 只是 Svelte 官方模板自带的那份）。**默认版权全保留 → 它的 `App.svelte`
> /`Packet.svelte`/`proto_raw_decoder.js` 都不能复制、不能改、不能进我们的仓库。**
> 能用的只有：交互模型与信息架构（不受版权保护的思想层），以及它依赖的那些有明确许可的库
> （Svelte/Rollup 模板 MIT、`svelte-jsoneditor` MIT）。
> 另外：最后一次提交 2023-05-05，30 star，构建链是 Svelte 3 + Rollup 2。
> **结论：抄形，不抄码。** 见 §9 决定 2。

它是 Go 后端 + 一个**本地网页前端**（`//go:embed frontend/public` + gin 监听 `:1984`，SSE 推流）。
整个 UI 只有 ~920 行（`App.svelte` 640 / `Packet.svelte` 83），无路由、无状态库。
对我们最有用的是它的**视图模型**，不是它的传输层。
它的 HTTP 面小得可以忽略：`/api/start`、`/api/stop`、`/api/upload`(POST multipart)、
`/api/stream`(SSE) 四个端点 + 静态目录（`frontend_server.go:21-25`）——**Rust 里几百行就能提供同样形状**。

**必须抄（且抄在 core 里，不是抄在前端里）：**

1. **无 schema 的字段号树**（raw protobuf decode）。它在浏览器里用 313 行
   （`frontend/src/proto_raw_decoder.js` + `protobuf_decoder/`）把 base64 原始字节解成
   `{fieldNumber: value}` 树，和解码后的 JSON **并排**看，还有一个 "RD" 按钮在两种解释间切
   （`Repeated Int` vs `Repeated Signed Int`，`leftOver` 报告剩余字节）。
   → 这一条直接命中我们已确认的硬伤：`proto_json.rs:35` 对不在 `CMD_ID_MESSAGES` 里的命令
   **直接返回 `None`**，所以 `capture.pcap` 的 172 条、`reconnect.pcap` 的 703 条 `unknown`
   在两个前端里**什么都没有**（连 raw 都不给，`raw` 只在"有类型但解析失败"时才吐，`proto_json.rs:52-58`）。
   把字段号树放进 core 的输出契约，Android 详情页**免费**一起受益，只需一份实现。
2. **上传 pcap/pcapng 做离线回放**是前端一等公民（`App.svelte:69-80` + `frontend_server.go:37-49`）
   —— 印证我们 P3 的"用 pcap 文件调试"是对的方向。
3. **连接级事件当作行插进包墙**：它用 `packetId === 0` 的合成行显示
   `Handshake established / Handshake pls / Disconnected`（`sniffer.go:183-196`），前端靠它重置
   相对时间基准。→ 我们的 `SessionPhase` 变化、`KeyRecovery` 命中/失败、`handshake requested`
   应该同样作为**内联系统行**出现在列表里，而不是只写在日志面板。调试盲态时一眼能看懂。
4. **配置化噪声黑名单** `config.json.packetFilter`（`sniffer.go:295-297`，进 SSE 之前就丢）
   —— 我们有 923 个批次和一大堆 `GetScenePointReq`，需要同样的过滤位（放在展示层，不能影响缓存/采集）。
5. 双轴过滤（命令名/ID 与 JSON 内容，带 AND/OR），且**命中结果单独一张表放在流式表上方**
   （`App.svelte:349-379`），流继续滚、搜索不打断 —— 比我们 Android 现在的单列表好。
6. "锁底滚动"开关、"复制 JSON"与"复制 raw base64"分开两个按钮、导出**过滤后**集合。

**不要抄：**

- 传输层：`frontend_server.go:12` 是**一个全局 `chan string`**，SSE 只有一条通道
  —— 第二个浏览器标签会抢走事件、慢客户端会阻塞抓包循环（`sniffer.go` 侧 fire-and-forget 掩盖了这点）。
- 每包 base64 全量随 SSE 推（大包时流量翻倍）。
- 无错误通道：缺密钥时 `log.Println` + `closeHandle()`（`sniffer.go:206-209`）直接停抓，
  前端**完全没有任何提示**；SSE 断开只 `console.log`。→ 我们要的正是反面：密钥失败必须成为一等事件
  （本方案 §5 的 `KeyRecovery`）。
- 无上限 `Packets` 数组 + 每包 `concat` 复制；每次按键 + 每包都重算 `FilteredPackets`，
  还夹 `tick()` + `setTimeout(10)` 双滚动 hack（`App.svelte:240-259`）。
- 它**没有**进度视图（没有 items/characters/achievements 收到了没），"抓包要在进门前启动"
  只写在 README 里不在界面上 —— 这块我们 Android 已有的 `SessionPhase` 比它强，别倒退。

### 4.2 stove-helper

**抄：**
1. `UnionCmdNotify` 展开（见 §3）。
2. 全量解码日志：它每条包都吐 `head/body` JSON。→ 我们做成 `--dump-jsonl`（P3），
   做版本间 diff、回归对比非常好用。
3. 抓包同时落原始流（它是 pcapng，我们是 pcap）+ 存 seed 以便续跑（我们已用 `known_bodies.bin`，更优）。

**不抄：**
1. 启动时解析外部 `.proto` 文本 + `cmdid.csv`：我们从嵌入 `FileDescriptorSet` 反射，版本更新只需重生成 blob。
2. 它的 `if/else if name == ...` 手工分派：我们按 descriptor 走。
3. 它的密钥路径（dispatch HTTP 的 `client_secret_key` → EC2B → 4096 keystream）：那是私有服 StoveGI 的路子，
   官方 CN 7.0 用不了。
4. 它的工程缺陷：`:8080` 是空 default mux（**它没有任何对外传输层**）、goroutine 无锁写同一 rawlog 文件、
   `GetFieldByName(...).(T)` 不断言会 panic、方向靠 src port 猜、每版本要重新 dump `.pem`。

---

## 5. 推荐架构

```
输入源                       共享核心 irminsul-decode（纯 Rust，无 JNI / 无 GUI / 无 libpcap 硬依赖）
┌ Android VPN (tun) ─┐  ┌───────────────────────────────────────────────────┐
│ macOS pcap 文件     ┼─▶│ CaptureSource: Live | PcapFile                    │
│ 回归 pcap           │  │ Session::feed(frame) -> Vec<PacketEvent>          │
└───────────────────┘  │   · 命令展开：信封 → 子命令（父子关联，见 §3）        │
                        │   · 环形缓存：摘要快取 + 全文惰性（两入口）           │
                        │   · 密钥推导：握手 seed → 已知明文 → 时间暴力         │
                        │   · KeyRecovery 结果作为事件输出（可诊断）           │
                        │   · known bodies：存储由宿主注入（trait SampleStore） │
                        │   · SessionPhase / CaptureTraffic / 完成边沿        │
                        │ PlayerData + GOOD / UIAF / CSV / JSONL 导出         │
                        └───────────────┬───────────────────┬───────────────┘
                                  irminsul-jni（薄）   irminsul-viewer（bin，开发机跑）
                                  只做 JSON 序列化 +    4 个端点(start/stop/upload/stream)
                                  线程 + JNI 生命周期    + SSE fan-out + 自写网页前端
                                  → Android App        包墙 · 详情 · raw 树 · 回放/过滤
```

**桌面 egui 客户端从计划里删除**：不再维护 `irminsul/src/app.rs`、`monitor.rs`、`capture/*`，
`konkers/irminsul` 桌面版只作为 GOOD 导出的**对照实现**留着。mac 侧的全部需求由 `irminsul-viewer` 满足，
而它薄到不构成"一个客户端"。

五条设计约束：

1. **契约不新造**：把 `summary_status.json` 那套双端契约从 JNI 抬到 core，viewer 读同一份 fixture。
   门禁（`contract_tests` + `StatusDecoderTest`）继续锁住。前端拿到的 JSON 与 Android 拿到的**同源同形**。
2. **回放路径零系统依赖**：用 core 自带的 pcap 解析（把 `pcap-check` 那份提升进来），
   **不引 libpcap**，所以 mac 上 `cargo run -- --file x.pcap` 不需要 sudo、不需要 `/dev/bpf`。
   libpcap 只在以后做 live 时才加（feature gate）。
3. **平台差异只走 trait 注入**：样本存储（Android `filesDir` / mac `~/.irminsul` 或 `--out`）、
   日志落地、时间戳来源。core 里不出现 `jni`、`eframe`、`android`。
4. **一条命令 = 一个可检视记录**：信封展开后，`PacketRecord` 需要 `parent`/`depth`（或 `inner_index`），
   列表里父子同列可折叠。这会影响契约字段 → 放 P0 一起做，别二次改。
5. **`unknown` 也必须可读**：无 schema 的字段号树（`{fieldNumber: value}` + 变长/定长两种解释 +
   `leftOver`）由 **core 产出并写进 JSON 契约**，不放进任何一个前端。这样 Android 详情页与桌面共用一份实现，
   且修掉 `proto_json.rs:35` 对未知命令返回 `None` 导致的 172/703 条空白。同样并入 P1 的这次 schema 变更。

aa 库（fork）只补三件事，不重写解析模型：
- 信封展开能力（把 `cmd_list` 子命令以结构化形式交出去）；
- 未知命令的字段号树（并把 `command_to_json` 的 `Option` 改成"永远有输出"）；
- `KeyRecovery` 结果外显（今天只能从 logcat 字符串判断"为什么没解开"）。

---

## 6. 阶段计划与验收

| 阶段 | 内容 | 验收（全部可机器判定） |
|---|---|---|
| **P0 抬核心** | `irminsul-decode` crate：pipeline + 缓存 + JSON 契约 + known bodies + `CaptureSource{Live,PcapFile}`；JNI 变薄；两份 `irminsul-core` 合一 | 1071/0、5285/0 不变；fixture 双端仍过；JNI 符号仍 12（反向验证仍会失败）；Android 实况仍全量收 |
| **P1 拆信封 + 补可检视性** | `UnionCmdNotify` 展开、未知命令字段号树、`PacketRecord` 父子字段、`KeyRecovery` 事件 —— **一次改完 schema**；Android 详情页同步显示 raw 树 | 记录数上升且 `UnionCmdNotify` 的 `field_count` 不再是 1；`unknown` 命令从"空白"变成有字段树；fixture 双端仍过；0 解析错误不退化；并**量化"内层是否藏着未被 `matches_*` 的数据类别"**，据此决定是否扩到采集路径 |
| **P2 viewer 骨架** | 新 bin `irminsul-viewer`：`--file <pcap>` 起 http，4 个端点 + **per-client SSE fan-out**（不抄它的单 channel）；GOOD/CSV 导出做成子命令；桌面 crate 的 `app.rs/monitor.rs/capture/*` 不再维护 | `curl -N localhost:1984/api/stream` 能收到与 Android 契约同形的 JSON；两个浏览器标签同时收得到；`cargo build --locked` 通过 |
| **P3 自写网页前端** | 一个页面：虚拟滚动包墙 + 详情双栏（解码 JSON / 字段号 raw 树）+ 双轴过滤与独立命中表 + 噪声黑名单 + 锁底滚动 + 复制 JSON / 复制 raw + 拖入 pcap 回放；**加它没有的**：进度与 `SessionPhase`、`KeyRecovery` 内联系统行、错误提示 | `/tmp/full.pcap` 出 ≥2707、`/tmp/e2e.pcap` 出 3919，列表里直接看得见 `KeyRecovery::KnownBody` 那一行；`unknown` 命令能看到字段号树；`--dump-jsonl` 与 Android 侧输出逐行 diff |
| **P4 可选** | mac live 后端（libpcap，feature 门控）、盲态提示/stall 刻度搬到桌面、pcapng 对齐 | — |

P0/P1 有依赖关系（父子字段会改契约），所以信封要一次做完，不要分两次改 schema。

---

## 7. 否掉的方案

| 方案 | 否因 |
|---|---|
| 直接复用 Iridium-NG 的前端 | **它没有 LICENSE**（`license: null`、无文件、无版权头）→ 默认版权全保留，不能复制/修改/再分发；我们这条链要对外分发 AAR，不能带这种依赖。且它的信息不够：无父子命令、无 `KeyRecovery`/进度/错误通道、还要外部 `packetIds.json`/`proto/` 数据；最后提交 2023-05。**抄形不抄码**（见 §4.1、§9 决定 2） |
| 只在桌面加 file 模式 + 包界面（不共享核心） | 最快看到 UI，但成为第 4 份实现；Android/桌面行为必漂。本轮盲态 bug 正是"三处实现各执一词"漂出来的 |
| 桌面起 HTTP/WS，Android 连它 | 传输层本身可做（Iridium-NG 用 gin + SSE 就跑起来了），但**它的实现方式是坏的**：单个全局 channel，第二个客户端抢事件、慢客户端阻塞抓包循环。真要做必须 per-client fan-out + 背压 + 错误通道，那是独立一块工作；而 Android 已有可用 UI，为它造协议没有收益。桌面若走 web 前端另说，见 §9 决定 2 |
| Compose Multiplatform 统一 UI | 省不掉 Rust 侧工作，却要重做已过门禁的 JVM 契约测试，收益/风险不划算 |
| 把 aa 换成 stove-helper 式外部 proto + CSV | 可维护性倒退，且我们已经从 codegen 迁到嵌入描述符（见 commit `2408e9b`） |

---

## 8. 风险与前置

- **许可纪律**：参考项目一律"抄形不抄码"。Iridium-NG 无 LICENSE、stove-helper 带的是逐版本 dump 出来的
  `private_key_*.pem` —— 两者都不要把文件搬进我们的仓库。我们的密钥只来自 `auto-artifactarium` 上游既有那两把。
- `irminsul/Cargo.lock` 里没有 `shadyrispy` 源 → `--locked` 现在直接失败；`build.rs:22-52` 首次构建要联网取
  `anime-game-data`，失败是 panic。走 viewer 路线后这两条**只剩对照价值**（不再发布桌面 App 就不必修），
  但如果还想要上游桌面的 GOOD 导出做对照，P2 得先解决。
- `irminsul/docs/android-split-plan.md` 里"`feat/heuristic-matching` 基于 6.6、落后两个游戏版本"**已过期**
  （fork 现在含 V70 protos + 反射 JSON + 扩展头修复 + 已知明文）。要么更新该文档，要么在本文件里指认它过期。
- 上游删了 `[lib]`（纯 binary crate），桌面 fork 历史上为此自己加回 `[lib]`+`jni.rs`，每次同步必冲突。
  本方案把核心外置到独立 crate 后，桌面 fork **不再需要** `[lib]` —— 顺手消掉这个长期冲突源。
- 首次遇到新游戏版本时，core 里没有样本 → 第一份 pcap 仍可能只解 4 条命令。回放调试时要记住这点，
  别当成回归。
- 环形缓存上限 64MB 是给手机定的；mac 上跑 14MB 的 `reconnect.pcap` 会丢历史包。P3 要不要给桌面放开
  （或改成落盘 sqlite/jsonl）需要决定，见 §9。

---

## 9. 待你拍的三个决定（不影响 P0 开工）

1. 新 crate 名字与归属：`irminsul-decode` 放在 `irminsul-android/capture/rust/` 下，还是独立仓库当上游？
   （倾向：先在 `capture/rust/` 下建，稳定后再上提，避免现在就动 submodule 结构。）
2. ~~桌面 UI 走哪条路？~~ **已定（2026-09-21）**：不写桌面客户端。
   `core + irminsul-viewer(薄 bin) + 自写网页前端`；Iridium-NG 的前端**只用形、不用码**（无 LICENSE）。
   桌面 egui 那条路整体作废，`konkers/irminsul` 只当 GOOD 导出的对照参考。
   副产品：设备侧不必跑任何 server —— Android 用现成的 `dumpRawPackets` 落 pcap，`adb pull` 后拖进 viewer 回放。
   （真要实时看设备流量，再加 `adb forward tcp:1984 tcp:1984` 一类通道，属 P4。）
3. 桌面侧缓存策略：沿用 64MB 环形、还是允许无上限/落盘？（倾向：桌面默认 512MB + `--dump-jsonl`，
   契约字段不变。）

---

## 10. 重跑这些测量的命令（防止下次又靠猜）

```bash
export PATH=$PATH:$HOME/.cargo/bin
export CARGO_TARGET_DIR=/tmp/pcaptarget
cd /Users/esc/Documents/yas2/irminsul-android/capture/rust/pcap-check
cargo build --release --offline
for f in capture reconnect full; do
  /tmp/pcaptarget/release/pcap-check /tmp/$f.pcap 2>/dev/null | grep -E 'commands decoded|parse errors'
done

# 频次与信封现状（7516 = CN 7.0.0 的 UnionCmdNotify）
/tmp/pcaptarget/release/pcap-check /tmp/capture.pcap 2>/dev/null | grep -oE 'id=[0-9]+ [A-Za-z]+' | awk '{print $2}' | sort | uniq -c | sort -rn | head
/tmp/pcaptarget/release/pcap-check /tmp/capture.pcap 7516 2>/dev/null | sed -n '/cmd 7516/,/^\[cmd\]/p'

# 多会话夹具：不要用 cat a.pcap b.pcap（24 字节全局头不是 16 的倍数，会在接缝后静默截断），
# 要解析后重写一个头 + 全部记录。
```
