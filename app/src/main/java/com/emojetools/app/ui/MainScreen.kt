package com.emojetools.app.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.emojetools.app.R
import com.emojetools.app.core.CommentTarget
import com.emojetools.app.core.CommentOrder
import com.emojetools.app.core.AssetKind
import com.emojetools.app.core.CommentResult
import com.emojetools.app.core.CommentCandidate
import com.emojetools.app.core.OutputAction
import com.emojetools.app.core.matching
import com.emojetools.app.core.store.StoredAsset

enum class DownloadMode(val label: Int, val icon: Int) {
    POST(R.string.mode_post, R.drawable.ic_layers_2),
    COMMENTS(R.string.mode_comments, R.drawable.ic_message_circle),
    FAVORITES(R.string.favorite_stickers, R.drawable.ic_file_image),
    ACCOUNT_WORKS(R.string.account_works, R.drawable.ic_layers_2),
    BILIBILI(R.string.mode_post, R.drawable.ic_layers_2)
}
enum class TaskStage { RESOLVING, RESOLVED, FETCHING, SELECTING, DOWNLOADING, DONE, PARTIAL, FAILED, CANCELLED, INTERRUPTED }
data class UiTask(
    val id: Long, val source: String, val mode: DownloadMode, val format: Int,
    var target: String, var stage: TaskStage = TaskStage.RESOLVING,
    var postId: String = "", var detail: String = "", var targetKind: Int = R.string.target_text,
    val selectedIds: MutableSet<String> = linkedSetOf(), var errorCode: String = "", var title: String = "",
    var operation: String = "discover", var accountIds: List<String> = emptyList(), var accountName: String = "",
    var sessionTag: String = "", var commentsFetchedAt: Long = 0, var cacheUsed: Boolean = false, var forceRefresh: Boolean = false,
    var outputAction: OutputAction = OutputAction.SAVE, val outputs: MutableMap<String, String> = linkedMapOf(),
    val commentOrder: CommentOrder = CommentOrder.TOP_FIRST,
    var retainedCommentIds: List<String> = emptyList(),
    val collectionKey: String = ""
)

val UiTask.storageId: String get() = if (mode == DownloadMode.FAVORITES) collectionKey else postId

fun UiTask.targetType() = when (targetKind) {
    R.string.target_id -> CommentTarget.COMMENT_ID
    R.string.target_author -> CommentTarget.AUTHOR_ID
    R.string.target_author_name -> CommentTarget.AUTHOR_NAME
    R.string.target_mine -> CommentTarget.MINE
    else -> CommentTarget.TEXT
}
fun UiTask.matching(result: CommentResult): List<CommentCandidate> {
    if (retainedCommentIds.isEmpty()) return result.matching(targetType(), target, accountIds)
    val comments = result.comments.associateBy { it.id }
    return (retainedCommentIds.mapNotNull(comments::get) + result.matching(targetType(), target, accountIds)).distinctBy { it.id }
}
fun TaskStage.isRunning() = this in listOf(TaskStage.RESOLVING, TaskStage.FETCHING, TaskStage.DOWNLOADING)
fun UiTask.retryLabel() = if (mode == DownloadMode.COMMENTS && operation == "download" && stage in listOf(TaskStage.FAILED, TaskStage.PARTIAL))
    R.string.retry_failed_assets else R.string.resume_task

