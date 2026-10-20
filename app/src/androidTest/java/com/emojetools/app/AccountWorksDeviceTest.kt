package com.emojetools.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.*
import com.emojetools.app.core.download.*
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.store.*
import com.emojetools.app.platform.douyin.*
import com.emojetools.app.ui.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountWorksDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun id(n: Int) = (7000000000000000000L + n).toString()
    private fun work(n: Int, images: Int = 0) = AccountWork(PostRef("douyin", id(n)), "Fixture work $n", "Fixture author", listOf("https://example.invalid/$n.png"), images, 12000)
    private fun flatten(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
    private fun foreground() {
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/.ui.MainActivity").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    @Test fun tabSelectionsAndThumbnailsRemainStableAcrossPaginationAndRetry() {
        foreground()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var screen: AccountWorksScreen
            var saved = AccountWorksCollection(WorkCollectionKind.SAVED, "fixture-session", WorkAccount("Alice", "fixture-account"), listOf(work(1)))
            var tab = WorkCollectionKind.SAVED
            val liked = saved.copy(kind = WorkCollectionKind.LIKED)
            var retries = 0
            val loads = mutableMapOf<ImageView, Int>()
            var originalImage: ImageView? = null
            var originalDrawable: android.graphics.drawable.Drawable? = null
            var initialLoads = 0
            scenario.onActivity { activity ->
                screen = AccountWorksScreen(activity, { image, _ ->
                    loads[image] = (loads[image] ?: 0) + 1
                    image.setImageBitmap(instrumentation.context.assets.open("media/image.png").use { BitmapFactory.decodeStream(it) })
                }, { next -> tab = next; screen.render(next, if (next == WorkCollectionKind.SAVED) saved else liked, false) },
                    { retries++ }, {}, {}, { selected, checked -> saved = saved.select(selected, checked); screen.render(tab, saved, false) }, {}, {}, {}, {}, {}, {})
                activity.setContentView(screen.root); screen.render(tab, saved, false)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                originalImage = flatten(screen.grid).filterIsInstance<ImageView>().single()
                originalDrawable = originalImage!!.drawable
                initialLoads = loads.getValue(originalImage!!)
                flatten(screen.grid).filterIsInstance<CheckBox>().single().isChecked = true
                saved = saved.append(AccountWorksPage(listOf(work(2)), "next", false, 1), 200)
                screen.render(tab, saved, true)
                screen.render(tab, saved, false, AppFailure(ErrorCode.NETWORK, "Offline fixture"))
                flatten(screen.root).filterIsInstance<Button>().single { it.text == activity.getString(R.string.retry) && it.isShown }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(1, retries)
                // GridView can bind an extra measurement view; visible previews must stay intact.
                assertSame(originalImage, flatten(screen.grid).filterIsInstance<ImageView>().first())
                assertSame(originalDrawable, originalImage!!.drawable)
                assertEquals(initialLoads, loads.getValue(originalImage!!))
                assertTrue(flatten(screen.grid).filterIsInstance<CheckBox>().first().isChecked)
                flatten(screen.root).filterIsInstance<TextView>().single { it.text == activity.getString(R.string.account_liked_works) }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(flatten(screen.grid).filterIsInstance<CheckBox>().none { it.isChecked })
                flatten(screen.root).filterIsInstance<TextView>().single { it.text == activity.getString(R.string.account_saved_works) }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertTrue(flatten(screen.grid).filterIsInstance<CheckBox>().first().isChecked)
                screen.render(tab, null, false)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(0, screen.grid.adapter.count) }
        }
    }

    @Test fun batchTasksRestoreRealWorkIdsAndTheirReadableOutputKeys() {
        val task = UiTask(1, WorkCollectionKind.LIKED.name, DownloadMode.ACCOUNT_WORKS, R.string.format_original, "",
            stage = TaskStage.DOWNLOADING, operation = "download", collectionKey = WorkAccount("A", "fixture-account").key,
            selectedIds = mutableSetOf(id(1), id(2)), outputs = mutableMapOf("${id(1)}:image:one" to "content://fixture/one"))
        val restored = TaskJson.decode(TaskJson.encode(task, context.resources), context.resources)
        assertEquals("", restored.postId); assertEquals(task.selectedIds, restored.selectedIds)
        assertEquals(task.collectionKey, restored.collectionKey); assertEquals(task.outputs, restored.outputs)
        assertEquals(TaskStage.INTERRUPTED, restored.stage)
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun interruptedBatchReopensItsManifestsAndReusesAlreadyPublishedFiles(): Unit = runBlocking {
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "materials/work-recovery-$token")
        val database = "work-recovery-$token.db"
        var store = AssetStore(context, database, root)
        val works = listOf(work(1))
        val assets = (1..2).map { n -> Asset("image:$n", AssetKind.IMAGE, "POST", listOf("https://example.invalid/$n.png"),
            null, null, null, null, meta = mapOf("index" to "$n")) }
        val media = PostMedia(Post(works.single().ref, "Fixture", "Alice", "", null, null, null, emptyMap(), emptyList()), assets)
        val published = mutableSetOf<Uri>()
        val entered = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        var interrupt = true
        var fetched = 0
        fun saver() = WorkBatchDownloader(store, File(root, "sources"), MediaTools(), { fetched++; media }, { assets },
            { asset, directory, stem ->
                calls += asset.id
                if (asset.id == assets[1].id && interrupt) { entered.complete(Unit); awaitCancellation() }
                check(directory.isDirectory || directory.mkdirs())
                val file = File(directory, "$stem.png")
                instrumentation.context.assets.open("media/image.png").use { input -> file.outputStream().use { input.copyTo(it) } }
                MediaTools.measure(file, "png")
            }, 0, { saved -> published += MediaPublisher(context, store).save(saved) })
        try {
            val manifests = File(root, "manifests")
            val job = launch { saver().save(works, manifests) { _, _, _ -> } }
            withTimeout(5000) { entered.await() }; job.cancelAndJoin()
            val first = store.verified(works[0].ref.id, assets[0].id)!!.uri!!
            assertNull(store.verified(works[0].ref.id, assets[1].id))
            store.close(); store = AssetStore(context, database, root)
            calls.clear(); interrupt = false
            assertEquals(0, saver().save(works, manifests) { _, _, _ -> }.failed)
            assertEquals(listOf(assets[1].id), calls); assertEquals(1, fetched)
            assertEquals(first, store.verified(works[0].ref.id, assets[0].id)!!.uri)
        } finally {
            try { published.forEach { context.contentResolver.delete(it, null, null) } }
            finally { store.close(); context.deleteDatabase(database); root.deleteRecursively() }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun batchRetriesOnlyMissingMediaAndReusesPublishedSystemUris() = runBlocking<Unit> {
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "materials/account-work-test-$token")
        val database = "account-work-test-$token.db"
        val store = AssetStore(context, database, root)
        val publisher = MediaPublisher(context, store)
        val published = mutableSetOf<Uri>()
        val outputs = linkedMapOf<String, Uri>()
        var fetched = 0; var downloaded = 0; var failSecond = true
        val works = listOf(work(1, 2), work(2))
        fun media(ref: PostRef): PostMedia {
            val assets = if (ref.id == id(1)) (1..2).map { n ->
                Asset("image:$n", AssetKind.IMAGE, "POST", listOf("https://example.invalid/$n.png"), null, null, null, null, meta = mapOf("index" to "$n"))
            } else listOf(Asset("video:${ref.id}", AssetKind.VIDEO, "POST", listOf("https://example.invalid/video.mp4"), "mp4", null, null, null, meta = mapOf("quality" to "original")))
            return PostMedia(Post(ref, "Fixture", "Alice", "", null, null, null, emptyMap(), emptyList()), assets)
        }
        try {
            val downloader = WorkBatchDownloader(store, File(root, "sources"), MediaTools(), { ref ->
                fetched++
                if (ref.id == id(2) && failSecond) { failSecond = false; throw AppFailure(ErrorCode.NETWORK, "Offline fixture") }
                media(ref)
            }, { ref -> media(ref).assets }, { asset, directory, stem ->
                downloaded++; check(directory.isDirectory || directory.mkdirs())
                val ext = if (asset.kind == AssetKind.VIDEO) "mp4" else "png"
                val file = File(directory, "$stem.$ext")
                instrumentation.context.assets.open(if (ext == "mp4") "media/source.mp4" else "media/image.png").use { input -> file.outputStream().use { input.copyTo(it) } }
                MediaTools.measure(file, ext)
            }, 1, { saved -> publisher.save(saved).also { uri -> published += uri; outputs[saved.key] = uri } })
            val manifests = File(root, "manifests")
            val partial = downloader.save(works, manifests) { _, _, _ -> }
            assertEquals(2, partial.saved); assertEquals(1, partial.failed); assertEquals(2, downloaded)
            val firstUris = outputs.toMap()
            val retry = downloader.save(works, manifests) { _, _, _ -> }
            assertEquals(3, retry.saved); assertEquals(0, retry.failed)
            assertEquals(3, fetched); assertEquals(3, downloaded)
            firstUris.forEach { (key, uri) -> assertEquals(uri, outputs[key]) }
            val repeated = downloader.save(works, manifests) { _, _, _ -> }
            assertEquals(0, repeated.failed); assertEquals(3, fetched); assertEquals(3, downloaded)
            assertTrue(store.all().none { it.file.exists() })
            val share = MediaSharing.intent(context, store.all().map { store.verified(it.postId, it.assetId)!! })
            assertEquals(Intent.ACTION_SEND_MULTIPLE, share.action); assertEquals(3, share.clipData!!.itemCount)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            repeat(3) { index -> context.contentResolver.openInputStream(share.clipData!!.getItemAt(index).uri)!!.use { assertTrue(it.read() >= 0) } }
            val deleted = outputs.values.first()
            assertEquals(1, context.contentResolver.delete(deleted, null, null))
            published.remove(deleted)
            assertEquals(0, downloader.save(works, manifests) { _, _, _ -> }.failed)
            assertEquals(4, downloaded); assertEquals(3, fetched)
        } finally {
            try { published.forEach { context.contentResolver.delete(it, null, null) } }
            finally { store.close(); context.deleteDatabase(database); root.deleteRecursively() }
        }
    }

    @Test fun productionAccountListsUseRealSavedAndLikedPagination() = runBlocking<Unit> {
        assumeTrue("Explicit account-work live opt-in required", InstrumentationRegistry.getArguments().getString("account_works_live") == "true")
        val tasks = (context.applicationContext as TaskApplication).tasks
        tasks.initialize()
        val account = tasks.adapter.workAccount()
        val report = JSONObject().put("bundle", tasks.manager.active.version).put("local_saved_session", true)
            .put("browser_api_requests", false).put("account_identifiers_exported", false)
        for (kind in WorkCollectionKind.entries) {
            var state = AccountWorksCollection(kind, "live-test", account)
            val pages = JSONArray()
            repeat(2) {
                if (!state.completed && !state.stopped) {
                    val cursor = state.cursor
                    val page = tasks.adapter.accountWorks(kind, account.secUid, cursor)
                    state = state.append(page, 200)
                    pages.put(JSONObject().put("rows", page.works.size).put("completed", page.completed)
                        .put("cursor_advanced", cursor != page.nextCursor).put("unavailable", page.unavailable))
                }
            }
            assertEquals("Acceptance account needs two real pages", 2, pages.length())
            assertTrue(state.works.isNotEmpty()); assertFalse(state.stopped)
            report.put(kind.name.lowercase(), JSONObject().put("unique_works", state.works.size).put("pages", pages).put("completed", state.completed))
        }
        FetchLedger.write(File(context.getExternalFilesDir("test-artifacts"), "account-works-live.json"), report)
    }
}
