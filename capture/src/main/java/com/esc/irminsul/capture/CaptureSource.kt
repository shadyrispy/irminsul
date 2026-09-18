package com.esc.irminsul.capture

/**
 * Where the packets come from. Both sources feed one pipeline and publish the
 * same [DataStatusSink] / [PacketRecord] stream, so a host renders them
 * identically — which is the point of [com.esc.irminsul.capture.internal.StatusDecoder]
 * existing behind one key-name contract.
 */
sealed interface CaptureSource {
    /** Live traffic, via the module's VPN service, until [IrminsulCapture.stop]. */
    data object Vpn : CaptureSource

    /**
     * A recorded pcap, replayed on a module-owned thread. The session ends on
     * its own when the file runs out; [IrminsulCapture.isCapturing] never
     * becomes true for this source.
     */
    data class File(val path: String) : CaptureSource
}
