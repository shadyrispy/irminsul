package com.esc.irminsul.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One decoded game command as shown in the packet list. Kept intentionally
 * small: the full proto body is fetched on demand via
 * [NativeLib.commandBody] using [packetId] + [commandIndex].
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
    val timestampMillis: Long
)

/**
 * Ring buffer of packet records published as a StateFlow. Independent from
 * [UiState] so that list updates never invalidate export-filter state.
 */
class PacketLog(private val capacity: Int = DEFAULT_CAPACITY) {

    private val buffer = ArrayDeque<PacketRecord>(capacity)
    private val _records = MutableStateFlow<List<PacketRecord>>(emptyList())
    val records: StateFlow<List<PacketRecord>> = _records.asStateFlow()

    /**
     * Append a batch of records with a single StateFlow emission, so a packet
     * containing many commands triggers one recomposition instead of one per
     * command.
     */
    fun appendAll(records: List<PacketRecord>) {
        if (records.isEmpty()) return
        synchronized(buffer) {
            records.forEach { buffer.addLast(it) }
            while (buffer.size > capacity) {
                buffer.removeFirst()
            }
            _records.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _records.value = emptyList()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 2000
    }
}
