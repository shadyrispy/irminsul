# Irminsul — domain language

Shared vocabulary for the capture stack. Use these words exactly; the
architecture words (module, interface, seam, depth) come from the
`codebase-design` vocabulary and are not repeated here.

## Capture session

One continuous run of the VPN capture service, from consent-granted start to
stop. A session owns the session key, the decoded-command ring buffer and the
collected player data. `IrminsulCapture.start` begins one; `stop` ends it;
`abortStart` gives up a start whose VPN consent was declined.

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

The moment a session has all four data categories. Emitted once as
`Completion` (with counts) and, unless the host's `Config` opts out, posted as
the heads-up notification. Distinct from `DataStatus`: completion is an event,
progress is state.

## Permission snapshot

`PermissionSnapshot`: everything a capture can be blocked on, judged by the
module, including ROM-specific guidance. A host asks what is missing and calls
`openFixSettings(kind)`; it never assembles settings intents itself.

## Seam

The capture module's interface is `com.esc.irminsul.capture` — the facade
`IrminsulCapture` plus `DataStatus`, `DataStatusSink`, `PacketRecord`,
`PacketLog`, `PermissionSnapshot`, `Completion`, `PermissionKind`, `InitResult`.
Everything else lives in `com.esc.irminsul.capture.internal` and is `internal`;
see `docs/adr/0001`.
