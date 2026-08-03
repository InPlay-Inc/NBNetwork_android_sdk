package com.nanobeaconnetwork.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceKeyFactoryTest {
    @Test fun `same normalized MAC and install key produce same local digest`() {
        val key = ByteArray(32) { it.toByte() }
        val first = SourceKeyFactory.create("AA:BB:CC:DD:EE:FF", "0011223344556677", key)
        val second = SourceKeyFactory.create("aa-bb-cc-dd-ee-ff", "ffeeddccbbaa9988", key)
        assertEquals(first, second)
        assertTrue(first.startsWith("mac:"))
        assertFalseContains(first, "aabbccddeeff")
    }

    @Test fun `different installations cannot correlate the same MAC`() {
        val first = SourceKeyFactory.create("AA:BB:CC:DD:EE:FF", "00", ByteArray(32) { 1 })
        val second = SourceKeyFactory.create("AA:BB:CC:DD:EE:FF", "00", ByteArray(32) { 2 })
        assertNotEquals(first, second)
    }

    @Test fun `missing MAC falls back to EID`() {
        assertEquals("eid:aabb", SourceKeyFactory.create(null, "AABB", ByteArray(32)))
    }

    private fun assertFalseContains(value: String, secret: String) {
        assertTrue(!value.contains(secret))
    }
}
