package com.emojetools.app.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.emojetools.app.R
import com.emojetools.app.core.*
import com.emojetools.app.core.store.AccountWorksCollection
import com.emojetools.app.platform.douyin.*

class AccountWorksScreen(
    private val activity: Activity, private val preview: (ImageView, Asset) -> Unit,
    private val onTab: (WorkCollectionKind) -> Unit, private val onMore: () -> Unit,
    private val onReload: () -> Unit, private val onLogin: () -> Unit,
    private val onSelect: (String, Boolean) -> Unit, private val onSelectAll: (Boolean) -> Unit,
    private val onOpen: (AccountWork) -> Unit, private val onOutput: (OutputAction) -> Unit,
    private val onRetry: () -> Unit, private val onStop: () -> Unit, private val onShareReady: () -> Unit,
    private val bilibili: Boolean = false
) {
    val root = column().apply { fitsSystemWindows = true; setBackgroundColor(BACKGROUND) }
    private var state: AccountWorksCollection? = null
    private var updating = false
    private var busy = false
    private val tabs = WorkCollectionKind.entries.associateWith { kind -> label(activity.getString(if (bilibili && kind == WorkCollectionKind.LIKED) R.string.bilibili_liked_works else labelFor(kind)), 14, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(4), 0, dp(4), 0); isFocusable = true
        setOnClickListener { onTab(kind) }
    } }
    private val count = label("", 12)
    private val all = CheckBox(activity).apply {
        setText(R.string.account_select_all); textSize = 12f; letterSpacing = 0f; buttonTintList = ColorStateList.valueOf(ACCENT)
        setOnCheckedChangeListener { _, checked -> if (!updating) onSelectAll(checked) }
    }
    private val pageStatus = label("", 13).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(6)) }
    private val more = Button(activity).apply { isAllCaps = false; textSize = 14f; letterSpacing = 0f }
    private val outputStatus = label("", 13).apply {
        setPadding(dp(20), dp(6), dp(20), dp(4)); gravity = Gravity.CENTER; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
    }
    private val retry = Button(activity).apply { setText(R.string.resume_task); isAllCaps = false; textSize = 13f; letterSpacing = 0f; setOnClickListener { onRetry() } }
    private val share = Button(activity).apply { setText(R.string.favorites_ready_share); isAllCaps = false; textSize = 13f; letterSpacing = 0f; setOnClickListener { onShareReady() } }
    private val pause = icon(R.drawable.ic_pause, R.string.account_pause, onStop)
    private val commands = row().apply {
        setPadding(dp(16), 0, dp(16), 0)
        addView(pause, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(retry, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(share, LinearLayout.LayoutParams(0, dp(48), 1f))
    }
    private val actions = SelectionActions(activity) { onOutput(it) }
    private val adapter = WorkAdapter()
    private val folderPicker = Spinner(activity).apply { visibility = View.GONE; contentDescription = "收藏夹" }
    private var folderBinding = ""
    val grid = GridView(activity).apply {
        numColumns = GridView.AUTO_FIT; columnWidth = dp(140); stretchMode = GridView.STRETCH_COLUMN_WIDTH
        horizontalSpacing = dp(10); verticalSpacing = dp(12); setPadding(dp(16), dp(4), dp(16), dp(12)); clipToPadding = false
        isVerticalScrollBarEnabled = false; adapter = this@AccountWorksScreen.adapter
    }

    init {
        root.addView(row().apply {
            setPadding(dp(8), 0, dp(12), 0)
            addView(icon(R.drawable.ic_chevron_right, R.string.close) { activity.finish() }.apply { rotation = 180f }, LinearLayout.LayoutParams(dp(48), dp(56)))
            addView(label(activity.getString(R.string.account_works), 18, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(icon(R.drawable.ic_rotate_ccw, R.string.favorites_reload, onReload), LinearLayout.LayoutParams(dp(48), dp(56)))
        }, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(row().apply {
            setPadding(dp(16), 0, dp(16), 0)
            tabs.values.forEach { addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
        }, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(folderPicker, LinearLayout.LayoutParams(-1, dp(48)).apply { leftMargin = dp(16); rightMargin = dp(16) })
        root.addView(row().apply {
            setPadding(dp(20), 0, dp(12), 0)
            addView(count, LinearLayout.LayoutParams(0, -2, 1f))
            addView(all, LinearLayout.LayoutParams(-2, dp(48)))
        })
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(pageStatus)
        root.addView(more, LinearLayout.LayoutParams(-1, dp(48)).apply { leftMargin = dp(20); rightMargin = dp(20) })
        root.addView(outputStatus)
        root.addView(commands)
        if (bilibili) root.addView(label(activity.getString(R.string.bilibili_batch_scope), 12).apply {
            gravity = Gravity.CENTER; setTextColor(MUTED); setPadding(dp(20), dp(4), dp(20), 0)
        })
        root.addView(actions.root)
        render(WorkCollectionKind.SAVED, null, false)
        renderTask(null, false, false)
    }

    fun renderFolders(folders: List<Pair<String, String>>, selected: String, visible: Boolean, onSelect: (String) -> Unit) {
        folderPicker.visibility = if (visible && folders.isNotEmpty()) View.VISIBLE else View.GONE
        val binding = folders.toString() + selected
        if (binding == folderBinding) return
        folderBinding = binding
        folderPicker.onItemSelectedListener = null
        folderPicker.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, folders.map { it.second })
        folderPicker.setSelection(folders.indexOfFirst { it.first == selected }.coerceAtLeast(0))
        folderPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                folders.getOrNull(position)?.first?.takeIf { it != selected }?.let(onSelect)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    fun render(kind: WorkCollectionKind, collection: AccountWorksCollection?, loading: Boolean, error: AppFailure? = null) {
        val switched = state?.kind != collection?.kind
        state = collection
        tabs.forEach { (tab, label) ->
            label.isSelected = tab == kind; label.setTextColor(if (tab == kind) ACCENT else MUTED)
            label.background = GradientDrawable().apply { setColor(if (tab == kind) 0xffe4efec.toInt() else Color.TRANSPARENT); cornerRadius = dp(6).toFloat() }
        }
        updating = true
        count.text = activity.getString(R.string.account_count, collection?.works?.size ?: 0)
        all.isChecked = collection != null && collection.works.isNotEmpty() && collection.selected.size == collection.works.size
        all.isEnabled = collection?.works?.isNotEmpty() == true
        adapter.submit(collection?.works.orEmpty())
        updating = false
        if (switched) grid.setSelection(0)
        val text = when {
            loading -> activity.getString(R.string.account_loading)
            error != null -> error.message.orEmpty()
            collection == null -> activity.getString(R.string.account_login)
            collection.stopped -> activity.getString(R.string.favorites_stopped)
            collection.completed && collection.works.isEmpty() -> activity.getString(R.string.account_empty)
            collection.completed && bilibili && kind == WorkCollectionKind.LIKED -> activity.getString(R.string.bilibili_recent_complete, collection.works.size)
            collection.completed -> activity.getString(R.string.account_complete, collection.works.size)
            else -> ""
        }
        pageStatus.text = if ((collection?.unavailable ?: 0) > 0)
            activity.getString(R.string.account_page_status, text, activity.getString(R.string.account_unavailable, collection!!.unavailable)).trim() else text
        pageStatus.visibility = if (pageStatus.text.isBlank()) View.GONE else View.VISIBLE
        more.visibility = if (loading || (collection?.completed == true && error == null)) View.GONE else View.VISIBLE
        more.setText(when {
            error?.code == ErrorCode.NEED_LOGIN || (collection == null && error == null) -> R.string.login
            collection?.stopped == true -> R.string.favorites_reload
            error != null -> R.string.retry
            else -> R.string.favorites_more
        })
        more.setOnClickListener {
            when {
                error?.code == ErrorCode.NEED_LOGIN || (collection == null && error == null) -> onLogin()
                collection?.stopped == true -> onReload()
                else -> onMore()
            }
        }
        actions.update(collection?.selected?.size ?: 0)
        actions.setEnabled(!busy, collection?.selected?.size ?: 0)
    }

    fun renderTask(task: UiTask?, otherBusy: Boolean, opening: Boolean) {
        val running = task?.stage?.isRunning() == true
        busy = otherBusy || running || opening
        outputStatus.text = when {
            opening -> activity.getString(R.string.account_opening)
            task != null -> task.detail
            otherBusy -> activity.getString(R.string.favorites_busy)
            else -> ""
        }
        outputStatus.visibility = if (outputStatus.text.isBlank()) View.GONE else View.VISIBLE
        pause.visibility = if (running) View.VISIBLE else View.GONE
        retry.visibility = if (!busy && task?.errorCode != ErrorCode.UNSUPPORTED.name && task?.stage in listOf(TaskStage.PARTIAL, TaskStage.FAILED, TaskStage.CANCELLED, TaskStage.INTERRUPTED)) View.VISIBLE else View.GONE
        share.visibility = if (!busy && task?.stage == TaskStage.PARTIAL && task.outputAction == OutputAction.SHARE && task.outputs.isNotEmpty()) View.VISIBLE else View.GONE
        commands.visibility = if (listOf(pause, retry, share).any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
        actions.setEnabled(!busy, state?.selected?.size ?: 0)
    }

    private inner class WorkAdapter : BaseAdapter() {
        private var works = emptyList<AccountWork>()
        fun submit(items: List<AccountWork>) { works = items; notifyDataSetChanged() }
        override fun getCount() = works.size
        override fun getItem(position: Int) = works[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val tile = recycled as? LinearLayout ?: column().apply {
                setBackgroundColor(Color.WHITE); layoutParams = AbsListView.LayoutParams(-1, dp(238))
                addView(FrameLayout(activity).apply {
                    addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
                    addView(label("", 11).apply { setTextColor(Color.WHITE); setBackgroundColor(0xb3000000.toInt()); setPadding(dp(5), dp(2), dp(5), dp(2)) }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
                    addView(CheckBox(activity).apply {
                        buttonTintList = ColorStateList.valueOf(ACCENT)
                        background = InsetDrawable(GradientDrawable().apply { setColor(0xeaffffff.toInt()); cornerRadius = dp(3).toFloat() }, dp(8))
                    }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
                }, LinearLayout.LayoutParams(-1, dp(168)))
                addView(label("", 13, true).apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), dp(6), dp(8), 0)
                }, LinearLayout.LayoutParams(-1, dp(46)))
                addView(label("", 11).apply { setTextColor(MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(8), 0) }, LinearLayout.LayoutParams(-1, dp(24)))
            }
            val work = works[position]
            val frame = tile.getChildAt(0) as FrameLayout
            val image = frame.getChildAt(0) as ImageView
            val binding = work.ref.id + work.covers.joinToString()
            if (tile.tag != binding || image.tag?.toString()?.startsWith("failed:") == true) {
                tile.tag = binding; image.setImageResource(if (work.imageCount > 0) R.drawable.ic_file_image else R.drawable.ic_file_video)
                work.cover()?.let { preview(image, it) }
            }
            image.contentDescription = activity.getString(R.string.account_open_work, position + 1)
            tile.setOnClickListener { onOpen(work) }
            image.setOnClickListener { onOpen(work) }
            (frame.getChildAt(1) as TextView).text = if (work.imageCount > 0) activity.getString(R.string.account_image_count, work.imageCount)
                else work.durationMs?.takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it / 1000) } ?: activity.getString(R.string.video_with_sound)
            (frame.getChildAt(2) as CheckBox).apply {
                setOnCheckedChangeListener(null)
                contentDescription = activity.getString(R.string.account_select_work, position + 1)
                isChecked = work.ref.id in state?.selected.orEmpty()
                setOnCheckedChangeListener { _, checked -> onSelect(work.ref.id, checked) }
            }
            (tile.getChildAt(1) as TextView).text = work.title.ifBlank { activity.getString(R.string.untitled_work) }
            (tile.getChildAt(2) as TextView).text = work.author
            return tile
        }
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(text: String, size: Int, bold: Boolean = false) = TextView(activity).apply {
        this.text = text; textSize = size.toFloat(); letterSpacing = 0f; setTextColor(INK)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    private fun icon(drawable: Int, label: Int, action: () -> Unit) = ImageButton(activity).apply {
        setImageResource(drawable); setColorFilter(ACCENT); setBackgroundColor(Color.TRANSPARENT)
        contentDescription = activity.getString(label); tooltipText = contentDescription; setOnClickListener { action() }
    }
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density + 0.5f).toInt()
    companion object {
        fun labelFor(kind: WorkCollectionKind) = if (kind == WorkCollectionKind.SAVED) R.string.account_saved_works else R.string.account_liked_works
        private val BACKGROUND = 0xfff7f8fa.toInt()
        private val INK = 0xff1e272b.toInt()
        private val MUTED = 0xff78838b.toInt()
        private val ACCENT = 0xff167c68.toInt()
    }
}
