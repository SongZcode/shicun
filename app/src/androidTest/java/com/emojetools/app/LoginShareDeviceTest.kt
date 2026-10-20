package com.emojetools.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.Intent
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.*
import com.emojetools.app.core.download.MediaTools
import com.emojetools.app.core.store.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LoginShareDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun encryptedSessionsAndLogoutStayWithinTheirPlatform(): Unit = runBlocking {
        fun config(host: String) = JSONObject().put("login_url", "https://$host/").put("ua", "fixture-browser")
            .put("cookie_names", JSONArray(listOf("fixture_session")))
            .put("session_cookie_names", JSONArray(listOf("fixture_session")))
            .put("cookie_origins", JSONArray(listOf("https://$host/")))
            .put("cookie_domains", JSONArray(listOf(".$host")))
        val a = config("session-a.invalid"); val b = config("session-b.invalid")
        val first = SessionStore(context, "session-a-fixture")
        val second = SessionStore(context, "session-b-fixture")
        suspend fun setCookie(config: JSONObject, value: String) {
            val done = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                CookieManager.getInstance().setCookie(config.getString("login_url"),
                    "fixture_session=$value; Domain=${config.getJSONArray("cookie_domains").getString(0)}; Path=/; Secure; HttpOnly") { done.complete(Unit) }
            }
            withTimeout(5000) { done.await() }
        }
        suspend fun clear(store: SessionStore, config: JSONObject) {
            val done = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) { store.clear(config) { done.complete(Unit) } }
            withTimeout(5000) { done.await() }
        }
        try {
            setCookie(a, "first"); setCookie(b, "second")
            first.capture(a); second.capture(b)
            assertEquals("first", SessionStore(context, "session-a-fixture").read("fixture-browser").cookie("fixture_session"))
            assertEquals("second", second.read("fixture-browser").cookie("fixture_session"))
            clear(second, b)
            assertTrue(second.read("fixture-browser").cookies.isEmpty())
            assertNull(second.webSessionFingerprint(b))
            assertNotNull(first.webSessionFingerprint(a))
            assertEquals("first", first.read("fixture-browser").cookie("fixture_session"))
            try { first.read("changed-browser"); fail("Changed browser must require login") }
            catch (e: AppFailure) { assertEquals(ErrorCode.NEED_LOGIN, e.code) }
        } finally {
            clear(first, a); clear(second, b)
            listOf("session-a-fixture", "session-b-fixture").forEach { name ->
                context.getSharedPreferences("login.$name", 0).edit().clear().commit()
            }
        }
    }

    @Test fun compatibleSharingKeepsTransparencyMotionAndOriginals(): Unit = runBlocking {
        val root = File(context.cacheDir, "materials/share-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        fun asset(file: File, kind: AssetKind = AssetKind.STICKER): StoredAsset {
            val measured = MediaTools.measure(file, file.extension)
            return StoredAsset(file.name, "fixture", file.name, kind, file, measured.sha1, measured.bytes)
        }
        try {
            fun webp(name: String, alpha: Boolean): StoredAsset {
                val bitmap = Bitmap.createBitmap(32, 24, if (alpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565)
                bitmap.eraseColor(Color.GREEN)
                if (alpha) bitmap.setPixel(0, 0, Color.TRANSPARENT)
                val file = File(root, "$name.webp")
                try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP, 100, it)) } }
                finally { bitmap.recycle() }
                return asset(file)
            }
            val opaque = webp("opaque", false); val transparent = webp("transparent", true)
            val animated = asset(File(root, "animated.webp").also { file ->
                instrumentation.context.assets.open("media/animated.webp").use { input -> file.outputStream().use { input.copyTo(it) } }
            })
            val video = asset(File(root, "source.mp4").also { file ->
                instrumentation.context.assets.open("media/source.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
            }, AssetKind.VIDEO)
            val originals = listOf(opaque, transparent, animated, video)
            val prepared = CompatibleImages.prepare(context, originals, File(root, "converted"))
            assertEquals("jpg", prepared[0].file.extension)
            assertEquals("png", prepared[1].file.extension)
            val bitmap = BitmapFactory.decodeFile(prepared[1].file.path)
            try { assertEquals(32, bitmap.width); assertEquals(24, bitmap.height); assertEquals(0, Color.alpha(bitmap.getPixel(0, 0))) }
            finally { bitmap.recycle() }
            assertSame(animated, prepared[2]); assertSame(video, prepared[3])
            originals.forEach { original -> assertEquals(original.sha1, MediaTools.measure(original.file, original.file.extension).sha1) }
            val share = MediaSharing.intent(context, prepared)
            assertEquals(Intent.ACTION_SEND_MULTIPLE, share.action)
            assertEquals("*/*", share.type)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            prepared.forEachIndexed { index, item ->
                val uri = share.clipData!!.getItemAt(index).uri
                val readback = File(root, "readback-$index")
                context.contentResolver.openInputStream(uri)!!.use { input -> readback.outputStream().use { input.copyTo(it) } }
                assertEquals(item.sha1, MediaTools.measure(readback, item.file.extension).sha1)
            }
            val broken = asset(File(root, "broken.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) })
            try { CompatibleImages.prepare(context, listOf(broken), File(root, "failed")); fail("Invalid image must fail explicitly") }
            catch (e: AppFailure) { assertEquals(ErrorCode.UNSUPPORTED, e.code) }
            assertEquals(broken.sha1, MediaTools.measure(broken.file, "webp").sha1)
            assertTrue(File(root, "failed").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
