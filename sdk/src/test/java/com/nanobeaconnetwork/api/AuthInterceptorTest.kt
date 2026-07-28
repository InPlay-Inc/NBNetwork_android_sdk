package com.nanobeaconnetwork.api

import com.nanobeaconnetwork.internal.api.AuthInterceptor
import com.nanobeaconnetwork.internal.prefs.SdkPrefs
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AuthInterceptorTest {

    private val mockServer = MockWebServer()

    @Before fun setup() { mockServer.start() }
    @After fun teardown() { mockServer.shutdown() }

    private fun makeClient(
        prefs: SdkPrefs,
        ensureAnonymous: suspend () -> String? = { null },
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(prefs, ensureAnonymous))
        .build()

    private fun fakePrefs(anon: String = ""): SdkPrefs = mock(SdkPrefs::class.java).also { p ->
        `when`(p.anonymousToken).thenReturn(anon)
    }

    @Test fun `attaches anonymous token as Bearer header`() {
        mockServer.enqueue(MockResponse().setResponseCode(200))
        val req = Request.Builder().url(mockServer.url("/test")).build()
        makeClient(fakePrefs(anon = "anon-xyz")).newCall(req).execute()
        assertEquals("Bearer anon-xyz", mockServer.takeRequest().getHeader("Authorization"))
    }

    @Test fun `lazily fetches anonymous token when none stored`() {
        mockServer.enqueue(MockResponse().setResponseCode(200))
        val req = Request.Builder().url(mockServer.url("/test")).build()
        makeClient(fakePrefs(anon = ""), ensureAnonymous = { "fetched-tok" })
            .newCall(req).execute()
        assertEquals("Bearer fetched-tok", mockServer.takeRequest().getHeader("Authorization"))
    }

    @Test fun `sends no Authorization header when no token and fetch fails`() {
        mockServer.enqueue(MockResponse().setResponseCode(200))
        val req = Request.Builder().url(mockServer.url("/test")).build()
        makeClient(fakePrefs()).newCall(req).execute()
        assertNull(mockServer.takeRequest().getHeader("Authorization"))
    }

    @Test fun `refetches anonymous token on 401 and retries with new token`() {
        val prefs = mock(SdkPrefs::class.java)
        `when`(prefs.anonymousToken).thenReturn("old-anon")

        mockServer.enqueue(MockResponse().setResponseCode(401))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val req = Request.Builder().url(mockServer.url("/api")).build()
        val resp = makeClient(prefs, ensureAnonymous = { "new-anon" }).newCall(req).execute()

        assertEquals(200, resp.code)
        assertEquals(2, mockServer.requestCount)
        mockServer.takeRequest()                          // first (401)
        val retried = mockServer.takeRequest()            // second (200)
        assertEquals("Bearer new-anon", retried.getHeader("Authorization"))
    }

    @Test fun `does not retry on 401 when anonymous refetch fails`() {
        mockServer.enqueue(MockResponse().setResponseCode(401))
        val req = Request.Builder().url(mockServer.url("/api")).build()
        val resp = makeClient(fakePrefs(anon = "tok"), ensureAnonymous = { null })
            .newCall(req).execute()
        assertEquals(401, resp.code)
        assertEquals(1, mockServer.requestCount)
    }
}