class MainScreen(
    private val activity: Activity,
    private val onSubmit: () -> Unit,
    private val onLogin: () -> Unit,
    private val onSettings: (View) -> Unit,
    private val onTask: (UiTask) -> Unit,
    private val onCancel: (UiTask) -> Unit,
    private val onRetry: (UiTask) -> Unit,
    private val onAsset: (StoredAsset) -> Unit,
    private val preview: (ImageView, StoredAsset) -> Unit,
    private val previewTask: (ImageView, UiTask) -> Unit = { _, _ -> },
    private val onDeleteTask: (UiTask) -> Unit = {},
    private val onFavorites: () -> Unit = {},
    private val onAccountWorks: () -> Unit = {}
) {
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        fitsSystemWindows = true
        setBackgroundColor(BACKGROUND)
    }
    lateinit var sourceInput: EditText
        private set
    lateinit var targetInput: EditText
        private set
    var mode = DownloadMode.POST
        private set
    var targetKind = R.string.target_text
        private set
    var commentOrder = CommentOrder.TOP_FIRST
        private set
    val outputFormat: Int get() = R.string.format_original
    private var ready = false
    private var working = false
    private var currentTab = 0
    val selectedTab: Int get() = currentTab
    private var taskFilter = 0
    private var libraryFilter = 0
    private var taskItems: List<UiTask> = emptyList()
    private val taskImages = mutableMapOf<String, ImageView>()
    private val pages = FrameLayout(activity)
    private val pageViews = mutableListOf<View>()
    private val navViews = mutableListOf<LinearLayout>()
    private val modeViews = mutableListOf<LinearLayout>()
    private val orderViews = mutableListOf<TextView>()
    private lateinit var commentFields: LinearLayout
    private lateinit var targetField: FrameLayout
    private lateinit var primary: Button
    private lateinit var accountText: TextView
    private lateinit var platformText: TextView
    private var douyinStored = false
    private var bilibiliStored = false
    private val douyinOnly = mutableListOf<View>()
    private lateinit var targetKindText: TextView
    private lateinit var clearSource: ImageButton
    private lateinit var recent: LinearLayout
    private lateinit var taskList: LinearLayout
    private lateinit var taskCount: TextView
    private lateinit var taskFilters: LinearLayout
    private lateinit var libraryFilters: LinearLayout
    private lateinit var libraryEmpty: FrameLayout
    private lateinit var librarySearch: EditText
    private lateinit var assetCount: TextView
    private var storedAssets: List<StoredAsset> = emptyList()

    init {
        root.addView(header(), LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        pageViews += buildGetPage()
        pageViews += buildTaskPage()
        pageViews += buildLibraryPage()
        pageViews.forEach { pages.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(View(activity).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, dp(1)))
        root.addView(bottomNavigation(), LinearLayout.LayoutParams(-1, dp(68)))
        sourceInput.addTextChangedListener(watcher {
            clearSource.visibility = if (sourceInput.text.isNullOrBlank()) View.INVISIBLE else View.VISIBLE
            updateAction()
        })
        targetInput.addTextChangedListener(watcher { updateAction() })
        selectMode(DownloadMode.POST)
        selectTab(0)
        renderTasks(emptyList())
    }

    private fun header(): View = row().apply {
        setPadding(dp(22), 0, dp(14), 0)
        addView(FrameLayout(activity).apply {
            background = shape(ACCENT, 8)
            addView(icon(R.drawable.ic_layers_2, Color.WHITE, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        addView(text(R.string.app_name, 19, INK, true).apply {
            setPadding(dp(10), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        accountText = text(R.string.not_logged_in, 12, MUTED).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onLogin() }
            contentDescription = activity.getString(R.string.login)
        }
        addView(accountText, LinearLayout.LayoutParams(-2, dp(48)))
        addView(iconButton(R.drawable.ic_settings_2, R.string.settings) { onSettings(it) }, LinearLayout.LayoutParams(dp(44), dp(48)))
    }

    private fun buildGetPage(): View {
        val content = column().apply { setPadding(dp(24), dp(12), dp(24), dp(24)) }
        content.addView(row().apply {
            addView(row().apply {
                platformText = text(R.string.platform_douyin, 26, INK, true)
                addView(platformText)
                addView(icon(R.drawable.ic_chevron_down, MUTED, 20), LinearLayout.LayoutParams(dp(20), dp(20)).apply { leftMargin = dp(8) })
                contentDescription = activity.getString(R.string.choose_platform)
                setOnClickListener { anchor ->
                    PopupMenu(activity, anchor).apply {
                        menu.add(0, 0, 0, R.string.platform_douyin)
                        menu.add(0, 1, 1, R.string.platform_bilibili)
                        setOnMenuItemClickListener { selectMode(if (it.itemId == 1) DownloadMode.BILIBILI else DownloadMode.POST); true }
                    }.show()
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(text(R.string.new_task, 12, MUTED))
        })
        content.addView(space(22))
        val collectionsStart = content.childCount
        content.addView(row().apply {
            setPadding(dp(4), 0, dp(4), 0)
            addView(icon(R.drawable.ic_file_image, ACCENT, 22))
            addView(text(R.string.favorite_stickers, 16, INK, true).apply { setPadding(dp(12), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(icon(R.drawable.ic_chevron_right, MUTED, 20))
            contentDescription = activity.getString(R.string.favorite_stickers)
            background = ripple(Color.TRANSPARENT, 0); isFocusable = true
            setOnClickListener { if (ready) onFavorites() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        content.addView(View(activity).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, dp(1)))
        (collectionsStart until content.childCount).forEach { douyinOnly += content.getChildAt(it) }
        content.addView(row().apply {
            setPadding(dp(4), 0, dp(4), 0)
            addView(icon(R.drawable.ic_layers_2, ACCENT, 22))
            addView(text(R.string.account_works, 16, INK, true).apply { setPadding(dp(12), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(icon(R.drawable.ic_chevron_right, MUTED, 20))
            contentDescription = activity.getString(R.string.account_works)
            background = ripple(Color.TRANSPARENT, 0); isFocusable = true
            setOnClickListener { if (ready) onAccountWorks() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        content.addView(View(activity).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, dp(1)))
        content.addView(space(16))
        content.addView(row().apply {
            addView(text(R.string.work_link, 14, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(command(R.string.paste, R.drawable.ic_clipboard) { paste() }, LinearLayout.LayoutParams(-2, dp(36)))
        })
        content.addView(space(6))
        content.addView(FrameLayout(activity).apply {
            background = shape(Color.WHITE, 8, LINE)
            sourceInput = EditText(activity).apply {
                setHint(R.string.link_hint); setTextColor(INK); setHintTextColor(FAINT)
                textSize = 14f; letterSpacing = 0f; background = null; gravity = Gravity.TOP
                setPadding(dp(16), dp(15), dp(45), dp(12)); minLines = 3; maxLines = 3
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            addView(sourceInput, FrameLayout.LayoutParams(-1, -1))
            clearSource = iconButton(R.drawable.ic_x, R.string.clear_source) { sourceInput.text.clear() }.apply { visibility = View.INVISIBLE }
            addView(clearSource, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END))
        }, LinearLayout.LayoutParams(-1, dp(100)))
        val scopesStart = content.childCount
        content.addView(space(22))
        content.addView(text(R.string.source_scope, 14, INK, true))
        content.addView(space(10))
        content.addView(row().apply {
            background = shape(SOFT, 8); setPadding(dp(4), dp(4), dp(4), dp(4))
            ENTRY_SCOPES.forEach { item ->
                val button = row().apply {
                    gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0)
                    addView(icon(item.icon, MUTED, 18), LinearLayout.LayoutParams(dp(18), dp(18)))
                    addView(text(item.label, 14, MUTED, true).apply {
                        gravity = Gravity.CENTER; maxLines = 2; setPadding(dp(8), 0, 0, 0)
                    }, LinearLayout.LayoutParams(-2, -2))
                    contentDescription = activity.getString(item.label)
                    setOnClickListener { selectMode(item) }; isFocusable = true
                }
                modeViews += button
                addView(button, LinearLayout.LayoutParams(0, -1, 1f))
            }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        (scopesStart until content.childCount).forEach { douyinOnly += content.getChildAt(it) }
        commentFields = column().apply {
            addView(space(17))
            addView(row().apply {
                addView(text(R.string.target_comment, 14, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
                targetKindText = text(R.string.target_text, 12, ACCENT).apply { setPadding(dp(8), 0, dp(4), 0) }
                val choose = row().apply {
                    addView(targetKindText)
                    addView(icon(R.drawable.ic_chevron_down, ACCENT, 14))
                    setPadding(dp(8), 0, 0, 0)
                    setOnClickListener { chooseTargetKind(it) }
                }
                addView(choose, LinearLayout.LayoutParams(-2, dp(36)))
            })
            targetInput = EditText(activity).apply {
                setHint(R.string.target_text_hint); textSize = 14f; letterSpacing = 0f
                setTextColor(INK); setHintTextColor(FAINT); isSingleLine = true
                setPadding(dp(15), 0, dp(52), 0); background = null
            }
            targetField = FrameLayout(activity).apply {
                background = shape(Color.WHITE, 8, LINE)
                addView(targetInput, FrameLayout.LayoutParams(-1, -1))
                addView(iconButton(R.drawable.ic_clipboard, R.string.paste_comment) { paste(targetInput) },
                    FrameLayout.LayoutParams(dp(48), -1, Gravity.END))
            }
            addView(targetField, LinearLayout.LayoutParams(-1, dp(50)))
            addView(space(16))
            addView(text(R.string.comment_search_order, 14, INK, true))
            addView(space(10))
            addView(row().apply {
                background = shape(SOFT, 8); setPadding(dp(4), dp(4), dp(4), dp(4))
                CommentOrder.values().forEach { order ->
                    val label = if (order == CommentOrder.TOP_FIRST) R.string.comments_top_first else R.string.comments_replies_first
                    val option = text(label, 13, MUTED, true).apply {
                        gravity = Gravity.CENTER; maxLines = 2; setPadding(dp(6), 0, dp(6), 0)
                        setOnClickListener { selectCommentOrder(order) }; isFocusable = true
                    }
                    orderViews += option
                    addView(option, LinearLayout.LayoutParams(0, -1, 1f))
                }
            }, LinearLayout.LayoutParams(-1, dp(52)))
            selectCommentOrder(CommentOrder.TOP_FIRST)
        }
        content.addView(commentFields)
        content.addView(space(22))
        primary = Button(activity).apply {
            setText(R.string.parse_work); textSize = 15f; letterSpacing = 0f; isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE); background = ripple(ACCENT, 8); stateListAnimator = null; elevation = 0f
            setPadding(dp(20), 0, dp(20), 0)
            val arrow = activity.getDrawable(R.drawable.ic_arrow_right)!!.mutate().apply {
                setTint(Color.WHITE); setBounds(0, 0, dp(19), dp(19))
            }
            setCompoundDrawablesRelative(null, null, arrow, null)
            setOnClickListener { sourceInput.clearFocus(); targetInput.clearFocus(); hideKeyboard(); onSubmit() }
        }
        content.addView(primary, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(space(29))
        content.addView(row().apply {
            addView(text(R.string.recent_tasks, 17, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(command(R.string.view_all, R.drawable.ic_chevron_right) { selectTab(1) }, LinearLayout.LayoutParams(-2, dp(38)))
        })
        recent = column()
        content.addView(recent)
        return ScrollView(activity).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content) }
    }

    private fun buildTaskPage(): View = column().apply {
        setPadding(dp(24), dp(12), dp(24), 0)
        addView(text(R.string.tasks, 26, INK, true))
        taskCount = text(activity.getString(R.string.task_count, 0), 12, MUTED).apply { setPadding(0, dp(9), 0, 0) }
        addView(taskCount)
        addView(space(18))
        taskFilters = filterRow(listOf(R.string.filter_all, R.string.filter_active, R.string.filter_completed)) { index -> taskFilter = index; updateTaskList() }
        addView(taskFilters)
        taskList = column().apply { setPadding(0, dp(18), 0, dp(20)) }
        addView(ScrollView(activity).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(taskList) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun buildLibraryPage(): View = column().apply {
        setPadding(dp(24), dp(12), dp(24), 0)
        addView(row().apply {
            addView(text(R.string.nav_library, 26, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton(R.drawable.ic_search, R.string.search_assets) { control ->
                librarySearch.visibility = if (librarySearch.visibility == View.GONE) View.VISIBLE else View.GONE
                val open = librarySearch.visibility == View.VISIBLE
                (control as ImageButton).setImageResource(if (open) R.drawable.ic_x else R.drawable.ic_search)
                control.contentDescription = activity.getString(if (open) R.string.close_search else R.string.search_assets)
                if (!open) { librarySearch.text.clear(); hideKeyboard() } else {
                    librarySearch.requestFocus()
                    librarySearch.post {
                        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                            .showSoftInput(librarySearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
        })
        assetCount = text(activity.getString(R.string.asset_count, 0), 12, MUTED).apply { setPadding(0, dp(7), 0, 0) }
        addView(assetCount)
        librarySearch = EditText(activity).apply {
            setHint(R.string.search_assets); textSize = 14f; isSingleLine = true
            setPadding(dp(14), 0, dp(14), 0); background = shape(Color.WHITE, 8, LINE); visibility = View.GONE
            addTextChangedListener(watcher { updateLibraryEmpty() })
        }
        addView(librarySearch, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        addView(space(16))
        libraryFilters = filterRow(listOf(R.string.filter_all, R.string.mode_video, R.string.filter_images, R.string.mode_audio, R.string.filter_stickers)) { index -> libraryFilter = index; updateLibraryEmpty() }
        addView(libraryFilters)
        libraryEmpty = FrameLayout(activity)
        addView(libraryEmpty, LinearLayout.LayoutParams(-1, 0, 1f))
        updateLibraryEmpty()
    }

    private fun bottomNavigation(): View = row().apply {
        setBackgroundColor(Color.WHITE)
        listOf(R.string.nav_get to R.drawable.ic_download, R.string.nav_tasks to R.drawable.ic_list_todo,
            R.string.nav_library to R.drawable.ic_folder_open).forEachIndexed { index, (label, drawable) ->
            val item = column().apply {
                gravity = Gravity.CENTER; setPadding(0, dp(7), 0, dp(6))
                addView(icon(drawable, MUTED, 22))
                addView(text(label, 11, MUTED, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0) })
                setOnClickListener { selectTab(index) }; contentDescription = activity.getString(label); isFocusable = true
            }
            navViews += item
            addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    fun selectTab(index: Int) {
        currentTab = index
        pageViews.forEachIndexed { i, view -> view.visibility = if (i == index) View.VISIBLE else View.GONE }
        navViews.forEachIndexed { i, nav ->
            val color = if (i == index) ACCENT else MUTED
            (nav.getChildAt(0) as ImageView).setColorFilter(color)
            (nav.getChildAt(1) as TextView).setTextColor(color)
            nav.isSelected = i == index
        }
        hideKeyboard()
    }

    fun selectMode(value: DownloadMode) {
        mode = value
        val bili = mode == DownloadMode.BILIBILI
        platformText.setText(if (bili) R.string.platform_bilibili else R.string.platform_douyin)
        douyinOnly.forEach { it.visibility = if (bili) View.GONE else View.VISIBLE }
        renderAccount()
        sourceInput.setHint(if (bili) R.string.bilibili_link_hint else R.string.link_hint)
        modeViews.forEachIndexed { index, view ->
            val selected = ENTRY_SCOPES[index] == mode
            view.background = if (selected) shape(Color.WHITE, 6, ACCENT) else shape(Color.TRANSPARENT, 6)
            (view.getChildAt(0) as ImageView).setColorFilter(if (selected) ACCENT else MUTED)
            (view.getChildAt(1) as TextView).setTextColor(if (selected) ACCENT else MUTED)
            view.isSelected = selected
        }
        commentFields.visibility = if (mode == DownloadMode.COMMENTS) View.VISIBLE else View.GONE
        updateAction()
    }

    fun setReady(value: Boolean) { ready = value; updateAction() }
    fun setWorking(value: Boolean) { working = value; updateAction() }
    fun setAccountStored(value: Boolean, bilibili: Boolean = false) {
        if (bilibili) bilibiliStored = value else douyinStored = value
        renderAccount()
    }
    private fun renderAccount() {
        val bili = mode == DownloadMode.BILIBILI
        accountText.setText(if (if (bili) bilibiliStored else douyinStored) R.string.login_stored else R.string.not_logged_in)
        accountText.contentDescription = activity.getString(if (bili) R.string.login_bilibili else R.string.login)
    }

    fun restoreSelection(modeName: String?, kind: Int, tab: Int, order: CommentOrder = CommentOrder.TOP_FIRST) {
        selectMode(DownloadMode.values().firstOrNull { it.name == modeName && it in listOf(DownloadMode.POST, DownloadMode.COMMENTS, DownloadMode.BILIBILI) } ?: DownloadMode.POST)
        if (kind in listOf(R.string.target_text, R.string.target_id, R.string.target_author, R.string.target_author_name, R.string.target_mine)) updateTargetKind(kind)
        selectCommentOrder(order)
        selectTab(tab.coerceIn(0, 2))
    }

    private fun selectCommentOrder(order: CommentOrder) {
        commentOrder = order
        orderViews.forEachIndexed { index, view ->
            val selected = CommentOrder.values()[index] == order
            view.isSelected = selected
            view.background = if (selected) shape(Color.WHITE, 6, ACCENT) else shape(Color.TRANSPARENT, 6)
            view.setTextColor(if (selected) ACCENT else MUTED)
        }
    }

    fun renderTasks(items: List<UiTask>) {
        taskItems = items.toList()
        val ids = items.map { it.id.toString() }.toSet()
        taskImages.keys.removeAll { it.substringAfter(':') !in ids }
        recent.removeAllViews()
        if (items.isEmpty()) recent.addView(emptyState(R.drawable.ic_clock_3, R.string.no_recent_tasks, false), LinearLayout.LayoutParams(-1, dp(112)))
        else items.take(2).forEach { recent.addView(taskRow(it, "recent"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }
        taskCount.text = activity.getString(R.string.task_count_limit, items.size, com.emojetools.app.core.task.TaskCoordinator.MAX_TASKS)
        updateTaskList()
    }

    private fun updateTaskList() {
        taskList.removeAllViews()
        val visible = taskItems.filter { taskFilter == 0 ||
            (taskFilter == 1 && it.stage !in listOf(TaskStage.DONE, TaskStage.FAILED, TaskStage.CANCELLED)) ||
            (taskFilter == 2 && it.stage == TaskStage.DONE) }
        if (visible.isEmpty()) taskList.addView(emptyState(R.drawable.ic_list_todo,
            when (taskFilter) { 1 -> R.string.no_active_tasks; 2 -> R.string.no_completed_tasks; else -> R.string.no_tasks }, true), LinearLayout.LayoutParams(-1, dp(300)))
        else visible.forEach { taskList.addView(taskRow(it, "list"), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }) }
    }

    private fun taskRow(task: UiTask, section: String): View = column().apply {
        tag = "task:${task.id}"
        setPadding(dp(14), dp(14), dp(12), dp(14)); background = shape(Color.WHITE, 8, LINE)
        val color = when (task.stage) { TaskStage.FAILED -> ERROR; TaskStage.CANCELLED -> MUTED; else -> ACCENT }
        val status = when (task.stage) {
            TaskStage.RESOLVING -> R.string.task_resolving; TaskStage.RESOLVED -> R.string.task_resolved
            TaskStage.FETCHING -> R.string.task_fetching; TaskStage.SELECTING -> R.string.task_selecting
            TaskStage.DOWNLOADING -> if (task.outputAction == OutputAction.SHARE) R.string.preparing_share else R.string.task_downloading
            TaskStage.DONE -> if (task.outputAction == OutputAction.SHARE) R.string.share_ready else R.string.task_done
            TaskStage.PARTIAL -> if (task.outputAction == OutputAction.SHARE) R.string.share_partial else R.string.task_partial
            TaskStage.FAILED -> R.string.task_failed; TaskStage.CANCELLED -> R.string.task_cancelled
            TaskStage.INTERRUPTED -> R.string.task_interrupted
        }
        addView(row().apply {
            val image = taskImages.getOrPut("$section:${task.id}") { ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; background = shape(SOFT, 6); clipToOutline = true
                contentDescription = activity.getString(R.string.task_thumbnail)
            } }
            (image.parent as? ViewGroup)?.removeView(image)
            addView(image, LinearLayout.LayoutParams(dp(56), dp(56)))
            previewTask(image, task)
            addView(column().apply {
                setPadding(dp(12), 0, dp(8), 0)
                addView(text(task.title.ifBlank { task.postId.ifBlank { task.source } }, 14, INK, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                val bili = task.mode == DownloadMode.BILIBILI || task.collectionKey.startsWith("bilibili-works-")
                addView(text(activity.getString(if (bili) R.string.platform_bilibili else R.string.platform_douyin) + " · " + activity.getString(task.mode.label) + " · " + activity.getString(task.format), 12, MUTED).apply { setPadding(0, dp(5), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (task.stage.isRunning()) addView(iconButton(R.drawable.ic_x, R.string.cancel) { onCancel(task) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            else if (task.stage in listOf(TaskStage.FAILED, TaskStage.CANCELLED, TaskStage.PARTIAL, TaskStage.INTERRUPTED)) addView(iconButton(R.drawable.ic_rotate_ccw,
                if (task.mode == DownloadMode.COMMENTS) task.retryLabel()
                else if (task.stage == TaskStage.FAILED || task.stage == TaskStage.PARTIAL) R.string.retry else R.string.resume_task) { onRetry(task) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            else addView(icon(R.drawable.ic_chevron_right, MUTED, 18))
            addView(iconButton(R.drawable.ic_trash_2, R.string.delete_task) { onDeleteTask(task) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        })
        addView(text(status, 12, color).apply { setPadding(0, dp(12), 0, 0) })
        if (task.stage.isRunning()) addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(ACCENT)
        }, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(10) })
        if (task.stage == TaskStage.RESOLVED) addView(text(R.string.task_waiting, 11, MUTED).apply { setPadding(0, dp(5), 0, 0) })
        if (task.detail.isNotBlank()) addView(text(task.detail, 12, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        setOnClickListener { onTask(task) }
    }

    private fun updateLibraryEmpty() {
        if (!::libraryEmpty.isInitialized) return
        libraryEmpty.removeAllViews()
        val query = librarySearch.text.toString().trim()
        val visible = storedAssets.filter { item ->
            (query.isBlank() || item.file.name.contains(query, true) || item.postId.contains(query)) && when (libraryFilter) {
                1 -> item.kind == AssetKind.VIDEO
                2 -> item.kind in listOf(AssetKind.IMAGE, AssetKind.COVER)
                3 -> item.kind == AssetKind.AUDIO
                4 -> item.kind == AssetKind.STICKER
                else -> true
            }
        }
        if (visible.isNotEmpty()) {
            val list = column().apply { setPadding(0, dp(16), 0, dp(20)) }
            visible.forEach { asset ->
                list.addView(row().apply {
                    setPadding(0, dp(10), 0, dp(10))
                    val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE) }
                    addView(image, LinearLayout.LayoutParams(dp(76), dp(76)))
                    preview(image, asset)
                    addView(column().apply {
                        setPadding(dp(14), 0, dp(8), 0)
                        addView(text(asset.file.name, 14, INK, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                        addView(text(android.text.format.Formatter.formatShortFileSize(activity, asset.bytes), 12, MUTED).apply { setPadding(0, dp(7), 0, 0) })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(icon(R.drawable.ic_chevron_right, MUTED, 18))
                    setOnClickListener { onAsset(asset) }
                }, LinearLayout.LayoutParams(-1, -2))
            }
            libraryEmpty.addView(ScrollView(activity).apply { addView(list) }, FrameLayout.LayoutParams(-1, -1))
            return
        }
        val title = if (!librarySearch.text.isNullOrBlank()) R.string.no_matches else R.string.no_assets
        libraryEmpty.addView(emptyState(when (libraryFilter) {
            1 -> R.drawable.ic_file_video; 2 -> R.drawable.ic_file_image; 3 -> R.drawable.ic_music_2
            4 -> R.drawable.ic_message_circle; else -> R.drawable.ic_folder_open
        }, title, true), FrameLayout.LayoutParams(-1, -1))
    }

    fun renderLibrary(items: List<StoredAsset>) {
        storedAssets = items.filter { it.file.isFile || it.uri != null }
        assetCount.text = activity.getString(R.string.asset_count, storedAssets.size)
        updateLibraryEmpty()
    }

    private fun emptyState(drawable: Int, title: Int, action: Boolean): View = column().apply {
        gravity = Gravity.CENTER
        addView(icon(drawable, EMPTY_ICON, if (action) 42 else 28))
        addView(text(title, 13, MUTED).apply { setPadding(0, dp(14), 0, 0); gravity = Gravity.CENTER })
        if (action) addView(command(R.string.new_task, R.drawable.ic_plus) { selectTab(0) }, LinearLayout.LayoutParams(-2, dp(44)).apply { topMargin = dp(12) })
    }

    private fun filterRow(labels: List<Int>, onSelect: (Int) -> Unit): LinearLayout = row().apply {
        labels.forEachIndexed { index, label ->
            addView(text(label, 13, if (index == 0) ACCENT else MUTED, index == 0).apply {
                gravity = Gravity.CENTER
                background = underline(index == 0)
                setOnClickListener {
                    val parent = parent as LinearLayout
                    for (i in 0 until parent.childCount) {
                        val tab = parent.getChildAt(i) as TextView
                        tab.setTextColor(if (i == index) ACCENT else MUTED); tab.background = underline(i == index)
                    }
                    onSelect(index)
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
    }

    private fun chooseTargetKind(anchor: View) {
        PopupMenu(activity, anchor).apply {
            listOf(R.string.target_mine, R.string.target_text, R.string.target_author_name).forEach { id -> menu.add(0, id, 0, id) }
            setOnMenuItemClickListener { item ->
                updateTargetKind(item.itemId)
                true
            }
            show()
        }
    }
    private fun updateTargetKind(kind: Int) {
        targetKind = kind; targetKindText.setText(kind)
        targetField.visibility = if (kind == R.string.target_mine) View.GONE else View.VISIBLE
        targetInput.setHint(when (kind) {
            R.string.target_id -> R.string.target_id_hint
            R.string.target_author -> R.string.target_author_hint
            R.string.target_author_name -> R.string.target_author_name_hint
            else -> R.string.target_text_hint
        })
        updateAction()
    }
    private fun paste(input: EditText = sourceInput) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val value = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
        if (value.isBlank()) Toast.makeText(activity, R.string.empty_clipboard, Toast.LENGTH_SHORT).show()
        else { input.setText(value); input.setSelection(input.text.length) }
    }
    private fun updateAction() {
        if (!::primary.isInitialized) return
        primary.setText(if (mode == DownloadMode.COMMENTS) R.string.find_comments else R.string.parse_work)
        primary.isEnabled = ready && !working && !sourceInput.text.isNullOrBlank() &&
            (mode != DownloadMode.COMMENTS || targetKind == R.string.target_mine || !targetInput.text.isNullOrBlank())
        primary.alpha = if (primary.isEnabled) 1f else 0.48f
    }
    private fun hideKeyboard() {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
    }
    private fun underline(selected: Boolean) = android.graphics.drawable.LayerDrawable(arrayOf(
        android.graphics.drawable.ColorDrawable(LINE), android.graphics.drawable.ColorDrawable(if (selected) ACCENT else LINE)
    )).apply { setLayerInset(0, 0, dp(43), 0, 0); setLayerInset(1, dp(8), dp(42), dp(8), 0) }
    private fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun space(height: Int) = View(activity).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun text(id: Int, size: Int, color: Int, bold: Boolean = false) = text(activity.getString(id), size, color, bold)
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size.toFloat(); letterSpacing = 0f; setTextColor(color); includeFontPadding = false
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    private fun icon(drawable: Int, color: Int, size: Int) = ImageView(activity).apply {
        setImageResource(drawable); setColorFilter(color); layoutParams = ViewGroup.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun iconButton(drawable: Int, label: Int, action: (View) -> Unit) = ImageButton(activity).apply {
        setImageResource(drawable); setColorFilter(MUTED); scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(11), dp(11), dp(11), dp(11)); background = ripple(Color.TRANSPARENT, 8)
        contentDescription = activity.getString(label)
        tooltipText = activity.getString(label)
        setOnClickListener(action)
    }
    private fun command(label: Int, drawable: Int, action: () -> Unit) = row().apply {
        addView(icon(drawable, ACCENT, 15))
        addView(text(label, 12, ACCENT, true).apply { setPadding(dp(5), 0, dp(2), 0) })
        setPadding(dp(7), 0, 0, 0); background = ripple(Color.TRANSPARENT, 5)
        contentDescription = activity.getString(label); setOnClickListener { action() }; isFocusable = true
    }
    private fun shape(color: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); border?.let { setStroke(dp(1), it) }
    }
    private fun ripple(color: Int, radius: Int) = RippleDrawable(ColorStateList.valueOf(0x18000000), shape(color, radius), null)
    private fun watcher(changed: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed()
        override fun afterTextChanged(s: Editable?) = Unit
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    companion object {
        private val ENTRY_SCOPES = listOf(DownloadMode.POST, DownloadMode.COMMENTS)
        private val BACKGROUND = Color.rgb(247, 248, 250)
        private val INK = Color.rgb(30, 39, 43)
        private val MUTED = Color.rgb(120, 131, 139)
        private val FAINT = Color.rgb(160, 169, 175)
        private val LINE = Color.rgb(226, 231, 234)
        private val SOFT = Color.rgb(235, 239, 242)
        private val ACCENT = Color.rgb(22, 124, 104)
        private val ERROR = Color.rgb(191, 77, 65)
        private val EMPTY_ICON = Color.rgb(177, 190, 196)
    }
}
