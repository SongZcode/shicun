package com.emojetools.app

import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.*
import com.emojetools.app.core.download.*
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.store.AssetStore
import com.emojetools.app.core.store.MediaPublisher
import com.emojetools.app.core.task.TaskCoordinator
import com.emojetools.app.platform.douyin.CommentFetcher
import com.emojetools.app.platform.douyin.PostParser
import com.emojetools.app.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LiveAcceptanceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private suspend fun coordinator(): TaskCoordinator {
        assumeTrue("Explicit live acceptance opt-in required", arguments.getString("live_acceptance") == "true")
        return (context.applicationContext as TaskApplication).tasks.also { it.initialize() }
    }
    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun report(name: String, value: JSONObject) {
        FetchLedger.write(File(context.getExternalFilesDir("test-artifacts"), name), value)
        Log.i("EmojeLiveAcceptance", value.toString())
    }

    @Test fun suppliedNativeMultiImageCommentDownloadsPreviewsAndPublishesBothOriginals(): Unit = runBlocking {
        val tasks = coordinator()
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 28)
        val sample = File(context.getExternalFilesDir("test-artifacts"), "native-model.json")
        assumeTrue("Supply a local native model sample", sample.isFile)
        val comments = JSONObject(sample.readText()).getJSONArray("comments")
        val row = (0 until comments.length()).map(comments::getJSONObject)
            .single { it.getString("cid") == arguments.getString("native_cid") }
        val postId = row.getString("aweme_id")
        val root = File(context.cacheDir, "live-acceptance-${UUID.randomUUID()}")
        val database = "live-acceptance-${UUID.randomUUID()}.db"
        val store = AssetStore(context, database, root)
        val publisher = MediaPublisher(context, store)
        val published = mutableListOf<Uri>()
        val entries = JSONArray()
        try {
            val manifest = File(root, "manifest.json")
            val response = JSONObject().put("comments", JSONArray().put(row)).put("cursor", tasks.config.getInt("count_top"))
                .put("has_more", 0).put("total", 1)
            val result = CommentFetcher(tasks.config, tasks.mapper, { _, _ -> response }, { FetchLedger.write(manifest, it) }, {})
                .fetch(PostRef("douyin", postId), tasks.config.getInt("limit_top"), manifest.absolutePath)
            val originals = result.assets.filter { it.kind == AssetKind.IMAGE }
            assertEquals(2, originals.size); assertEquals(2, originals.map { it.id }.toSet().size)
            var downloads = 0
            val saver = CommentDownloader(store, { saved ->
                val uri = publisher.save(saved)
                if (uri !in published) {
                    published += uri
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    assertEquals(saved.sha1, sha1(bytes)); assertEquals(saved.bytes, bytes.size.toLong())
                    var width = 0; var height = 0
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) { _, info, _ ->
                        width = info.size.width; height = info.size.height
                    }
                    assertTrue(width > 0 && height > 0)
                    assertFalse(saved.file.exists())
                    entries.put(JSONObject().put("id", saved.assetId).put("format", saved.file.extension)
                        .put("width", width).put("height", height).put("bytes", saved.bytes).put("sha1", saved.sha1)
                        .put("system_copy_byte_identical", true))
                }
            }) { asset, directory, stem ->
                downloads++
                tasks.downloader.download(asset, directory, stem, tasks.sessions.read(tasks.config.getString("ua")).userAgent,
                    tasks.config.getString("referer"), tasks.config.getLong("max_asset_bytes"))
            }
            val first = saver.save(postId, originals, result) { _, _ -> }
            assertEquals(first.failures.joinToString { it.code.name + ": " + it.message }, 0, first.failed)
            assertEquals(2, first.saved); assertEquals(2, downloads)
            assertEquals(2, saver.save(postId, originals, result) { _, _ -> }.saved); assertEquals(2, downloads)
            val diagnostics = JSONArray()
            for ((index, uri) in published.withIndex()) {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val detail = JSONObject().put("index", index).put("bytes", bytes.size).put("sha1", sha1(bytes))
                    .put("magic", bytes.take(16).joinToString("") { "%02x".format(it.toInt() and 255) })
                    .put("mime", context.contentResolver.getType(uri))
                for (kind in listOf("uri", "buffer")) {
                    try {
                        val source = if (kind == "uri") ImageDecoder.createSource(context.contentResolver, uri)
                            else ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                        val drawable = ImageDecoder.decodeDrawable(source)
                        detail.put(kind, "${drawable.intrinsicWidth}x${drawable.intrinsicHeight}")
                    } catch (e: Exception) { detail.put(kind, e.javaClass.simpleName + ": " + e.message) }
                }
                diagnostics.put(detail)
            }
            report("native-decode-diagnostics.json", JSONObject().put("assets", diagnostics))
            instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/.ui.MainActivity").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
            val drawables = originals.mapIndexed { index, asset -> asset.id to
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, published[index])) }.toMap()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var dialog: android.app.Dialog
                scenario.onActivity { activity ->
                    val comment = result.comments.single()
                    dialog = CommentSelection(activity, UiTask(1, "native-model fixture", DownloadMode.COMMENTS,
                        R.string.format_original, comment.text.ifBlank { comment.id },
                        targetKind = if (comment.text.isBlank()) R.string.target_id else R.string.target_text,
                        selectedIds = originals.map { it.id }.toMutableSet()),
                        result, { image, asset -> image.setImageDrawable(drawables.getValue(asset.id)) }) { _, _ -> }.show()
                }
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.waitForIdle(350, 5000)
                android.os.SystemClock.sleep(250)
                val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
                File(context.getExternalFilesDir("test-artifacts"), "native-two-originals.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
                scenario.onActivity { dialog.dismiss() }
            }
            report("native-two-originals.json", JSONObject().put("comment_id", row.getString("cid"))
                .put("list_api_requested", false).put("source", "supplied_native_loaded_model")
                .put("saved", first.saved).put("source_downloads", downloads).put("reused_without_download", true).put("assets", entries))
        } finally {
            published.forEach { context.contentResolver.delete(it, null, null) }
            store.close(); context.deleteDatabase(database); root.deleteRecursively()
        }
    }

    @Test fun liveVideoUsesTheHighestAvailableRenditionWithoutReencoding(): Unit = runBlocking {
        val tasks = coordinator()
        val source = arguments.getString("video_source")
        assumeTrue("Supply a live video source", !source.isNullOrBlank())
        val ref = tasks.adapter.resolve(source!!)
        val response = tasks.signed.get("detail", mapOf("postId" to ref.id))
        val media = PostParser(tasks.mapper, tasks.config).parse(ref, response)
        val videos = media.assets.filter { it.kind == AssetKind.VIDEO }
        val highest = videos.first()
        assertTrue(videos.all { (it.width ?: 0).toLong() * (it.height ?: 0) <= highest.width!!.toLong() * highest.height!! })
        val root = File(context.cacheDir, "live-video-${UUID.randomUUID()}")
        val database = "live-video-${UUID.randomUUID()}.db"
        val store = AssetStore(context, database, root)
        var downloaded: DownloadedFile? = null
        try {
            val saver = PostDownloader(store, File(root, "staging"), MediaTools(), { asset, directory, stem ->
                tasks.downloader.download(asset, directory, stem, tasks.sessions.read(tasks.config.getString("ua")).userAgent,
                    tasks.config.getString("referer"), tasks.config.getLong("max_asset_bytes")).also { downloaded = it }
            }, { error("Freshly fetched source must not require refresh") }, 0)
            val summary = saver.save(media, listOf(highest), File(root, "manifest.json"))
            assertEquals(summary.failures.joinToString { it.code.name + ": " + it.message }, 0, summary.failed)
            assertEquals(1, summary.saved)
            val saved = store.verified(ref.id, highest.id)!!
            assertEquals(downloaded!!.sha1, saved.sha1); assertEquals(downloaded!!.bytes, saved.bytes)
            val extractor = MediaExtractor()
            val tracks = JSONArray()
            try {
                extractor.setDataSource(saved.file.absolutePath)
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val track = JSONObject().put("mime", format.getString(MediaFormat.KEY_MIME))
                    if (format.containsKey(MediaFormat.KEY_WIDTH)) track.put("width", format.getInteger(MediaFormat.KEY_WIDTH))
                    if (format.containsKey(MediaFormat.KEY_HEIGHT)) track.put("height", format.getInteger(MediaFormat.KEY_HEIGHT))
                    tracks.put(track)
                }
            } finally { extractor.release() }
            report("highest-video.json", JSONObject().put("aweme_id", ref.id).put("renditions", videos.size)
                .put("api_rendition_rows", tasks.mapper.values("videoRates", response).size)
                .put("gear", highest.meta["gear"]).put("api_width", highest.width).put("api_height", highest.height)
                .put("api_bytes", highest.bytes).put("saved_bytes", saved.bytes).put("sha1", saved.sha1)
                .put("source_byte_identical", true).put("tracks", tracks))
        } finally { store.close(); context.deleteDatabase(database); root.deleteRecursively() }
    }
}
