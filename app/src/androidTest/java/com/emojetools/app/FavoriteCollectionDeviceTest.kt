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
import com.emojetools.app.core.download.FavoriteDownloader
import com.emojetools.app.core.download.MediaTools
import com.emojetools.app.core.store.*
import com.emojetools.app.platform.douyin.FavoriteStickerPage
import com.emojetools.app.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FavoriteCollectionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun flatten(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
    private fun asset(n: Int) = Asset("favorite:$n", AssetKind.STICKER, "FAVORITE", listOf("https://example.invalid/$n"), null, null, null, null)
    private fun foreground() {
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/.ui.MainActivity").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    @Test fun paginationAndRetryKeepCheckedItemsAndLoadedThumbnailsAndSessionChangeClearsThem() {
        foreground()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var screen: FavoriteStickersScreen
            var state = FavoriteCollection("session-a", "account-a", "Alice", listOf(asset(1)))
            var retries = 0
            val loads = mutableMapOf<ImageView, Int>()
            var initialLoads = 0
            var originalImage: ImageView? = null
            var originalDrawable: android.graphics.drawable.Drawable? = null
            scenario.onActivity { activity ->
                screen = FavoriteStickersScreen(activity, { image, _, _, _ ->
                    loads[image] = (loads[image] ?: 0) + 1
                    image.setImageBitmap(instrumentation.context.assets.open("media/image.png").use { BitmapFactory.decodeStream(it) })
                }, { retries++ }, {}, {}, { id, checked -> state = state.select(id, checked); screen.render(state, false) }, {}, { _, _ -> }, {}, {})
                activity.setContentView(screen.root); screen.render(state, false)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                originalImage = flatten(screen.grid).filterIsInstance<ImageView>().single()
                originalDrawable = originalImage!!.drawable
                initialLoads = loads.getValue(originalImage!!)
                flatten(screen.grid).filterIsInstance<CheckBox>().single().isChecked = true
                state = state.append(FavoriteStickerPage(listOf(asset(1), asset(2)), "next", false), 200)
                screen.render(state, true)
                screen.render(state, false, AppFailure(ErrorCode.NETWORK, "Offline fixture"))
                flatten(screen.root).filterIsInstance<Button>().single { it.text == activity.getString(R.string.retry) && it.isShown }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(1, retries)
                assertSame(originalImage, flatten(screen.grid).filterIsInstance<ImageView>().first())
                assertSame(originalDrawable, originalImage!!.drawable)
                assertEquals(initialLoads, loads.getValue(originalImage!!))
                assertTrue(flatten(screen.grid).filterIsInstance<CheckBox>().first().isChecked)
                assertTrue(flatten(screen.root).filterIsInstance<EditText>().isEmpty())
                screen.render(FavoriteCollection("session-b", "account-b", "Bob", listOf(asset(3))), false)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertTrue(flatten(screen.grid).filterIsInstance<CheckBox>().none { it.isChecked })
                assertEquals(1, screen.grid.adapter.count)
                screen.close()
            }
        }
    }

    @Test fun loginEmptyAndSchemaFailureHaveDistinctRecoverableActions() {
        foreground()
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            var logins = 0; var retries = 0
            val screen = FavoriteStickersScreen(activity, { _, _, _, _ -> }, { retries++ }, {}, { logins++ }, { _, _ -> }, {}, { _, _ -> }, {}, {})
            activity.setContentView(screen.root)
            screen.render(null, false)
            flatten(screen.root).filterIsInstance<Button>().single { it.text == activity.getString(R.string.login) }.performClick()
            assertEquals(1, logins)
            screen.render(null, false, AppFailure(ErrorCode.SCHEMA, "Invalid response fixture"))
            flatten(screen.root).filterIsInstance<Button>().first { it.text == activity.getString(R.string.retry) && it.visibility == View.VISIBLE }.performClick()
            assertEquals(1, retries)
            screen.render(FavoriteCollection("session", "key", "", completed = true), false)
            assertTrue(flatten(screen.root).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.favorites_empty) && it.isShown })
            assertTrue(flatten(screen.root).filterIsInstance<Button>().filter { it.text == activity.getString(R.string.save_selected) }.none { it.isEnabled })
        } }
    }

    @Test fun favoriteTaskUsesCollectionIdentityAndRestoresSelectionWithoutWorkOrCommentIds() {
        val task = UiTask(1, "", DownloadMode.FAVORITES, R.string.format_original, "", stage = TaskStage.DOWNLOADING,
            collectionKey = "favorites-${"a".repeat(64)}", selectedIds = mutableSetOf("favorite:1"), sessionTag = "session", operation = "download")
        val restored = TaskJson.decode(TaskJson.encode(task, context.resources), context.resources)
        assertEquals("", restored.postId); assertEquals("", restored.source)
        assertEquals(task.collectionKey, restored.storageId); assertEquals(task.selectedIds, restored.selectedIds)
        assertEquals(TaskStage.INTERRUPTED, restored.stage)
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun interruptedFavoriteBatchReopensItsStoreAndDownloadsOnlyTheMissingItem(): Unit = runBlocking {
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "materials/favorite-recovery-$token")
        val database = "favorite-recovery-$token.db"
        var store = AssetStore(context, database, root)
        val key = "favorites-${"c".repeat(64)}"
        val selected = listOf(asset(1), asset(2))
        val entered = CompletableDeferred<Unit>()
        val published = mutableSetOf<Uri>()
        val calls = mutableListOf<String>()
        var interrupt = true
        fun saver() = FavoriteDownloader(store, { saved -> published += MediaPublisher(context, store).save(saved) }) { asset, directory, stem ->
            calls += asset.id
            if (asset.id == selected[1].id && interrupt) { entered.complete(Unit); awaitCancellation() }
            check(directory.isDirectory || directory.mkdirs())
            val file = File(directory, "$stem.png")
            instrumentation.context.assets.open("media/image.png").use { input -> file.outputStream().use { input.copyTo(it) } }
            MediaTools.measure(file, "png")
        }
        try {
            val job = launch { saver().save(key, selected) { _, _ -> } }
            withTimeout(5000) { entered.await() }; job.cancelAndJoin()
            val first = store.verified(key, selected[0].id)!!.uri!!
            assertNull(store.verified(key, selected[1].id))
            store.close(); store = AssetStore(context, database, root)
            calls.clear(); interrupt = false
            assertEquals(0, saver().save(key, selected) { _, _ -> }.failed)
            assertEquals(listOf(selected[1].id), calls)
            assertEquals(first, store.verified(key, selected[0].id)!!.uri)
        } finally {
            try { published.forEach { context.contentResolver.delete(it, null, null) } }
            finally { store.close(); context.deleteDatabase(database); root.deleteRecursively() }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun savingReusesVerifiedSystemFilesAndSharingWorksBeforeAndAfterPublication() = runBlocking<Unit> {
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "materials/favorite-fixture-$token")
        val database = "favorite-fixture-$token.db"
        val store = AssetStore(context, database, root)
        val publisher = MediaPublisher(context, store)
        val published = mutableSetOf<Uri>()
        val selected = listOf(asset(1), asset(2))
        val key = "favorites-${"a".repeat(64)}"
        var downloads = 0
        try {
            fun downloader(deliver: suspend (StoredAsset) -> Unit) = FavoriteDownloader(store, deliver) { _, directory, stem ->
                downloads++; check(directory.isDirectory || directory.mkdirs())
                val file = File(directory, "$stem.png")
                instrumentation.context.assets.open("media/image.png").use { input -> file.outputStream().use { input.copyTo(it) } }
                MediaTools.measure(file, "png")
            }
            val sharer = downloader { }
            assertEquals(0, sharer.save(key, selected) { _, _ -> }.failed)
            assertEquals(2, downloads)
            val temporary = MediaSharing.intent(context, selected.map { store.verified(key, it.id)!! })
            assertEquals(Intent.ACTION_SEND_MULTIPLE, temporary.action)
            assertEquals("${context.packageName}.files", temporary.clipData!!.getItemAt(0).uri.authority)
            assertTrue(temporary.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            repeat(2) { context.contentResolver.openInputStream(temporary.clipData!!.getItemAt(it).uri)!!.use { input -> assertTrue(input.read() >= 0) } }
            val saver = downloader { saved -> published += publisher.save(saved) }
            assertEquals(0, saver.save(key, selected) { _, _ -> }.failed)
            assertEquals(2, downloads)
            val uris = selected.map { store.verified(key, it.id)!!.uri }
            assertTrue(store.all().none { it.file.exists() })
            assertEquals(0, saver.save(key, selected) { _, _ -> }.failed)
            assertEquals(2, downloads); assertEquals(uris, selected.map { store.verified(key, it.id)!!.uri })
            assertEquals(1, context.contentResolver.delete(uris.first()!!, null, null))
            published.remove(uris.first()!!)
            assertEquals(0, saver.save(key, selected) { _, _ -> }.failed)
            assertEquals(3, downloads)
            assertNotEquals(uris.first(), store.verified(key, selected.first().id)!!.uri)
            assertNull(store.verified("favorites-${"b".repeat(64)}", selected.first().id))
        } finally {
            try { published.forEach { context.contentResolver.delete(it, null, null) } }
            finally { store.close(); context.deleteDatabase(database); root.deleteRecursively() }
        }
    }
}
