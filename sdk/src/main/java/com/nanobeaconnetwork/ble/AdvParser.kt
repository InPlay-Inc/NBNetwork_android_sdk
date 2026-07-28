package com.nanobeaconnetwork.ble

import android.util.Base64

data class AdvData(
    val payload: String,
    val eid: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdvData) return false
        return payload == other.payload && eid.contentEquals(other.eid)
    }
    override fun hashCode(): Int = 31 * payload.hashCode() + eid.contentHashCode()
}

internal object AdvParser {
    private const val MIN_SERVICE_DATA_LEN = 23

    /**
     * Parse Service Data payload (UUID prefix already stripped by Android BLE API).
     *
     * Service-data layout (23 bytes; bytes 8–30 of the full ADV):
     *   0-1:   frame type (2 bytes)
     *   2:     version (0x01)
     *   3-10:  EID (8 bytes)
     *   11:    VCC (encrypted)
     *   12-13: Temp (encrypted)
     *   14-18: Reserved (encrypted; completes the 8-byte EAX ciphertext, bytes 11–18)
     *   19-20: nonce (2 effective bytes)
     *   21-22: EAX tag (2 bytes)
     *
     * The SDK only extracts the EID (for dedup) and forwards the whole block base64-encoded;
     * decryption of the ciphertext happens server-side.
     */
    fun parse(serviceData: ByteArray): AdvData? {
        if (serviceData.size < MIN_SERVICE_DATA_LEN) return null

        val eid = serviceData.copyOfRange(3, 11)
        return AdvData(payload = Base64.encodeToString(serviceData, Base64.NO_WRAP), eid = eid)
    }
}
