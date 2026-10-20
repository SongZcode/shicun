package com.emojetools.app

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.*
import com.emojetools.app.core.download.MediaTools
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.store.*
import com.emojetools.app.core.task.TaskService
import com.emojetools.app.platform.douyin.CommentFetcher
import com.emojetools.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CommentExperienceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun foreground() {
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/.ui.MainActivity").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
    private fun flatten(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
    private fun result(count: Int) = CommentResult((1..count).map { n ->
        Asset("asset-$n", AssetKind.STICKER, "COMMENT", listOf("https://example.invalid/$n.gif"), "gif", null, null, null)
    }, count.toLong(), null, count, 0, "server_end", (1..count).map { n ->
        CommentCandidate("comment-$n", null, "Matching comment $n", "Alice $n", listOf("user-$n"), listOf("asset-$n"))
    })

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    fun mediaOnlyGridOpensLargePreviewAndKeepsSelectionWhilePlayingMotion(): Unit = runBlocking {
        foreground()
        val root = File(context.cacheDir, "comment-preview-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val still = File(root, "image.png")
        val motion = File(root, "source.mp4")
        for (file in listOf(still, motion)) instrumentation.context.assets.open("media/${file.name}").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: Dialog
            lateinit var controller: CommentSelection
            val original = result(3)
            val media = original.copy(assets = original.assets.mapIndexed { index, asset ->
                asset.copy(kind = if (index == 2) AssetKind.VIDEO else AssetKind.IMAGE)
            }, comments = listOf(CommentCandidate("plain", null, "Matching text", "Text only", emptyList(), emptyList()),
                CommentCandidate("media", null, "Matching images", "Media author", emptyList(), original.assets.map { it.id })))
            fun previewWindow() = android.view.inspector.WindowInspector.getGlobalWindowViews().single { view ->
                flatten(view).filterIsInstance<TextView>().any { it.text.toString().startsWith("素材 ") }
            }
            scenario.onActivity { activity ->
                controller = CommentSelection(activity, UiTask(1, "fixture", DownloadMode.COMMENTS, R.string.format_original, "Matching"), media,
                    { image, asset -> image.tag = asset.id; image.setImageResource(R.drawable.ic_file_image) },
                    loadPreview = { asset -> if (asset.kind == AssetKind.VIDEO) motion else still }) { _, _ -> }
                dialog = controller.show()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val views = flatten(dialog.window!!.decorView)
                assertFalse(views.filterIsInstance<TextView>().any { it.text.toString().contains("Text only") })
                assertEquals(3, views.filterIsInstance<CheckBox>().size)
                val images = views.filterIsInstance<ImageView>().filter { it.tag in setOf("asset-1", "asset-2") }
                val locations = images.map { view -> IntArray(2).also { view.getLocationOnScreen(it) } }
                assertEquals(locations[0][1], locations[1][1]); assertTrue(locations[1][0] > locations[0][0])
                images.first().performClick()
            }
            withTimeout(5000) {
                while (true) {
                    var loaded = false
                    scenario.onActivity { loaded = flatten(previewWindow()).filterIsInstance<ImageView>()
                        .any { it.contentDescription?.startsWith("素材 ") == true && it.drawable != null } }
                    if (loaded) break else delay(50)
                }
            }
            lateinit var video: VideoView
            scenario.onActivity { activity ->
                val viewer = previewWindow()
                flatten(viewer).filterIsInstance<CheckBox>().single().isChecked = true
                assertEquals(setOf("asset-1"), controller.selectionIds())
                val next = flatten(viewer).single { it.contentDescription == activity.getString(R.string.favorites_next) }
                next.performClick(); next.performClick()
                video = flatten(viewer).filterIsInstance<VideoView>().single()
            }
            withTimeout(5000) {
                while (true) {
                    var playing = false
                    scenario.onActivity { playing = video.isPlaying }
                    if (playing) break else delay(50)
                }
            }
            scenario.onActivity { activity ->
                flatten(previewWindow()).single { it.contentDescription == activity.getString(R.string.close) }.performClick()
                assertFalse(video.isPlaying)
                assertTrue(flatten(dialog.window!!.decorView).filterIsInstance<CheckBox>().first().isChecked)
                dialog.dismiss()
            }
        } } finally { root.deleteRecursively() }
    }

    @Test fun ownCommentEntryNeedsNoTypedIdentifierAndTaskMetadataSurvivesRestoration() {
        foreground()
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val screen = MainScreen(activity, {}, {}, {}, {}, {}, {}, {}, { _, _ -> })
            activity.setContentView(screen.root); screen.setReady(true)
            screen.sourceInput.setText("1000000000000000001")
            screen.restoreSelection(DownloadMode.COMMENTS.name, R.string.target_mine, 0)
            assertFalse(screen.targetInput.isShown)
            assertTrue(flatten(screen.root).filterIsInstance<Button>().single { it.text == activity.getString(R.string.find_comments) }.isEnabled)
            screen.restoreSelection(DownloadMode.COMMENTS.name, R.string.target_author_name, 0)
            assertTrue(screen.targetInput.isShown)
            assertEquals(activity.getString(R.string.target_author_name_hint), screen.targetInput.hint)
            val task = UiTask(1, "fixture", DownloadMode.COMMENTS, R.string.format_original, "", targetKind = R.string.target_mine,
                accountIds = listOf("9007199254740993"), accountName = "Alice", sessionTag = "fixture-hash",
                commentsFetchedAt = 123, cacheUsed = true)
            assertEquals(task.copy(stage = TaskStage.INTERRUPTED), TaskJson.decode(TaskJson.encode(task, activity.resources), activity.resources))
        } }
    }

    @Test fun fuzzyResultsKeepGrowingAndKeepTheUsersSelectionUntilConfirmation() {
        foreground()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: Dialog
            lateinit var controller: CommentSelection
            var confirmations = 0
            var chosen = emptyList<Asset>()
            scenario.onActivity { activity ->
                val task = UiTask(1, "fixture", DownloadMode.COMMENTS, R.string.format_original, "Ali", targetKind = R.string.target_author_name)
                controller = CommentSelection(activity, task, result(1), { view, _ -> view.setImageResource(R.drawable.ic_file_image) },
                    onStop = {}, searching = true) { assets, action ->
                    assertEquals(OutputAction.SAVE, action); confirmations++; chosen = assets
                }
                dialog = controller.show()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                flatten(dialog.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = true
                controller.update(result(2), true)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views = flatten(dialog.window!!.decorView)
                val checks = views.filterIsInstance<CheckBox>()
                assertEquals(2, checks.size); assertTrue(checks.first().isChecked); assertFalse(checks.last().isChecked)
                assertEquals(0, confirmations)
                val save = views.filterIsInstance<Button>().single { it.text == activity.getString(R.string.stop_and_save_selected, 1) }
                assertTrue(save.isEnabled); save.performClick()
            }
            assertEquals(1, confirmations); assertEquals(listOf("asset-1"), chosen.map { it.id })
        }
    }

    @Test fun progressAndNewMatchesKeepExistingPreviewsAndSharedSelections() {
        foreground()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: Dialog
            lateinit var controller: CommentSelection
            lateinit var firstImage: ImageView
            val loaded = ColorDrawable(0xff167c68.toInt())
            val loads = mutableMapOf<String, Int>()
            var initialLoads = 0
            var chosen = emptyList<Asset>()
            fun images() = flatten(dialog.window!!.decorView).filterIsInstance<ImageView>().filter { it.tag == "asset-1" }
            scenario.onActivity { activity ->
                val task = UiTask(1, "fixture", DownloadMode.COMMENTS, R.string.format_original, "Ali", targetKind = R.string.target_author_name)
                controller = CommentSelection(activity, task, result(1), { image, asset ->
                    image.tag = asset.id
                    loads[asset.id] = (loads[asset.id] ?: 0) + 1
                    if (asset.id == "asset-1") image.setImageDrawable(loaded)
                }, searching = true) { assets, action -> assertEquals(OutputAction.SAVE, action); chosen = assets }
                dialog = controller.show()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                firstImage = images().single()
                initialLoads = loads.getValue("asset-1")
                flatten(dialog.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = true
            }
            repeat(4) { page ->
                scenario.onActivity { controller.update(result(1).copy(fetchedTopLevel = (page + 1) * 50), true) }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertSame(firstImage, images().single())
                    assertSame(loaded, firstImage.drawable)
                    assertEquals(initialLoads, loads.getValue("asset-1"))
                }
            }
            scenario.onActivity { controller.update(result(2), true) }
            instrumentation.waitForIdleSync()
            lateinit var pendingImage: ImageView
            var pendingLoads = 0
            scenario.onActivity {
                assertSame(firstImage, images().single())
                assertSame(loaded, firstImage.drawable)
                assertEquals(initialLoads, loads.getValue("asset-1"))
                pendingImage = flatten(dialog.window!!.decorView).filterIsInstance<ImageView>().single { it.tag == "asset-2" }
                pendingLoads = loads.getValue("asset-2")
                val checks = flatten(dialog.window!!.decorView).filterIsInstance<CheckBox>()
                assertTrue(checks.first().isChecked); assertFalse(checks.last().isChecked)
                controller.update(result(2).copy(fetchedReplies = 20), true)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertSame(pendingImage, flatten(dialog.window!!.decorView).filterIsInstance<ImageView>().single { it.tag == "asset-2" })
                assertEquals(pendingLoads, loads.getValue("asset-2"))
            }
            val shared = result(2).let { value -> value.copy(assets = value.assets.take(1),
                comments = value.comments.map { it.copy(assetIds = listOf("asset-1")) }) }
            scenario.onActivity { controller.update(shared, false) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertSame(firstImage, images().first())
                assertSame(loaded, firstImage.drawable)
                val checks = flatten(dialog.window!!.decorView).filterIsInstance<CheckBox>()
                assertEquals(2, checks.size); assertTrue(checks.all { it.isChecked })
                checks.last().isChecked = false
                assertTrue(checks.none { it.isChecked })
                checks.last().isChecked = true
                assertTrue(checks.all { it.isChecked })
                flatten(dialog.window!!.decorView).filterIsInstance<Button>()
                    .single { it.text == activity.getString(R.string.save_selected, 1) }.performClick()
            }
            assertEquals(listOf("asset-1"), chosen.map { it.id })
        }
    }

    @Test fun searchCanStopAndContinueWithoutResettingSelectionsAndHidesContinuationWhenComplete() {
        foreground()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: Dialog
            lateinit var controller: CommentSelection
            var stops = 0
            var resumedSelection = emptySet<String>()
            scenario.onActivity { activity ->
                val task = UiTask(1, "fixture", DownloadMode.COMMENTS, R.string.format_original, "Matching")
                controller = CommentSelection(activity, task, result(1), { _, _ -> },
                    onContinue = { resumedSelection = it }, onStop = { stops++ }, searching = true) { _, _ -> }
                dialog = controller.show()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views = flatten(dialog.window!!.decorView)
                views.filterIsInstance<CheckBox>().single().isChecked = true
                val stop = views.filterIsInstance<Button>().single { it.text == activity.getString(R.string.stop_comment_search) }
                stop.performClick(); assertEquals(1, stops)
                controller.updateSearchState(false, true)
                assertEquals(activity.getString(R.string.continue_comment_search), stop.text)
                assertTrue(stop.isShown); stop.performClick()
                assertEquals(setOf("asset-1"), resumedSelection)
                controller.updateSearchState(true, false)
                assertEquals(activity.getString(R.string.stop_comment_search), stop.text)
                controller.updateSearchState(false, false)
                assertFalse(stop.isShown)
                assertTrue(views.filterIsInstance<CheckBox>().single().isChecked)
                dialog.dismiss()
            }
        }
    }

    @Test fun audioGetsAnOfflineCoverWithoutAddingAnExtraMaterialOrRedownloadingAudio() = runBlocking<Unit> {
        val root = File(context.cacheDir, "cover-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val database = "cover-test-${UUID.randomUUID()}.db"
        var store = AssetStore(context, database, root)
        try {
            val audioFile = File(root, "audio.mp3").apply {
                instrumentation.context.assets.open("media/music.mp3").use { input -> outputStream().use { input.copyTo(it) } }
            }
            val audio = Asset("audio", AssetKind.AUDIO, "POST", emptyList(), "mp3", null, null, null)
            val cover = Asset("cover", AssetKind.COVER, "POST", listOf("https://example.invalid/cover.png"), null, null, null, null)
            val post = Post(PostRef("douyin", "123"), "Fixture", "Alice", "uid", null, null, null, emptyMap(), emptyList())
            store.record("123", audio, MediaTools.measure(audioFile, "mp3"))
            assertNull(store.all().single().previewFile)
            var requests = 0
            val covers = AudioCoverStore(store) { _, destination, stem ->
                requests++
                val file = File(destination, "$stem.png")
                instrumentation.context.assets.open("media/image.png").use { input -> file.outputStream().use { input.copyTo(it) } }
                MediaTools.measure(file, "png")
            }
            val media = PostMedia(post, listOf(audio, cover))
            val image = covers.ensure(media)!!
            assertEquals(image, covers.ensure(media)); assertEquals(1, requests)
            store.close(); store = AssetStore(context, database, root)
            val saved = store.all().single()
            assertEquals(AssetKind.AUDIO, saved.kind); assertEquals(image, saved.previewFile); assertTrue(saved.file.isFile)
            foreground()
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                var previewed: File? = null
                val dialog = AssetViewer(activity, saved, { _, file -> previewed = file }, {}, {}).show()
                assertEquals(image, previewed)
                assertTrue(flatten(dialog.window!!.decorView).any { it is ImageView && it !is ImageButton })
                dialog.dismiss()
            } }
        } finally { store.close(); context.deleteDatabase(database); root.deleteRecursively() }
    }

    @Test fun aSecondSearchUsesTheSameWorkCacheAndKeepsItsOriginalFetchTime() = runBlocking<Unit> {
        val coordinator = (context.applicationContext as TaskApplication).tasks
        coordinator.initialize()
        val tag = coordinator.commentLookupTag()
        val first = UiTask(System.currentTimeMillis(), "fixture-cache", DownloadMode.COMMENTS, R.string.format_original, "Matching",
            stage = TaskStage.SELECTING, postId = "1000000000000000981", sessionTag = tag, commentsFetchedAt = System.currentTimeMillis() - 1000)
        val firstId = coordinator.add(first)
        val source = coordinator.task(firstId)!!
        val checkpoint = coordinator.checkpointFile(source)
        val fixture = CommentFetcher(coordinator.config, coordinator.mapper, { _, _ ->
            JSONObject().put("cursor", 50).put("has_more", 0).put("total", 2).put("comments", JSONArray((1..2).map { n ->
                JSONObject().put("cid", "comment-$n").put("text", "Matching comment $n")
                    .put("user", JSONObject().put("nickname", "Alice $n").put("sec_uid", "user-$n"))
            }))
        }, { FetchLedger.write(checkpoint, it) }, {}).fetch(PostRef("douyin", first.postId), coordinator.config.getInt("limit_top"), checkpoint.absolutePath)
        FetchLedger.write(coordinator.resultFile(source), CommentResultJson.encode(fixture))
        val nextId = coordinator.add(UiTask(firstId + 1, "fixture-cache", DownloadMode.COMMENTS, R.string.format_original,
            "Alice", postId = first.postId, targetKind = R.string.target_author_name))
        try {
            coordinator.run(nextId)
            val cached = coordinator.task(nextId)!!
            assertEquals(TaskStage.SELECTING, cached.stage); assertTrue(cached.cacheUsed)
            assertEquals(first.commentsFetchedAt, cached.commentsFetchedAt)
            assertEquals(2, cached.matching(coordinator.loadResult(cached)!!).size)
            assertNotEquals(checkpoint.absolutePath, coordinator.loadResult(cached)!!.ledgerPath)
            FetchLedger.restore(JSONObject(coordinator.checkpointFile(cached).readText()))
        } finally {
            coordinator.resultFile(source).delete(); coordinator.resultFile(coordinator.task(nextId)!!).delete()
            checkpoint.parentFile!!.deleteRecursively(); coordinator.checkpointFile(coordinator.task(nextId)!!).parentFile!!.deleteRecursively()
        }
    }

    @Test fun stoppingAServiceTaskCanHandOffToSavingWithoutDroppingTheNextRun() = runBlocking {
        foreground()
        val coordinator = (context.applicationContext as TaskApplication).tasks
        coordinator.initialize(); ServiceFixture.reset()
        val id = coordinator.add(UiTask(System.currentTimeMillis(), "fixture://m3/selection-handoff", DownloadMode.POST, R.string.format_original, ""))
        TaskService.start(context, id)
        withTimeout(10000) { ServiceFixture.entered.await() }
        coordinator.stopForSelection(id)
        ServiceFixture.reset(); ServiceFixture.finish.complete(Unit)
        coordinator.select(id, listOf(Asset("fixture", AssetKind.IMAGE, "POST", emptyList(), null, null, null, null)))
        TaskService.start(context, id)
        withTimeout(10000) { coordinator.state.first { state -> state.tasks.any { it.id == id && it.stage == TaskStage.DONE } && state.activeId == null } }
        assertEquals(1, ServiceFixture.calls)
    }
}
