# 4. A blind session is cured by restarting the game, not by touching the tunnel

Date: 2026-09-20
Status: accepted

## Context

The session key comes from `GetPlayerTokenRsp` in the login handshake, so a
capture that starts after the player is already in-game sees traffic and decrypts
none of it. Until now that state was invisible: `packets` was empty, which looks
exactly like "the game is not playing". The first fix anyone reaches for is to
interrupt the game's connection and let it reconnect in front of the tunnel.

Measured on a live client (BlueStacks, CN 7.0.0, 2026-09-20), with a session
known to be blind:

- Black-holing the tunnel for 5s — the game's sockets left unserviced, its
  outbound packets read and discarded — twice in a row: traffic resumed, the
  client retransmitted, **no new handshake**. Nothing decoded.
- Restarting the game process (`am kill`, then launch): the client logs in from
  scratch, the running capture catches the handshake, and the session finished
  with all four categories (`Complete`, 1045 artifacts / 94 characters / 217
  weapons / 1845 achievements — the same counts the recorded pcap gives).

An earlier draft of this ADR also claimed "a 4s full outage does not work". That
test was invalid and is retracted: the tunnel uses `addAllowedApplication`, so
when it is down the game simply uses the underlying network — stopping the
capture is not an outage at all. The pcap numbers below were about
background-to-foreground freezes, not a controlled stall; they do not bound what
a stall can do.

**Amendment (2026-09-20, evening — controlled black-hole ladder on BlueStacks).**
With the tunnel genuinely black-holed for a fixed time (packets dropped in both
directions), the client has a hard heartbeat timeout and the threshold is clean:

| stall | outcome |
|---|---|
| 5 / 10 / 15 / 20 / 30 / 45s | client silently *resumes* with the old key |
| 50s | 「连接已断开 · 连接超时」 — session given up |
| 60s | same dialog |
| 90s | 「网络错误 4201」, kicked to the title screen |

After the kick, tapping 确认 → 返回标题 → 点击进入 runs a fresh login, and the
still-running capture caught it end to end: `All data collected! Artifacts: 1045,
Weapons: 217, Materials: 1230, Characters: 94, Achievements: 1845`, then
auto-stop. So the stall *is* the lever — roughly 50s of silence breaks the
client — and it needs no extra permission; what it costs is a visible
"connection lost" and a re-entry, which the player drives.

**Amendment 2 (2026-09-20, late — the stall works; the live key derivation is what fails).**
Later trials contradicted the 18:45 success: after 50s/60s/90s kicks, re-entry
landed the game back in-world while the live session decoded *nothing*. Raw-packet
dumping (`IrminsulCapture.dumpRawPackets`, written from the live queue) settled the
first question: the client really does re-login — the dump contains
`GetPlayerTokenReq` + `GetPlayerTokenRsp (cmd 6000)` and offline `pcap-check`
derives the key from it (4 commands decoded). So the packets arrive; the live
sniffer is the one that fails.

Routing the sniffer's own `tracing` into logcat then named the failure point:

```
crypto: Running bruteforce loop.                       ×12
crypto: Unable to find the encryption key seed.        ×12
crypto: before decryption data=…                       ×240263
```

`bruteforce()` searches ±1500ms around `self.sent_time`. Two candidate faults were
guessed at that point — a stale anchor, and anchoring on the server's clock. **Both
are retracted; both were measured away on 2026-09-21:**

- `GetPlayerTokenRsp`'s `sent_ms` is *identical* to the client's own
  `GetPlayerTokenReq.sent_ms` (`1789914368753` in both), so the server echoes the
  client's stamp and the anchor is not a foreign clock.
- `HandshakeRequested` not resetting `sent_time`/`possible_seeds` is irrelevant:
  those are overwritten by the current handshake before the first failing packet.

The variable that separates the working captures from the failing ones is
`PacketHead.client_sequence_id`, which counts packets since the client process
started:

| capture | seq at `GetPlayerTokenReq` | ±1.5s search |
|---|---|---|
| capture.pcap | 1 | found — 1071 commands |
| reconnect.pcap | 1, 1, 1, ~300 | found in all four sessions |
| reentry.pcap | 31374 | 2868 misses |
| full.pcap | 133613 | 201 misses |

A cold login (`seq = 1`) is derivable; a re-auth inside a long-running client is
not, because the client chose its rand key once, when the process started. The
fourth session in `reconnect.pcap` proves the mechanism from the other side: its
seq was already past 127 and it still decoded, because the same sniffer instance
had retained the client seed from that capture's first login.

