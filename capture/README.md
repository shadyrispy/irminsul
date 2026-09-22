# Irminsul Capture

Genshin packet capture, session decryption and protobuf command decoding as an
embeddable Android library. The AAR is self-contained: the VPN service and its
permissions merge in automatically, and it ships both native libraries
(`libcapture.so` — VPN loop + zdtun flow tracking; `libirminsul.so` —
decryption and proto parsing, arm64-v8a only). Nothing else: it used to carry a
`libc++_shared.so` too, which no library in it links against (their only
undefined C++-adjacent symbols are `__cxa_atexit`/`__cxa_finalize@LIBC`, from
bionic) and which collided, content for content nothing, with the one every
OpenCV host already ships.

## Publish

```bash
./gradlew :capture:check :capture:assembleRelease :capture:publishToMavenLocal
```

Produces `com.esc.irminsul:capture:<version>`, where the version is the one line
`captureVersion` in `gradle.properties`. That line also numbers the host app
(`versionName`, and `versionCode` derived from it as `1.9.0 → 10900`) and the
sample's dependency on the published AAR, so a release cannot be stamped two ways.
Bump it when the seam moves: a new public type or member is a minor, a behaviour fix
is a patch. The Rust crates keep their own versions — those are internal, and nothing
a host installs is numbered from them.

CI runs the same three tasks in a `library` job, then insists the AAR actually
carries `libirminsul.so` and `libcapture.so` and builds `capture-sample` against the
published artifact rather than the source project — the one claim a host's
`implementation(...)` depends on.

## Integrate

```kotlin
dependencies { implementation("com.esc.irminsul:capture:1.9.0") }   // = captureVersion
```

The public interface is the `com.esc.irminsul.capture` package; everything else
is `internal` (see `docs/adr/0001` and `docs/adr/0002`). The classes are compiled
for Java 11, so a host does not have to raise its own target to take the library.

```kotlin
IrminsulCapture.probeNativeSupport()   // Ok, or Err(NativeUnavailable) on an ABI the libs
                                       // are not built for — ask this before offering capture
IrminsulCapture.initNative(context)      // CaptureResult.Ok | Err(NativeUnavailable | SnifferInitFailed)

val sink = object : DataStatusSink {
    override fun publish(status: DataStatus) { /* chars/artifacts/weapons/achievements */ }
}

// Permissions: ask the module what is missing, let it open the right page.
val blocked = IrminsulCapture.refreshPermissions(context)   // also StateFlow<PermissionSnapshot>
if (!blocked.vpnPermissionGranted) {
    IrminsulCapture.vpnConsentIntent(context)?.let { launcher.launch(it) }
}
IrminsulCapture.openFixSettings(context, PermissionKind.Notifications)  // ROM chain resolved inside

IrminsulCapture.start(applicationContext, CaptureSource.Vpn, sink)
    // CaptureResult<Unit>: Ok once the session has actually been set up.
    // Config(completionNotification = false) for hosts with their own UI
    // start() ends any running session first; there is one capture at a time
    // Err(WrongProcess) starts nothing: a live capture handed the packet queue
    // through a process-static field, so from a second process the tunnel would
    // come up and deliver nothing. A host with more than one process has to start
    // from the default one; a CaptureSource.File replay may run anywhere.
IrminsulCapture.stop(applicationContext)                    // works for either source
IrminsulCapture.close()

IrminsulCapture.packets: StateFlow<List<PacketRecord>>      // ring buffer, newest last, read-only
IrminsulCapture.isCapturing / droppedPackets / logs / completion / permissions
IrminsulCapture.keyOrigin: StateFlow<KeyOrigin?>              // which key decrypts, and how
IrminsulCapture.commandBody(packetId, commandIndex)         // full proto body JSON, on demand
IrminsulCapture.exportGood(settingsJson) / exportAchievements(format)
IrminsulCapture.exportKnownBodies(destDir)                   // the samples, where a shell can read them
```

### The GOOD export

