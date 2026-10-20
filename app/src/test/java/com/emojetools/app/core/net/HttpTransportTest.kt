package com.emojetools.app.core.net

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpTransportTest {
    @Test fun longDownloadsCanOutliveIdleTimeoutWhileDataContinuesArriving(): Unit = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("12345").throttleBody(1, 500, TimeUnit.MILLISECONDS))
            val transport = HttpTransport(1, callTimeoutSeconds = 5)
            val body = transport.consume(Request.Builder().url(server.url("/video")).build()) { it.body!!.string() }
            assertEquals("12345", body)
        } finally { server.shutdown() }
    }
}
