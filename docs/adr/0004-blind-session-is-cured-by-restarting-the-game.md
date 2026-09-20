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

The tunnel-pause implementation is deleted rather than kept as a tunable:
`pause_until_ms`, `capture_set_pause` and `nativePauseTunnel` went, so the
symbol gate reports 11 exports instead of 12.

## Consequences

- A blind session is now *visible* everywhere and *repairable* nowhere by
  default. That is the intended trade: the host either asks the player or opts
  into the restart with its own permission declaration.
- `killBackgroundProcesses` cannot touch a foreground process, so a host that
  keeps the game in the foreground (a floating-window flow) stays blind even
  with the action enabled — the player has to restart it.
- The sniffer keeps its key across `stop`/`start` inside one process, so a
  second session in the same process can still decrypt an ongoing game session.
  Persisting that key across app restarts is deliberately still open, and is
  the better answer for the cases a restart cannot reach.
