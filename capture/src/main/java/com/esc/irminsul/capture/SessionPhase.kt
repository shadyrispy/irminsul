package com.esc.irminsul.capture

/**
 * Where the current session stands. Exists because "we joined the game's
 * session too late" is otherwise indistinguishable from "the game is not
 * playing": both look like zero decoded commands.
 *
 * The session key can only come from the handshake's `GetPlayerTokenRsp`, so a
 * session that starts after the game connected stays unable to decrypt until the
 * game logs in again — see [IrminsulCapture.forceRelogin].
 */
enum class SessionPhase {
    /** No capture session is running. */
    Idle,

    /** Game traffic in the tunnel, but nothing has decrypted: no key yet. */
    AwaitingLogin,

    /** Commands are decoding; the full snapshots have not all arrived. */
    Collecting,

    /** Items, characters and achievements all arrived in this session. */
    Complete
}

/** Bytes and connections the capture loop has accounted for this session. */
data class CaptureTraffic(
    val sentBytes: Long = 0,
    val receivedBytes: Long = 0,
    val connections: Int = 0
) {
    val totalBytes: Long get() = sentBytes + receivedBytes
}
