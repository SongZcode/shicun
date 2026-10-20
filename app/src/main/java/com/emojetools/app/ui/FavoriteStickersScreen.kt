package com.emojetools.app.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.emojetools.app.R
import com.emojetools.app.core.*
import com.emojetools.app.core.store.FavoriteCollection

class FavoriteStickersScreen(
    private val activity: Activity, private val preview: (ImageView, Asset, Boolean, () -> Unit) -> Unit,
    private val onMore: () -> Unit, private val onReload: () -> Unit, private val onLogin: () -> Unit,
    private val onSelect: (String, Boolean) -> Unit, private val onSelectAll: (Boolean) -> Unit,
    private val onOutput: (OutputAction, Set<String>?) -> Unit, private val onRetryOutput: () -> Unit,
    private val onShareReady: () -> Unit
) {
    private var collection: FavoriteCollection? = null
    private var updating = false
    private var busy = false
    private var previewDialog: Dialog? = null
    private var previewCheck: CheckBox? = null
    private var previewId: String? = null
    private var viewerActions: SelectionActions? = null
    val root = column().apply { fitsSystemWindows = true; setBackgroundColor(BACKGROUND) }
    private val count = label("", 13)
    private val selectAll = CheckBox(activity).apply {
        setText(R.string.favorites_select_all); textSize = 13f; letterSpacing = 0f
        buttonTintList = ColorStateList.valueOf(ACCENT)
        setOnCheckedChangeListener { _, checked -> if (!updating) onSelectAll(checked) }
    }
    private val status = label("", 14).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(6)) }
    private val pageAction = Button(activity).apply { isAllCaps = false; textSize = 14f; letterSpacing = 0f }
    private val outputStatus = label("", 13).apply { setPadding(dp(20), dp(4), dp(20), dp(4)); gravity = Gravity.CENTER }
    private val retryOutput = Button(activity).apply { setText(R.string.retry); setOnClickListener { onRetryOutput() } }
    private val shareReady = Button(activity).apply { setText(R.string.favorites_ready_share); setOnClickListener { onShareReady() } }
    private val outputCommands = row().apply {
        addView(retryOutput, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(shareReady, LinearLayout.LayoutParams(0, dp(48), 1f))
    }
    private val actions = SelectionActions(activity) { onOutput(it, null) }
    private val adapter = StickerAdapter()
    val grid = GridView(activity).apply {
        numColumns = GridView.AUTO_FIT; columnWidth = dp(104); stretchMode = GridView.STRETCH_COLUMN_WIDTH
        horizontalSpacing = dp(8); verticalSpacing = dp(8); setPadding(dp(16), dp(8), dp(16), dp(8)); clipToPadding = false
        isVerticalScrollBarEnabled = false; adapter = this@FavoriteStickersScreen.adapter
    }

    init {
        root.addView(row().apply {
            setPadding(dp(8), 0, dp(12), 0)
            addView(icon(R.drawable.ic_chevron_right, R.string.close) { activity.finish() }.apply { rotation = 180f }, LinearLayout.LayoutParams(dp(48), dp(56)))
            addView(label(activity.getString(R.string.favorite_stickers), 19, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(icon(R.drawable.ic_rotate_ccw, R.string.favorites_reload) { onReload() }, LinearLayout.LayoutParams(dp(48), dp(56)))
        }, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(row().apply {
            setPadding(dp(20), 0, dp(12), 0)
            addView(count, LinearLayout.LayoutParams(0, -2, 1f))
            addView(selectAll, LinearLayout.LayoutParams(-2, dp(48)))
        })
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status)
        root.addView(pageAction, LinearLayout.LayoutParams(-1, dp(48)).apply { leftMargin = dp(20); rightMargin = dp(20) })
        root.addView(outputStatus)
        root.addView(outputCommands)
        root.addView(actions.root)
        render(null, false)
        renderTask(null, false)
    }

    fun render(state: FavoriteCollection?, loading: Boolean, error: AppFailure? = null) {
        val changedSession = collection?.sessionTag != state?.sessionTag
        collection = state
        if (changedSession) previewDialog?.dismiss()
        updating = true
        count.text = activity.getString(R.string.favorites_count, state?.assets?.size ?: 0)
        selectAll.isChecked = state != null && state.assets.isNotEmpty() && state.selected.size == state.assets.size
        selectAll.isEnabled = state?.assets?.isNotEmpty() == true
        adapter.submit(state?.assets.orEmpty())
        previewCheck?.isChecked = previewId in state?.selected.orEmpty()
        updating = false
        actions.update(state?.selected?.size ?: 0); actions.setEnabled(!busy, state?.selected?.size ?: 0)
        status.text = when {
            loading -> activity.getString(R.string.favorites_loading)
            error != null -> error.message
            state == null -> activity.getString(R.string.favorites_login)
            state.stopped -> activity.getString(R.string.favorites_stopped)
            state.completed && state.assets.isEmpty() -> activity.getString(R.string.favorites_empty)
            state.completed -> activity.getString(R.string.favorites_complete, state.assets.size)
            else -> ""
        }
        status.visibility = if (status.text.isBlank()) View.GONE else View.VISIBLE
        pageAction.visibility = if (loading || (state?.completed == true && error == null)) View.GONE else View.VISIBLE
        pageAction.setText(when {
            error?.code == ErrorCode.NEED_LOGIN || (state == null && error == null) -> R.string.login
            state == null -> R.string.retry
            state.stopped -> R.string.favorites_reload
            error != null -> R.string.retry
            else -> R.string.favorites_more
        })
        pageAction.setOnClickListener {
            when {
                error?.code == ErrorCode.NEED_LOGIN || (state == null && error == null) -> onLogin()
                state == null -> onMore()
                state.stopped -> onReload()
                else -> onMore()
            }
        }
    }

    fun renderTask(task: UiTask?, otherBusy: Boolean) {
        busy = otherBusy || task?.stage?.isRunning() == true
        outputStatus.text = task?.detail.orEmpty().ifBlank { if (busy) activity.getString(R.string.favorites_busy) else "" }
        outputStatus.visibility = if (outputStatus.text.isBlank()) View.GONE else View.VISIBLE
        retryOutput.visibility = if (!busy && task?.errorCode != ErrorCode.UNSUPPORTED.name && task?.stage in listOf(TaskStage.FAILED, TaskStage.PARTIAL, TaskStage.CANCELLED, TaskStage.INTERRUPTED)) View.VISIBLE else View.GONE
        shareReady.visibility = if (!busy && task?.stage != TaskStage.DONE && task?.outputAction == OutputAction.SHARE && task.outputs.isNotEmpty()) View.VISIBLE else View.GONE
        outputCommands.visibility = if (retryOutput.visibility == View.VISIBLE || shareReady.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        actions.setEnabled(!busy, collection?.selected?.size ?: 0)
        viewerActions?.setEnabled(!busy, 1)
    }

    fun close() { previewDialog?.dismiss() }

    private inner class StickerAdapter : BaseAdapter() {
        private var items = emptyList<Asset>()
        fun submit(assets: List<Asset>) { items = assets; notifyDataSetChanged() }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val tile = recycled as? FrameLayout ?: FrameLayout(activity).apply {
                setBackgroundColor(Color.WHITE)
                layoutParams = AbsListView.LayoutParams(-1, dp(120))
                addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(10), dp(10), dp(10), dp(10)) }, FrameLayout.LayoutParams(-1, -1))
                addView(label(activity.getString(R.string.favorites_motion), 11).apply {
                    setBackgroundColor(0xe6ffffff.toInt()); setPadding(dp(5), dp(2), dp(5), dp(2))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
                addView(CheckBox(activity).apply {
                    buttonTintList = ColorStateList.valueOf(ACCENT)
                    background = InsetDrawable(GradientDrawable().apply { setColor(0xeaffffff.toInt()); cornerRadius = dp(3).toFloat() }, dp(8))
                }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
            }
            val asset = items[position]
            val image = tile.getChildAt(0) as ImageView
            if (tile.tag != asset.id) {
                tile.tag = asset.id; image.setImageDrawable(null)
                preview(image, asset, true) {}
            }
            image.contentDescription = activity.getString(R.string.favorites_preview_item, position + 1)
            image.setOnClickListener { showPreview(asset.id) }
            tile.getChildAt(1).visibility = if (asset.meta["animated"] == "true") View.VISIBLE else View.GONE
            (tile.getChildAt(2) as CheckBox).apply {
                setOnCheckedChangeListener(null)
                contentDescription = activity.getString(R.string.favorites_select_item, position + 1)
                isChecked = asset.id in collection?.selected.orEmpty()
                setOnCheckedChangeListener { _, checked -> onSelect(asset.id, checked) }
            }
            return tile
        }
    }

    private fun showPreview(id: String) {
        previewDialog?.dismiss()
        var index = collection?.assets?.indexOfFirst { it.id == id } ?: return
        if (index < 0) return
        val dialog = Dialog(activity)
        previewDialog = dialog
        val content = column().apply { fitsSystemWindows = true; setBackgroundColor(BACKGROUND) }
        val title = label("", 16, true)
        content.addView(row().apply {
            addView(icon(R.drawable.ic_x, R.string.close) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, dp(64)))
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        content.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        val retry = Button(activity).apply { setText(R.string.retry); visibility = View.GONE }
        content.addView(retry, LinearLayout.LayoutParams(-1, dp(48)))
        val check = CheckBox(activity).apply {
            setText(R.string.favorites_selected_item); textSize = 14f; buttonTintList = ColorStateList.valueOf(ACCENT)
        }
        previewCheck = check
        val previous = icon(R.drawable.ic_chevron_right, R.string.favorites_previous) {}.apply { rotation = 180f }
        val next = icon(R.drawable.ic_chevron_right, R.string.favorites_next) {}
        content.addView(row().apply {
            setPadding(dp(16), 0, dp(16), 0)
            addView(previous, LinearLayout.LayoutParams(dp(48), dp(56)))
            addView(check, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(next, LinearLayout.LayoutParams(dp(48), dp(56)))
        })
        val itemActions = SelectionActions(activity) { action ->
            collection?.assets?.getOrNull(index)?.let { asset -> dialog.dismiss(); onOutput(action, setOf(asset.id)) }
        }
        viewerActions = itemActions; itemActions.update(1); itemActions.setEnabled(!busy, 1)
        content.addView(itemActions.root)
        fun bind() {
            val assets = collection?.assets.orEmpty()
            val asset = assets.getOrNull(index) ?: return
            previewId = asset.id
            if (Build.VERSION.SDK_INT >= 28) (image.drawable as? AnimatedImageDrawable)?.stop()
            image.setImageDrawable(null); retry.visibility = View.GONE
            title.text = activity.getString(R.string.favorites_position, index + 1, assets.size)
            image.contentDescription = activity.getString(R.string.favorites_preview_item, index + 1)
            check.setOnCheckedChangeListener(null); check.isChecked = asset.id in collection?.selected.orEmpty()
            check.setOnCheckedChangeListener { _, checked -> if (!updating) onSelect(asset.id, checked) }
            previous.isEnabled = index > 0; next.isEnabled = index + 1 < assets.size
            previous.alpha = if (previous.isEnabled) 1f else 0.35f; next.alpha = if (next.isEnabled) 1f else 0.35f
            preview(image, asset, false) { image.contentDescription = activity.getString(R.string.favorites_preview_failed); retry.visibility = View.VISIBLE }
        }
        previous.setOnClickListener { index--; bind() }; next.setOnClickListener { index++; bind() }; retry.setOnClickListener { bind() }
        bind()
        dialog.setOnDismissListener {
            image.tag = null
            if (Build.VERSION.SDK_INT >= 28) (image.drawable as? AnimatedImageDrawable)?.stop()
            if (previewDialog === dialog) { previewDialog = null; previewCheck = null; previewId = null; viewerActions = null }
        }
        dialog.setContentView(content); dialog.window?.setBackgroundDrawable(ColorDrawable(BACKGROUND))
        dialog.show(); dialog.window?.setLayout(-1, -1)
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(value: String, size: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size.toFloat(); letterSpacing = 0f; setTextColor(INK)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    private fun icon(drawable: Int, label: Int, action: () -> Unit) = ImageButton(activity).apply {
        setImageResource(drawable); setColorFilter(ACCENT); setBackgroundColor(Color.TRANSPARENT)
        contentDescription = activity.getString(label); tooltipText = contentDescription; setOnClickListener { action() }
    }
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density + 0.5f).toInt()
    companion object {
        private val BACKGROUND = 0xfff7f8fa.toInt()
        private val INK = 0xff1e272b.toInt()
        private val ACCENT = 0xff167c68.toInt()
    }
}
