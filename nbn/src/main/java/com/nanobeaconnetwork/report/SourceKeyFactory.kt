package com.nanobeaconnetwork.report

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object SourceKeyFactory {
    private val macPattern = Regex("^[0-9a-f]{12}$")

    fun create(bleAddress: String?, eidHex: String, installKey: ByteArray): String {
        val normalized = bleAddress.orEmpty().replace(Regex("[^0-9A-Fa-f]"), "").lowercase()
        if (!macPattern.matches(normalized)) return "eid:${eidHex.lowercase()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(installKey, "HmacSHA256"))
        val digest = mac.doFinal(normalized.toByteArray(Charsets.US_ASCII))
        return "mac:" + digest.joinToString("") { "%02x".format(it) }
    }
}