`exportGood(settingsJson)` returns `{format:"GOOD", version:3, source:"Irminsul",
characters, artifacts, weapons, materials}` with **camelCase** keys (`setKey`,
`slotKey`, `mainStatKey`, `substats[{key,value,initialValue}]`, `location`, `lock`,
`totalRolls`, `astralMark`, `elixerCrafted`, `unactivatedSubstats`), which is the
shape a host keys its own plans off. `settingsJson` filters it
(`include_*`, `min_artifact_rarity`, `fake_initialize_4th_line`, …); a partial object is
fine — the fields it omits take their defaults, which *include* 3★ artifacts, so a host
that only accepts 4★/5★ has to ask for that here rather than filter after the fact.

Two fields are ours rather than GOOD's, both because a host would otherwise
re-derive them from a name table: `weapons[].rarity` (the star count the export
already filtered on) and `characters[].element` (read off the burst, so null for a
character whose burst was never sent). What is **not** in the export, because the
packets do not carry it: an artifact's favourite flag, a character's fame, and a
talent's grey-lock state; and no main-stat *value*, which needs a level table the
embedded game data does not hold. `pcap-check --good <capture.pcap>` prints this
export for a recorded capture on a desktop — the same `Session::export_good` the
phone calls, so a renamed key fails there instead of on a player.

### Catching the login

A key only exists if the handshake passes through the tunnel, so a capture that
starts after the player is already in-game sees traffic and decrypts nothing.
The module reports that state rather than hiding it. Which key is decrypting is
session state, read after every packet (`KeyOrigin`: `Dispatch` = handshake-era
packets only, `KnownBody` = opened from a saved sample, `TimeSearch` = derived
from this handshake), because the frames where a key dies are exactly the frames
that carry no payload to say so:

```kotlin
IrminsulCapture.sessionPhase: StateFlow<SessionPhase>  // Idle | AwaitingLogin | Collecting | Complete
IrminsulCapture.traffic: StateFlow<CaptureTraffic>     // bytes + connections this session

// Close the game and reopen it, so its login runs in front of the capture:
IrminsulCapture.forceRelogin(applicationContext)
```

`SessionPhase` is derived from `keyOrigin`, not from a packet count: the dispatch
key alone decodes the handshake's own packets, so a blind session would otherwise
read as `Collecting` (see `docs/adr/0004`, amendment 4).

Nothing closes the game unless a host asks it to. `Config.autoForceRelogin` is
**off by default**, and `forceRelogin` also needs `KILL_BACKGROUND_PROCESSES`
(normal protection level, granted at install), which this library deliberately
does not declare on its hosts' behalf — a host that opts in adds it to its own
manifest. Without it the game is only brought to the foreground, which produces
no new login. What the module does guarantee is that the blind state is
nameable: `sessionPhase == AwaitingLogin` while `traffic` moves.

Why a stall and how long, measured on a live client (2026-09-20, BlueStacks,
CN 7.0.0): the client has a hard heartbeat timeout. With the tunnel genuinely
black-holed — packets dropped in both directions — up to 45s of silence is
silently resumed with the old key; 50s and 60s end in 「连接已断开 · 连接超时」,
and the client sits on the title screen until the player re-enters, at which
point the still-running capture catches the fresh login and completes. So the
dial is roughly 60s, and the lever needs no extra permission — see
`docs/adr/0004` for the ladder.

### Re-entering is enough, because a re-auth is decryptable

A re-auth is *not* derivable from its own handshake: the client picks its rand key
once per process, so the packet that reveals the server's half was stamped hours
after the seed was chosen, and no wall-clock time near it yields the key. What
opens such a session instead is a command body the sniffer has already seen —
the anti-cheat Lua shell payload, whose 167875 bytes carry the 4096-byte XOR key
41 times over and which was measured identical across client processes, days and
two devices. The sniffer keeps the four longest such bodies it decodes, persists
them under the app's files dir (`known_bodies.bin`), and replays them against
later traffic:

```kotlin
IrminsulCapture.initNative(context)   // takes context.filesDir for the samples
```

