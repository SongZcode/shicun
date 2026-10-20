package com.emojetools.app.core.download

import com.emojetools.app.core.*
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.net.HttpTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelAndJoin
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun asset(urls: List<String>) = Asset("sticker:s", AssetKind.STICKER, "COMMENT", urls, null, null, null, null)

    @Test fun pausingDuringAStalledBodyClosesTheConnectionAndRemovesPartialFiles() = runBlocking {
        val server = MockWebServer(); server.start()
        val transport = HttpTransport(30)
        val received = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().setBody("GIF89a-original").throttleBody(1, 3, TimeUnit.SECONDS))
        val job = launch(Dispatchers.IO) {
            Downloader(transport).download(asset(listOf(server.url("/slow").toString())), temporary.root,
                "01_T_123456", "ua", "referer", 1024) { received.complete(Unit) }
        }
        try {
            withTimeout(5000) { received.await() }
            job.cancel()
            val stopped = withTimeoutOrNull(1500) { job.join(); true } == true
            assertTrue("Pause must close a stalled body without waiting for its read timeout", stopped)
            assertTrue(temporary.root.listFiles()!!.isEmpty())
        } finally {
            transport.client.dispatcher.cancelAll(); job.cancelAndJoin(); server.shutdown()
        }
    }

    @Test fun disconnectedBodyCanRetryWithoutReusingAnIncompleteFile() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("GIF89a-incomplete").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val downloader = Downloader(HttpTransport())
            val chosen = asset(listOf(server.url("/image").toString()))
            try {
                downloader.download(chosen, temporary.root, "01_T_123456", "ua", "referer", 1024)
                fail("A disconnected response must fail")
            } catch (e: AppFailure) { assertEquals(ErrorCode.NETWORK, e.code) }
            assertTrue(temporary.root.listFiles()!!.isEmpty())
            server.enqueue(MockResponse().setBody("GIF89a-complete"))
            val saved = downloader.download(chosen, temporary.root, "01_T_123456", "ua", "referer", 1024)
            assertEquals("GIF89a-complete", saved.file.readText())
            assertEquals(2, server.requestCount)
            assertEquals(listOf("01_T_123456.gif"), temporary.root.list()!!.toList())
        } finally { server.shutdown() }
    }

    @Test fun mirrorsMagicHashAndHeadersAreVerified() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("<html>blocked</html>"))
            server.enqueue(MockResponse().setBody("GIF89a-original"))
            val downloaded = Downloader(HttpTransport()).download(asset(listOf(server.url("/bad").toString(), server.url("/good").toString())),
                temporary.root, "01_R_123456", "test-ua", "https://www.douyin.com/", 1024)
            assertEquals("gif", downloaded.ext)
            assertEquals(15L, downloaded.bytes)
            assertEquals("01_R_123456.gif", downloaded.file.name)
            val hash = MessageDigest.getInstance("SHA-1").digest(downloaded.file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(hash, downloaded.sha1)
            assertFalse(File(temporary.root, "01_R_123456.part").exists())
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("test-ua", request.getHeader("User-Agent"))
                assertEquals("https://www.douyin.com/", request.getHeader("Referer"))
                assertNull(request.getHeader("Cookie"))
            }
        } finally { server.shutdown() }
    }

    @Test fun oversizedAndInvalidMediaAreFailuresAndDoNotLeaveCompletedFiles() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("GIF89a-too-large"))
            try {
                Downloader(HttpTransport()).download(asset(listOf(server.url("/large").toString())), temporary.root,
                    "01_T_123456", "ua", "referer", 3)
                fail("Must fail")
            } catch (e: AppFailure) { assertEquals(ErrorCode.NETWORK, e.code) }
            assertEquals(0, temporary.root.listFiles()!!.size)
        } finally { server.shutdown() }
    }

    @Test fun unreadableOriginalMirrorFallsBackWithoutChangingTheAcceptedFile() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("0000ftypheic-original"))
            val jpeg = byteArrayOf(-1, -40, -1) + "compatible-original".toByteArray()
            server.enqueue(MockResponse().setBody(okio.Buffer().write(jpeg)))
            val checked = mutableListOf<String>()
            val downloader = Downloader(HttpTransport()) { _, extension ->
                checked += extension
                if (extension == "heic") throw IOException("Unsupported device decoder")
            }
            val saved = downloader.download(asset(listOf(server.url("/heic").toString(), server.url("/jpeg").toString())),
                temporary.root, "01_T_123456", "ua", "referer", 1024)
            assertEquals(listOf("heic", "jpg"), checked)
            assertEquals(2, server.requestCount)
            assertEquals("jpg", saved.ext); assertArrayEquals(jpeg, saved.file.readBytes())
            assertEquals(jpeg.size.toLong(), saved.bytes)
            assertEquals(listOf("01_T_123456.jpg"), temporary.root.list()!!.toList())
        } finally { server.shutdown() }
    }

    @Test fun unreadableMirrorsExhaustTheFiniteListAndRemoveStagingFiles() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            repeat(2) { server.enqueue(MockResponse().setBody("0000ftypheic-original")) }
            val downloader = Downloader(HttpTransport()) { _, _ -> throw IOException("Unsupported device decoder") }
            try {
                downloader.download(asset(listOf(server.url("/one").toString(), server.url("/two").toString())),
                    temporary.root, "01_T_123456", "ua", "referer", 1024)
                fail("Unreadable images must not be reported as saved")
            } catch (e: AppFailure) { assertEquals(ErrorCode.NETWORK, e.code) }
            assertEquals(2, server.requestCount); assertTrue(temporary.root.listFiles()!!.isEmpty())
        } finally { server.shutdown() }
    }

    @Test fun mixedHeifAndJpegOriginalsUseTheProvidedJpegWithoutReencoding() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val jpeg = byteArrayOf(-1, -40, -1) + "full-size-original".toByteArray()
            server.enqueue(MockResponse().setBody(okio.Buffer().write(jpeg)))
            val image = asset(listOf(server.url("/original.heic?signature=one").toString(),
                server.url("/original.jpeg?signature=two").toString())).copy(kind = AssetKind.IMAGE)
            val saved = Downloader(HttpTransport()).download(image, temporary.root, "01_T_123456", "ua", "referer", 1024)
            assertEquals("/original.jpeg?signature=two", server.takeRequest().path)
            assertEquals(1, server.requestCount); assertArrayEquals(jpeg, saved.file.readBytes())
        } finally { server.shutdown() }
    }

    @Test fun animatedStickersRetainTheirOriginalMirrorOrder() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("GIF89a-original-animation"))
            val sticker = asset(listOf(server.url("/animation.gif").toString(), server.url("/still.heic").toString(),
                server.url("/still.jpeg").toString()))
            val saved = Downloader(HttpTransport()).download(sticker, temporary.root, "01_T_123456", "ua", "referer", 1024)
            assertEquals("/animation.gif", server.takeRequest().path)
            assertEquals("gif", saved.ext); assertEquals("GIF89a-original-animation", saved.file.readText())
        } finally { server.shutdown() }
    }

    @Test fun onlyExplicitlyStaticStickersPreferTheCompatibleJpegMirror() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            for (animated in listOf("false", "true", "")) {
                val payload = if (animated == "false") byteArrayOf(-1, -40, -1) else "0000ftypheic".toByteArray()
                server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
                val sticker = asset(listOf(server.url("/s.gif~original.heif").toString(), server.url("/still.jpeg").toString()))
                    .copy(meta = if (animated.isEmpty()) emptyMap() else mapOf("animated" to animated))
                val saved = Downloader(HttpTransport()).download(sticker, temporary.root, "sticker", "ua", "referer", 1024)
                assertEquals(if (animated == "false") "/still.jpeg" else "/s.gif~original.heif", server.takeRequest().path)
                assertArrayEquals(payload, saved.file.readBytes())
            }
        } finally { server.shutdown() }
    }

    @Test fun magicFormatsIncludeWebpJpegPngAndIsoContainers() {
        assertEquals("gif", Downloader.detect("GIF87a".toByteArray()))
        assertEquals("png", Downloader.detect(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        assertEquals("webp", Downloader.detect("RIFF1234WEBP".toByteArray()))
        assertEquals("jpg", Downloader.detect(byteArrayOf(-1, -40, -1)))
        assertEquals("heic", Downloader.detect("0000ftypmif1".toByteArray()))
        assertEquals("mp4", Downloader.detect("0000ftypisom".toByteArray()))
        assertEquals("m4a", Downloader.detect("0000ftypM4A ".toByteArray()))
        assertEquals("mp3", Downloader.detect("ID3-original".toByteArray()))
        assertEquals("mp3", Downloader.detect(byteArrayOf(-1, -5, -112)))
        assertNull(Downloader.detect(byteArrayOf(-1, -15, 80)))
        assertNull(Downloader.detect("<html>not media</html>".toByteArray()))
    }

    @Test fun audioDownloadFollowsCdnRedirectsAndRejectsImageMirrors() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("GIF89a-image"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/music.mp3")))
            server.enqueue(MockResponse().setBody("ID3-original-music"))
            val audio = Asset("music:1", AssetKind.AUDIO, "POST", listOf(server.url("/wrong").toString(),
                server.url("/redirect").toString()), null, null, null, null)
            val saved = Downloader(HttpTransport(followRedirects = true)).download(audio, temporary.root,
                "a01_000001", "test-ua", "https://www.douyin.com/", 1024)
            assertEquals("mp3", saved.ext)
            assertEquals("ID3-original-music", saved.file.readText())
            repeat(3) {
                val request = server.takeRequest()
                assertEquals("test-ua", request.getHeader("User-Agent"))
                assertEquals("https://www.douyin.com/", request.getHeader("Referer"))
                assertNull(request.getHeader("Cookie"))
            }
            assertEquals(listOf("a01_000001.mp3"), temporary.root.list()!!.toList())
        } finally { server.shutdown() }
    }

    @Test fun ledgerDownloadRetryReplacesFailureRatherThanDoubleCounting() {
        val file = temporary.newFile("manifest.json")
        val ledger = FetchLedger("douyin", "123", JSONObject())
        FetchLedger.write(file, ledger.snapshot(true))
        FetchLedger.recordDownload(file, "sticker:s", null, null, "failed")
        FetchLedger.recordDownload(file, "sticker:s", "abc", 5)
        val result = JSONObject(file.readText())
        assertEquals(1, result.getJSONObject("counters").getInt("assets_downloaded"))
        assertEquals(0, result.getJSONObject("counters").getInt("assets_failed"))
        val checksum = result.getString("checksum")
        FetchLedger.seal(result)
        assertEquals(checksum, result.getString("checksum"))
    }
}