Widening the window is not the answer, and that was measured rather than argued:
scanning ±3 hours of candidate times (108M key derivations) and then 26 hours
backwards (468M more, which covers the emulator's entire uptime) around the
re-auth's `sent_ms` produced **no hit**. So the seed behind a re-auth is not a
wall-clock millisecond value reachable from that packet at all.

Reading the seed directly is also closed: `GetPlayerTokenReq.clientRandKey` is a
256-byte RSA-2048 blob, and both bundled private keys fail to decrypt it
(`Err(Decryption)`) in the same run where the response's blob decrypts to
`Ok(8)`. That is precisely why upstream resorts to a time search.

What the sniffer's INFO stream costs when it is forwarded carelessly was also
confirmed the hard way: the hex dumps exhausted the heap, left an
`OutOfMemoryError` pending, and the next JNI call aborted the process in the
decode thread. The filter now keeps INFO and drops DEBUG/`trace!` — and note
`tracing`'s `Level` ordering is *inverted* (the least severe is the largest), so
"this level or more severe" is `<=`, and an earlier draft written `>= WARN` was
forwarding exactly the `trace!` hex it meant to exclude.

**Amendment 3 (2026-09-21 — a blind session is opened by known plaintext, so the
player only has to reconnect).**

The title no longer holds. Restarting the process was never the cure; it only
looked like one because a *cold* login is the single handshake the time search
can reach. There is a second way in that needs neither seed nor anchor, and it is
what the library now uses.

The XOR key repeats every 4096 bytes, so a long enough command carries the whole
key many times over. `PlayerShellCodeLuaShellNotify` (cmd 22485 in CN 7.0.0, the
`WindSeedClientNotify` successor) has a 167875-byte body — 41 copies of the key —
and its bytes were measured identical across client processes, across days, and
across two devices: eight samples taken pairwise differ in **zero** bytes. So one
recovered during a session we could open lets a later blind session be opened by
majority-voting `plaintext ⊕ ciphertext` per key byte. Measured, offline:
`full.pcap` goes from 4 commands to 2707-2848 with 0 parse errors when the sample
is present, and a fixture that runs a cold login followed by a blind re-auth
decodes 3919 commands with nothing injected.

Two details mattered more than the idea:

- The sample must be matched **tail-aligned**, not by frame length. The frame is
  `10 + header_len + body + 2`, and `header_len` grows with
  `client_sequence_id` (9 bytes for `seq = 1`, 11 for `seq = 133613`), so the
  emulator's frames are exactly 2 bytes longer than the phone's. The first
  implementation required equal lengths and never fired once.
- A session key must be validated against the trailer, not just the two leading
  magic bytes. The time search can hit two bytes by chance, and a false key made
  the sniffer drop every packet after it — including the ones that would have
  corrected it. That is why the same sample produced 4 commands before this fix
  and 2707 after.

Live on BlueStacks with the shipped build, a sniffer holding only samples loaded
from disk and no seeds (the game re-entered the world on a connection whose
handshake it never saw):

```
recovered session key from a known command body
Matched item packet: 3961 items / avatar packet: 94 / achievement packet: 1845
[SUCCESS] All data collected! Artifacts: 1045, Weapons: 217, Materials: 1231, Characters: 94, Achievements: 1845
```

Samples are written to `filesDir/known_bodies.bin` as soon as the sniffer notes
one, and reloaded by the next process, so the capability survives an app restart.
It expires when the game changes those payloads — roughly per version.

This changes the product answer: a 50-90s kick makes the client re-authenticate,
which is the case the attack covers, so the player tapping through 「连接已断开」 is
enough. Killing the process was only ever needed because re-auth traffic was
unreadable.

## Decision

Ship the **detection**, not the action.

`SessionPhase` and `CaptureTraffic` make the blind state nameable —
`AwaitingLogin` plus moving bytes *is* it — and they are derived from flows the
module already had rather than a new flag someone must remember to clear. That
is what the library guarantees to every host.

`forceRelogin` keeps the working mechanism (close the game with
`killBackgroundProcesses`, then start its launch intent) as an explicitly
opt-in call:

- `Config.autoForceRelogin` is **off by default**. A capture library that
  silently closes the player's game is not a library anyone should depend on,
  and the case it fixes is rare enough to be the host's product decision.
- The library does **not** declare `KILL_BACKGROUND_PROCESSES`. Merging the AAR
  would otherwise hand every host a permission whose only purpose is stopping
  other apps' processes. A host that opts in declares it itself; without it
  `forceRelogin` degrades to a foreground bring-up and says so on `logs`.
- `:app` does not call it. Its dialog tells the player to restart the game and
  leaves the choice with them.

The tunnel pause came back as a **measurement instrument**, not a product action:
`pause_until_ms` / `capture_set_pause` / `nativePauseTunnel` (12 exports across the
two native libraries) plus `IrminsulCapture.stallTunnel(durationMs)`, driven from
`:app`'s `StallTestReceiver` over adb. It is what produced the stall ladder and
the dumps that settled this question, and nothing in the capture flow calls it.

## Consequences

- A blind session is *visible* everywhere and *curable* by the player alone: they
  reconnect or re-enter the game, and the library opens that session from its
  samples. No host has to kill anything, and the library still declares no
  permission for it.
- The samples are the whole basis for that, so a first-ever capture of a new game
  version is still blind until one session has been opened by handshake. The
  file is written the moment a sample appears, and the fallback for a host with an
  empty file is unchanged: ask the player to log in again.
- `forceRelogin` is now a convenience rather than the fix it was believed to be.
  It stays opt-in and unused by `:app`.
- The sniffer keeps its key across `stop`/`start` inside one process, so a second
  session in the same process can still decrypt an ongoing game session.
