package com.esc.irminsul.capture

/**
 * Receives player-data status updates derived from captured packets. The
 * capture module reports into this sink; the host app implements it with its
 * own state holder, keeping the module free of app-level storage.
 */
interface DataStatusSink {
    fun publish(status: DataStatus)
}
