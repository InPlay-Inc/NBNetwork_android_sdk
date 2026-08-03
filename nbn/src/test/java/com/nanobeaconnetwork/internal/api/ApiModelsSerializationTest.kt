package com.nanobeaconnetwork.internal.api

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Serialization contract for the wire models. Uses the library's own Gson configuration and asserts
 * the exact JSON field names, so a rename that drops or mistypes a [com.google.gson.annotations.SerializedName]
 * (which would silently break the API once the host app runs R8 in full mode) fails here.
 *
 * Runs under Robolectric — the models are pure Kotlin/Gson, but this keeps the serialization
 * check on the Android runtime the library actually ships against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ApiModelsSerializationTest {

    // Same builder the library uses in ApiClient.buildService().
    private val gson = GsonBuilder().serializeNulls().create()

    @Test
    fun `AnonymousTokenRequest serializes only installation proof`() {
        val json = JsonParser.parseString(
            gson.toJson(AnonymousTokenRequest("key-1", "public-1", "signature-1")),
        ).asJsonObject
        assertEquals(
            setOf("installation_key_id", "installation_public_key", "installation_signature"),
            json.keySet(),
        )
        assertEquals("key-1", json.get("installation_key_id").asString)
        assertEquals(false, json.has("android_id"))
    }

    @Test
    fun `AnonymousTokenResponse maps access_token and config`() {
        val resp = gson.fromJson(
            """{"access_token":"tok-1","config":{"dedup_window_seconds":"600"}}""",
            AnonymousTokenResponse::class.java,
        )
        assertEquals("tok-1", resp.token)
        assertEquals("600", resp.config?.get("dedup_window_seconds"))
    }

    @Test
    fun `ConfigResponse maps config`() {
        val resp = gson.fromJson("""{"config":{"report_batch_threshold":50.0}}""", ConfigResponse::class.java)
        assertEquals(50.0, resp.config?.get("report_batch_threshold"))
    }

    @Test
    fun `BatchReportRequest round-trips with stable field names`() {
        val request = BatchReportRequest(
            "550e8400-e29b-41d4-a716-446655440000",
            listOf(
                ReportItem(
                    observationId = "550e8400-e29b-41d4-a716-446655440001",
                    payload = "AQID",
                    rssi = -70,
                    latitude = 37.1,
                    longitude = -122.2,
                    locationAccuracyMeters = 12.5,
                    locationSource = "sdk_fused",
                    locationIsMock = false,
                    clientSeenAt = "2026-01-01T00:00:00Z",
                ),
            ),
        )

        val json = JsonParser.parseString(gson.toJson(request)).asJsonObject
        assertEquals(setOf("batch_id", "reports", "request_evidence"), json.keySet())
        val item = json.getAsJsonArray("reports")[0].asJsonObject
        assertEquals(
            setOf(
                "observation_id", "payload", "rssi", "latitude", "longitude",
                "location_accuracy_m", "location_source", "location_is_mock", "client_seen_at",
            ),
            item.keySet(),
        )
        assertEquals("AQID", item.get("payload").asString)
        assertEquals(-70, item.get("rssi").asInt)
        assertEquals(37.1, item.get("latitude").asDouble, 0.0)
        assertEquals(-122.2, item.get("longitude").asDouble, 0.0)
        assertEquals("2026-01-01T00:00:00Z", item.get("client_seen_at").asString)

        // And back: the library never deserializes ReportItem, but the round-trip proves the mapping.
        val restored = gson.fromJson(gson.toJson(request), BatchReportRequest::class.java)
        assertEquals(request.reports[0].payload, restored.reports[0].payload)
        assertEquals(request.reports[0].rssi, restored.reports[0].rssi)
    }

    @Test
    fun `BatchReportRequest renders absent location as paired JSON nulls`() {
        val request = BatchReportRequest(
            "550e8400-e29b-41d4-a716-446655440000",
            listOf(
                ReportItem(
                    observationId = "550e8400-e29b-41d4-a716-446655440001",
                    payload = "AQID",
                    rssi = -70,
                    latitude = null,
                    longitude = null,
                    locationAccuracyMeters = null,
                    locationSource = "unknown",
                    locationIsMock = false,
                    clientSeenAt = "2026-01-01T00:00:00Z",
                ),
            ),
        )

        val item = JsonParser.parseString(gson.toJson(request))
            .asJsonObject
            .getAsJsonArray("reports")[0]
            .asJsonObject
        assertEquals(
            setOf(
                "observation_id", "payload", "rssi", "latitude", "longitude",
                "location_accuracy_m", "location_source", "location_is_mock", "client_seen_at",
            ),
            item.keySet(),
        )
        assertTrue(item.get("latitude").isJsonNull)
        assertTrue(item.get("longitude").isJsonNull)
        assertTrue(item.get("location_accuracy_m").isJsonNull)
        assertEquals("unknown", item.get("location_source").asString)
        assertEquals(false, item.get("location_is_mock").asBoolean)
    }

    @Test
    fun `BatchReportResponse maps fixed accepted envelope`() {
        val resp = gson.fromJson(
            """{"status":"accepted","batch_id":"550e8400-e29b-41d4-a716-446655440000"}""",
            BatchReportResponse::class.java,
        )
        assertEquals("accepted", resp.status)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", resp.batchId)
    }

    @Test
    fun `BatchReportResponse applies defaults when fields are absent`() {
        val resp = gson.fromJson("{}", BatchReportResponse::class.java)
        assertEquals(null, resp.status)
        assertEquals(null, resp.batchId)
    }
}
