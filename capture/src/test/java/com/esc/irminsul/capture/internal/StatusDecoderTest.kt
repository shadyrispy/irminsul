package com.esc.irminsul.capture.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin half of the payload contract. `/summary_status.json` is the same
 * file the native producer asserts against in
 * `capture/rust/irminsul-jni/src/lib.rs` (`contract_tests`), so renaming a key
 * on one side fails the other side's test rather than reading back as zero.
 */
class StatusDecoderTest {

    private val fixture: String by lazy {
        val stream = javaClass.getResourceAsStream("/summary_status.json")
        requireNotNull(stream) { "capture/testdata/summary_status.json missing from test resources" }
            .use { it.readBytes().decodeToString() }
    }

    @Test
    fun `decodes collection progress`() {
        val status = StatusDecoder.decode(fixture, 1234L)!!.status
        assertEquals(true, status.itemsLoaded)
        assertEquals(true, status.charactersLoaded)
        assertEquals(false, status.achievementsLoaded)
        assertEquals(1200, status.artifactsCount)
        assertEquals(150, status.weaponsCount)
        assertEquals(2307, status.materialsCount)
        assertEquals(90, status.charactersCount)
        assertEquals(1712, status.achievementsCount)
        // Weapons ride on the items notify; the decoder must not invent a third flag.
        assertEquals(status.itemsLoaded, status.weaponsLoaded)
    }

    @Test
    fun `maps every command summary onto a record`() {
        val records = StatusDecoder.decode(fixture, 99L)!!.records
        assertEquals(3, records.size)

        val unknown = records[0]
        assertEquals(7L, unknown.packetId)
        assertEquals(0, unknown.commandIndex)
        assertEquals(0, unknown.cmdId)
        assertEquals("unknown", unknown.name)
        assertEquals(false, unknown.isSent)
        assertEquals(0, unknown.sizeBytes)
        assertNull("absent field_count is null, not zero", unknown.fieldCount)
        assertEquals(emptyList<String>(), unknown.briefKeys)
        assertEquals(false, unknown.parseError)
        assertEquals(99L, unknown.timestampMillis)

        val sent = records[1]
        assertEquals(1, sent.commandIndex)
        assertEquals(26915, sent.cmdId)
        assertEquals("GadgetInteractReq", sent.name)
        assertTrue(sent.isSent)
        assertEquals(12, sent.sizeBytes)
        assertEquals(3, sent.fieldCount)
        assertEquals(listOf("gadget", "ctx"), sent.briefKeys)

        assertTrue("parse_error true must survive", records[2].parseError)
        assertNull(records[2].fieldCount)
    }

    @Test
    fun `payload without commands decodes to an empty batch`() {
        val update = StatusDecoder.decode(
            """{"packet_id":1,"has_items":false,"artifact_count":0}""",
            5L
        )!!
        assertEquals(emptyList<Any>(), update.records)
        assertEquals(false, update.status.itemsLoaded)
        assertEquals(0, update.status.artifactsCount)
    }

    @Test
    fun `malformed payload decodes to null instead of throwing`() {
        assertNull(StatusDecoder.decode("not json", 0L))
    }
}
