package com.emojetools.app

import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.*
import com.emojetools.app.core.download.CommentDownloader
import com.emojetools.app.core.download.MediaTools
import com.emojetools.app.core.download.PostDownloader
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.store.*
import com.emojetools.app.core.task.TaskCoordinator
import com.emojetools.app.platform.douyin.CommentFetcher
import com.emojetools.app.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class SystemMediaDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver

    private inner class Space : AutoCloseable {
        val token = UUID.randomUUID().toString()
        val root = File(context.getExternalFilesDir(null), "materials/system-test-$token").apply { check(mkdirs()) }
        val database = "system-test-$token.db"
        val store = AssetStore(context, database, root)
        val publisher = MediaPublisher(context, store)
        val published = mutableSetOf<Uri>()
        fun source(name: String, kind: AssetKind, post: String = "123"): StoredAsset {
            val directory = File(root, "fixture-$token").apply { check(isDirectory || mkdirs()) }
            val file = File(directory, name)
            if (name.endsWith(".gif")) file.writeBytes(Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT))
            else instrumentation.context.assets.open("media/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            return store.record(post, Asset(name, kind, "COMMENT", emptyList(), file.extension, null, null, null,
                meta = mapOf("commentId" to "8000000000000000001", "level" to "T")), MediaTools.measure(file, file.extension))
        }
        suspend fun publish(asset: StoredAsset): Uri = publisher.save(asset).also { published += it }
        override fun close() {
            try { published.forEach(::deleteIfPresent) } finally { store.close() }
            context.deleteDatabase(database); root.deleteRecursively()
        }
    }
    private fun deleteIfPresent(uri: Uri) {
        val present = resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
        if (present) resolver.delete(uri, null, null)
    }
    @Before @After fun cleanupFixtureArtifacts() {
        val names = context.databaseList().filter { it.matches(Regex("(?:system-test|task-output)-[0-9a-f-]{36}\\.db")) }
        names.forEach { name ->
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT uri FROM published_assets", null).use { rows ->
                    while (rows.moveToNext()) {
                        val uri = Uri.parse(rows.getString(0))
                        if (uri.scheme == "content" && uri.authority == "media") deleteIfPresent(uri)
                    }
                }
            }
            context.deleteDatabase(name)
            File(context.getExternalFilesDir(null), "materials/${name.removeSuffix(".db")}").deleteRecursively()
        }
    }
    private fun digest(uri: Uri): String = resolver.openInputStream(uri)!!.use { input ->
        val hash = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun rowsAt(asset: StoredAsset, publisher: MediaPublisher): Int = resolver.query(
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(publisher.relativePath(asset)), null)!!.use { it.count }

    @Test fun picturesGifVideoAndAudioPublishWithTheirTypesAndReuseSavedUris(): Unit = runBlocking {
        Space().use { space ->
            val entries = listOf(space.source("image.png", AssetKind.IMAGE), space.source("animated.gif", AssetKind.STICKER),
                space.source("source.mp4", AssetKind.VIDEO), space.source("music.mp3", AssetKind.AUDIO))
            entries.forEach { asset ->
                val uri = space.publish(asset)
                assertFalse(asset.file.exists())
                assertEquals(uri, space.store.verified(asset.postId, asset.assetId)!!.uri)
                assertEquals(asset.sha1, digest(uri))
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE), null, null, null)!!.use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals(MediaPublisher.mime(asset), row.getString(0))
                    assertEquals(space.publisher.relativePath(asset), row.getString(1))
                    assertEquals(0, row.getInt(2)); assertEquals(asset.bytes, row.getLong(3))
                    assertTrue(row.getString(1).startsWith(when (asset.kind) {
                        AssetKind.AUDIO -> "Music/"; AssetKind.VIDEO -> "Movies/"; else -> "Pictures/"
                    }))
                }
                assertEquals(uri, MediaPublisher(context, space.store).save(asset))
                assertFalse(space.store.publication(asset.key)!!.pending)
            }
            assertEquals(2, rowsAt(entries.first(), space.publisher))
            assertEquals(1, rowsAt(entries[2], space.publisher)); assertEquals(1, rowsAt(entries[3], space.publisher))
        }
    }

    @Test fun aValidPendingCopyRecoversAndDeletedMediaCanBeDownloadedAgain(): Unit = runBlocking {
        Space().use { space ->
            val asset = space.source("image.png", AssetKind.IMAGE)
            val pending = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, asset.file.name); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, space.publisher.relativePath(asset)); put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            space.published += pending
            resolver.openOutputStream(pending)!!.use { output -> asset.file.inputStream().use { it.copyTo(output) } }
            space.store.recordPublication(PublishedAsset(asset.key, pending.toString(), asset.sha1, asset.bytes, true))
            assertEquals(pending, space.publish(asset)); assertFalse(space.store.publication(asset.key)!!.pending)
            assertEquals(1, resolver.delete(pending, null, null))
            space.published.remove(pending)
            assertNull(space.store.verified(asset.postId, asset.assetId))
            val replacement = space.publish(space.source("image.png", AssetKind.IMAGE))
            assertNotEquals(pending, replacement); assertEquals(asset.sha1, digest(replacement))
            assertEquals(1, rowsAt(asset, space.publisher))
        }
    }

    @Test fun aCorruptCopyFailsWithoutLeavingAnUnfinishedAlbumEntry(): Unit = runBlocking {
        Space().use { space ->
            val asset = space.source("image.png", AssetKind.IMAGE).copy(sha1 = "0".repeat(40))
            try { space.publish(asset); fail("Corrupt source must fail") }
            catch (e: AppFailure) { assertEquals(ErrorCode.STORAGE, e.code) }
            assertNull(space.store.publication(asset.key)); assertEquals(0, rowsAt(asset, space.publisher))
        }
    }

    @Test fun aFailedDeliveryIsNotSuccessAndRetryReusesTheDownloadedFile(): Unit = runBlocking {
        Space().use { space ->
            val tasks = (context.applicationContext as TaskApplication).tasks
            tasks.initialize()
            val row = JSONObject().put("cid", "8000000000000000001").put("text", "Fixture")
                .put("user", JSONObject().put("nickname", "Fixture"))
                .put("image_list", JSONArray().put(JSONObject().put("origin_url", JSONObject()
                    .put("uri", "fixture-image").put("url_list", JSONArray().put("https://example.invalid/image.png")))))
            val page = JSONObject().put("comments", JSONArray().put(row)).put("cursor", 50).put("has_more", 0)
            val ledger = File(space.root, "manifest.json")
            val result = CommentFetcher(tasks.config, tasks.mapper, { _, _ -> page }, { FetchLedger.write(ledger, it) }, {})
                .fetch(PostRef("douyin", "123"), 0, ledger.absolutePath)
            var downloads = 0
            var reject = true
            val saver = CommentDownloader(space.store, { stored ->
                if (reject) throw AppFailure(ErrorCode.STORAGE, "Fixture publication failed")
                space.publish(stored)
            }) { _, directory, stem ->
                downloads++; check(directory.isDirectory || directory.mkdirs())
                val file = File(directory, "$stem.png")
                instrumentation.context.assets.open("media/image.png").use { input -> file.outputStream().use { input.copyTo(it) } }
                MediaTools.measure(file, "png")
            }
            val failed = saver.save("123", result.assets, result) { _, _ -> }
            assertEquals(0, failed.saved); assertEquals(1, failed.failed)
            assertEquals(1, downloads)
            reject = false
            val saved = saver.save("123", result.assets, result) { _, _ -> }
            assertEquals(1, saved.saved); assertEquals(0, saved.failed); assertEquals(1, downloads)
            val document = JSONObject(ledger.readText())
            FetchLedger.restore(document)
            assertEquals(1, document.getJSONObject("counters").getInt("assets_downloaded"))
            assertEquals(0, document.getJSONObject("counters").getInt("assets_failed"))
        }
    }

    @Test fun taskSavePublishesWhileTaskShareOnlyPreparesReadableFiles(): Unit = runBlocking {
        val token = UUID.randomUUID().toString()
        val root = File(context.getExternalFilesDir(null), "materials/task-output-$token").apply { check(mkdirs()) }
        val database = "task-output-$token.db"
        val tasks = TaskCoordinator(context, database, root)
        val publicUris = mutableListOf<Uri>()
        val ownedFiles = mutableListOf<File>()
        try {
            tasks.initialize()
            for ((index, action) in OutputAction.entries.withIndex()) {
                val file = File(root, "fixture-$index.png")
                instrumentation.context.assets.open("media/image.png").use { input -> file.outputStream().use { input.copyTo(it) } }
                val asset = Asset("fixture-$index", AssetKind.IMAGE, "COMMENT", emptyList(), "png", null, null, null,
                    meta = mapOf("commentId" to "8000000000000000001", "level" to "T"))
                tasks.store.record("123", asset, MediaTools.measure(file, "png"))
                val id = tasks.add(UiTask(System.currentTimeMillis() + index, "fixture-output", DownloadMode.COMMENTS,
                    R.string.format_original, "Fixture", postId = "123"))
                val task = tasks.task(id)!!
                val result = CommentResult(listOf(asset), 1, null, 1, 0, "server_end", listOf(
                    CommentCandidate("8000000000000000001", null, "Fixture", "Fixture", emptyList(), listOf(asset.id))))
                val resultFile = tasks.resultFile(task).also { ownedFiles += it }
                FetchLedger.write(resultFile, CommentResultJson.encode(result))
                tasks.select(id, listOf(asset), action); tasks.run(id)
                val done = tasks.task(id)!!
                assertEquals(TaskStage.DONE, done.stage); assertEquals(action, done.outputAction)
                assertEquals(setOf(asset.id), done.outputs.keys)
                assertEquals(done, TaskJson.decode(TaskJson.encode(done, context.resources), context.resources))
                val uri = Uri.parse(done.outputs.getValue(asset.id))
                if (action == OutputAction.SAVE) {
                    publicUris += uri; assertEquals("media", uri.authority)
                    assertFalse(file.exists())
                    tasks.run(id); assertEquals(uri.toString(), tasks.task(id)!!.outputs[asset.id])
                } else {
                    assertNull(tasks.store.publication(tasks.store.key("123", asset.id)))
                    assertEquals("${context.packageName}.files", uri.authority)
                    val intent = tasks.shareIntent(done)
                    assertEquals(Intent.ACTION_SEND, intent.action)
                    assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
                    assertEquals(tasks.store.find("123", asset.id)!!.sha1, digest(uri))
                }
            }
        } finally {
            publicUris.forEach { resolver.delete(it, null, null) }; ownedFiles.forEach { it.delete() }
            tasks.store.close(); withContext(Dispatchers.Main) { tasks.js.close() }; context.deleteDatabase(database); root.deleteRecursively()
        }
    }

    @Test fun commentRetryKeepsSuccessfulOutputsAndReportsTheWholeSelection(): Unit = runBlocking {
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "materials/comment-retry-$token").apply { check(mkdirs()) }
        val database = "comment-retry-$token.db"
        val tasks = TaskCoordinator(context, database, root)
        val publicUris = mutableSetOf<Uri>()
        var resultFile: File? = null
        try {
            tasks.initialize()
            val assets = (1..2).map { n -> Asset("retry-$n", AssetKind.IMAGE, "COMMENT", emptyList(), "png", null, null, null) }
            for ((n, asset) in assets.withIndex()) {
                val file = File(root, "retry-$n.png")
                instrumentation.context.assets.open("media/image.png").use { input -> file.outputStream().use { input.copyTo(it) } }
                tasks.store.record("123", asset, MediaTools.measure(file, "png"))
            }
            val first = tasks.mediaPublisher.save(tasks.store.verified("123", assets[0].id)!!).also { publicUris += it }
            val id = tasks.add(UiTask(System.currentTimeMillis(), "fixture-retry", DownloadMode.COMMENTS, R.string.format_original,
                "Matching", stage = TaskStage.PARTIAL, postId = "123", operation = "download", selectedIds = assets.map { it.id }.toMutableSet(),
                outputs = linkedMapOf(assets[0].id to first.toString())))
            val task = tasks.task(id)!!
            val result = CommentResult(assets, 1, null, 1, 0, "server_end", listOf(
                CommentCandidate("1", null, "Matching", "Fixture", emptyList(), assets.map { it.id })))
            resultFile = tasks.resultFile(task)
            FetchLedger.write(resultFile!!, CommentResultJson.encode(result))
            tasks.run(id)
            val done = tasks.task(id)!!
            publicUris += done.outputs.values.map(Uri::parse)
            assertEquals(TaskStage.DONE, done.stage)
            assertEquals(context.getString(R.string.download_summary, 2, 0), done.detail)
            assertEquals(first.toString(), done.outputs[assets[0].id])
            assertEquals(2, done.outputs.size)
            assertEquals(task.selectedIds, done.selectedIds)
        } finally {
            publicUris.forEach { resolver.delete(it, null, null) }; resultFile?.delete()
            tasks.store.close(); withContext(Dispatchers.Main) { tasks.js.close() }
            context.deleteDatabase(database); root.deleteRecursively()
        }
    }

    @Test fun multiShareHasReadableClipDataAndDoesNotPublishToAlbums(): Unit = runBlocking {
        Space().use { space ->
            val assets = listOf(space.source("image.png", AssetKind.IMAGE), space.source("source.mp4", AssetKind.VIDEO))
            val intent = MediaSharing.intent(context, assets)
            assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action); assertEquals("*/*", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(2, intent.clipData!!.itemCount)
            assets.forEachIndexed { index, asset ->
                assertEquals(asset.sha1, digest(intent.clipData!!.getItemAt(index).uri))
                assertNull(space.store.publication(asset.key)); assertEquals(0, rowsAt(asset, space.publisher))
            }
            val chooser = Intent.createChooser(intent, "Fixture")
            assertEquals(2, chooser.clipData!!.itemCount)
            assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }

    @Test fun savedImagesAndVideoPreviewDirectlyFromSystemUris(): Unit = runBlocking {
        Space().use { space ->
            listOf(space.source("image.png", AssetKind.IMAGE), space.source("animated.gif", AssetKind.STICKER),
                space.source("source.mp4", AssetKind.VIDEO)).forEach { asset ->
                val uri = space.publish(asset)
                withContext(Dispatchers.Main) {
                    val view = ImageView(context)
                    MediaPreview.load(view, uri, asset.file.extension)
                    assertNotNull(view.drawable)
                }
                assertFalse(asset.file.exists())
            }
        }
    }

    @Test fun expiredTemporaryShareMediaIsCleanedWithoutDeletingPublishedMedia(): Unit = runBlocking {
        Space().use { space ->
            val saved = space.source("image.png", AssetKind.IMAGE)
            val uri = space.publish(saved)
            val shared = space.source("source.mp4", AssetKind.VIDEO)
            check(shared.file.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L))
            space.store.cleanupTemporaryMedia()
            assertFalse(shared.file.exists()); assertNull(space.store.verified(shared.postId, shared.assetId))
            assertEquals(saved.sha1, digest(uri)); assertEquals(uri, space.store.verified(saved.postId, saved.assetId)!!.uri)
        }
    }

    @Test fun aSavedWorkAndItsCompleteAudioReuseTheSystemCopyWithoutAnotherDownload(): Unit = runBlocking {
        Space().use { space ->
            val storedVideo = space.source("source.mp4", AssetKind.VIDEO)
            val videoUri = space.publish(storedVideo)
            val video = Asset(storedVideo.assetId, AssetKind.VIDEO, "POST", emptyList(), "mp4", 160, 120, storedVideo.bytes)
            val audio = Asset("derived-audio", AssetKind.AUDIO, "POST", emptyList(), "m4a", null, null, null,
                meta = mapOf("transform" to "complete_audio", "derivedFrom" to video.id, "role" to "complete_audio"))
            val post = Post(PostRef("douyin", "123"), "Fixture", "Fixture", "fixture", null, null, null, emptyMap(), emptyList())
            val manifest = File(space.root, "post-manifest.json")
            val media = PostMedia(post, listOf(video, audio))
            var downloads = 0
            val saver = PostDownloader(space.store, File(space.root, "staging"), MediaTools(), { _, _, _ ->
                downloads++; throw AssertionError("Saved system media must not be downloaded again")
            }, { throw AssertionError("No refresh required") }, 0, { space.publish(it) })
            assertEquals(2, saver.save(media, media.assets, manifest).saved)
            assertEquals(0, downloads)
            assertEquals(videoUri, space.store.verified("123", video.id)!!.uri)
            val savedAudio = space.store.verified("123", audio.id)!!
            assertNotNull(savedAudio.uri); assertFalse(savedAudio.file.exists())
            assertTrue(space.publisher.relativePath(savedAudio).startsWith("Music/"))
            assertTrue(JSONObject(manifest.readText()).getJSONObject("assets").getJSONObject(audio.id).getString("path").startsWith("content://media/"))
            assertEquals(2, saver.save(media, media.assets, manifest).saved); assertEquals(0, downloads)
            File(context.getExternalFilesDir("test-artifacts"), "direct-save-manifest.json").writeText(manifest.readText())
        }
    }

    @Test fun bothSelectionScreensExposeSaveAndShareDirectly() {
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/.ui.MainActivity").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        val asset = Asset("fixture", AssetKind.IMAGE, "COMMENT", listOf("https://example.invalid/image.png"), "png", null, null, null)
        val result = CommentResult(listOf(asset), 1, null, 1, 0, "server_end", listOf(
            CommentCandidate("1", null, "Fixture", "Fixture", emptyList(), listOf(asset.id))))
        fun flatten(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: Dialog
            var chosen = emptyList<Asset>()
            var action: OutputAction? = null
            scenario.onActivity { activity ->
                dialog = CommentSelection(activity, UiTask(1, "fixture", DownloadMode.COMMENTS, R.string.format_original, "Fixture"), result,
                    { view, _ -> view.setImageResource(R.drawable.ic_file_image) }, searching = true) { selected, output -> chosen = selected; action = output }.show()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val buttons = flatten(dialog.window!!.decorView).filterIsInstance<Button>().filter { it !is CheckBox && it.isShown }
                assertTrue(buttons.all { !it.isEnabled })
                flatten(dialog.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = true
                assertTrue(buttons.all { it.isEnabled })
                assertEquals(listOf(activity.getString(R.string.save_selected), activity.getString(R.string.share_asset)), buttons.map { it.text.toString() })
            }
            screenshot("direct-save-comment-fixture.png")
            scenario.onActivity { activity ->
                flatten(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text == activity.getString(R.string.share_asset) }.performClick()
                assertEquals(OutputAction.SHARE, action); assertEquals(listOf("fixture"), chosen.map { it.id })
                val post = Post(PostRef("douyin", "123"), "Fixture work", "Fixture author", "fixture", null, null, null, emptyMap(), emptyList())
                dialog = PostSelection(activity, UiTask(2, "fixture", DownloadMode.POST, R.string.format_original, ""),
                    PostMedia(post, listOf(asset.copy(source = "POST"))), { view, _ -> view.setImageResource(R.drawable.ic_file_image) }) { selected, output -> chosen = selected; action = output }.show()
            }
            instrumentation.waitForIdleSync()
            screenshot("direct-save-post-fixture.png")
            scenario.onActivity { activity ->
                val save = flatten(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text == activity.getString(R.string.save_selected) }
                assertTrue(save.isEnabled); save.performClick(); assertEquals(OutputAction.SAVE, action)
            }
        }
    }

    @Test fun obsoleteDevelopmentIndexIsResetWithoutDeletingMediaFiles() {
        val token = UUID.randomUUID().toString()
        val name = "obsolete-media-$token.db"
        val media = File(context.cacheDir, "preserved-$token.png").apply { writeText("preserve") }
        val path = context.getDatabasePath(name)
        val old = SQLiteDatabase.openOrCreateDatabase(path, null)
        old.execSQL("CREATE TABLE assets (asset_key TEXT PRIMARY KEY, post_id TEXT NOT NULL, asset_id TEXT NOT NULL, kind TEXT NOT NULL, path TEXT NOT NULL, sha1 TEXT NOT NULL, bytes INTEGER NOT NULL)")
        old.execSQL("CREATE TABLE tasks (task_id INTEGER PRIMARY KEY, document TEXT NOT NULL)")
        old.execSQL("INSERT INTO assets VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf("123:old", "123", "old", "IMAGE", media.absolutePath, "a".repeat(40), media.length()))
        old.execSQL("INSERT INTO tasks VALUES (?, ?)", arrayOf(1, "{\"fixture\":true}"))
        old.version = 1; old.close()
        try { AssetStore(context, name).use { upgraded ->
            assertTrue(upgraded.all().isEmpty()); assertTrue(upgraded.tasks().isEmpty())
            assertNull(upgraded.publication("123:old"))
            assertEquals("preserve", media.readText())
        } } finally { context.deleteDatabase(name); media.delete() }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(350, 5000)
        SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(context.getExternalFilesDir("test-artifacts"), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
