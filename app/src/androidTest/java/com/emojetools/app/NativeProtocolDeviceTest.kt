package com.emojetools.app

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.AssetKind
import com.emojetools.app.core.download.MediaTools
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.store.AssetStore
import com.emojetools.app.core.store.MediaPublisher
import com.emojetools.app.platform.douyin.CommentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeProtocolDeviceTest {
    @Test fun productionRulesFetchMultiImagesPagesRepliesAndSaveOriginals(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit native protocol opt-in required",
            InstrumentationRegistry.getArguments().getString("native_protocol") == "true")
        val context = instrumentation.targetContext
        val tasks = (context.applicationContext as TaskApplication).tasks
        tasks.initialize()
        val recipe = JSONObject(instrumentation.context.assets.open("comment-originals-case.json").bufferedReader().use { it.readText() })
        val parser = CommentParser(tasks.mapper, tasks.config)
        val variables = mapOf("postId" to recipe.getString("work_id"))
        val root = File(context.cacheDir, "native-protocol-${UUID.randomUUID()}")
        val database = "native-protocol-${UUID.randomUUID()}.db"
        val store = AssetStore(context, database, root)
        val publisher = MediaPublisher(context, store)
        val published = mutableSetOf<Uri>()
        val calls = JSONArray()
        val savedFiles = JSONArray()
        var downloads = 0
        try {
            assertNotNull(tasks.signed.anonymousTag("comment"))
            assertEquals(tasks.signed.anonymousTag("comment"), tasks.signed.anonymousTag("reply"))
            fun comments(document: JSONObject): List<JSONObject> {
                val rows = tasks.mapper.required("comments", document) as JSONArray
                return (0 until rows.length()).map(rows::getJSONObject)
            }
            suspend fun request(operation: String, page: Map<String, String>): JSONObject {
                val document = tasks.signed.request(operation, variables + page)
                val rows = comments(document)
                calls.put(JSONObject().put("operation", operation).put("requested_cursor", page.getValue("cursor"))
                    .put("successful_response", true).put("rows", rows.size).put("cursor", tasks.mapper.required("cursor", document))
                    .put("has_more", tasks.mapper.required("hasMore", document))
                    .put("multi_comments", rows.count { tasks.mapper.values("commentImages", it).size > 1 }))
                return document
            }
            val first = request("comment", mapOf("cursor" to "0", "count" to tasks.config.getInt("count_top").toString()))
            val rows = comments(first)
            rows.forEach { parser.parse(it, null) }
            val target = rows.single { parser.id(it) == recipe.getString("target_comment_id") }
            val originals = parser.parse(target, null).second.filter { it.kind == AssetKind.IMAGE }
            assertEquals(recipe.getInt("target_images"), originals.size)
            assertEquals(originals.size, originals.map { it.id }.toSet().size)
            delay(tasks.config.getLong("page_delay_ms"))
            val cursor = tasks.mapper.required("cursor", first).toString()
            val second = request("comment", mapOf("cursor" to cursor, "count" to tasks.config.getInt("count_top").toString()))
            assertNotEquals(cursor, tasks.mapper.required("cursor", second).toString())
            val media = (rows + comments(second)).flatMap { parser.parse(it, null).second }.toMutableList()
            var samplePage = second
            var samplePages = 2
            while (samplePages < 12 && tasks.mapper.required("hasMore", samplePage).toString() == "1" &&
                (media.none { it.kind == AssetKind.VIDEO } || media.none { it.kind == AssetKind.STICKER })) {
                delay(tasks.config.getLong("page_delay_ms"))
                samplePage = request("comment", mapOf("cursor" to tasks.mapper.required("cursor", samplePage).toString(),
                    "count" to tasks.config.getInt("count_top").toString()))
                media += comments(samplePage).flatMap { parser.parse(it, null).second }
                samplePages++
            }
            val motion = media.firstOrNull { it.kind == AssetKind.VIDEO }
            val sticker = media.firstOrNull { it.kind == AssetKind.STICKER }
            assertNotNull("Acceptance sample needs actual live-photo motion", motion)
            assertNotNull("Acceptance sample needs a sticker", sticker)
            delay(tasks.config.getLong("reply_delay_ms"))
            val reply = request("reply", mapOf("parentId" to parser.id(target), "cursor" to "0",
                "count" to tasks.config.getInt("count_reply").toString()))
            assertTrue(comments(reply).isNotEmpty())
            for (asset in originals + listOfNotNull(motion, sticker)) {
                val downloaded = tasks.downloader.download(asset, File(root, "staging"), "image-${downloads + 1}",
                    tasks.config.getString("ua"), tasks.config.getString("referer"), tasks.config.getLong("max_asset_bytes"))
                downloads++
                val stored = store.record(variables.getValue("postId"), asset, downloaded)
                val uri = publisher.save(stored)
                published += uri
                val digest = MessageDigest.getInstance("SHA-1")
                var bytes = 0L
                val original = File(root, "readback-${downloads}.${downloaded.ext}")
                context.contentResolver.openInputStream(uri)!!.use { input -> original.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); digest.update(buffer, 0, count); bytes += count
                    }
                } }
                if (asset.kind == AssetKind.VIDEO) {
                    assertTrue(MediaTools().tracks(original).video)
                    val decoder = android.media.MediaMetadataRetriever()
                    try {
                        decoder.setDataSource(original.absolutePath)
                        val frame = decoder.getFrameAtTime(0)
                        assertNotNull("Live motion must decode to a real frame", frame)
                        frame!!.recycle()
                    } finally { decoder.release() }
                } else MediaTools().validateImage(original)
                val sha1 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(downloaded.sha1, sha1); assertEquals(downloaded.bytes, bytes)
                assertFalse(stored.file.exists())
                assertEquals(uri, publisher.save(store.verified(stored.postId, stored.assetId)!!))
                savedFiles.put(JSONObject().put("kind", asset.kind.name).put("role", asset.meta["role"])
                    .put("source_animated", asset.meta["animated"])
                    .put("bytes", bytes).put("sha1", sha1).put("ext", downloaded.ext)
                    .put("system_copy_byte_identical", true))
            }
            val report = JSONObject().put("source", "production_comment_rules_on_android").put("bundle", tasks.manager.active.version)
                .put("aweme_id", variables.getValue("postId")).put("target_comment", parser.id(target))
                .put("copied_device_identity", false).put("native_app_process_required", false)
                .put("locally_generated_identity", true).put("local_js_url_builder", true)
                .put("requests", calls).put("downloaded", downloads).put("assets", savedFiles)
            FetchLedger.write(File(context.getExternalFilesDir("test-artifacts"), "native-protocol-live.json"), report)
            Log.i("EmojeNativeProtocol", report.toString())
        } finally {
            try { published.forEach { context.contentResolver.delete(it, null, null) } }
            finally {
                store.close(); context.deleteDatabase(database); root.deleteRecursively()
            }
        }
    }
}
