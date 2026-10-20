package com.emojetools.app

import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.*
import com.emojetools.app.core.download.FavoriteDownloader
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.store.*
import com.emojetools.app.platform.douyin.FavoriteStickerFetcher
import kotlinx.coroutines.*
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
@SdkSuppress(minSdkVersion = 29)
class FavoriteStickersDeviceTest {
    @Test fun productionRulesPageRealFavoritesAndSaveShareReuseBothFormats(): Unit = runBlocking {
        assumeTrue("Explicit live favorites opt-in required", InstrumentationRegistry.getArguments().getString("favorites_protocol") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tasks = (context.applicationContext as TaskApplication).tasks
        tasks.initialize()
        val first = tasks.adapter.favoriteStickers("0")
        assertTrue("Acceptance account needs favorite stickers", first.assets.isNotEmpty())
        val profile = JSONObject(tasks.config.toString()).apply { getJSONObject("favorites").put("page_size", 2) }
        val fetcher = FavoriteStickerFetcher(profile, tasks.mapper, tasks.signed::get)
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "favorite-acceptance-$token")
        val database = "favorite-acceptance-$token.db"
        val store = AssetStore(context, database, root)
        val publisher = MediaPublisher(context, store)
        val published = mutableSetOf<Uri>()
        val outputs = linkedMapOf<String, Uri>()
        val pages = JSONArray()
        val files = JSONArray()
        var downloads = 0
        try {
            var state = FavoriteCollection("live-test", "favorites-" + MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }, "")
            while (!state.completed && !state.stopped) {
                if (state.cursors.isNotEmpty()) delay(profile.getLong("page_delay_ms"))
                val cursor = state.cursor
                val page = fetcher.page(cursor)
                state = state.append(page, 100)
                pages.put(JSONObject().put("request_cursor", cursor).put("next_cursor", page.nextCursor)
                    .put("rows", page.assets.size).put("completed", page.completed))
            }
            assertTrue("Real pagination must reach server end", state.completed)
            assertTrue("Small page size must exercise real continuation", pages.length() > 1)
            if (first.completed) assertEquals(first.assets.map { it.id }.toSet(), state.assets.map { it.id }.toSet())
            val selected = listOfNotNull(state.assets.firstOrNull { it.meta["format"] == "gif" },
                state.assets.firstOrNull { it.meta["format"] in setOf("jpeg", "png") })
            assertEquals("Acceptance needs an animated and a static sample", 2, selected.size)
            val saver = FavoriteDownloader(store, { saved ->
                publisher.save(saved).also { uri -> published += uri; outputs[saved.assetId] = uri }
            }) { asset, directory, stem ->
                downloads++
                tasks.downloader.download(asset, directory, stem, tasks.sessions.read(tasks.config.getString("ua")).userAgent,
                    tasks.config.getString("referer"), tasks.config.getLong("max_asset_bytes"))
            }
            assertEquals(0, saver.save(state.key, selected) { _, _ -> }.failed)
            assertEquals(2, downloads)
            val originalUris = outputs.toMap()
            assertEquals(0, saver.save(state.key, selected) { _, _ -> }.failed)
            assertEquals(2, downloads); assertEquals(originalUris, outputs)
            for (asset in selected) {
                val saved = store.verified(state.key, asset.id)!!
                assertFalse("Save must remove staging copy", saved.file.exists())
                val uri = saved.uri!!
                val digest = MessageDigest.getInstance("SHA-1")
                var bytes = 0L
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); bytes += n }
                }
                assertEquals(saved.bytes, bytes)
                assertEquals(saved.sha1, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri))
                files.put(JSONObject().put("declared_format", asset.meta["format"]).put("actual_extension", saved.file.extension)
                    .put("animated", drawable is AnimatedImageDrawable).put("width", drawable.intrinsicWidth).put("height", drawable.intrinsicHeight)
                    .put("bytes", bytes).put("sha1", saved.sha1).put("byte_identical", true).put("reused_uri", true))
                if (drawable is AnimatedImageDrawable) drawable.stop()
            }
            assertTrue(files.getJSONObject(0).getBoolean("animated"))
            assertFalse(files.getJSONObject(1).getBoolean("animated"))
            val share = MediaSharing.intent(context, selected.map { store.verified(state.key, it.id)!! })
            assertEquals(Intent.ACTION_SEND_MULTIPLE, share.action); assertEquals(2, share.clipData!!.itemCount)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            for (i in 0 until share.clipData!!.itemCount) context.contentResolver.openInputStream(share.clipData!!.getItemAt(i).uri)!!.use { assertTrue(it.read() >= 0) }
            val deleted = outputs.getValue(selected[1].id)
            assertEquals(1, context.contentResolver.delete(deleted, null, null))
            published.remove(deleted)
            assertEquals(0, saver.save(state.key, selected) { _, _ -> }.failed)
            assertEquals(3, downloads)
            assertNotEquals(originalUris[selected[1].id], outputs[selected[1].id])
            val report = JSONObject().put("bundle", tasks.manager.active.version).put("production_rules", true)
                .put("unique_stickers", state.assets.size).put("pages", pages).put("termination", "server_end")
                .put("samples", files).put("readable_share_uris", true).put("read_grant", true)
                .put("deleted_system_file_resaved", true).put("isolated_store", true).put("owned_publications_removed", true)
            FetchLedger.write(File(context.getExternalFilesDir("test-artifacts"), "favorite-stickers-acceptance.json"), report)
            Log.i("EmojeFavoriteAcceptance", report.toString())
        } finally {
            try { published.forEach { context.contentResolver.delete(it, null, null) } }
            finally { store.close(); context.deleteDatabase(database); root.deleteRecursively() }
        }
    }
}
