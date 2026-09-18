# 1. The capture module's public interface is its package edge

Date: 2026-09-18
Status: accepted

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
  `assembleDebug` (NDK `llvm-nm` against the merged `.so` files) and fails if
  either library is missing from the merge.
- Capture state has one writer: the service, surfaced through the facade.
  `abortStart()` exists so a host declining VPN consent can abandon a start
  without writing module state.
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
