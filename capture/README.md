# Irminsul Capture

Genshin packet capture, session decryption and protobuf command decoding as an
embeddable Android library. The AAR is self-contained: the VPN service and its
permissions merge in automatically, and it ships both native libraries
(`libcapture.so` — VPN loop + zdtun flow tracking; `libirminsul.so` —
decryption and proto parsing, arm64-v8a only).

## Publish

```bash
./gradlew :capture:publishToMavenLocal     # or publishReleasePublicationTo<repo>
```

Produces `com.esc.irminsul:capture:1.4.0`.

## Integrate

```kotlin
dependencies { implementation("com.esc.irminsul:capture:1.4.0") }
```

The public interface is the `com.esc.irminsul.capture` package; everything else
is `internal` (see `docs/adr/0001` and `docs/adr/0002`).

```kotlin
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
    // Config(completionNotification = false) for hosts with their own UI
    // start() ends any running session first; there is one capture at a time
IrminsulCapture.stop(applicationContext)                    // works for either source
IrminsulCapture.close()

IrminsulCapture.packets: StateFlow<List<PacketRecord>>      // ring buffer, newest last, read-only
IrminsulCapture.isCapturing / droppedPackets / logs / completion / permissions
IrminsulCapture.commandBody(packetId, commandIndex)         // full proto body JSON, on demand
IrminsulCapture.exportGood(settingsJson) / exportAchievements(format)
```

To replay a saved capture through the same pipeline, start with the other
source — the module reads the file off the caller's thread:

```kotlin
IrminsulCapture.start(context, CaptureSource.File(path), sink)
```

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
  `com.miHoYo.GenshinImpact` / `.Yuanshen` / `.ys.bilibili`.
- The library posts a foreground-service notification; request
  `POST_NOTIFICATIONS` on Android 13+.
- Decryption keys and the V70 proto schema are embedded in the AAR — publishing
  it publicly distributes that capability.
