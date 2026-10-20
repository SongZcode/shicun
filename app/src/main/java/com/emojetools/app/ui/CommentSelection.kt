package com.emojetools.app.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.emojetools.app.R
import com.emojetools.app.core.*
import java.lang.ref.WeakReference
import java.io.File

class CommentSelection(
    private val activity: Activity, private var task: UiTask, private var result: CommentResult,
    private val preview: (ImageView, Asset) -> Unit,
    private val onContinue: ((Set<String>) -> Unit)? = null, private val onStop: (() -> Unit)? = null,
    private var searching: Boolean = false,
    private val onSearch: (() -> Unit)? = null,
    private val loadPreview: (suspend (Asset) -> File)? = null,
    private val onConfirm: (List<Asset>, OutputAction) -> Unit
) {
    private val selected = task.selectedIds.toMutableSet()
    private val checks = mutableMapOf<String, MutableList<WeakReference<CheckBox>>>()
    private val rowIds = mutableMapOf<String, Long>()
    private var assets = result.assets.associateBy { it.id }
    private var matched = mediaComments(result)
    private var allowed = matched.flatMap { it.assetIds }.filter { it in assets }.toSet()
    private var viewer: Dialog? = null
    private var canContinue = false
    private lateinit var actions: SelectionActions
    private lateinit var scanText: TextView
    private lateinit var matchText: TextView
    private lateinit var filterText: TextView
    private lateinit var notice: TextView
    private lateinit var empty: TextView
    private lateinit var action: Button
    private lateinit var adapter: BaseAdapter
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density + 0.5f).toInt()
    private fun label(value: String, size: Int, color: Int = 0xff273237.toInt()) = TextView(activity).apply {
        text = value; textSize = size.toFloat(); letterSpacing = 0f; setTextColor(color)
    }

    fun show(): Dialog {
        selected.retainAll(allowed)
        val dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xfff7f8fa.toInt()); fitsSystemWindows = true
        }
        root.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(20), 0)
            addView(ImageButton(activity).apply {
                setImageResource(R.drawable.ic_x); setBackgroundColor(Color.TRANSPARENT)
                contentDescription = activity.getString(R.string.close); tooltipText = contentDescription
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(48), dp(56)))
            addView(label(activity.getString(R.string.choose_comment_assets), 19), LinearLayout.LayoutParams(0, -2, 1f))
            if (onSearch != null) addView(ImageButton(activity).apply {
                setImageResource(R.drawable.ic_search); setBackgroundColor(Color.TRANSPARENT)
                contentDescription = activity.getString(R.string.search_cached_comments); tooltipText = contentDescription
                setOnClickListener { onSearch.invoke() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
        }, LinearLayout.LayoutParams(-1, dp(64)))
        val header = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        filterText = label("", 13, 0xff78838b.toInt()).apply { maxLines = 2; setPadding(0, 0, 0, dp(12)) }
        header.addView(filterText)
        scanText = label("", 12, 0xff78838b.toInt()); header.addView(scanText)
        matchText = label("", 16).apply { setPadding(0, dp(12), 0, dp(16)) }; header.addView(matchText)
        notice = label("", 13, 0xff78838b.toInt()).apply { setPadding(0, 0, 0, dp(16)) }; header.addView(notice)
        empty = label("", 14).apply { setPadding(0, dp(24), 0, dp(24)) }; header.addView(empty)
        val list = ListView(activity).apply { divider = null; setPadding(dp(24), 0, dp(24), 0); clipToPadding = false }
        header.setPadding(0, 0, 0, dp(20))
        list.addHeaderView(header, null, false)
        adapter = object : BaseAdapter() {
            override fun getCount() = matched.size
            override fun getItem(position: Int) = matched[position]
            override fun hasStableIds() = true
            override fun getItemId(position: Int) = rowIds.getOrPut(matched[position].id) { rowIds.size.toLong() }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val comment = matched[position]
            val content = convertView as? LinearLayout ?: LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            // Retain loaded previews and running animations when the same comment is rebound.
            if (content.tag == comment) return content
            content.tag = comment
            content.removeAllViews()
            content.addView(label(comment.author.ifBlank { activity.getString(R.string.unnamed_author) } + " · " +
                activity.getString(if (comment.parentId == null) R.string.top_comment else R.string.reply_comment), 14))
            content.addView(label(comment.text.ifBlank { activity.getString(R.string.media_only_comment) }, 15).apply { setPadding(0, dp(8), 0, dp(4)) })
            val columns = if (activity.resources.configuration.screenWidthDp < 360) 2 else 3
            val grid = GridLayout(activity).apply { columnCount = columns }
            val mediaIds = comment.assetIds.filter { it in assets }
            mediaIds.forEachIndexed { index, id ->
                val asset = assets.getValue(id)
                val tile = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                val frame = object : FrameLayout(activity) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY))
                    }
                }.apply { setBackgroundColor(Color.WHITE) }
                val image = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE)
                    setImageResource(R.drawable.ic_file_image); contentDescription = activity.getString(R.string.media_preview)
                }
                frame.addView(image, FrameLayout.LayoutParams(-1, -1))
                if (asset.kind == AssetKind.VIDEO) frame.addView(ImageView(activity).apply {
                    setImageResource(R.drawable.ic_play); setColorFilter(0xff167c68.toInt())
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
                tile.addView(frame, LinearLayout.LayoutParams(-1, -2))
                val check = CheckBox(activity).apply {
                    text = activity.getString(when (asset.kind) {
                        AssetKind.STICKER -> R.string.sticker
                        AssetKind.VIDEO -> R.string.live_photo_motion
                        else -> R.string.original_image
                    })
                    textSize = 12f; letterSpacing = 0f; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END; isChecked = id in selected
                    buttonTintList = android.content.res.ColorStateList.valueOf(0xff167c68.toInt())
                    setOnCheckedChangeListener { _, checked -> select(id, checked) }
                }
                checks.getOrPut(id) { mutableListOf() }.apply { removeAll { it.get() == null }; add(WeakReference(check)) }
                tile.addView(check, LinearLayout.LayoutParams(-1, dp(48)))
                image.setOnClickListener {
                    loadPreview?.let { load ->
                        viewer?.dismiss()
                        viewer = CommentMediaViewer(activity, comment.assetIds.mapNotNull(assets::get), id, load,
                            { it in selected }, ::select).show()
                    }
                }
                grid.addView(tile, GridLayout.LayoutParams(GridLayout.spec(index / columns), GridLayout.spec(index % columns, 1f)).apply {
                    width = 0; height = -2; setMargins(dp(3), dp(8), dp(3), 0)
                })
                preview(image, asset)
            }
            val remainder = mediaIds.size % columns
            if (remainder > 0) for (column in remainder until columns) grid.addView(Space(activity),
                GridLayout.LayoutParams(GridLayout.spec(mediaIds.size / columns), GridLayout.spec(column, 1f)).apply {
                    width = 0; height = 0; setMargins(dp(3), 0, dp(3), 0)
                })
            content.addView(grid, LinearLayout.LayoutParams(-1, -2))
            content.addView(View(activity).apply { setBackgroundColor(0xffe2e7ea.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(16); bottomMargin = dp(20)
            })
            return content
            }
        }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        action = Button(activity).apply {
            setPadding(dp(16), 0, dp(16), 0); compoundDrawablePadding = dp(8)
            setOnClickListener { if (searching) onStop?.invoke() else onContinue?.invoke(selected.toSet()) }
        }
        actions = SelectionActions(activity, action) { action ->
                task.selectedIds.clear(); task.selectedIds.addAll(selected)
                val chosen = selected.map { id ->
                    val owner = matched.first { id in it.assetIds }
                    assets.getValue(id).copy(meta = assets.getValue(id).meta + mapOf("commentId" to owner.id,
                        "level" to if (owner.parentId == null) "T" else "R"))
                }
                dialog.dismiss(); onConfirm(chosen, action)
        }
        root.addView(actions.root, LinearLayout.LayoutParams(-1, -2))
        dialog.setOnDismissListener { closePreview() }
        update(result, searching)
        dialog.setContentView(root)
        dialog.window?.setLayout(-1, -1)
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        return dialog
    }

    fun update(value: CommentResult, isSearching: Boolean) {
        val nextMatched = mediaComments(value)
        val rowsChanged = matched != nextMatched
        result = value; searching = isSearching
        assets = value.assets.associateBy { it.id }; matched = nextMatched
        allowed = matched.flatMap { it.assetIds }.filter { it in assets }.toSet(); selected.retainAll(allowed)
        if (!::actions.isInitialized) return
        filterText.text = if (task.targetType() == CommentTarget.MINE) activity.getString(task.targetKind)
            else activity.getString(R.string.comment_filter_summary, activity.getString(task.targetKind), task.target)
        scanText.text = activity.getString(R.string.comments_scanned, value.fetchedTopLevel, value.fetchedReplies)
        matchText.text = if (task.retainedCommentIds.isEmpty()) activity.getString(R.string.comments_matched, matched.size, allowed.size)
            else activity.getString(R.string.comments_accumulated,
                value.matching(task.targetType(), task.target, task.accountIds).count { row -> row.assetIds.any { id -> id in assets } }, allowed.size)
        val incomplete = value.topTermination != "server_end" || value.replyTermination != "server_end"
        notice.visibility = if (searching || task.cacheUsed || incomplete) View.VISIBLE else View.GONE
        notice.setText(when { searching -> R.string.task_fetching; task.cacheUsed -> R.string.comment_cached; else -> R.string.comment_partial })
        empty.visibility = if (matched.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(if (searching) R.string.comment_media_not_found_yet else R.string.comment_media_not_found)
        action.setText(if (searching) R.string.stop_comment_search else R.string.continue_comment_search)
        val icon = activity.getDrawable(if (searching) R.drawable.ic_pause else R.drawable.ic_play)!!.mutate().apply {
            setTint(0xff167c68.toInt()); setBounds(0, 0, dp(18), dp(18))
        }
        action.setCompoundDrawablesRelative(icon, null, null, null)
        action.contentDescription = action.text
        action.tooltipText = action.contentDescription
        action.visibility = if ((searching && onStop != null) || (!searching && canContinue && onContinue != null)) View.VISIBLE else View.GONE
        checks.entries.removeAll { (_, refs) -> refs.removeAll { it.get() == null }; refs.isEmpty() }
        checks.forEach { (id, refs) -> refs.mapNotNull { it.get() }.forEach { it.isChecked = id in selected } }
        if (rowsChanged) adapter.notifyDataSetChanged()
        updateConfirm()
    }

    fun retarget(value: UiTask) {
        task = value
        selected.addAll(value.selectedIds)
        update(result, searching)
    }

    fun selectionIds(): Set<String> = selected.toSet()

    fun closePreview() { viewer?.dismiss(); viewer = null }

    private fun mediaComments(value: CommentResult): List<CommentCandidate> {
        val ids = value.assets.map { it.id }.toSet()
        return task.matching(value).filter { row -> row.assetIds.any { it in ids } }
    }

    private fun select(id: String, checked: Boolean) {
        if (id !in allowed) return
        if (checked) selected += id else selected -= id
        checks[id]?.mapNotNull { it.get() }?.filter { it.isChecked != checked }?.forEach { it.isChecked = checked }
        updateConfirm()
    }

    fun updateSearchState(isSearching: Boolean, resumable: Boolean) {
        canContinue = resumable
        update(result, isSearching)
    }

    private fun updateConfirm() {
        actions.update(selected.size)
    }
}
