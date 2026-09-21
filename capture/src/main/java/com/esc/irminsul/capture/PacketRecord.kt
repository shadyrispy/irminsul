package com.esc.irminsul.capture

/**
 * One decoded game command as shown in the packet list. Kept intentionally
 * small: the full proto body is fetched on demand via
 * [IrminsulCapture.commandBody] using [packetId] + [commandIndex], and may be
 * gone if the native cache evicted that packet.
 */
data class PacketRecord(
    val packetId: Long,
    val commandIndex: Int,
    val cmdId: Int,
    val name: String,
    val isSent: Boolean,
    val sizeBytes: Int,
    val fieldCount: Int?,
    val briefKeys: List<String>,
    val parseError: Boolean,
    val timestampMillis: Long,
    /**
     * Position, within the same packet, of the batch envelope that carried this
     * command — `UnionCmdNotify` delivers dozens of commands at once. Null for a
     * command that arrived on its own. A list ordered by [commandIndex] keeps a
     * parent above its children, so this is all a view needs to indent them.
     */
    val parentIndex: Int? = null
)
