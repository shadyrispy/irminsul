package com.esc.irminsul.capture

/**
 * Where the current session stands. Exists because "we joined the game's
 * session too late" is otherwise indistinguishable from "the game is not
 * playing": both look like zero decoded commands.
 *
 * Derived from [IrminsulCapture.keyOrigin], because a session holding only the
 * dispatch key does decode some commands — the handshake's own — and would look
 * like it was collecting.
 */
enum class SessionPhase {
    /** No capture session is running. */
    Idle,

    /**
     * Game traffic in the tunnel, but no session key: nothing beyond the
     * handshake's own packets can be read. Ends when the tunnel catches a login
     * ([IrminsulCapture.forceRelogin]) or when a saved command body opens the
     * session ([KeyOrigin.KnownBody]).
     */
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
