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

Produces `com.esc.irminsul:capture:1.2.0`.

## Integrate

```kotlin
dependencies { implementation("com.esc.irminsul:capture:1.2.0") }
```

The public interface is the `com.esc.irminsul.capture` package; everything else
is `internal` (see `docs/adr/0001`).

```kotlin
IrminsulCapture.initNative(context)                       // InitResult.Ready | NotInstalled | Failed

val sink = object : DataStatusSink {
    override fun publish(status: DataStatus) { /* chars/artifacts/weapons/achievements */ }
}

// Permissions: ask the module what is missing, let it open the right page.
val blocked = IrminsulCapture.refreshPermissions(context)   // also StateFlow<PermissionSnapshot>
if (!blocked.vpnPermissionGranted) {
    IrminsulCapture.vpnConsentIntent(context)?.let { launcher.launch(it) }
}
IrminsulCapture.openFixSettings(context, PermissionKind.Notifications)  // ROM chain resolved inside

IrminsulCapture.start(applicationContext, sink)             // Config(completionNotification = false) for hosts with their own UI
IrminsulCapture.abortStart()                                // consent declined
IrminsulCapture.stop(applicationContext)
IrminsulCapture.close()

IrminsulCapture.packets.records: StateFlow<List<PacketRecord>>   // ring buffer, newest last
IrminsulCapture.isCapturing / logs / completion / permissions
IrminsulCapture.importPcap(path)                        // replay a saved capture
IrminsulCapture.commandBody(packetId, commandIndex)     // full proto body JSON, on demand
IrminsulCapture.exportGood(settingsJson) / exportAchievements(format)
```

`capture-sample/` is a working host that consumes the published coordinates —
it is built against the AAR from mavenLocal, never against the source project.

## Build-time guarantees

`verifyNativeSymbols` (finalises `:capture:assembleDebug`) runs `llvm-nm` over
the merged native libs and fails if either `.so` is missing or a JNI symbol
Kotlin declares is not exported. Kotlin names `external fun`s by package and
class, while C and Rust export them as literal strings, so a one-sided rename
would otherwise only surface as a runtime `UnsatisfiedLinkError`.

## Constraints

- **One VPN at a time** (Android): starting here disconnects any other tunnel.
- **arm64-v8a only**; the game client packages captured are
  `com.miHoYo.GenshinImpact` / `.Yuanshen` / `.ys.bilibili`.
- The library posts a foreground-service notification; request
  `POST_NOTIFICATIONS` on Android 13+.
- Decryption keys and the V70 proto schema are embedded in the AAR — publishing
  it publicly distributes that capability.
