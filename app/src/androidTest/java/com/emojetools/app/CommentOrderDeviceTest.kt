package com.emojetools.app

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.*
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.store.CommentResultJson
import com.emojetools.app.platform.douyin.CommentFetcher
import com.emojetools.app.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CommentOrderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun flatten(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
    private fun screen(activity: MainActivity) = MainActivity::class.java.getDeclaredField("screen").run {
        isAccessible = true; get(activity) as MainScreen
    }
    private fun foreground() {
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/.ui.MainActivity").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    @Test fun pastedCopiedCommentsMatchFromTheCommenterEntryWithoutChangingTheWorkLink() {
        foreground()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val previous = clipboard.primaryClip
                try {
                    val copied = "@小刘刷抖音: 工作做完了吗就跳舞"
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", copied))
                    val screen = MainScreen(activity, {}, {}, {}, {}, {}, {}, {}, { _, _ -> })
                    activity.setContentView(screen.root); screen.setReady(true)
                    screen.restoreSelection(DownloadMode.COMMENTS.name, R.string.target_author_name, 0, CommentOrder.REPLIES_FIRST)
                    screen.sourceInput.setText("1000000000000000001")
                    val paste = flatten(screen.root).single { it.contentDescription == activity.getString(R.string.paste_comment) }
                    assertTrue(paste.isShown); paste.performClick()
                    assertEquals(copied, screen.targetInput.text.toString())
                    assertEquals("1000000000000000001", screen.sourceInput.text.toString())
                    val candidate = CommentCandidate("reply", "parent", "工作做完了吗就跳舞", "小刘刷抖音", emptyList(), emptyList())
                    val result = CommentResult(emptyList(), null, null, 0, 1, "server_end", listOf(candidate))
                    val task = UiTask(1, "fixture", DownloadMode.COMMENTS, R.string.format_original, screen.targetInput.text.toString(),
                        targetKind = screen.targetKind)
                    assertEquals(listOf("reply"), task.matching(result).map { it.id })
                    screen.restoreSelection(DownloadMode.COMMENTS.name, R.string.target_mine, 0)
                    assertFalse(paste.isShown)
                    screen.restoreSelection(DownloadMode.COMMENTS.name, R.string.target_author_name, 0, CommentOrder.REPLIES_FIRST)
                } finally {
                    if (previous != null) clipboard.setPrimaryClip(previous)
                    else if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                }
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(context.filesDir, "comment-paste.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun searchOrderSurvivesActivityAndTaskRestoration() {
        foreground()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val screen = screen(activity)
                screen.restoreSelection(DownloadMode.COMMENTS.name, R.string.target_author_name, 0)
                screen.sourceInput.setText("1000000000000000001"); screen.targetInput.setText("抖音号：Ab_123")
                val options = flatten(screen.root).filterIsInstance<TextView>()
                val top = options.single { it.text == activity.getString(R.string.comments_top_first) }
                val replies = options.single { it.text == activity.getString(R.string.comments_replies_first) }
                assertTrue(top.isSelected); assertFalse(replies.isSelected)
                replies.performClick()
                assertFalse(top.isSelected); assertTrue(replies.isSelected)
                assertEquals(CommentOrder.REPLIES_FIRST, screen.commentOrder)
                val task = UiTask(1, "fixture", DownloadMode.COMMENTS, R.string.format_original, "Ab_123",
                    stage = TaskStage.SELECTING, targetKind = R.string.target_author_name, commentOrder = screen.commentOrder)
                val encoded = TaskJson.encode(task, activity.resources)
                assertEquals(task, TaskJson.decode(encoded, activity.resources))
            }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val screen = screen(activity)
                assertEquals(CommentOrder.REPLIES_FIRST, screen.commentOrder)
                assertEquals("抖音号：Ab_123", screen.targetInput.text.toString())
                assertTrue(flatten(screen.root).filterIsInstance<TextView>().single {
                    it.text == activity.getString(R.string.comments_replies_first)
                }.isSelected)
            }
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(context.filesDir, "comment-search-order.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun embeddedMappingsFindShortHandlesInProgressiveRepliesAndPreserveBothCursors() = runBlocking {
        val coordinator = (context.applicationContext as TaskApplication).tasks
        coordinator.initialize()
        fun row(id: String, handle: String, replies: Int = 0) = JSONObject().put("cid", id).put("text", "Fixture $id")
            .put("reply_comment_total", replies).put("user", JSONObject().put("nickname", "Fixture author")
                .put("uid", "internal-$id").put("unique_id", "").put("short_id", handle))
        fun page(cursor: Int, more: Int, vararg rows: JSONObject) = JSONObject().put("cursor", cursor).put("has_more", more)
            .put("total", 3).put("comments", JSONArray(rows.toList()))
        val responses = listOf(page(39, 1, row("parent", "123456", 1)), page(7, 0, row("reply", "Ab_123")),
            page(81, 0, row("next-parent", "654321")))
        val calls = mutableListOf<String>()
        val matches = mutableListOf<List<String>>()
        var ledger = JSONObject()
        val result = CommentFetcher(coordinator.config, coordinator.mapper, { op, vars ->
            calls += "$op:${vars.getValue("cursor")}"
            responses[calls.lastIndex]
        }, { document ->
            ledger = document
            val partial = CommentResultJson.decode(FetchLedger.resumeState(document).getJSONObject("result"))
            matches += partial.matching(CommentTarget.AUTHOR_NAME, "抖音号：ＡＢ＿１２３").map { it.id }
        }, {}).fetch(PostRef("douyin", "1000000000000000001"), 0, order = CommentOrder.REPLIES_FIRST)
        assertEquals(listOf("comment:0", "reply:0", "comment:39"), calls)
        assertEquals(listOf("reply"), result.matching(CommentTarget.AUTHOR_NAME, "@ab_123").map { it.id })
        assertTrue(matches[1].isEmpty()); assertEquals(listOf("reply"), matches[2])
        assertEquals(2, result.fetchedTopLevel); assertEquals(1, result.fetchedReplies)
        FetchLedger.restore(ledger)
        FetchLedger.write(File(context.filesDir, "comment-search-manifest-fixture.json"), ledger)
    }
}
