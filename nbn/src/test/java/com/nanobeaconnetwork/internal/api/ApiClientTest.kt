package com.nanobeaconnetwork.internal.api

import com.google.gson.JsonParser
import com.nanobeaconnetwork.internal.prefs.NbnPrefs
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ApiClientTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    @Test fun `relative API paths preserve configured base path`() = runTest {
        val prefs = prefs(server.url("/tenant/").toString().trimEnd('/'))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"config":{"dedup_window_seconds":600}}"""
            )
        )

        val response = ApiClient(prefs, { null }).service.getConfig()

        val request = server.takeRequest()
        assertEquals("/tenant/api/v1/config", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals(600.0, response.config?.get("dedup_window_seconds"))
    }

    @Test fun `batch request uses stable JSON field names`() = runTest {
        val prefs = prefs(server.url("/").toString().trimEnd('/'))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"accepted","batch_id":"550e8400-e29b-41d4-a716-446655440000"}"""))
        val item = ReportItem(
            observationId = "550e8400-e29b-41d4-a716-446655440001",
            payload = "AQID",
            rssi = -70,
            latitude = 37.1,
            longitude = -122.2,
            locationAccuracyMeters = 12.5,
            locationSource = "sdk_fused",
            locationIsMock = false,
            clientSeenAt = "2026-01-01T00:00:00Z",
        )

        val response = ApiClient(prefs, { null }).service.batchReport(
            BatchReportRequest("550e8400-e29b-41d4-a716-446655440000", listOf(item)),
        )

        val request = server.takeRequest()
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val report = json.getAsJsonArray("reports")[0].asJsonObject
        assertEquals("AQID", report.get("payload").asString)
        assertEquals(-70, report.get("rssi").asInt)
        assertEquals(37.1, report.get("latitude").asDouble, 0.0)
        assertEquals(-122.2, report.get("longitude").asDouble, 0.0)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", json.get("batch_id").asString)
        assertEquals("550e8400-e29b-41d4-a716-446655440001", report.get("observation_id").asString)
        assertEquals(12.5, report.get("location_accuracy_m").asDouble, 0.0)
        assertEquals("sdk_fused", report.get("location_source").asString)
        assertEquals(false, report.get("location_is_mock").asBoolean)
        assertEquals("2026-01-01T00:00:00Z", report.get("client_seen_at").asString)
        assertEquals(202, response.code())
        assertEquals("accepted", response.body()?.status)
    }

    @Test fun `service rebuilds after URL invalidation`() = runTest {
        val prefs = mock<NbnPrefs>()
        val firstUrl = server.url("/first/").toString().trimEnd('/')
        val secondUrl = server.url("/second/").toString().trimEnd('/')
        whenever(prefs.serverUrl).thenReturn(firstUrl, secondUrl)
        whenever(prefs.anonymousToken).doReturn("token")
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = ApiClient(prefs, { null })

        client.service.getConfig()
        client.invalidate()
        client.service.getConfig()

        assertEquals("/first/api/v1/config", server.takeRequest().path)
        assertEquals("/second/api/v1/config", server.takeRequest().path)
    }


    @Test fun `anonymous token bootstrap works with configured base path`() = runTest {
        val prefs = mock<NbnPrefs>()
        whenever(prefs.serverUrl).doReturn(server.url("/tenant/").toString().trimEnd('/'))
        whenever(prefs.anonymousToken).doReturn("")
        lateinit var client: ApiClient
        client = ApiClient(prefs, ensureAnonymousTokenFn = {
            val tokenResponse = client.service.anonymousToken(AnonymousTokenRequest("key", "public", "signature"))
            tokenResponse.token
        })
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"fresh-token"}""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"config":{}}"""))

        client.service.getConfig()

        val authRequest = server.takeRequest()
        val configRequest = server.takeRequest()
        assertEquals("/tenant/api/v1/auth/anonymous-token", authRequest.path)
        assertNull(authRequest.getHeader("Authorization"))
        assertEquals("/tenant/api/v1/config", configRequest.path)
        assertEquals("Bearer fresh-token", configRequest.getHeader("Authorization"))
    }
    @Test fun `empty server URL fails instead of silently using localhost`() {
        val prefs = prefs("")
        assertThrows(IllegalArgumentException::class.java) {
            ApiClient(prefs, { null }).service
        }
    }

    private fun prefs(serverUrl: String): NbnPrefs = mock {
        on { this.serverUrl } doReturn serverUrl
        on { anonymousToken } doReturn "token"
    }
}
