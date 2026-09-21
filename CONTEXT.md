# Irminsul — domain language

Shared vocabulary for the capture stack. Use these words exactly; the
architecture words (module, interface, seam, depth) come from the
`codebase-design` vocabulary and are not repeated here.

## Capture session

One continuous run of the capture, from consent-granted start to stop. A session
owns the session key, the decoded-command ring buffer, the drop counter, the
collected player data and the completion edge. `IrminsulCapture.start` begins
one — ending the previous session first — and `stop` ends it. Starting a session
resets the native per-session flags, so a second capture can complete again
without discarding anything the host already exported.

## Session phase

`SessionPhase`: where a session stands — `Idle`, `AwaitingLogin`, `Collecting`,
`Complete` — derived from **`KeyOrigin`** and the completion edge, never stored.
Its reason for existing is the **blind session**: `AwaitingLogin` while
`CaptureTraffic` moves means game traffic the session cannot decrypt, which is
otherwise indistinguishable from a game that is simply not playing. The phase
cannot be derived from a decoded-command count, because the dispatch key alone
decodes the handshake's own packets (`docs/adr/0004`, amendment 4).

## Key origin

`KeyOrigin`: how the key that decrypts the session was obtained — `Dispatch`
(the per-version dispatch key, so only the handshake's own packets), `KnownBody`
(a saved sample opened a blind session), `TimeSearch` (derived from this
handshake's seed). Null until something opens the session, and it can go back to
null when the client re-logs in — which is why it is session **state** read after
every packet (`IrminsulCapture.keyOrigin`, `nativeKeyOrigin`), not a field of the
per-packet payload: a frame that decodes nothing produces no payload at all.

## Force re-login

`forceRelogin` closes the game and reopens it so its login runs inside the
tunnel. It is a convenience, not the cure for a blind session: the player
reconnecting or re-entering from the title screen is enough, because a re-auth is
decryptable from a **known body sample**. It reaches a backgrounded game only
(`killBackgroundProcesses` cannot touch a foreground process), and it needs a
permission the library deliberately does not declare for its hosts. So it is
opt-in everywhere: `Config.autoForceRelogin` is off by default, and the hosts tell
the player rather than doing it to them. See `docs/adr/0004`.

## Known body sample

A decrypted command body long enough to hold the whole session key — the key
repeats every 4096 bytes, so the 167875-byte anti-cheat Lua shell body holds 41
copies of it. Bodies are matched up **tail-aligned** with the frame's trailer,
because the header grows with the client's packet counter. The sniffer keeps the
four longest it decodes and the decode core persists them, which is what lets a
session whose handshake was never seen — a re-auth inside a long-running client,
whose rand key predates the capture — still be opened. Samples go stale when the
game changes those payloads, i.e. per version.
`IrminsulCapture.exportKnownBodies(destDir)` copies them out of the private files
dir, because on API 30+ neither `run-as` nor `adb pull` can reach it.

## Decoded command

One game command recovered from the session's traffic: a `PacketRecord` with
its command id, message name, direction, size, and how many proto fields were
present. A network packet may carry several; a session may carry thousands.
The full protobuf body of a decoded command is fetched on demand by
`IrminsulCapture.commandBody` and may be gone if the native cache evicted it.

## Progress snapshot

`DataStatus`: how much of the player's data the session has decoded so far —
items / characters / weapons / achievements, each with a loaded flag and a
count. Produced by the module, published to the host through `DataStatusSink`.
"Loaded" means the game sent that collection, not that it is complete.

## Completion

The moment a session has all four data categories. Emitted exactly once per
session as `Completion` (with counts) — the native side edge-triggers it,
because the three "have we seen this" flags are sticky — and, unless the host's
`Config` opts out, posted as the heads-up notification. Distinct from
`DataStatus`: completion is an event, progress is state.

## Permission snapshot

`PermissionSnapshot`: everything a capture can be blocked on, judged by the
module, including ROM-specific guidance. A host asks what is missing and calls
`openFixSettings(kind)`; it never assembles settings intents itself.

## Capture source

Where a session's packets come from: `CaptureSource.Vpn` (live, until `stop`)
or `CaptureSource.File` (a recorded pcap, replayed on a module thread). One
pipeline and one `DataStatusSink` behind both, so a host renders either without
a special case.

## Viewer source

Where a desktop replay's frames come from: a dumped file, standard input, or a live
interface read through libpcap (`--file <pcap>`, `--file -`,
`--live [iface] [--bpf <expr>]`). The Android *capture source* is a different choice
made by a different half; the file and the pipe are read by the core's one pcap
reader, generic over any buffered reader for exactly that reason, and a live
interface needs no pcap stream at all — libpcap hands over the same
`(packet, timestamp)` pairs directly.

A source is read on its own thread, because the waits differ: an interface with no
traffic comes back empty every 20ms and that is the moment it notices the replay
stopped listening, while a replay loop that blocked in a read instead could not hear
the operator press `stop`. Only the command line starts a capture — a browser can
load files, never ask for a device. `--live` is one cargo feature (`live`, on by
default), so a build without it still replays files and answers `--live` with why.

## Seam

The capture module's interface is `com.esc.irminsul.capture` — the facade
`IrminsulCapture` plus `DataStatus`, `DataStatusSink`, `PacketRecord`,
`CaptureSource`, `CaptureResult`/`CaptureError`, `PermissionSnapshot`,
`PermissionKind`, `Completion`, `SessionPhase`, `KeyOrigin` and
`CaptureTraffic`. Everything
else lives in `com.esc.irminsul.capture.internal` and is `internal`; see
`docs/adr/0001`.

## Native contracts

Two things are name-level agreements between the Kotlin and native halves, each
with a build-time gate: the JNI symbol names, derived from the Kotlin
`external fun` declarations and diffed against the merged `.so` files
(`verifyNativeSymbols`), and the keys of the per-packet status JSON
(`capture/testdata/summary_status.json`, asserted by both `contract_tests` in
`irminsul-decode` and `StatusDecoderTest`). Both gates run in CI. The desktop
viewer takes the same payload from the same core — `irminsul-viewer
--dump-jsonl` is byte-identical to `pcap-check --status`, whether the capture
reached the viewer as a file or as a pipe, which is how "what a browser sees" is
pinned to "what the phone sees". See `docs/adr/0001`, `docs/adr/0002` and
`docs/adr/0004`.
