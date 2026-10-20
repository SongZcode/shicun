package com.emojetools.app

import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.*
import com.emojetools.app.core.download.MediaTools
import com.emojetools.app.core.store.AssetStore
import com.emojetools.app.core.task.TaskCoordinator
import com.emojetools.app.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BilibiliDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun muxPreservesCompressedSamplesAndPlatformStorage(): Unit = runBlocking {
        val root = File(context.cacheDir, "bili-mux-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val database = "bili-mux-${UUID.randomUUID()}.db"
        val store = AssetStore(context, database, root)
        try {
            fun source(name: String) = File(root, name).also { file ->
                instrumentation.context.assets.open("media/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            val video = source("silent.mp4")
            val original = source("source.mp4")
            val tools = MediaTools()
            val audio = tools.extractCompleteAudio(original, File(root, "audio.m4a"))
            val merged = tools.muxTracks(video, audio.file, File(root, "merged.mp4"))
            tools.validateVideo(merged.file)
            assertEquals(samples(video, "video/"), samples(merged.file, "video/"))
            assertEquals(samples(audio.file, "audio/"), samples(merged.file, "audio/"))
            val ref = PostRef("bilibili", "123456789", mapOf("cid" to "987", "bvid" to "BV1nx411u79K"))
            val asset = Asset("video:987:16", AssetKind.VIDEO, "POST", emptyList(), "mp4", null, null, null)
            store.record(ref.storageId, asset, merged)
            assertNotNull(store.verified(ref.storageId, asset.id))
            assertNull(store.verified(ref.id, asset.id))
            assertTrue(store.directory(ref.storageId, "title").path.contains("bilibili"))
            assertEquals("title_12345678", store.directory("123456789", "title").name)
            assertNotEquals(store.previewDirectory(ref.storageId), store.previewDirectory(ref.id))
        } finally { store.close(); context.deleteDatabase(database); root.deleteRecursively() }
    }

    @Test fun publicVideoPartsDownloadSaveReuseAndShare(): Unit = runBlocking {
        assumeTrue("Explicit public Bilibili smoke opt-in required",
            InstrumentationRegistry.getArguments().getString("bilibili_live") == "true")
        val root = File(context.cacheDir, "bili-live-${UUID.randomUUID()}")
        val database = "bili-live-${UUID.randomUUID()}.db"
        val tasks = TaskCoordinator(context, database, root)
        val published = mutableSetOf<Uri>()
        val ids = mutableListOf<Long>()
        try {
            tasks.initialize()
            val adapter = tasks.bilibili!!
            val multipart = adapter.resolve("https://www.bilibili.com/video/BV1ms411Q7vw?p=4")
            val post = adapter.fetchPost(multipart)
            assertTrue(post.parts.size >= 4)
            assertEquals(post.parts[3].id, multipart.extra["cid"])
            assertEquals(multipart.id, adapter.resolve("av${multipart.id}").id)
            val id = tasks.add(UiTask(System.currentTimeMillis(), "BV1bK411W797", DownloadMode.BILIBILI, R.string.format_original, ""))
            ids += id; tasks.run(id)
            var task = tasks.task(id)!!
            assertEquals(task.detail, TaskStage.SELECTING, task.stage)
            val media = tasks.loadPost(task)!!
            assertTrue(media.post.title.isNotBlank()); assertTrue(media.post.coverUrl!!.startsWith("https://"))
            val video = media.assets.filter { it.kind == AssetKind.VIDEO }.last()
            assertEquals("dash_mux", video.meta["transform"])
            val selected = listOf(video, media.assets.first { it.meta["derivedFrom"] == video.id }, media.assets.first { it.kind == AssetKind.COVER })
            tasks.select(id, selected); tasks.run(id); task = tasks.task(id)!!
            published += task.outputs.values.map(Uri::parse)
            assertEquals(task.detail, TaskStage.DONE, task.stage)
            assertEquals(3, task.outputs.size)
            val stored = tasks.store.verified(task.postId, video.id)!!
            val readback = File(root, "readback.mp4")
            tasks.store.open(stored).use { input -> readback.outputStream().use { input.copyTo(it) } }
            MediaTools().validateVideo(readback)
            assertEquals(stored.sha1, MediaTools.measure(readback, "mp4").sha1)
            val first = task.outputs.toMap()
            tasks.select(id, selected); tasks.run(id); task = tasks.task(id)!!
            published += task.outputs.values.map(Uri::parse)
            assertEquals(first, task.outputs)
            tasks.select(id, selected, OutputAction.SHARE); tasks.run(id); task = tasks.task(id)!!
            assertEquals(TaskStage.DONE, task.stage)
            val share = tasks.shareIntent(task)
            assertEquals(Intent.ACTION_SEND_MULTIPLE, share.action)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val multiId = tasks.add(UiTask(System.currentTimeMillis(), "https://www.bilibili.com/video/BV1ms411Q7vw?p=4", DownloadMode.BILIBILI, R.string.format_original, ""))
            ids += multiId; tasks.run(multiId)
            val multi = tasks.task(multiId)!!
            assertEquals(multi.detail, TaskStage.SELECTING, multi.stage)
            tasks.selectBilibiliPart(multiId, post.parts[0].id)
            assertEquals(post.parts[0].id, tasks.loadPost(tasks.task(multiId)!!)!!.post.ref.extra["cid"])
            assertTrue(tasks.task(multiId)!!.selectedIds.isEmpty())
            Log.i("BilibiliAcceptance", "public_parts=${post.parts.size} variants=${media.assets.count { it.kind == AssetKind.VIDEO }} saved=3 video_audio_tracks=true hash_readback=true uri_reuse=true share_read_grant=true part_switch=true")
        } finally {
            published.forEach { context.contentResolver.delete(it, null, null) }
            ids.forEach { tasks.delete(it) }
            withContext(Dispatchers.Main) { tasks.bilibili?.close(); tasks.js.close() }
            tasks.store.close()
            context.deleteDatabase(database); root.deleteRecursively()
        }
    }

    private fun samples(file: File, prefix: String): String {
        val input = MediaExtractor()
        try {
            input.setDataSource(file.path)
            val track = (0 until input.trackCount).first { input.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(prefix) }
            input.selectTrack(track)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(1024 * 1024)
            var packets = 0
            while (true) {
                buffer.clear(); val count = input.readSampleData(buffer, 0); if (count < 0) break
                val bytes = ByteArray(count); buffer.position(0); buffer.get(bytes); digest.update(bytes); packets++
                if (!input.advance()) break
            }
            return "$packets:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        } finally { input.release() }
    }

    @Test fun authenticatedAccountGetsAvailableHighQuality(): Unit = runBlocking {
        assumeTrue("User-completed Bilibili login opt-in required",
            InstrumentationRegistry.getArguments().getString("bilibili_authenticated") == "true")
        val root = File(context.cacheDir, "bili-auth-${UUID.randomUUID()}")
        val database = "bili-auth-${UUID.randomUUID()}.db"
        val tasks = TaskCoordinator(context, database, root)
        val published = mutableSetOf<Uri>()
        var id: Long? = null
        try {
            tasks.initialize()
            assertEquals(LoginState.LOGGED_IN, tasks.bilibili!!.loginState())
            id = tasks.add(UiTask(System.currentTimeMillis(), "BV1bK411W797", DownloadMode.BILIBILI, R.string.format_original, ""))
            tasks.run(id)
            val task = tasks.task(id)!!
            assertEquals(task.detail, TaskStage.SELECTING, task.stage)
            val media = tasks.loadPost(task)!!
            val video = media.assets.first { it.kind == AssetKind.VIDEO }
            assertTrue("Acceptance sample must expose HD after login", (video.height ?: 0) >= 720)
            tasks.select(id, listOf(video)); tasks.run(id)
            val saved = tasks.task(id)!!
            published += saved.outputs.values.map(Uri::parse)
            assertEquals(saved.detail, TaskStage.DONE, saved.stage)
            val stored = tasks.store.verified(saved.postId, video.id)!!
            val readback = File(root, "readback.mp4")
            tasks.store.open(stored).use { input -> readback.outputStream().use { input.copyTo(it) } }
            MediaTools().validateVideo(readback)
            assertEquals(stored.sha1, MediaTools.measure(readback, "mp4").sha1)
            val decoder = android.media.MediaMetadataRetriever()
            try {
                decoder.setDataSource(readback.path)
                val frame = decoder.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                assertNotNull(frame); frame?.recycle()
            } finally { decoder.release() }
            Log.i("BilibiliAcceptance", "authenticated=true height=${video.height} quality=${video.meta["quality"]} saved=1 frame_decoded=true")
        } finally {
            published.forEach { context.contentResolver.delete(it, null, null) }
            id?.let { tasks.delete(it) }
            withContext(Dispatchers.Main) { tasks.bilibili?.close(); tasks.js.close() }
            tasks.store.close(); context.deleteDatabase(database); root.deleteRecursively()
        }
    }
}
