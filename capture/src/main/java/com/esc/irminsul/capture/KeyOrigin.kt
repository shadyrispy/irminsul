package com.esc.irminsul.capture

/**
 * How the key that decrypts the current session was obtained.
 *
 * Reported live by [IrminsulCapture.keyOrigin]. Which one it is tells a host
 * apart three states that otherwise all look like "some packets are decoding":
 * a capture that joined mid-game, one whose handshake it caught, and one riding
 * on samples saved by an earlier process.
 */
enum class KeyOrigin(val wireName: String) {

    /**
     * Only the per-version dispatch key works, so just the handshake's own
     * packets decrypt. Everything after them stays encrypted: this is the state
     * that needs a new login, or a [KeyOrigin.KnownBody] sample.
     */
    Dispatch("dispatch"),

    /**
     * The session key was recovered by matching a command body that repeats
     * verbatim across client processes against the ciphertext. Works for a
     * session whose handshake was never captured.
     */
    KnownBody("known_body"),

    /**
     * The session key came from searching client-clock seeds around the
     * handshake. Only ever works for a process's first login.
     */
    TimeSearch("time_search");

    companion object {
        /**
         * Maps the native name. An unrecognized name yields null rather than a
         * guess: [IrminsulCapture.sessionPhase] reads null as "no key", which is
         * the safe thing to show a player when the two halves have drifted.
         */
        fun fromWire(value: String?): KeyOrigin? = entries.firstOrNull { it.wireName == value }
    }
}
