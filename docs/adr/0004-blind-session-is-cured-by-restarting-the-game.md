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
- A 4s full outage (stop, then start): same. The client *resumes* its session
  with the key the capture never saw.
- Restarting the game process (`am kill`, then launch): the client logs in from
  scratch, the running capture catches the handshake, and the session finished
  with all four categories (`Complete`, 1045 artifacts / 94 characters / 217
  weapons / 1845 achievements — the same counts the recorded pcap gives).

A longer outage might also work: the player's own recordings show a
background-to-foreground stint sometimes resuming and sometimes reloading. That
is not a mechanism, and the reload case costs the player more than a restart.

## Decision

`forceRelogin` manufactures a login by closing and reopening the game:
`killBackgroundProcesses` for each installed target package, then its launch
intent. `Config.autoForceRelogin` (default on) does this 10s after a session
goes blind, at most twice with 3 minutes between attempts — longer than a cold
launch, so a second attempt can never interrupt the first one's login. A host
that wants to warn the player sets it to `false` and asks first; `:app` does
that, because the restart closes the player's game.

`SessionPhase` and `CaptureTraffic` were added to make the state nameable at all
— `AwaitingLogin` plus moving bytes *is* the blind session, and it is derived
from flows the module already had rather than a new flag someone must remember to
clear.

The tunnel-pause implementation is deleted rather than kept as a tunable:
`pause_until_ms`, `capture_set_pause` and `nativePauseTunnel` went, so the
symbol gate reports 11 exports instead of 12.

## Consequences

- The library now asks for `KILL_BACKGROUND_PROCESSES`, and merging the AAR
  gives that to every host. It cannot touch a foreground process, so a host that
  keeps the game in the foreground (a floating-window flow) stays blind and has
  to restart the game itself — the interface says so rather than pretending the
  call succeeded.
- "Restart the game" is a heavier user-visible action than a reconnect, so the
  confirmation copy has to say the game will close and reload.
- The sniffer keeps its key across `stop`/`start` inside one process, so a
  second session in the same process can still decrypt an ongoing game session.
  Persisting that key across app restarts is deliberately still open.
