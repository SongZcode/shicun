package com.emojetools.app

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.*
import com.emojetools.app.core.download.DownloadedFile
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.net.JsonMapper
import com.emojetools.app.core.store.AssetStore
import com.emojetools.app.platform.douyin.CommentFetcher
import com.emojetools.app.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class M1DeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Before fun foreground() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/.ui.MainActivity")).use { it.readBytes() }
    }

    @Test fun channelEntryAndCommentTargetSurviveRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            screenshot("channel-entry-post.png")
            scenario.onActivity { activity ->
                val views = flatten(activity.window.decorView)
                assertTrue(views.filterIsInstance<TextView>().any { it.isShown && it.text == activity.getString(R.string.platform_douyin) })
                listOf(R.string.mode_video, R.string.mode_audio, R.string.mode_images).forEach { label ->
                    assertFalse(views.any { it.isShown && it.contentDescription == activity.getString(label) })
                }
                val post = views.single { it.isShown && it.contentDescription == activity.getString(R.string.mode_post) }
                assertTrue(post.isSelected)
                views.filterIsInstance<EditText>().single { it.hint == activity.getString(R.string.link_hint) }.setText("1000000000000000001")
                views.single { it.isShown && it.contentDescription == activity.getString(R.string.mode_comments) }.performClick()
                val target = views.filterIsInstance<EditText>().single { it.hint == activity.getString(R.string.target_text_hint) }
                assertTrue(target.isShown)
                target.setText("fixture target")
                assertFalse(views.filterIsInstance<TextView>().any { it.isShown && it.text == activity.getString(R.string.output_format) })
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val views = flatten(activity.window.decorView)
                assertTrue(views.single { it.isShown && it.contentDescription == activity.getString(R.string.mode_comments) }.isSelected)
                val source = views.filterIsInstance<EditText>().single { it.hint == activity.getString(R.string.link_hint) }
                val target = views.filterIsInstance<EditText>().single { it.hint == activity.getString(R.string.target_text_hint) }
                assertEquals("1000000000000000001", source.text.toString())
                assertEquals("fixture target", target.text.toString())
                assertTrue(target.isShown)
                source.text.clear(); target.text.clear()
            }
            instrumentation.waitForIdleSync()
            screenshot("channel-entry-comments.png")
        }
    }

    @Test fun sqliteResumeVerifiesBytesAndHashAcrossReopen() {
        val name = "m1-test-${System.nanoTime()}.db"
        val file = File(context.cacheDir, "m1-test-${System.nanoTime()}.gif")
        val original = "GIF89a-original".toByteArray()
        file.writeBytes(original)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(original).joinToString("") { "%02x".format(it.toInt() and 255) }
        val asset = Asset("sticker:fixture", AssetKind.STICKER, "COMMENT", emptyList(), "gif", null, null, null)
        var store = AssetStore(context, name)
        try {
            store.record("123", asset, DownloadedFile(file, sha1, file.length(), "gif"))
            store.saveTask(123L, JSONObject().put("fixture", true))
            store.close(); store = AssetStore(context, name)
            assertNotNull(store.verified("123", asset.id))
            assertTrue(store.tasks().single().getBoolean("fixture"))
            file.writeBytes(ByteArray(original.size))
            assertNull(store.verified("123", asset.id))
            file.writeBytes(original.copyOfRange(0, 4))
            assertNull(store.verified("123", asset.id))
        } finally { store.close(); context.deleteDatabase(name); file.delete() }
    }

    @Test fun candidateSelectionDedupesSharedStickerAndWritesNativeLedger() = runBlocking {
        val bundleFiles = mutableMapOf<String, String>()
        val manifest = JSONObject(context.assets.open("bundle/manifest.json").bufferedReader().use { it.readText() })
        val version = manifest.getInt("bundle_version")
        ZipInputStream(context.assets.open("bundle/bundle_v$version.zip")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                bundleFiles[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        val fixture = JSONObject(context.assets.open("m1_comments.json").bufferedReader().use { it.readText() })
        val ledger = File(context.filesDir, "m1_device_manifest.json")
        val result = CommentFetcher(JSONObject(bundleFiles.getValue("platforms/douyin.json")),
            JsonMapper(JSONObject(bundleFiles.getValue("parsers/douyin.json"))), { operation, variables ->
                fixture.getJSONObject("pages").getJSONObject(listOf(operation, variables["parentId"].orEmpty(), variables.getValue("cursor")).joinToString(":"))
            }, { FetchLedger.write(ledger, it) }, {}).fetch(PostRef("douyin", fixture.getString("post_id")), 2000, ledger.absolutePath)
        assertEquals(2, result.fetchedTopLevel); assertEquals(2, result.fetchedReplies)
        assertEquals(2, result.assets.size)
        var selected = emptyList<Asset>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: android.app.Dialog
            scenario.onActivity { activity ->
                val task = UiTask(1, "fixture", DownloadMode.COMMENTS, R.string.format_original, "fixture-author", targetKind = R.string.target_author)
                dialog = CommentSelection(activity, task, result, { view, _ ->
                    view.setImageResource(R.drawable.ic_file_image)
                }) { assets, _ -> selected = assets }.show()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val views = flatten(dialog.window!!.decorView)
                val checks = views.filterIsInstance<CheckBox>()
                val save = views.filterIsInstance<Button>().single { it.text == context.getString(R.string.save_selected) }
                assertEquals(2, checks.size); assertFalse(save.isEnabled)
                checks.first().isChecked = true
                assertTrue(checks.all { it.isChecked })
                assertTrue(save.isEnabled)
                assertTrue(views.filterIsInstance<TextView>().any { it.text == context.getString(R.string.selected_count, 1) })
            }
            instrumentation.waitForIdleSync()
            (instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")).let { screenshot ->
                File(context.filesDir, "m1-candidates-fixture.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            scenario.onActivity {
                flatten(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text == context.getString(R.string.save_selected) }.performClick()
            }
            assertEquals(1, selected.size)
            assertEquals("1000000000000000002", selected.single().meta["commentId"])
        }
    }

    private fun flatten(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()

    private fun screenshot(name: String) {
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(context.filesDir, name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
