package com.emojetools.app

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.*
import com.emojetools.app.core.download.CommentDownloader
import com.emojetools.app.core.download.MediaTools
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.store.AssetStore
import com.emojetools.app.platform.douyin.CommentFetcher
import com.emojetools.app.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LiveCommentMediaDeviceTest {
    @Test fun picturesAndMotionHaveSeparateChoices(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val coordinator = (context.applicationContext as TaskApplication).tasks
        coordinator.initialize()
        val row = JSONObject(instrumentation.context.assets.open("rich-media.json").bufferedReader().use { it.readText() })
        val page = JSONObject().put("comments", JSONArray().put(row)).put("cursor", 50).put("has_more", 0).put("total", 1)
        val result = CommentFetcher(coordinator.config, coordinator.mapper, { _, _ -> page }, {}, {})
            .fetch(PostRef("douyin", "123"), 0)
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/.ui.MainActivity").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: android.app.Dialog
            var selected = emptyList<Asset>()
            scenario.onActivity { activity ->
                val task = UiTask(1, "fixture", DownloadMode.COMMENTS, R.string.format_original, "Fixture")
                dialog = CommentSelection(activity, task, result, { _, _ -> }) { assets, action ->
                    assertEquals(OutputAction.SAVE, action); selected = assets
                }.show()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                fun flatten(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                    (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
                val checks = flatten(dialog.window!!.decorView).filterIsInstance<CheckBox>()
                assertEquals(3, checks.size)
                assertEquals(2, checks.count { it.text == activity.getString(R.string.original_image) })
                checks.single { it.text == activity.getString(R.string.live_photo_motion) }.isChecked = true
                flatten(dialog.window!!.decorView).filterIsInstance<Button>()
                    .single { it.text == activity.getString(R.string.save_selected, 1) }.performClick()
            }
            assertEquals(listOf(AssetKind.VIDEO), selected.map { it.kind })
        }
    }

    @Test fun oldRuleCheckpointRequiresANewSearchWithoutChangingSavedProgress(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = (context.applicationContext as TaskApplication).tasks
        coordinator.initialize()
        val id = coordinator.add(UiTask(System.currentTimeMillis(), "fixture", DownloadMode.COMMENTS,
            R.string.format_original, "Fixture", postId = "1000000000000000982", sessionTag = "old-rules"))
        val file = coordinator.checkpointFile(coordinator.task(id)!!)
        file.parentFile!!.mkdirs()
        val previous = "fixture checkpoint retained"
        file.writeText(previous)
        try {
            coordinator.run(id)
            assertEquals(TaskStage.FAILED, coordinator.task(id)!!.stage)
            assertEquals(ErrorCode.SCHEMA.name, coordinator.task(id)!!.errorCode)
            assertEquals(previous, file.readText())
        } finally { file.parentFile!!.deleteRecursively() }
    }

    @Test fun originalPicturesAndMotionSaveSeparatelyAndResumeWithoutRedownloading(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val coordinator = (context.applicationContext as TaskApplication).tasks
        coordinator.initialize()
        val root = File(context.cacheDir, "live-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val database = "live-test-${UUID.randomUUID()}.db"
        val store = AssetStore(context, database, root)
        try {
            val row = JSONObject(instrumentation.context.assets.open("rich-media.json").bufferedReader().use { it.readText() })
            val page = JSONObject().put("comments", JSONArray().put(row)).put("cursor", 50).put("has_more", 0).put("total", 1)
            val manifest = File(root, "manifest.json")
            val result = CommentFetcher(coordinator.config, coordinator.mapper, { _, _ -> page }, { FetchLedger.write(manifest, it) }, {})
                .fetch(PostRef("douyin", "123"), 0, manifest.absolutePath)
            assertEquals(3, result.assets.size)
            var downloads = 0
            val saver = CommentDownloader(store) { asset, directory, stem ->
                downloads++; check(directory.isDirectory || directory.mkdirs())
                val motion = asset.kind == AssetKind.VIDEO
                val extension = if (motion) "mp4" else "png"
                val file = File(directory, "$stem.$extension")
                instrumentation.context.assets.open(if (motion) "media/source.mp4" else "media/image.png").use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                MediaTools.measure(file, extension)
            }
            assertEquals(3, saver.save("123", result.assets, result) { _, _ -> }.saved)
            assertEquals(3, downloads)
            val saved = store.all()
            assertEquals(3, saved.map { it.file.name }.toSet().size)
            assertEquals("mp4", saved.single { it.kind == AssetKind.VIDEO }.file.extension)
            assertTrue(saved.all { it.sha1.length == 40 && it.bytes > 0 && it.bytes == it.file.length() })
            assertEquals(3, saver.save("123", result.assets, result) { _, _ -> }.saved)
            assertEquals(3, downloads)
            FetchLedger.restore(JSONObject(manifest.readText()))
        } finally { store.close(); context.deleteDatabase(database); root.deleteRecursively() }
    }
}
