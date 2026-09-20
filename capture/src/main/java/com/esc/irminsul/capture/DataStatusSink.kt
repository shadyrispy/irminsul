package com.esc.irminsul.capture

/**
 * Receives player-data status updates derived from captured packets. The
 * capture module reports into this sink; the host app implements it with its
 * own state holder, keeping the module free of app-level storage.
 *
 * [publish] runs on the module's decode thread, not the main thread — it must
 * not stall the packet pipeline. A host that touches a view has to post to the
 * main thread itself; state holders (e.g. a `MutableStateFlow`) are safe as-is.
 */
interface DataStatusSink {
    fun publish(status: DataStatus)
}
