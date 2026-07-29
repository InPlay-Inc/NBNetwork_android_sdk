package com.nanobeaconnetwork.ble

import org.junit.Assert.*
import org.junit.Test

class AdvParserTest {

    /**
     * Build a 23-byte service-data payload (bytes 8–30 of the full ADV).
     * Layout: [0..1]=frame type, [2]=version, [3..10]=EID, [11..18]=ciphertext,
     * [19..20]=nonce, [21..22]=tag.
     */
    private fun makeServiceData(
        size: Int = 23,
        eid: ByteArray = ByteArray(8) { it.toByte() }
    ): ByteArray {
        val data = ByteArray(size)
        if (size >= 11) eid.copyInto(data, destinationOffset = 3)
        return data
    }

    @Test fun `parse returns null for empty data`() = assertNull(AdvParser.parse(ByteArray(0)))
    @Test fun `parse returns null for 22 bytes`() = assertNull(AdvParser.parse(ByteArray(22)))
    @Test fun `parse succeeds with exactly 23 bytes`() = assertNotNull(AdvParser.parse(makeServiceData(23)))
    @Test fun `parse succeeds with more than 23 bytes`() = assertNotNull(AdvParser.parse(makeServiceData(30)))

    @Test fun `payload is base64 of the 23-byte service data`() {
        val result = AdvParser.parse(makeServiceData(23))!!
        val decoded = java.util.Base64.getDecoder().decode(result.payload)
        assertEquals(23, decoded.size)
    }

    @Test fun `EID is extracted from service data indices 3 to 10`() {
        val expectedEid = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(),
            0xEE.toByte(), 0xFF.toByte(), 0x11, 0x22)
        val result = AdvParser.parse(makeServiceData(eid = expectedEid))!!
        assertArrayEquals(expectedEid, result.eid)
    }

    @Test fun `EID is 8 bytes`() {
        assertEquals(8, AdvParser.parse(makeServiceData(23))!!.eid.size)
    }

    @Test fun `EID in payload matches returned eid field`() {
        val result = AdvParser.parse(makeServiceData(23))!!
        // EID lives at service-data byte offsets 3..10 (inclusive).
        val decoded = java.util.Base64.getDecoder().decode(result.payload)
        assertArrayEquals(result.eid, decoded.copyOfRange(3, 11))
    }
}
