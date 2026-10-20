package com.emojetools.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.*
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.download.CommentDownloader
import com.emojetools.app.core.download.MediaTools
import com.emojetools.app.core.store.AssetStore
import com.emojetools.app.core.task.*
import com.emojetools.app.platform.douyin.CommentFetcher
import com.emojetools.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class M3DeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val coordinator get() = (context.applicationContext as TaskApplication).tasks
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private suspend fun add(suffix: String, selected: Boolean = false): Long = withContext(Dispatchers.Main) {
        coordinator.initialize()
        coordinator.add(UiTask(System.currentTimeMillis(), "fixture://m3/$suffix", DownloadMode.POST, R.string.format_original, "",
            title = "Fixture background task", selectedIds = if (selected) linkedSetOf("fixture-asset") else linkedSetOf(),
            operation = if (selected) "download" else "discover"))
    }
    private suspend fun start(id: Long) {
        withContext(Dispatchers.Main) { TaskService.start(context, id) }
        withTimeout(10_000) { ServiceFixture.entered.await() }
    }
    private suspend fun finished(id: Long): UiTask = withTimeout(10_000) {
        coordinator.state.first { it.activeId == null && it.tasks.any { task -> task.id == id && !task.stage.isRunning() } }.tasks.single { it.id == id }
    }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    @Before fun foreground() { shell("am start -W -n ${context.packageName}/.ui.MainActivity") }

    @Test fun foregroundWorkSurvivesActivityRecreationDestructionAndScreenLock(): Unit = runBlocking {
        ServiceFixture.reset()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val id = add("background")
        try {
            start(id)
            scenario.recreate()
            assertEquals(id, coordinator.state.value.activeId)
            scenario.close()
            shell("input keyevent 223")
            assertEquals(id, coordinator.state.value.activeId)
            val notice = notifications.activeNotifications.single { it.id == TaskService.NOTIFICATION_ID }.notification
            assertTrue(notice.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            assertEquals(1, notice.actions.size)
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            val displayDeadline = SystemClock.uptimeMillis() + 5000
            while (!context.getSystemService(android.os.PowerManager::class.java).isInteractive && SystemClock.uptimeMillis() < displayDeadline) SystemClock.sleep(50)
            shell("cmd statusbar expand-notifications")
            SystemClock.sleep(600)
            if (!notificationTitleVisible()) shell("input swipe 540 20 540 1800 500")
            val notificationDeadline = SystemClock.uptimeMillis() + 5000
            while (!notificationTitleVisible() && SystemClock.uptimeMillis() < notificationDeadline) SystemClock.sleep(100)
            assertTrue("Foreground notification must be visible before capture", notificationTitleVisible())
            screenshot("m3-background-notification-fixture.png")
            shell("cmd statusbar collapse"); shell("input keyevent 223")
            ServiceFixture.finish.complete(Unit)
            assertEquals(TaskStage.DONE, finished(id).stage)
            assertEquals(1, ServiceFixture.calls)
            Log.i("EmojeM3Test", "fixture=true api_requested=false activity_destroyed=true screen_locked=true background_js_signing=true completed=true")
        } finally { scenario.close(); shell("input keyevent 224"); shell("wm dismiss-keyguard"); withContext(Dispatchers.Main) { coordinator.cancel(id) } }
    }

    @Test fun notificationCancelAndRetryPreserveTheSelectedOperation(): Unit = runBlocking {
        ServiceFixture.reset()
        val id = add("cancel", selected = true)
        start(id)
        notifications.activeNotifications.single { it.id == TaskService.NOTIFICATION_ID }.notification.actions.single().actionIntent.send()
        val cancelled = finished(id)
        assertEquals(TaskStage.CANCELLED, cancelled.stage)
        assertEquals("download", cancelled.operation); assertEquals(setOf("fixture-asset"), cancelled.selectedIds)
        ServiceFixture.reset()
        start(id)
        withContext(Dispatchers.Main) { TaskService.start(context, id) }
        ServiceFixture.finish.complete(Unit)
        assertEquals(TaskStage.DONE, finished(id).stage)
        assertEquals(1, ServiceFixture.calls)
    }

    @Test fun loginFailureKeepsSelectionAndOffersLoginInTheNotification(): Unit = runBlocking {
        ServiceFixture.reset(login = true)
        val id = add("login", selected = true)
        start(id)
        val failed = finished(id)
        assertEquals(TaskStage.FAILED, failed.stage); assertEquals("NEED_LOGIN", failed.errorCode)
        assertEquals(setOf("fixture-asset"), failed.selectedIds)
        withTimeout(5000) {
            while (notifications.activeNotifications.none { it.id == TaskService.NOTIFICATION_ID && it.notification.actions?.any { action -> action.title == context.getString(R.string.login_and_resume) } == true }) delay(50)
        }
        ServiceFixture.reset()
        ServiceFixture.finish.complete(Unit)
        start(id)
        assertEquals(TaskStage.DONE, finished(id).stage)
        assertEquals("", coordinator.task(id)!!.errorCode)
    }

    @Test fun loginCompletionBeforeTheRecreatedPageIsReadyStillResumesTheTask(): Unit = runBlocking {
        ServiceFixture.reset()
        val id = add("login-recreated", selected = true)
        val app = context.applicationContext as android.app.Application
        var delivered = false
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            private var awaiting: android.app.Activity? = null
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {
                if (activity is MainActivity && state?.getLong("resume_login") == id) awaiting = activity
            }
            override fun onActivityStarted(activity: android.app.Activity) {
                if (activity is MainActivity && awaiting === activity) {
                    awaiting = null
                    delivered = true
                    val callback = MainActivity::class.java.getDeclaredMethod("onActivityResult", Integer.TYPE, Integer.TYPE, android.content.Intent::class.java)
                    callback.isAccessible = true
                    callback.invoke(activity, 303, android.app.Activity.RESULT_OK, null)
                }
            }
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {
                if (activity is MainActivity) state.putLong("resume_login", id)
            }
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.recreate()
                withTimeout(10_000) { ServiceFixture.entered.await() }
                assertTrue(delivered)
                ServiceFixture.finish.complete(Unit)
                assertEquals(TaskStage.DONE, finished(id).stage)
                assertEquals(setOf("fixture-asset"), coordinator.task(id)!!.selectedIds)
            }
        } finally { app.unregisterActivityLifecycleCallbacks(callbacks); withContext(Dispatchers.Main) { coordinator.cancel(id) } }
    }

    @Test fun reopeningDurableTasksMarksInterruptedAndRetainsSelection(): Unit = runBlocking {
        val database = "m3-reopen-${System.nanoTime()}.db"
        val root = File(context.cacheDir, "m3-reopen-${System.nanoTime()}").apply { mkdirs() }
        val saved = UiTask(101, "fixture", DownloadMode.POST, R.string.format_original, "", TaskStage.DOWNLOADING,
            selectedIds = linkedSetOf("asset-1", "asset-2"), operation = "download")
        AssetStore(context, database, root).use { it.saveTask(saved.id, TaskJson.encode(saved, context.resources)) }
        File(root, "incomplete.part").writeText("fixture")
        val reopened = withContext(Dispatchers.Main) { TaskCoordinator(context, database, root).also { it.initialize() } }
        try {
            val restored = reopened.task(saved.id)!!
            assertEquals(TaskStage.INTERRUPTED, restored.stage)
            assertEquals(saved.selectedIds, restored.selectedIds); assertEquals("download", restored.operation)
            assertFalse(File(root, "incomplete.part").exists())
            assertEquals("INTERRUPTED", reopened.store.tasks().single().getString("stage"))
        } finally {
            withContext(Dispatchers.Main) { reopened.js.close(); reopened.store.close() }
            context.deleteDatabase(database); root.deleteRecursively()
        }
    }

    @Test fun partialCommentDownloadRetainsTypedErrorsAndReusesCompletedFiles(): Unit = runBlocking {
        val database = "m3-download-${System.nanoTime()}.db"
        val root = File(context.cacheDir, "m3-download-${System.nanoTime()}").apply { mkdirs() }
        val manifest = File(root, "manifest.json")
        FetchLedger.write(manifest, FetchLedger("douyin", "123", JSONObject()).snapshot(true))
        val assets = (1..3).map { n -> Asset("fixture:$n", AssetKind.STICKER, "COMMENT", listOf("https://example.invalid/$n.gif"),
            null, null, null, null, meta = mapOf("level" to "T", "commentId" to "00000$n")) }
        val result = CommentResult(assets, 3, null, 3, 0, "server_end", ledgerPath = manifest.absolutePath)
        AssetStore(context, database, File(root, "materials")).use { store ->
            val calls = mutableListOf<String>()
            var failSecond = true
            val pipeline = CommentDownloader(store) { asset, directory, stem ->
                calls += asset.id
                if (asset.id == "fixture:2" && failSecond) throw AppFailure(ErrorCode.NETWORK, "Fixture disconnected")
                directory.mkdirs()
                val file = File(directory, "$stem.gif").apply { writeText("GIF89a-fixture-${asset.id}") }
                MediaTools.measure(file, "gif")
            }
            val first = pipeline.save("123", assets, result) { _, _ -> }
            assertEquals(2, first.saved); assertEquals(1, first.failed); assertEquals(ErrorCode.NETWORK, first.failures.single().code)
            calls.clear(); failSecond = false
            val second = pipeline.save("123", assets, result) { _, _ -> }
            assertEquals(3, second.saved); assertEquals(0, second.failed); assertEquals(listOf("fixture:2"), calls)
            assertEquals(3, JSONObject(manifest.readText()).getJSONObject("counters").getInt("assets_downloaded"))
            assertEquals(0, JSONObject(manifest.readText()).getJSONObject("counters").getInt("assets_failed"))
            val loginPipeline = CommentDownloader(store) { _, _, _ -> throw AppFailure(ErrorCode.NEED_LOGIN, "Fixture session expired") }
            val pending = assets.map { it.copy(id = it.id + "-login") }
            val failure = loginPipeline.save("123", pending, result) { _, _ -> }
            assertEquals(0, failure.saved); assertEquals(3, failure.failed)
            assertTrue(failure.failures.all { it.code == ErrorCode.NEED_LOGIN })
        }
        context.deleteDatabase(database); root.deleteRecursively()
    }

    @Test fun externalProcessRecovery(): Unit = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("recovery_phase")
        assumeNotNull(phase)
        withContext(Dispatchers.Main) { coordinator.initialize() }
        val file = File(context.filesDir, "fetch/123/99000003/manifest.json")
        val ref = PostRef("douyin", "123")
        fun row(id: String) = JSONObject().put("cid", id).put("text", "Fixture $id").put("reply_comment_total", 0)
        fun page(cursor: Int, more: Int, id: String) = JSONObject().put("comments", JSONArray().put(row(id))).put("cursor", cursor).put("has_more", more)
        if (phase == "seed") {
            check(context.applicationContext is FixtureApplication)
            coordinator.store.writableDatabase.delete("tasks", null, null)
            val task = UiTask(99000003, "fixture://m3/crash", DownloadMode.COMMENTS, R.string.format_original, "Fixture", TaskStage.FETCHING, postId = "123")
            coordinator.store.saveTask(task.id, TaskJson.encode(task, context.resources))
            try {
                CommentFetcher(coordinator.config, coordinator.mapper, { _, vars ->
                    if (vars["cursor"] == "0") page(39, 1, "1") else throw CancellationException("Fixture interruption")
                }, { FetchLedger.write(file, it) }, {}).fetch(ref, coordinator.config.getInt("limit_top"), file.absolutePath)
            } catch (_: CancellationException) { }
            Log.i("EmojeM3Test", "recovery_seed pid=${android.os.Process.myPid()} cursor=39 fixture=true")
        } else {
            val recovered = coordinator.task(99000003)!!
            assertEquals(TaskStage.INTERRUPTED, recovered.stage)
            assertTrue(coordinator.hasResult(recovered))
            assertEquals(1, coordinator.loadResult(recovered)!!.fetchedTopLevel)
            val calls = mutableListOf<String>()
            val result = CommentFetcher(coordinator.config, coordinator.mapper, { _, vars ->
                calls += vars.getValue("cursor"); page(81, 0, "2")
            }, { FetchLedger.write(file, it) }, {}).fetch(ref, coordinator.config.getInt("limit_top"), file.absolutePath, JSONObject(file.readText()))
            assertEquals(listOf("39"), calls); assertEquals(2, result.fetchedTopLevel)
            assertEquals(2, coordinator.loadResult(recovered)!!.fetchedTopLevel)
            File(context.filesDir, "m3_manifest_fixture.json").writeText(file.readText())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    flatten(activity.window.decorView).single { it.contentDescription == activity.getString(R.string.nav_tasks) }.performClick()
                }
                screenshot("m3-recovered-task-fixture.png")
            }
            Log.i("EmojeM3Test", "recovery_verified pid=${android.os.Process.myPid()} cursor=39 top=2 fixture=true api_requested=false")
        }
    }
    private fun flatten(view: android.view.View): List<android.view.View> = listOf(view) +
        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(300, 5000)
        SystemClock.sleep(300)
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(context.filesDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun notificationTitleVisible(): Boolean {
        fun contains(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.text?.toString() == "Fixture background task") return true
            return (0 until node.childCount).any { contains(node.getChild(it)) }
        }
        return contains(instrumentation.uiAutomation.rootInActiveWindow)
    }
}
