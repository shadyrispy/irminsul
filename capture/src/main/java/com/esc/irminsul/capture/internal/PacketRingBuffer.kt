package com.esc.irminsul.capture.internal

import com.esc.irminsul.capture.PacketRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ring buffer of decoded commands, published as one flow. Batches are appended
 * with a single emission so a packet carrying many commands redraws once.
 *
 * Hosts never see this type: [com.esc.irminsul.capture.IrminsulCapture.packets]
 * exposes the read side only, so the module stays the sole writer.
 */
internal class PacketRingBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    private val buffer = ArrayDeque<PacketRecord>(capacity)
    private val _records = MutableStateFlow<List<PacketRecord>>(emptyList())
    val records: StateFlow<List<PacketRecord>> = _records.asStateFlow()

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

    private companion object {
        private const val DEFAULT_CAPACITY = 2000
    }
}
