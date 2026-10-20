package com.emojetools.app

import android.content.Intent
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.*
import com.emojetools.app.core.download.MediaTools
import com.emojetools.app.core.js.JsEngine
import com.emojetools.app.core.net.HttpTransport
import com.emojetools.app.core.net.JsonMapper
import com.emojetools.app.core.store.AccountWorksCollection
import com.emojetools.app.core.task.TaskCoordinator
import com.emojetools.app.core.task.TaskService
import com.emojetools.app.platform.bilibili.BilibiliAdapter
import com.emojetools.app.platform.douyin.WorkCollectionKind
import com.emojetools.app.platform.douyin.AccountWork
import com.emojetools.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BilibiliLibraryDeviceTest {
    @Test fun longMemberDownloadPausesAndContinuesInBackground(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("bilibili_closeout") == "true")
        val context = instrumentation.targetContext
        val tasks = (context.applicationContext as TaskApplication).tasks
        val notices = context.getSystemService(NotificationManager::class.java)
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        suspend fun settled(id: Long): UiTask = withTimeout(180_000) {
            tasks.state.first { state -> state.activeId == null && state.tasks.any { it.id == id && !it.stage.isRunning() } }
                .tasks.single { it.id == id }
        }
        suspend fun waitForForeground() = withTimeout(10_000) {
            while (notices.activeNotifications.none { it.id == TaskService.NOTIFICATION_ID &&
                    it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }) delay(50)
        }
        val publications = mutableSetOf<Uri>()
        var id: Long? = null
        val readback = File(context.cacheDir, "bili-closeout-${UUID.randomUUID()}.mp4")
        try {
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            shell("am start -W -n ${context.packageName}/.ui.MainActivity")
            tasks.initialize()
            assertFalse("No fixture task may already be running", tasks.busy)
            id = tasks.add(UiTask(System.currentTimeMillis(), "https://b23.tv/ep718242", DownloadMode.BILIBILI, R.string.format_original, ""))
            tasks.run(id)
            val selecting = tasks.task(id)!!
            assertEquals(selecting.detail, TaskStage.SELECTING, selecting.stage)
            val media = tasks.loadPost(selecting)!!
            val video = media.assets.last { it.kind == AssetKind.VIDEO }
            assertNull("Use a fresh isolated download", tasks.store.verified(selecting.postId, video.id))
            tasks.select(id, listOf(video))
            val sources = File(context.cacheDir, "sources")
            val before = withContext(Dispatchers.IO) { sources.walkTopDown().filter(File::isFile).map { it.path }.toSet() }
            withContext(Dispatchers.Main) { TaskService.start(context, id) }
            waitForForeground()
            shell("input keyevent 3")
            val partials = withTimeout(30_000) {
                var found = emptyList<File>()
                while (found.isEmpty()) {
                    assertTrue("Download must still be active", tasks.task(id)!!.stage.isRunning())
                    found = withContext(Dispatchers.IO) {
                        sources.walkTopDown().filter { it.isFile && it.path !in before && it.extension == "part" && it.length() > 1024 * 1024 }.toList()
                    }
                    if (found.isEmpty()) delay(50)
                }
                found
            }
            shell("cmd statusbar expand-notifications"); delay(600)
            val screenshot = File(context.getExternalFilesDir(null), "closeout-background.png")
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No background screenshot")
            try { screenshot.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            shell("cmd statusbar collapse"); shell("input keyevent 223")
            delay(1500)
            assertFalse(context.getSystemService(PowerManager::class.java).isInteractive)
            assertEquals(id, tasks.state.value.activeId)
            withContext(Dispatchers.Main) { tasks.cancel(id, asInterrupted = true) }
            val paused = settled(id)
            assertEquals(TaskStage.INTERRUPTED, paused.stage)
            assertEquals("download", paused.operation)
            assertEquals(setOf(video.id), paused.selectedIds)
            assertTrue(paused.detail.contains(context.getString(R.string.download_resume_scope)))
            assertTrue(paused.outputs.isEmpty())
            assertTrue(withContext(Dispatchers.IO) { partials.none(File::exists) })
            assertEquals(TaskStage.INTERRUPTED, TaskJson.decode(JSONObject(tasks.store.taskDocuments().first { it.first == id }.second), context.resources).stage)
            withTimeout(5000) {
                while (notices.activeNotifications.any { it.id == TaskService.NOTIFICATION_ID &&
                        it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }) delay(50)
            }
            withContext(Dispatchers.Main) { TaskService.start(context, id) }
            waitForForeground()
            val saved = settled(id)
            publications += saved.outputs.values.map(Uri::parse)
            assertEquals(saved.detail, TaskStage.DONE, saved.stage)
            assertFalse(context.getSystemService(PowerManager::class.java).isInteractive)
            val stored = tasks.store.verified(saved.postId, video.id)!!
            withContext(Dispatchers.IO) { tasks.store.open(stored).use { input -> readback.outputStream().use { input.copyTo(it) } } }
            MediaTools().validateVideo(readback)
            assertEquals(stored.sha1, MediaTools.measure(readback, "mp4").sha1)
            val decoder = MediaMetadataRetriever()
            val duration: Long
            try {
                decoder.setDataSource(readback.path)
                duration = decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue(duration > 1_400_000)
                val frame = decoder.getFrameAtTime((duration - 10_000) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                assertNotNull(frame); frame?.recycle()
            } finally { decoder.release() }
            Log.i(TAG, "closeout=true background_service=true screen_off=true paused_partial_clean=true selection_retained=true continued=true duration_ms=$duration bytes=${stored.bytes} hash_readback=true end_frame=true")
        } finally {
            withContext(NonCancellable) {
                id?.let { taskId ->
                    withContext(Dispatchers.Main) { tasks.cancel(taskId, asInterrupted = true) }
                    if (tasks.state.value.activeId == taskId) settled(taskId)
                    tasks.task(taskId)?.outputs?.values?.map(Uri::parse)?.let { publications += it }
                    publications.forEach { context.contentResolver.delete(it, null, null) }
                    tasks.delete(taskId)
                }
                notices.cancel(TaskService.NOTIFICATION_ID)
                readback.delete()
                shell("input keyevent 224"); shell("wm dismiss-keyguard")
            }
        }
    }

    @Test fun realCollectionsAndWholeMemberEpisode(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("bilibili_library") == "true")
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "bili-library-${UUID.randomUUID()}")
        val database = "bili-library-${UUID.randomUUID()}.db"
        val tasks = TaskCoordinator(context, database, root)
        val publications = mutableSetOf<Uri>()
        val ids = mutableListOf<Long>()
        val anonymousJs = JsEngine(context)
        try {
            tasks.initialize()
            val adapter = tasks.bilibili!!
            val account = adapter.workAccount()
            val tag = tasks.accountWorksSessionTag("bilibili")!!
            assertNotEquals(tasks.commentCacheTag(), tag)
            val folders = adapter.folders(account.secUid)
            val candidates = mutableListOf<AccountWork>()
            Log.i(TAG, "folders=${folders.size}")
            for (kind in WorkCollectionKind.entries) {
                val folder = if (kind == WorkCollectionKind.SAVED) folders.firstOrNull()?.first ?: continue else ""
                var state = AccountWorksCollection(kind, tag, account, cursor = "1", folderId = folder)
                repeat(2) {
                    if (!state.completed && !state.stopped) {
                        val page = adapter.accountWorks(kind, account.secUid, state.cursor, folder,
                            if (kind == WorkCollectionKind.SAVED) 2 else 20)
                        state = state.append(page, 200)
                        state.works.firstOrNull()?.let { state = state.select(it.ref.id, true) }
                    }
                }
                assertTrue(state.works.all { it.ref.platform == "bilibili" && it.ref.extra["bvid"]!!.isNotBlank() && !it.ref.extra.containsKey("cid") })
                assertEquals(state.works.map { it.ref }, AccountWorksCollection.decode(state.encode()).works.map { it.ref })
                Log.i(TAG, "kind=$kind pages=${state.cursors.size} works=${state.works.size} complete=${state.completed} stopped=${state.stopped} selected=${state.selected.size} unavailable=${state.unavailable}")
                candidates += state.works
                if (state.works.isNotEmpty()) {
                    val ref = adapter.resolveWork(state.works.first().ref)
                    assertEquals(state.works.first().ref.id, ref.id)
                    assertTrue(adapter.fetchAssets(ref, QualityPrefs()).any { it.kind == AssetKind.VIDEO })
                    val draft = tasks.addAccountWorkDownload(state, OutputAction.SHARE)
                    ids += draft
                    assertEquals("bilibili", tasks.taskPlatform(tasks.task(draft)!!))
                    assertEquals(state.works.first().ref, tasks.loadAccountWorks(tasks.task(draft)!!).single().ref)
                }
            }
            if (InstrumentationRegistry.getArguments().getString("collections_only") == "true") {
                val work = candidates.filter { (it.durationMs ?: 0) > 0 }.minByOrNull { it.durationMs!! }!!
                val collection = AccountWorksCollection(WorkCollectionKind.LIKED, tag, account, works = listOf(work), selected = setOf(work.ref.id))
                val batch = tasks.addAccountWorkDownload(collection, OutputAction.SAVE)
                ids += batch; tasks.run(batch)
                var saved = tasks.task(batch)!!
                publications += saved.outputs.values.map(Uri::parse)
                assertEquals(saved.detail, TaskStage.DONE, saved.stage)
                assertEquals(1, saved.outputs.size)
                val original = saved.outputs.toMap()
                tasks.run(batch); saved = tasks.task(batch)!!
                publications += saved.outputs.values.map(Uri::parse)
                assertEquals(original, saved.outputs)
                Log.i(TAG, "collection_batch_saved=1 source_duration_ms=${work.durationMs} uri_reuse=true")
                return@runBlocking
            }
            val ref = adapter.resolve("https://b23.tv/ep718242")
            val post = adapter.fetchPost(ref)
            assertTrue(post.parts.size > 1)
            assertEquals(ref, adapter.selectPart(adapter.resolve("ep508404"), ref.extra.getValue("cid")))
            assertEquals(ref.extra["season"], adapter.resolve("ss${ref.extra.getValue("season")}").extra["season"])
            val bundle = tasks.manager.start()
            anonymousJs.load(bundle.text("sign/bilibili.js"), adapter.config)
            val anonymous = BilibiliAdapter(adapter.config, JsonMapper(JSONObject(bundle.text("parsers/bilibili.json"))), anonymousJs, HttpTransport())
            try { anonymous.fetchAssets(ref, QualityPrefs()); fail("Preview must not be offered as full episode") }
            catch (error: AppFailure) { assertEquals(ErrorCode.NEED_LOGIN, error.code) }
            val id = tasks.add(UiTask(System.currentTimeMillis(), "https://b23.tv/ep718242", DownloadMode.BILIBILI, R.string.format_original, ""))
            ids += id; tasks.run(id)
            val selecting = tasks.task(id)!!
            assertEquals(selecting.detail, TaskStage.SELECTING, selecting.stage)
            val media = tasks.loadPost(selecting)!!
            val video = media.assets.last { it.kind == AssetKind.VIDEO }
            tasks.select(id, listOf(video)); tasks.run(id)
            var saved = tasks.task(id)!!
            publications += saved.outputs.values.map(Uri::parse)
            assertEquals(saved.detail, TaskStage.DONE, saved.stage)
            val stored = tasks.store.verified(saved.postId, video.id)!!
            val readback = File(root, "readback.mp4")
            tasks.store.open(stored).use { input -> readback.outputStream().use { input.copyTo(it) } }
            MediaTools().validateVideo(readback)
            assertEquals(stored.sha1, MediaTools.measure(readback, "mp4").sha1)
            val decoder = MediaMetadataRetriever()
            var duration = 0L
            try {
                decoder.setDataSource(readback.path)
                duration = decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue(duration > 1_400_000L)
                val frame = decoder.getFrameAtTime((duration - 10_000) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                assertNotNull(frame); frame?.recycle()
            } finally { decoder.release() }
            val original = saved.outputs.toMap()
            tasks.select(id, listOf(video)); tasks.run(id); saved = tasks.task(id)!!
            publications += saved.outputs.values.map(Uri::parse)
            assertEquals(original, saved.outputs)
            tasks.select(id, listOf(video), OutputAction.SHARE); tasks.run(id)
            val share = tasks.shareIntent(tasks.task(id)!!)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            Log.i(TAG, "member_episode=718242 height=${video.height} full_duration_ms=$duration bytes=${stored.bytes} saved=1 uri_reuse=true share_grant=true end_frame=true")
        } finally {
            publications.forEach { context.contentResolver.delete(it, null, null) }
            ids.forEach { tasks.delete(it) }
            withContext(Dispatchers.Main) { anonymousJs.close(); tasks.bilibili?.close(); tasks.js.close() }
            tasks.store.close(); context.deleteDatabase(database); root.deleteRecursively()
        }
    }
    companion object { private const val TAG = "BilibiliLibrary" }
}
