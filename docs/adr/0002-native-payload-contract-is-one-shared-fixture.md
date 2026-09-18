# 2. The native payload contract is one shared fixture

Date: 2026-09-18
Status: accepted

## Context

`nativeProcessPacket` hands Kotlin a JSON string, and `PacketProcessor` read it
with 14 `optString`/`optInt`/`optBoolean` lookups whose key names had to match
the literals in the Rust `json!` macro. Every lookup had a default, so a rename
on one side compiled, linked, ran, and reported `artifact_count: 0` forever —
the same class of failure candidate 1 had just closed for JNI symbol names, in a
path that `verifyNativeSymbols` cannot see.

The drift was already there: Rust emitted `command_count`, which nothing read;
the producer's own doc comment listed `item_count` and `avatar_count`, keys that
no longer exist; and `weaponsLoaded` was filled from `has_items`, a coupling that
lived only on the right-hand side of an assignment.

It also had two consumers with different fates. `capture/rust/pcap-check` reads
the same payload natively, so a rename would keep the offline baseline green
while the shipped app silently zeroed out.

## Decision

One file is the contract: `capture/testdata/summary_status.json`. Both halves
test against it, and neither owns it.

- **Rust** (`irminsul-jni/src/lib.rs`, `contract_tests`, run by
  `:capture:verifyNativePayloadContract` as part of `:capture:check`): the
  payload builder is a pure `status_json(&StatusPayload)`, and the tests assert
  its key set equals the fixture's, that a real `GameCommand::summary_json()`
  produces the fixture's per-command keys, and that each value has the type the
  decoder reads.
- **Kotlin** (`StatusDecoderTest`): decodes the same file (it is a test resource
  directory of the `capture` module) into `DataStatus` and `PacketRecord`, and
  asserts the derived facts, including that `weaponsLoaded` mirrors
  `itemsLoaded` rather than inventing a third flag.

Key names live in exactly one non-test place per side: `StatusPayload` on the
producer, `StatusDecoder` on the reader. `StatusDecoder` is a pure function
outside the pipeline thread, so the reader can be tested without a queue.

## Alternatives rejected

- **A `CaptureStatus.proto` with two-sided codegen.** Genuinely single source of
  truth, and it deletes both literal lists. Rejected for now because it costs a
  protobuf-kotlin runtime and a Gradle protoc step to protect ~14 keys, and the
  schema pipeline would have to reach across the JVM/Rust boundary. Revisit if
  the payload grows past a handful of record types.
- **Typed JNI (object arrays / a struct in direct memory).** Faster, but the
  marshalling code becomes the thing that drifts, with no better gate than the
  one we now have.
- **Asserting byte equality between the Rust output and the fixture.** Rejected:
  it would couple the fixture to specific proto bodies and make the schema
  baseline a test dependency.

## Consequences

- Changing the payload means editing one fixture, then both test halves; the
  failure message names the file.
- `parse_error` is present only when true, which the fixture encodes as a third
  command rather than as a comment.
- The fixture gate runs under `:capture:check`, not `assembleDebug`: a plain
  debug build stays fast, and CI or a deliberate local run gets both gates.
- Removing `command_count` is safe: the array length is the count.