So a player who merely lets the game reconnect — or re-enters from the title
screen — is enough; nothing has to be killed. `exportKnownBodies(destDir)` copies
those samples out of the app's private dir, which on API 30+ images is the only
way to get them onto a machine that refuses `run-as` (the debug build's
`EXPORT_SAMPLES` adb hook points it at the public Downloads dir, since
`adb pull` cannot read `Android/data` either). A capture on a fresh game version,
before any session has been opened once, still has no sample to work from and
stays in `AwaitingLogin`.

To replay a saved capture through the same pipeline, start with the other
source — the module reads the file off the caller's thread:

```kotlin
IrminsulCapture.start(context, CaptureSource.File(path), sink)
```

Wait for [IrminsulCapture.replayFinished] to go `true` before reading the export —
not for [packets] to stop growing. That ring is capped, so its size flattens long
before a file runs out, and "size unchanged" is indistinguishable from "replay
over": a host that reads early gets an inventory missing its last category.

`capture-sample/` is a working host that consumes the published coordinates —
it is built against the AAR from mavenLocal, never against the source project.

## Build-time guarantees

Two gates, because the module has two contracts with its own native code:

- `verifyNativeSymbols` (finalises `:capture:assembleDebug`) derives the
  expected `Java_…` names from the Kotlin `external fun` declarations and diffs
  them against `llvm-nm` output for the merged native libs, in both directions:
  a missing export fails, and so does an export no Kotlin declaration asks for.
  Kotlin names `external fun`s by package and class, while C and Rust export
  them as literal strings, so a one-sided rename would otherwise only surface as
  a runtime `UnsatisfiedLinkError`.
- `verifyNativePayloadContract` (part of `:capture:check`, alongside the
  `StatusDecoderTest` unit tests) pins the JSON keys that cross
  `nativeProcessPacket`: `capture/testdata/summary_status.json` is the one
  fixture the Rust producer and the Kotlin decoder both test against, so
  renaming a key on either side fails a build instead of silently reading back
  as zero.

CI runs both: the debug job is `assembleDebug :capture:check`.

## Constraints

- **One VPN at a time** (Android): starting here disconnects any other tunnel.
- **A live capture drops, an import does not**: the decode pipeline cannot slow
  the native capture thread, so when `Config.queueCapacity` fills, packets are
  dropped and counted in `droppedPackets`. A `CaptureSource.File` replay blocks
  until the queue drains instead.
- **arm64-v8a only**; the game client packages captured are
  `com.miHoYo.GenshinImpact` / `.Yuanshen` / `.ys.bilibili`. Ask
  [IrminsulCapture.probeNativeSupport] rather than reading the ABI yourself: it is
  the same question the loader answers, and it does not start a session to ask it.
- **`INTERNET` is not optional, and neither is a working forwarder.** The tunnel
  carries the game's packets out again (zdtun opens the outbound sockets, and
  `protect()` keeps them out of the tunnel), so a host that strips `INTERNET` does
  not get a degraded capture — it gets a game that loses its connection. A host
  with a "this app never talks to the network" promise has to scope that promise
  around this feature rather than keep it through it. `ACCESS_NETWORK_STATE` is the
  softer one: without it the DNS forwarder falls back to a public resolver instead
  of the network's own.
- **The notification permission belongs to the host that shows the notification.**
  This module declares neither `POST_NOTIFICATIONS` nor
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: the completion heads-up is one line of
  [Config], and the battery-optimization list page opens without a permission of
  its own (the per-package page, which needs one, is still offered first to any
  host that declared it). A host that wants either **declares it in its own
  manifest** — asking at runtime for an undeclared permission is denied without
  ever showing a dialog, and `PermissionSnapshot` reports such a host as
  unblocked rather than waiting on a grant that will never come. The
  foreground-service notification the VPN needs is not gated on it.
- **Heads-up may need one manual enable.** Some ROMs (verified on EMUI 10)
  create a newly requested `IMPORTANCE_HIGH` channel at `DEFAULT` and mark the
  importance user-locked, so `PermissionSnapshot.headsUpEnabled` can be false on
  a fresh install and no amount of re-creating the channel will change it.
  `openFixSettings(PermissionKind.HeadsUp)` opens that channel's settings page,
  which is the only way through.
- Decryption keys and the V70 proto schema are embedded in the AAR — publishing
  it publicly distributes that capability.
