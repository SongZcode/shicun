package com.emojetools.app

import android.app.Dialog
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.*
import com.emojetools.app.core.download.CommentDownloader
import com.emojetools.app.core.download.MediaTools
import com.emojetools.app.core.store.*
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.task.TaskCoordinator
import com.emojetools.app.platform.douyin.CommentFetcher
import com.emojetools.app.ui.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaskHistoryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun flatten(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
    private fun foreground() {
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/.ui.MainActivity").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
    private fun fixture(id: Long, post: String = "1000000000000000971", query: String = "first") =
        UiTask(id, "fixture", DownloadMode.COMMENTS, R.string.format_original, query, postId = post)
    private fun comments() = CommentResult(listOf(
        Asset("first", AssetKind.IMAGE, "COMMENT", emptyList(), "png", null, null, null),
        Asset("second", AssetKind.IMAGE, "COMMENT", emptyList(), "png", null, null, null)),
        2, null, 2, 0, "server_end", listOf(
            CommentCandidate("cid-first", null, "first content", "Alice", listOf("alice"), listOf("first")),
            CommentCandidate("cid-second", null, "second content", "Bob", listOf("bob"), listOf("second"))))
    private fun source(store: AssetStore, post: String, id: String): StoredAsset {
        val file = File(store.root, "$id.png").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open("media/image.png").use { input -> file.outputStream().use { input.copyTo(it) } }
        return store.record(post, Asset(id, AssetKind.IMAGE, "COMMENT", emptyList(), "png", null, null, null), MediaTools.measure(file, "png"))
    }
    private suspend fun close(coordinator: TaskCoordinator, database: String, root: File) {
        coordinator.state.value.tasks.forEach { coordinator.delete(it.id) }
        withContext(Dispatchers.Main) { coordinator.js.close() }
        coordinator.store.close(); context.deleteDatabase(database); root.deleteRecursively()
    }

    @Test fun startupAndNewTasksRetainOnlyTheLatestTwentyAndPreservePublishedMedia(): Unit = runBlocking {
        val token = UUID.randomUUID().toString()
        val database = "history-$token.db"
        val root = File(context.cacheDir, "history-$token")
        val base = System.currentTimeMillis()
        val seed = AssetStore(context, database, root)
        val saved = source(seed, "1000000000000000971", "first")
        val publisher = MediaPublisher(context, seed)
        val uri = publisher.save(saved)
        val originalTasks = (0L..40L).map { fixture(base + it) }
        originalTasks.forEach { task ->
            seed.saveTask(task.id, TaskJson.encode(task, context.resources))
            val file = File(context.filesDir, "comment_results/${task.id}.json")
            FetchLedger.write(file, CommentResultJson.encode(comments()))
        }
        seed.close()
        val coordinator = TaskCoordinator(context, database, root)
        try {
            coordinator.initialize()
            assertEquals((21L..40L).map { base + it }.reversed(), coordinator.state.value.tasks.map { it.id })
            assertEquals(20, coordinator.store.tasks().size)
            assertTrue(originalTasks.take(21).none { coordinator.resultFile(it).exists() })
            assertEquals(uri, coordinator.store.verified(saved.postId, saved.assetId)!!.uri)
            val added = coordinator.add(fixture(base + 41))
            assertEquals(20, coordinator.state.value.tasks.size)
            assertNull(coordinator.task(base + 21)); assertNotNull(coordinator.task(added))
            val remove = coordinator.task(base + 40)!!
            coordinator.delete(remove.id)
            assertNull(coordinator.task(remove.id)); assertFalse(coordinator.resultFile(remove).exists())
            assertEquals(19, coordinator.store.tasks().size)
            assertEquals(uri, coordinator.store.verified(saved.postId, saved.assetId)!!.uri)
        } finally {
            context.contentResolver.delete(uri, null, null)
            close(coordinator, database, root)
            originalTasks.forEach { File(context.filesDir, "comment_results/${it.id}.json").delete() }
        }
    }

    @Test fun obsoleteAndMalformedTaskRecordsDoNotBlockCurrentTaskRecovery(): Unit = runBlocking {
        val token = UUID.randomUUID().toString()
        val database = "history-$token.db"
        val root = File(context.cacheDir, "history-$token")
        val current = fixture(System.currentTimeMillis()).copy(stage = TaskStage.FETCHING)
        AssetStore(context, database, root).use { seed ->
            seed.saveTask(current.id, TaskJson.encode(current, context.resources))
            seed.saveTask(current.id + 1, TaskJson.encode(current.copy(id = current.id + 1), context.resources).put("mode", "VIDEO"))
            seed.saveTask(current.id + 2, TaskJson.encode(current.copy(id = current.id + 2), context.resources).apply { remove("comment_order") })
            seed.writableDatabase.execSQL("INSERT INTO tasks VALUES (?, ?)", arrayOf(current.id + 3, "{invalid"))
        }
        val coordinator = TaskCoordinator(context, database, root)
        try {
            coordinator.initialize()
            assertTrue(coordinator.state.value.ready)
            assertEquals(current.id, coordinator.state.value.tasks.single().id)
            assertEquals(TaskStage.INTERRUPTED, coordinator.state.value.tasks.single().stage)
            assertEquals(current.id, coordinator.store.tasks().single().getLong("id"))
        } finally { close(coordinator, database, root) }
    }

    @Test fun deletingAnExecutingTaskWaitsForItsFinalCheckpointAndDoesNotResurrectIt(): Unit = runBlocking {
        val token = UUID.randomUUID().toString()
        val database = "history-$token.db"
        val root = File(context.cacheDir, "history-$token")
        val entered = CompletableDeferred<Unit>()
        val coordinator = object : TaskCoordinator(context, database, root) {
            override suspend fun execute(task: UiTask) {
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { FetchLedger.write(resultFile(task), JSONObject().put("finalized", true)) } }
            }
        }
        try {
            coordinator.initialize()
            val id = coordinator.add(fixture(System.currentTimeMillis()))
            val task = coordinator.task(id)!!
            val job = launch { coordinator.run(id) }
            withTimeout(10000) { entered.await() }
            coordinator.delete(id); job.join()
            assertTrue(job.isCancelled); assertFalse(coordinator.busy)
            assertNull(coordinator.task(id)); assertFalse(coordinator.resultFile(task).exists())
            assertTrue(coordinator.store.tasks().isEmpty())
        } finally { close(coordinator, database, root) }
    }

    @Test fun cachedQueriesAccumulateWithoutFetchingAndPreserveSelectionsAfterRestoration(): Unit = runBlocking {
        val token = UUID.randomUUID().toString()
        val database = "history-$token.db"
        val root = File(context.cacheDir, "history-$token")
        var scans = 0
        val coordinator = object : TaskCoordinator(context, database, root) {
            override suspend fun execute(task: UiTask) { scans++; error("Second query must not scan") }
        }
        try {
            coordinator.initialize()
            val id = coordinator.add(fixture(System.currentTimeMillis()))
            val task = coordinator.task(id)!!
            val result = comments()
            FetchLedger.write(coordinator.resultFile(task), CommentResultJson.encode(result))
            val before = coordinator.resultFile(task).readBytes()
            source(coordinator.store, task.postId, "second")
            val updated = coordinator.changeCommentQuery(id, R.string.target_author_name, "Bo", setOf("first"))
            assertEquals(listOf("cid-first", "cid-second"), updated.matching(coordinator.loadResult(updated)!!).map { it.id })
            assertEquals(setOf("first"), updated.selectedIds)
            assertEquals(0, scans); assertEquals(1, coordinator.state.value.tasks.size)
            assertArrayEquals(before, coordinator.resultFile(updated).readBytes())
            val stored = TaskJson.decode(coordinator.store.tasks().single(), context.resources)
            assertEquals("Bo", stored.target); assertEquals(R.string.target_author_name, stored.targetKind)
            assertEquals(listOf("cid-first"), stored.retainedCommentIds)
            assertEquals(setOf("first"), stored.selectedIds)
            assertEquals(listOf("cid-first", "cid-second"), stored.matching(result).map { it.id })
            val selected = result.assets.filter { it.id == "second" }
            coordinator.select(id, selected)
            val summary = CommentDownloader(coordinator.store) { _, _, _ -> error("Already downloaded") }
                .save(task.postId, selected, result) { _, _ -> }
            assertEquals(1, summary.saved); assertEquals(0, summary.failed)
        } finally { close(coordinator, database, root) }
    }

    @Test fun thumbnailsUseActualCachedImagesAndSurviveTaskProgressWhileDeleteIsReachable(): Unit = runBlocking {
        val token = UUID.randomUUID().toString()
        val database = "history-$token.db"
        val root = File(context.cacheDir, "history-$token")
        val coordinator = TaskCoordinator(context, database, root)
        try {
            coordinator.initialize()
            val id = coordinator.add(fixture(System.currentTimeMillis()))
            val task = coordinator.task(id)!!.copy(title = context.getString(R.string.choose_comment_assets))
            FetchLedger.write(coordinator.resultFile(task), CommentResultJson.encode(comments()))
            source(coordinator.store, task.postId, "first")
            val thumbnail = coordinator.thumbnail(task)!!
            val bitmap = BitmapFactory.decodeFile(thumbnail.absolutePath)
            assertNotNull(bitmap); assertTrue(maxOf(bitmap.width, bitmap.height) <= 160); bitmap.recycle()
            assertEquals(thumbnail, coordinator.thumbnail(task))
            foreground()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var screen: MainScreen
                lateinit var image: ImageView
                var deleted: Long? = null
                scenario.onActivity { activity ->
                    screen = MainScreen(activity, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { view, _ ->
                        if (view.drawable == null) view.setImageBitmap(BitmapFactory.decodeFile(thumbnail.absolutePath))
                    }, { deleted = it.id })
                    activity.setContentView(screen.root); screen.selectTab(1); screen.renderTasks(listOf(task))
                    val row = flatten(screen.root).last { it.tag == "task:$id" }
                    image = flatten(row).filterIsInstance<ImageView>().first { it.contentDescription == activity.getString(R.string.task_thumbnail) }
                    assertNotNull(image.drawable)
                    screen.renderTasks(listOf(task.copy(detail = "Progress changed")))
                    val next = flatten(screen.root).last { it.tag == "task:$id" }
                    assertSame(image, flatten(next).filterIsInstance<ImageView>().first { it.contentDescription == activity.getString(R.string.task_thumbnail) })
                    flatten(next).single { it.contentDescription == activity.getString(R.string.delete_task) }.performClick()
                    assertEquals(id, deleted)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val capture = android.graphics.Bitmap.createBitmap(screen.root.width, screen.root.height, android.graphics.Bitmap.Config.ARGB_8888)
                    screen.root.draw(android.graphics.Canvas(capture))
                    File(context.filesDir, "task-history.png").outputStream().use { capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    capture.recycle()
                }
            }
            coordinator.delete(id); assertFalse(thumbnail.exists())
        } finally { close(coordinator, database, root) }
    }

    @Test fun workThumbnailReusesPublishedVideoInsteadOfRequestingItsRemoteCover(): Unit = runBlocking {
        val token = UUID.randomUUID().toString()
        val database = "history-$token.db"
        val root = File(context.cacheDir, "history-$token")
        val coordinator = TaskCoordinator(context, database, root)
        var published: Uri? = null
        try {
            coordinator.initialize()
            val id = coordinator.add(fixture(System.currentTimeMillis()).copy(mode = DownloadMode.POST))
            val task = coordinator.task(id)!!
            val video = Asset("video", AssetKind.VIDEO, "POST", emptyList(), "mp4", null, null, null)
            val cover = Asset("cover", AssetKind.COVER, "POST", emptyList(), null, null, null, null)
            val source = File(root, "video.mp4").apply { parentFile!!.mkdirs() }
            instrumentation.context.assets.open("media/source.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            published = coordinator.mediaPublisher.save(coordinator.store.record(task.postId, video, MediaTools.measure(source, "mp4")))
            val post = Post(PostRef("douyin", task.postId), "Fixture", "", "", null, null, null, emptyMap(), emptyList())
            FetchLedger.write(coordinator.postFile(task), PostMediaJson.encode(PostMedia(post, listOf(cover, video))))
            val thumbnail = coordinator.thumbnail(task)!!
            assertNotNull(BitmapFactory.decodeFile(thumbnail.absolutePath).also { it?.recycle() })
            assertFalse(source.exists())
        } finally {
            published?.let { context.contentResolver.delete(it, null, null) }
            close(coordinator, database, root)
        }
    }

    @Test fun changingTheFilterAccumulatesResultsAndPreservesSelectionsAndPreviews() {
        foreground()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: CommentSelection
            lateinit var dialog: Dialog
            var selected = emptyList<Asset>()
            var searches = 0
            val loaded = mutableMapOf<String, Int>()
            lateinit var firstImage: ImageView
            var initialLoads = 0
            scenario.onActivity { activity ->
                val task = fixture(1)
                controller = CommentSelection(activity, task, comments(), { image, asset ->
                    image.tag = asset.id
                    image.setImageDrawable(android.graphics.drawable.ColorDrawable(0xff167c68.toInt()))
                    loaded[asset.id] = (loaded[asset.id] ?: 0) + 1
                }, searching = true, onSearch = { searches++ }) { assets, _ -> selected = assets }
                dialog = controller.show()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                flatten(dialog.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = true
                firstImage = flatten(dialog.window!!.decorView).filterIsInstance<ImageView>().single { it.tag == "first" }
                initialLoads = loaded.getValue("first")
                flatten(dialog.window!!.decorView).single { it.contentDescription == activity.getString(R.string.search_cached_comments) }.performClick()
                assertEquals(1, searches)
                controller.retarget(fixture(1, query = "first content").copy(retainedCommentIds = listOf("cid-first")))
                val sameResult = flatten(dialog.window!!.decorView).filterIsInstance<CheckBox>().single()
                assertTrue(sameResult.isChecked)
                controller.retarget(fixture(1, query = "second").copy(retainedCommentIds = listOf("cid-first")))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val checks = flatten(dialog.window!!.decorView).filterIsInstance<CheckBox>()
                assertEquals(2, checks.size); assertTrue(checks.first().isChecked); assertFalse(checks.last().isChecked)
                assertSame(firstImage, flatten(dialog.window!!.decorView).filterIsInstance<ImageView>().single { it.tag == "first" })
                assertEquals(initialLoads, loaded["first"])
                checks.last().isChecked = true
                controller.update(comments().copy(fetchedReplies = 20), true)
                flatten(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text == activity.getString(R.string.save_selected, 1) }.performClick()
            }
            assertEquals(listOf("first", "second"), selected.map { it.id })
        }
    }

    @Test fun taskThumbnailFramesReadPublishedImagesAndVideosAndLocalCoversWithoutKeepingOriginals(): Unit = runBlocking {
        val token = UUID.randomUUID().toString()
        val database = "history-$token.db"
        val root = File(context.cacheDir, "history-$token").apply { mkdirs() }
        val store = AssetStore(context, database, root)
        val thumbnails = TaskThumbnailStore(context, store) { _, _, _ -> error("Must use system copy") }
        val published = mutableListOf<Uri>()
        val base = System.currentTimeMillis()
        try {
            listOf("image.png" to AssetKind.IMAGE, "source.mp4" to AssetKind.VIDEO).forEachIndexed { index, (name, kind) ->
                val file = File(root, name)
                instrumentation.context.assets.open("media/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                val asset = Asset("thumbnail-$index", kind, "COMMENT", emptyList(), file.extension, null, null, null)
                val stored = store.record("1000000000000000972", asset, MediaTools.measure(file, file.extension))
                val uri = MediaPublisher(context, store).save(stored).also(published::add)
                assertFalse(file.exists())
                val output = thumbnails.ensure(base + index, asset, store.find(stored.postId, stored.assetId), null)!!
                BitmapFactory.decodeFile(output.absolutePath).let { bitmap ->
                    assertNotNull(bitmap); assertTrue(maxOf(bitmap.width, bitmap.height) <= 160); bitmap.recycle()
                }
                assertTrue(context.contentResolver.openInputStream(uri)!!.use { it.read() } >= 0)
                thumbnails.remove(base + index)
            }
            val cover = File(root, "cover.png")
            instrumentation.context.assets.open("media/image.png").use { input -> cover.outputStream().use { input.copyTo(it) } }
            assertNotNull(thumbnails.ensure(base + 2, null, null, cover))
            thumbnails.remove(base + 2)
        } finally {
            published.forEach { context.contentResolver.delete(it, null, null) }
            store.close(); context.deleteDatabase(database); root.deleteRecursively()
            repeat(3) { thumbnails.remove(base + it) }
        }
    }

    @Test fun theResultToolbarSearchesTheSameTaskAndPreservesItsCachedResponse(): Unit = runBlocking {
        val coordinator = (context.applicationContext as TaskApplication).tasks
        coordinator.initialize()
        val id = coordinator.add(fixture(System.currentTimeMillis()))
        val task = coordinator.task(id)!!
        FetchLedger.write(coordinator.resultFile(task), CommentResultJson.encode(comments()))
        val original = coordinator.resultFile(task).readBytes()
        foreground()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var search: android.app.AlertDialog
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("showComments", UiTask::class.java, CommentResult::class.java, Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(activity, task, comments(), false)
                    val selection = MainActivity::class.java.getDeclaredField("selectionDialog").apply { isAccessible = true }.get(activity) as Dialog
                    flatten(selection.window!!.decorView).single { it.contentDescription == activity.getString(R.string.search_cached_comments) }.performClick()
                    search = MainActivity::class.java.getDeclaredField("commentQueryDialog").apply { isAccessible = true }.get(activity) as android.app.AlertDialog
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val views = flatten(search.window!!.decorView)
                    views.filterIsInstance<android.widget.Spinner>().single().setSelection(1)
                    views.filterIsInstance<android.widget.EditText>().single().setText("Bob")
                    search.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                }
                withTimeout(10000) { while (coordinator.task(id)?.target != "Bob") delay(25) }
                instrumentation.waitForIdleSync()
                assertFalse(search.isShowing)
                assertEquals(R.string.target_author_name, coordinator.task(id)!!.targetKind)
                assertArrayEquals(original, coordinator.resultFile(task).readBytes())
                assertEquals(listOf("cid-first", "cid-second"), coordinator.task(id)!!.matching(coordinator.loadResult(task)!!).map { it.id })
            }
        } finally { coordinator.delete(id) }
    }

    @Test fun continuingUsesTheSameCheckpointAndKeepsAccumulatedQueriesAndSelections(): Unit = runBlocking {
        val token = UUID.randomUUID().toString()
        val database = "continuation-$token.db"
        val root = File(context.cacheDir, "continuation-$token")
        val coordinator = TaskCoordinator(context, database, root)
        try {
            coordinator.initialize()
            val id = coordinator.add(fixture(System.currentTimeMillis()))
            val task = coordinator.task(id)!!
            val checkpoint = coordinator.checkpointFile(task)
            val page = JSONObject().put("cursor", 39).put("has_more", 1).put("total", 2)
                .put("comments", org.json.JSONArray().put(JSONObject().put("cid", "cid-first").put("text", "first")
                    .put("user", JSONObject().put("nickname", "Alice")).put("image_list", org.json.JSONArray().put(
                        JSONObject().put("origin_url", JSONObject().put("uri", "first").put("url_list", org.json.JSONArray().put("https://example.invalid/first.png")))))))
            try {
                CommentFetcher(coordinator.config, coordinator.mapper, { _, variables ->
                    if (variables.getValue("cursor") == "0") page else throw CancellationException("User stopped")
                }, { FetchLedger.write(checkpoint, it) }, {}).fetch(PostRef("douyin", task.postId), coordinator.config.getInt("limit_top"))
                fail("Must stop before the second page")
            } catch (_: CancellationException) { }
            assertTrue(coordinator.canContinueCommentSearch(task))
            val partial = coordinator.loadResult(task)!!
            val asset = partial.assets.single().id
            val before = checkpoint.readBytes()
            coordinator.changeCommentQuery(id, R.string.target_text, "second", setOf(asset))
            val resumed = coordinator.prepareCommentContinuation(id, setOf(asset))
            assertEquals(1, coordinator.state.value.tasks.size); assertEquals(id, resumed.id)
            assertEquals("discover", resumed.operation); assertEquals("second", resumed.target)
            assertEquals(listOf("cid-first"), resumed.retainedCommentIds); assertEquals(setOf(asset), resumed.selectedIds)
            assertArrayEquals(before, checkpoint.readBytes())
            val calls = mutableListOf<String>()
            val result = CommentFetcher(coordinator.config, coordinator.mapper, { _, variables ->
                calls += variables.getValue("cursor")
                JSONObject().put("cursor", 81).put("has_more", 0).put("comments", org.json.JSONArray().put(
                    JSONObject().put("cid", "cid-second").put("text", "second").put("user", JSONObject().put("nickname", "Bob"))))
            }, { FetchLedger.write(checkpoint, it) }, {}).fetch(PostRef("douyin", task.postId), coordinator.config.getInt("limit_top"),
                resume = JSONObject(checkpoint.readText()))
            assertEquals(listOf("39"), calls)
            assertEquals(listOf("cid-first", "cid-second"), resumed.matching(result).map { it.id })
            assertFalse(coordinator.canContinueCommentSearch(resumed))
        } finally { close(coordinator, database, root) }
    }
}
