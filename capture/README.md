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

Produces `com.esc.irminsul:capture:1.1.0`.

## Integrate

```kotlin
dependencies { implementation("com.esc.irminsul:capture:1.1.0") }
```

```kotlin
IrminsulCapture.initNative()                        // once, at startup

val sink = object : DataStatusSink {
    override fun publish(status: DataStatus) { /* chars/artifacts/weapons/achievements */ }
}

IrminsulCapture.vpnPermissionIntent(context)        // null if already granted
    ?.let { consentLauncher.launch(it) }            // start after RESULT_OK

IrminsulCapture.start(context.applicationContext, sink)
IrminsulCapture.stop(context.applicationContext)

IrminsulCapture.isCapturing: StateFlow<Boolean>
IrminsulCapture.packets.records: StateFlow<List<PacketRecord>>   // ring buffer, newest last
IrminsulCapture.importPcap(path)                   // replay a saved capture
```

`capture-sample/` is a working host that consumes the published coordinates.

## Constraints

- **One VPN at a time** (Android): starting here disconnects any other tunnel.
- **arm64-v8a only**; the game client packages captured are
  `com.miHoYo.GenshinImpact` / `.Yuanshen` / `.ys.bilibili`.
- The library posts a foreground-service notification; request
  `POST_NOTIFICATIONS` on Android 13+.
- Decryption keys and the V70 proto schema are embedded in the AAR — publishing
  it publicly distributes that capability.
