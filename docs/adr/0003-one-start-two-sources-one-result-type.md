# 3. One start, two sources, one result type

Date: 2026-09-18
Status: accepted

## Context

After candidate 1, the facade still had four members describing two things:
`start(context, sink, config)` for live traffic, and `startPipeline` /
`stopPipeline` / `importPcap(path)` for replaying a recorded pcap. A host that
wanted the second had to learn that a "pipeline" exists, that `importPcap`
requires it to be started first, and that `startPipeline` is not the same as
`start`. Two of its three call sites in `:app` got that ordering right only by
convention, and `processPcapFile` read the file **on the main thread** — the
queue's blocking `put()` meant a large pcap could hang the UI.

Fallible calls disagreed about how to say no: `initNative` returned a sealed
`InitResult`, `openFixSettings` a `Boolean`, and the export/body calls `String?`,
which a host turned back into a `RuntimeException` with the reason thrown away.

Separately, `PacketLog` was public with public mutators, so a host could
`clear()` or `appendAll()` the decoded list — the second-writer problem candidate
1 had removed for `CaptureStatus`, re-appearing one file over. `CaptureStatus`
itself carried eight progress flows that no code ever read, duplicating
`DataStatus`.

## Decision

**`start(context, source, sink, config)` with `sealed interface CaptureSource {
Vpn, File(path) }`.** Two adapters at one seam, chosen because they genuinely
differ: one is started by an intent to a foreground service and ends on `stop()`;
the other is read on a module-owned IO thread and ends when the file does.
`startPipeline` / `stopPipeline` / `importPcap` are private again; `stop(context)`
works for both and only touches the service when a capture is live.

**`CaptureResult<T>` + `CaptureError` everywhere a call can fail.** One shape for
init, reset, settings, exports and on-demand bodies. The result carries a
*classification*, not a message: the native layer already reports detail to
`logs`, and duplicating it into a second channel would let the two disagree.

**`packets` is now `StateFlow<List<PacketRecord>>`.** The ring buffer
(`PacketRingBuffer`) is internal, so the module is the only writer again. A host
that wants the list emptied calls `clearPackets()`, which is an operation on the
module's own state rather than a handed-out mutator.

**`CaptureStatus` keeps one fact.** `isCapturing` stays (the service writes it);
the progress mirrors and `updateParsingProgress` are gone, since that data
already arrives on every `DataStatus` publish. `CaptureService`'s private
`_isRunning` was folded into the same flow, so "am I capturing" has one home.

## Alternatives rejected

- **A constructible `CaptureSession` object.** It would promise more than the
  implementation can keep: the native sniffer is process-global state (session
  keys, stream reassembly, a single packet-id counter), so a second session
  would corrupt the first. The facade stays an `object`, and the interface says
  so: `start` ends any running session first.
- **Returning `Result<T>` from kotlin.** Its `failure` is a `Throwable`, and the
  native side has nothing to throw across the boundary without inventing an
  exception type per condition.

## Consequences

- pcap replay no longer blocks the caller's thread, which removes an ANR in
  `:app`'s import path as a side effect.
- `start(File)` while a VPN capture is live leaves the service running, so live
  and replayed packets share the queue. Pre-existing; `stop()` first if a host
  cares. Not worth an interface member today.
- Dead code removed alongside: `CaptureService.isRunning`, the pcap magic byte
  array in `PacketProcessor`, and `MainViewModel.processPcapFileByPath` (no
  caller once `start` took the source).

## Follow-up — the session had to become real (2026-09-19)

A review of the above found that "session" was a word the interface used without
anything in the code owning it, and every "once per session" claim degraded into
a level-triggered one:

- `completion` was edge-triggered per **sniffer**, not per session: the sticky
  flags reset only in `nativeCreateSniffer`, so a second capture in the same
  process never completed again. `start` now calls `NativeLib.resetSession()`, a
  native export that clears the three `has_*` flags and the completion edge while
  leaving collected player data alone.
- `Config.onDataUpdated` documented "first show up" and fired on every packet,
  which made the host log `[SUCCESS] Items data captured!` continuously. The
  pipeline worker (which does live exactly one session) now tracks the edges.
- `stop()` guarded on `isCapturing`, which the service sets only after
  `establish()` succeeds, so a stop during VPN setup was dropped; and `start()`
  claimed to end the previous session while the service just early-returned. The
  facade now remembers the `CaptureSource` it was given and tears down from that
  — including cancelling a replay that would otherwise sit in a blocking `put()`
  forever with its consumer gone.
- `abortStart()` is gone (see the amendment in `docs/adr/0001`): the host's
  "waiting for consent" flag is host state, and no module flow edge was ever
  going to clear it.
- `droppedPackets` publishes on every drop. `StateFlow` conflates, so the burst
  throttling it replaced only made the counter lie by up to 99.
