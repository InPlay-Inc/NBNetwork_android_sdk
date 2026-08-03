package com.nanobeaconnetwork.internal.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nanobeaconnetwork.internal.api.AnonymousTokenRequest
import com.nanobeaconnetwork.internal.api.BatchReportRequest
import com.nanobeaconnetwork.internal.api.RequestEvidence
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

private const val KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "nbn_installation_signing_v1"
private const val ENROLLMENT_DOMAIN = "nbn-installation-enrollment-v1"
private const val REPORT_DOMAIN = "nbn-report-installation-v1"

internal class InstallationKeyException(cause: Throwable) : Exception("Installation signing key failed", cause)

/**
 * Owns the installation-scoped, non-exportable P-256 private key. Only the SPKI public key
 * and its SHA-256 identifier leave Android Keystore.
 */
internal class AndroidInstallationIdentity {
    private val lock = Any()

    val keyId: String
        get() = synchronized(lock) { keyId(loadOrCreate().second.encoded) }

    fun enrollmentRequest(): AnonymousTokenRequest = synchronized(lock) {
        val (privateKey, publicKey) = loadOrCreate()
        val publicDer = publicKey.encoded
        val id = keyId(publicDer)
        AnonymousTokenRequest(
            installationKeyId = id,
            installationPublicKey = base64Url(publicDer),
            installationSignature = base64Url(sign(privateKey, canonicalEnrollment(id, publicDer))),
        )
    }

    fun signReport(request: BatchReportRequest, bearerToken: String): RequestEvidence = synchronized(lock) {
        val (privateKey, publicKey) = loadOrCreate()
        val id = keyId(publicKey.encoded)
        val unsigned = request.copy(requestEvidence = RequestEvidence(id, ""))
        RequestEvidence(
            installationKeyId = id,
            installationSignature = base64Url(
                sign(privateKey, InstallationProofCanonicalizer.report(unsigned, bearerToken)),
            ),
        )
    }

    /** Retires a locally unusable key. The next access creates a fresh installation key. */
    fun rotate() = synchronized(lock) {
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            throw InstallationKeyException(e)
        }
    }

    private fun loadOrCreate(): Pair<PrivateKey, PublicKey> {
        try {
            val store = keyStore()
            val privateKey = store.getKey(KEY_ALIAS, null) as? PrivateKey
            val publicKey = store.getCertificate(KEY_ALIAS)?.publicKey
            if (privateKey != null && publicKey != null) return privateKey to publicKey

            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            val pair = generator.generateKeyPair()
            return pair.private to pair.public
        } catch (e: Exception) {
            throw InstallationKeyException(e)
        }
    }

    private fun sign(privateKey: PrivateKey, value: ByteArray): ByteArray = try {
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(value)
            sign()
        }
    } catch (e: Exception) {
        throw InstallationKeyException(e)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
}

/** Deterministic binary contract shared with the Go verifier. */
internal object InstallationProofCanonicalizer {
    fun report(request: BatchReportRequest, bearerToken: String): ByteArray = encode {
        bytes(REPORT_DOMAIN.toByteArray(Charsets.UTF_8))
        bytes(request.batchId.toByteArray(Charsets.UTF_8))
        int(request.reports.size)
        request.reports.forEach { report ->
            bytes(report.observationId.toByteArray(Charsets.UTF_8))
            bytes(report.payload.toByteArray(Charsets.UTF_8))
            int(report.rssi)
            if (report.latitude != null && report.longitude != null) {
                byte(1)
                double(report.latitude)
                double(report.longitude)
            } else {
                byte(0)
            }
            val seenAt = Instant.parse(report.clientSeenAt)
            long(seenAt.epochSecond)
            int(seenAt.nano)
            if (report.locationAccuracyMeters != null) {
                byte(1)
                double(report.locationAccuracyMeters)
            } else {
                byte(0)
            }
            bytes(report.locationSource.toByteArray(Charsets.UTF_8))
            byte(1)
            byte(if (report.locationIsMock) 1 else 0)
        }
        val keyId = requireNotNull(request.requestEvidence).installationKeyId
        bytes(keyId.toByteArray(Charsets.UTF_8))
        bytes(MessageDigest.getInstance("SHA-256").digest(bearerToken.toByteArray(Charsets.UTF_8)))
    }
}

private fun canonicalEnrollment(keyId: String, publicDer: ByteArray): ByteArray = encode {
    bytes(ENROLLMENT_DOMAIN.toByteArray(Charsets.UTF_8))
    bytes(keyId.toByteArray(Charsets.UTF_8))
    bytes(publicDer)
}

private fun keyId(publicDer: ByteArray): String =
    base64Url(MessageDigest.getInstance("SHA-256").digest(publicDer))

private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

private class CanonicalWriter(private val output: DataOutputStream) {
    fun byte(value: Int) = output.writeByte(value)
    fun int(value: Int) = output.writeInt(value)
    fun long(value: Long) = output.writeLong(value)
    fun double(value: Double) = output.writeDouble(value)
    fun bytes(value: ByteArray) {
        output.writeInt(value.size)
        output.write(value)
    }
}

private fun encode(block: CanonicalWriter.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output -> CanonicalWriter(output).block() }
    return bytes.toByteArray()
}
