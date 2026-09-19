# 1. The capture module's public interface is its package edge

Date: 2026-09-18
Status: accepted, amended 2026-09-19 (see "Amendments")

## Context

`:capture` was extracted out of `:app` and published as an AAR, but the
extraction moved files without moving packages: every class kept
`package com.esc.irminsul`, which is also `:app`'s package. The facade
`IrminsulCapture` was therefore advisory — `:app` reached past it into
`NativeLib`, `CaptureService`, `PermissionHelper`, `RomUtils` and
`CaptureStatus` at ~25 sites, while the second host (`:capture-sample`) never
touched any of them. That asymmetry proved the seam was real but unenforced:
callers were crossing it only because nothing stopped them.

Crossing it silently is expensive. `CaptureStatus` was a second writer of
capture state (the service and the facade already wrote it), the completion
notification was posted from `:app` with a channel `:app` also had to
pre-create, and the ROM-specific settings fallback chains were duplicated in
`MainViewModel` after being written once in `PermissionHelper`.

## Decision

The interface of the capture module is its package edge.

- **Public** — `com.esc.irminsul.capture`: `IrminsulCapture`, `DataStatus`,
  `DataStatusSink`, `PacketRecord`, `PacketLog`, `PermissionSnapshot`,
  `PermissionKind`, `Completion`, `InitResult`.
- **Internal** — `com.esc.irminsul.capture.internal`: `CaptureService`,
  `NativeLib`, `PacketProcessor`, `CaptureStatus`, `PermissionHelper`,
  `RomUtils`, `RawPacket`, marked Kotlin `internal`.

Two exceptions are deliberate and documented in code: `CaptureService` stays a
public class (the framework instantiates it from the manifest) and its
`onPacketCaptured` / `onCaptureStats` / `protectSocket` members stay
un-mangled, because libcapture looks them up by name via `GetMethodID`. Kotlin
mangles `internal` member names, which would break that lookup.

Hosts may not reach into `internal`; the compiler now enforces it. Where a host
genuinely needs something the facade did not expose, the facade gained it
(`commandBody`, `exportGood`, `exportAchievements`, `close`, `abortStart`,
`refreshPermissions`, `openFixSettings`, `logs`, `completion`) rather than the
host getting an exemption.

## Consequences

- **Native symbols are part of the interface.** `Java_com_esc_irminsul_capture_internal_NativeLib_*`
  (7 in Rust), the two `find_class("com/esc/irminsul/capture/internal/NativeLib")`
  strings, the three `Java_com_esc_irminsul_capture_internal_CaptureService_*`
  exports in C, and the service `android:name` in the manifest are all name
  literals tied to the package. Any future move of these classes must rename
  them **in the same commit**, or the app fails at runtime with
  `UnsatisfiedLinkError` while compiling and linking cleanly.
- `:capture`'s `verifyNativeSymbols` task enforces that pairing on every
  `assembleDebug`: it derives the expected `Java_…` names from the Kotlin
  `external fun` declarations and diffs them against `llvm-nm` output for the
  merged `.so` files, in both directions — a name exported by native but no
  longer declared in Kotlin fails too. See the amendment below for why the list
  is derived rather than written out.
- Capture state has one writer: the service, surfaced through the facade. A host
  that declines VPN consent resets its own optimistic UI flag rather than
  writing module state (see `docs/adr/0003`).
- The completion notification and its channel belong to the module; a host that
  renders its own UI opts out with `Config(completionNotification = false)`.
- ROM-specific settings chains moved into `PermissionHelper.fixIntents`;
  `MainViewModel` lost ~120 lines of duplicated fallback loops.
- **Breaking for external consumers**: released as `com.esc.irminsul:capture:1.2.0`.
  Anything that had imported the internal classes under 1.1.0 must switch to the
  facade. Acceptable because the only consumers so far are `:app`,
  `:capture-sample` and a mavenLocal artifact.

## Not decided here

Whether the facade should become a constructed session object with a single
`StateFlow`, whether the status JSON crossing the native boundary should be
typed, and whether the three error conventions should collapse into one are
separate candidates from the same architecture review; this ADR does not
pre-commit their shapes, only where the seam is.

## Amendments — 2026-09-19

The Decision section above lists the public types as of 1.2.0. Two of them no
longer exist, and the seam has grown; the current list is the one in
`CONTEXT.md` ("Seam"):

- **Removed** — `PacketLog` (its mutators made the host a second writer of the
  decoded list; `IrminsulCapture.packets` is now a read-only `StateFlow` over an
  internal ring buffer) and `InitResult` (replaced by `CaptureResult`).
- **Added** — `CaptureSource`, `CaptureResult` / `CaptureError`.
- **Also superseded** — the two questions this ADR left open are decided by
  `docs/adr/0002` (payload contract) and `docs/adr/0003` (one start, one result
  type). The "constructed session object" option was rejected there; the facade
  stays a singleton because the native sniffer is process-global.
- **`abortStart()` removed** — on the decline path the module's `isCapturing`
  was never true, so the call could not produce the state change hosts were
  relying on; the host now resets its own pending flag.
- **`verifyNativeSymbols` now derives its expectations** from the Kotlin sources
  instead of a hand-copied list of names. The list could only prove the native
  side had kept up, which is half the failure; and `consumer-rules.pro` had kept
  `-keep` lines for two pre-move class names, which no gate could see because
  R8 only runs in a host's release build. Consumer rules now keep the internal
  package as a whole.
