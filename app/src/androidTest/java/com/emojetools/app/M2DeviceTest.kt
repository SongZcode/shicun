package com.emojetools.app

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.*
import com.emojetools.app.core.bundle.CanonicalJson
import com.emojetools.app.core.download.*
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.net.JsonMapper
import com.emojetools.app.core.store.AssetStore
import com.emojetools.app.core.store.StoredAsset
import com.emojetools.app.platform.douyin.PostParser
import com.emojetools.app.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class M2DeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/.ui.MainActivity")).use { it.readBytes() }
    }

    private fun media(name: String): PostMedia {
        val files = mutableMapOf<String, String>()
        val manifest = JSONObject(context.assets.open("bundle/manifest.json").bufferedReader().use { it.readText() })
        ZipInputStream(context.assets.open("bundle/bundle_v${manifest.getInt("bundle_version")}.zip")).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        val json = JSONObject(instrumentation.context.assets.open("$name.json").bufferedReader().use { it.readText() })
        return PostParser(JsonMapper(JSONObject(files.getValue("parsers/douyin.json"))), JSONObject(files.getValue("platforms/douyin.json")))
            .parse(PostRef("douyin", if (name == "video") "1000000000000000001" else "1000000000000000002"), json)
    }
    private inner class Space : AutoCloseable {
        val root = File(context.cacheDir, "m2-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        private val database = "m2-test-${UUID.randomUUID()}.db"
        val store = AssetStore(context, database, File(root, "materials"))
        val manifest = File(root, "manifest.json")
        var requests = 0
        fun source(name: String): File = File(root, "input-$name").also { file ->
            if (!file.isFile) instrumentation.context.assets.open("media/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        fun copy(asset: Asset, destination: File, stem: String, forced: String? = null): DownloadedFile {
            requests++
            val source = source(forced ?: when {
                asset.meta["role"] == "background_music" -> "music.mp3"
                asset.kind == AssetKind.VIDEO -> "source.mp4"
                else -> "image.png"
            })
            check(destination.isDirectory || destination.mkdirs())
            val target = File(destination, "$stem.${source.extension}")
            source.copyTo(target, overwrite = true)
            return MediaTools.measure(target, source.extension)
        }
        fun pipeline(refresh: suspend () -> List<Asset> = { error("Unexpected refresh") },
            download: suspend (Asset, File, String) -> DownloadedFile = { asset, destination, stem -> copy(asset, destination, stem) }) =
            PostDownloader(store, File(root, "staging"), MediaTools(), download, refresh, 1)
        override fun close() { store.close(); context.deleteDatabase(database); root.deleteRecursively() }
    }

    @Test fun completeAudioCopiesEveryPacketAndBgmRemainsAnIndependentFile(): Unit = runBlocking {
        Space().use { space ->
            val media = media("video")
            val video = media.assets.first { it.kind == AssetKind.VIDEO }
            val audio = media.assets.first { it.meta["derivedFrom"] == video.id }
            val music = media.assets.first { it.meta["role"] == "background_music" }
            val chosen = listOf(video, audio, music)
            val summary = space.pipeline().save(media, chosen, space.manifest)
            assertEquals(3, summary.saved); assertEquals(0, summary.failed); assertEquals(2, space.requests)
            val savedAudio = space.store.verified(media.post.ref.id, audio.id)!!
            val original = audioSamples(space.source("source.mp4"))
            val exported = audioSamples(savedAudio.file)
            assertEquals(original.sha1, exported.sha1)
            assertEquals(original.packets, exported.packets)
            // MP4 and M4A track timescales can round the same sample timestamp by one microsecond.
            assertTrue(kotlin.math.abs(original.lastTime - exported.lastTime) <= 1)
            assertEquals(MediaTracks(video = false, audio = true), MediaTools().tracks(savedAudio.file))
            val savedMusic = space.store.verified(media.post.ref.id, music.id)!!
            assertEquals("mp3", savedMusic.file.extension)
            assertTrue(audioSamples(savedMusic.file).lastTime > original.lastTime)
            assertNotEquals(savedMusic.sha1, savedAudio.sha1)
            val again = space.pipeline().save(media, chosen, space.manifest)
            assertEquals(3, again.saved); assertEquals(2, space.requests)
            val audit = JSONObject(space.manifest.readText())
            val checksum = audit.getString("checksum")
            audit.remove("checksum"); audit.remove("checksum_algo")
            assertEquals(checksum, CanonicalJson.sha256(CanonicalJson.encode(audit).toByteArray(Charsets.UTF_8)))
            FetchLedger.write(File(context.filesDir, "m2_manifest_fixture.json"), JSONObject(space.manifest.readText()))
            Log.i("EmojeM2Test", "fixture=true api_requested=false saved=3 source_downloads=2 audio_packets=${original.packets} resumed_without_download=true")
        }
    }

    @Test fun audioOnlyKeepsTheSourceVideoOutOfTheLibraryAndCleansItsCache(): Unit = runBlocking {
        Space().use { space ->
            val media = media("video")
            val audio = media.assets.first { it.meta["role"] == "complete_audio" }
            val summary = space.pipeline().save(media, listOf(audio), space.manifest)
            assertEquals(1, summary.saved); assertEquals(1, space.requests)
            assertEquals(listOf(AssetKind.AUDIO), space.store.all().map { it.kind })
            assertEquals(0, File(space.root, "staging").listFiles()!!.size)
        }
        Space().use { space ->
            val media = media("video")
            val music = media.assets.single { it.meta["role"] == "background_music" }
            val summary = space.pipeline().save(media, listOf(music), space.manifest)
            assertEquals(1, summary.saved); assertEquals(1, space.requests)
            assertEquals(listOf(music.id), space.store.all().map { it.assetId })
            assertEquals("mp3", space.store.all().single().file.extension)
        }
    }

    @Test fun silentVideoAndVideoDisguisedAsMusicAreFailures(): Unit = runBlocking {
        Space().use { space ->
            val media = media("video")
            val selected = listOf(media.assets.first { it.kind == AssetKind.VIDEO }, media.assets.first { it.meta["role"] == "background_music" })
            val summary = space.pipeline(download = { asset, directory, stem ->
                space.copy(asset, directory, stem, if (asset.kind == AssetKind.VIDEO) "silent.mp4" else "source.mp4")
            }).save(media, selected, space.manifest)
            assertEquals(0, summary.saved); assertEquals(2, summary.failed)
            assertTrue(space.store.all().isEmpty())
            assertEquals("error", JSONObject(space.manifest.readText()).getString("termination"))
        }
    }

    @Test fun expiredUrlsRefreshOnceAndARepeatedFailureStops(): Unit = runBlocking {
        Space().use { space ->
            val media = media("video")
            val video = media.assets.first { it.kind == AssetKind.VIDEO }
            var refreshes = 0; var attempts = 0
            val summary = space.pipeline(refresh = { refreshes++; media.assets }, download = { asset, directory, stem ->
                if (attempts++ == 0) throw AppFailure(ErrorCode.NETWORK, "expired")
                space.copy(asset, directory, stem)
            }).save(media, listOf(video), space.manifest)
            assertEquals(1, summary.saved); assertEquals(1, refreshes); assertEquals(2, attempts)
        }
        Space().use { space ->
            val media = media("video")
            var attempts = 0; var refreshes = 0
            val summary = space.pipeline(refresh = { refreshes++; media.assets }, download = { _, _, _ ->
                attempts++; throw AppFailure(ErrorCode.NETWORK, "unavailable")
            }).save(media, listOf(media.assets.first { it.kind == AssetKind.VIDEO }), space.manifest)
            assertEquals(1, summary.failed); assertEquals(2, attempts); assertEquals(1, refreshes)
        }
    }

    @Test fun cancellationFinalizesTheWorkManifestAndRemovesStagingFiles(): Unit = runBlocking {
        Space().use { space ->
            val media = media("video")
            try {
                space.pipeline(download = { _, _, _ -> throw CancellationException("fixture cancellation") })
                    .save(media, listOf(media.assets.first { it.kind == AssetKind.VIDEO }), space.manifest)
                fail("Must cancel")
            } catch (_: CancellationException) { }
            assertEquals("cancelled", JSONObject(space.manifest.readText()).getString("termination"))
            assertTrue(space.store.all().isEmpty())
            assertEquals(0, File(space.root, "staging").listFiles()!!.size)
        }
    }

    @Test fun workSelectionSeparatesCompleteSoundAndMusicAndRecognizesImages() {
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/.ui.MainActivity").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        val video = media("video")
        val images = media("images")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: Dialog
            var selected = emptyList<Asset>()
            scenario.onActivity { activity ->
                dialog = PostSelection(activity, UiTask(1, "fixture", DownloadMode.POST, R.string.format_original, ""), video,
                    { view, _ -> view.setImageBitmap(BitmapFactory.decodeStream(instrumentation.context.assets.open("media/image.png"))) }, onConfirm = { assets, _ -> selected = assets }).show()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views = flatten(dialog.window!!.decorView)
                views.filterIsInstance<RadioButton>().single { it.text == activity.getString(R.string.complete_soundtrack) }.performClick()
                views.filterIsInstance<CheckBox>().single { it.text == activity.getString(R.string.background_music) }.isChecked = true
                views.filterIsInstance<ScrollView>().single().fullScroll(View.FOCUS_DOWN)
            }
            screenshot("m2-video-options-fixture.png")
            scenario.onActivity {
                saveButton(dialog).performClick()
                assertEquals(2, selected.size)
                assertTrue(selected.any { it.meta["role"] == "complete_audio" })
                assertTrue(selected.any { it.meta["role"] == "background_music" })
                assertTrue(selected.all { it.kind == AssetKind.AUDIO })
                dialog = PostSelection(it, UiTask(3, "fixture", DownloadMode.POST, R.string.format_original, ""), video,
                    { view, _ -> view.setImageBitmap(BitmapFactory.decodeStream(instrumentation.context.assets.open("media/image.png"))) }, onConfirm = { assets, _ -> selected = assets }).show()
                flatten(dialog.window!!.decorView).filterIsInstance<RadioButton>()
                    .single { radio -> radio.text == it.getString(R.string.background_music_only) }.performClick()
                assertFalse(flatten(dialog.window!!.decorView).filterIsInstance<Spinner>().single().isShown)
            }
            screenshot("m2-bgm-only-fixture.png")
            scenario.onActivity {
                saveButton(dialog).performClick()
                assertEquals(1, selected.size)
                assertEquals("background_music", selected.single().meta["role"])
                dialog = PostSelection(it, UiTask(2, "fixture", DownloadMode.POST, R.string.format_original, ""), images,
                    { view, _ -> view.setImageBitmap(BitmapFactory.decodeStream(instrumentation.context.assets.open("media/image.png"))) }, onConfirm = { assets, _ -> selected = assets }).show()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(flatten(dialog.window!!.decorView).filterIsInstance<RadioButton>().isEmpty())
                flatten(dialog.window!!.decorView).filterIsInstance<CheckBox>()
                    .single { it.text == activity.getString(R.string.original_image_number, 1) }.isChecked = false
            }
            screenshot("m2-image-options-fixture.png")
            scenario.onActivity { saveButton(dialog).performClick() }
            assertEquals(3, selected.size); assertTrue(selected.all { it.kind == AssetKind.IMAGE })
        }
    }

    @Test fun savedAudioCanPlayPauseAndSeekLocally(): Unit = runBlocking {
        Space().use { space ->
            val file = MediaTools().extractCompleteAudio(space.source("source.mp4"), File(space.root, "preview.m4a"))
            val asset = StoredAsset("fixture", "fixture", "fixture", AssetKind.AUDIO, file.file, file.sha1, file.bytes)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var dialog: Dialog
                scenario.onActivity { dialog = AssetViewer(it, asset, { _, _ -> }, {}, {}).show() }
                var prepared = false
                val deadline = SystemClock.uptimeMillis() + 5000
                while (!prepared && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity -> prepared = flatten(dialog.window!!.decorView).any { it.contentDescription == activity.getString(R.string.play_audio) && it.isEnabled } }
                    if (!prepared) SystemClock.sleep(50)
                }
                assertTrue(prepared)
                scenario.onActivity { activity ->
                    val play = flatten(dialog.window!!.decorView).single { it.contentDescription == activity.getString(R.string.play_audio) }
                    play.performClick()
                    assertEquals(activity.getString(R.string.pause_audio), play.contentDescription)
                    play.performClick()
                    val seek = flatten(dialog.window!!.decorView).filterIsInstance<SeekBar>().single()
                    assertTrue(seek.isEnabled); assertTrue(seek.max >= 2900)
                    val argument = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1500f) }
                    assertTrue(seek.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, argument))
                }
                var sought = false
                val seekDeadline = SystemClock.uptimeMillis() + 3000
                while (!sought && SystemClock.uptimeMillis() < seekDeadline) {
                    scenario.onActivity { sought = flatten(dialog.window!!.decorView).filterIsInstance<SeekBar>().single().progress in 1000..2000 }
                    if (!sought) SystemClock.sleep(100)
                }
                assertTrue(sought)
                screenshot("m2-audio-player-fixture.png")
                scenario.onActivity { dialog.dismiss() }
            }
        }
    }

    private data class Samples(val sha1: String, val packets: Int, val lastTime: Long)
    private fun audioSamples(file: File): Samples {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack((0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") })
            val digest = MessageDigest.getInstance("SHA-1")
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val first = extractor.sampleTime
            var packets = 0; var last = 0L
            while (true) {
                buffer.clear()
                val n = extractor.readSampleData(buffer, 0)
                if (n < 0) break
                buffer.position(0); buffer.limit(n); digest.update(buffer)
                last = extractor.sampleTime - first; packets++
                if (!extractor.advance()) break
            }
            return Samples(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, packets, last)
        } finally { extractor.release() }
    }
    private fun flatten(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
    private fun saveButton(dialog: Dialog) = flatten(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text == context.getString(R.string.save_selected) }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(300, 5000)
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
