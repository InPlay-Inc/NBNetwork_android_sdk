package com.nanobeaconnetwork.internal.security

import com.nanobeaconnetwork.internal.api.BatchReportRequest
import com.nanobeaconnetwork.internal.api.ReportItem
import com.nanobeaconnetwork.internal.api.RequestEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class InstallationProofCanonicalizerTest {
    @Test
    fun `canonical request matches the shared P2 vector`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            InstallationProofCanonicalizer.report(request(), "bearer-token-1"),
        )
        assertEquals("97a4ec84c8e5a6d8ad0735d343ce1830f48b14dc929a81e856e2c1860dde3ea6", digest.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `signature cannot move across bearer or batch`() {
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val canonical = InstallationProofCanonicalizer.report(request(), "bearer-token-1")
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(canonical)
            sign()
        }

        fun verifies(value: ByteArray): Boolean = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public)
            update(value)
            verify(signature)
        }

        assertTrue(verifies(canonical))
        assertFalse(verifies(InstallationProofCanonicalizer.report(request(), "bearer-token-2")))
        assertFalse(
            verifies(
                InstallationProofCanonicalizer.report(
                    request().copy(batchId = "550e8400-e29b-41d4-a716-446655440099"),
                    "bearer-token-1",
                ),
            ),
        )
    }

    private fun request() = BatchReportRequest(
        batchId = "550e8400-e29b-41d4-a716-446655440000",
        reports = listOf(
            ReportItem(
                observationId = "550e8400-e29b-41d4-a716-446655440001",
                payload = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhc=",
                rssi = -71,
                latitude = 37.25,
                longitude = -122.125,
                locationAccuracyMeters = 8.5,
                locationSource = "sdk_fused",
                locationIsMock = false,
                clientSeenAt = "2026-08-03T04:05:06.123456789Z",
            ),
        ),
        requestEvidence = RequestEvidence(
            installationKeyId = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            installationSignature = "ignored",
        ),
    )
}
