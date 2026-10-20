package com.emojetools.app.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.emojetools.app.R
import com.emojetools.app.core.*

class PostSelection(
    private val activity: Activity, private val task: UiTask, private val media: PostMedia,
    private val preview: (ImageView, Asset) -> Unit, private val onPart: ((String) -> Unit)? = null,
    private val onLogin: (() -> Unit)? = null,
    private val onConfirm: (List<Asset>, OutputAction) -> Unit
) {
    private val videos = media.assets.filter { it.kind == AssetKind.VIDEO }
    private val images = media.assets.filter { it.kind == AssetKind.IMAGE }.distinctBy { it.id }
    private val music = media.assets.firstOrNull { it.meta["role"] == "background_music" }
    private val cover = media.assets.firstOrNull { it.kind == AssetKind.COVER }
    private val selectedImages = images.map { it.id }.filter { task.selectedIds.isEmpty() || it in task.selectedIds }.toMutableSet()
    private var videoIndex = videos.indexOfFirst { video -> video.id in task.selectedIds || media.assets.any {
        it.id in task.selectedIds && it.meta["derivedFrom"] == video.id
    } }.coerceAtLeast(0)
    private var audioOnly = media.assets.any { it.id in task.selectedIds && it.meta["role"] == "complete_audio" }
    private var includeMusic = music?.id in task.selectedIds
    private var includeCover = cover?.id in task.selectedIds
    private var musicOnly = music != null && task.selectedIds.isNotEmpty() && task.selectedIds.all { it == music.id }
    private var sourceOptions: View? = null
    private var musicOption: View? = null
    private var coverOption: View? = null
    private lateinit var actions: SelectionActions

    fun show(): Dialog {
        val dialog = Dialog(activity)
        val root = column().apply { setBackgroundColor(BACKGROUND); fitsSystemWindows = true }
        root.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(20), 0)
            addView(ImageButton(activity).apply {
                setImageResource(R.drawable.ic_x); setBackgroundColor(Color.TRANSPARENT)
                contentDescription = activity.getString(R.string.close); tooltipText = contentDescription
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(48), dp(56)))
            addView(label(activity.getString(R.string.work_content), 19, true))
        }, LinearLayout.LayoutParams(-1, dp(64)))
        val content = column().apply { setPadding(dp(24), 0, dp(24), dp(24)) }
        (cover ?: images.firstOrNull())?.let { asset ->
            val image = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE)
                contentDescription = activity.getString(R.string.media_preview)
            }
            content.addView(image, LinearLayout.LayoutParams(-1, dp(180)).apply { bottomMargin = dp(16) })
            preview(image, asset)
        }
        content.addView(label(media.post.title.ifBlank { activity.getString(R.string.untitled_work) }, 18, true))
        content.addView(label(media.post.author, 13).apply { setTextColor(MUTED); setPadding(0, dp(8), 0, dp(18)) })
        onLogin?.let { login ->
            content.addView(Button(activity).apply {
                setText(R.string.login_refresh_quality); isAllCaps = false; textSize = 14f; letterSpacing = 0f
                setOnClickListener { dialog.dismiss(); login() }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(12) })
        }
        if (media.post.parts.size > 1 && onPart != null) {
            val episode = media.post.ref.extra.containsKey("episode")
            content.addView(label(activity.getString(if (episode) R.string.video_episode else R.string.video_part), 14, true))
            val selected = media.post.parts.indexOfFirst { it.id == media.post.ref.extra["cid"] }.coerceAtLeast(0)
            content.addView(Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                    media.post.parts.mapIndexed { index, part -> if (episode) part.title else "P${index + 1} · ${part.title}" })
                setSelection(selected)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (position != selected) { dialog.dismiss(); onPart.invoke(media.post.parts[position].id) }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }, LinearLayout.LayoutParams(-1, dp(52)))
        }
        if (videos.isNotEmpty()) {
            content.addView(label(activity.getString(R.string.download_content), 14, true))
            val video = RadioButton(activity).apply { text = activity.getString(R.string.video_with_sound); id = View.generateViewId(); textSize = 14f }
            val audio = RadioButton(activity).apply { text = activity.getString(R.string.complete_soundtrack); id = View.generateViewId(); textSize = 14f }
            val bgm = music?.let { RadioButton(activity).apply {
                text = activity.getString(R.string.background_music_only); id = View.generateViewId(); textSize = 14f
            } }
            content.addView(RadioGroup(activity).apply {
                orientation = RadioGroup.VERTICAL
                listOfNotNull(video, audio, bgm).forEach { option ->
                    option.minHeight = dp(48); addView(option, RadioGroup.LayoutParams(-1, -2))
                }
                check(if (musicOnly) bgm!!.id else if (audioOnly) audio.id else video.id)
                setOnCheckedChangeListener { _, id -> audioOnly = id == audio.id; musicOnly = id == bgm?.id; updateConfirm() }
            })
            val sourceSettings = column()
            sourceOptions = sourceSettings
            content.addView(sourceSettings)
            sourceSettings.addView(label(activity.getString(R.string.video_quality), 13).apply { setPadding(0, dp(12), 0, dp(4)) })
            sourceSettings.addView(Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, videos.map { asset ->
                    val dimensions = if (asset.width != null && asset.height != null) "${asset.width} × ${asset.height}" else activity.getString(R.string.format_original)
                    val bitrate = asset.meta["bitrate"]?.toLongOrNull()?.div(1000) ?: 0
                    asset.meta["qualityLabel"] ?: if (bitrate > 0) "$dimensions · $bitrate kbps" else dimensions
                })
                setSelection(videoIndex)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { videoIndex = position; updateConfirm() }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }, LinearLayout.LayoutParams(-1, dp(52)))
        } else {
            content.addView(label(activity.getString(R.string.choose_original_images, images.size), 14, true).apply { setPadding(0, 0, 0, dp(12)) })
            images.forEachIndexed { index, asset ->
                content.addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
                    val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE) }
                    addView(image, LinearLayout.LayoutParams(dp(88), dp(104))); preview(image, asset)
                    val check = CheckBox(activity).apply {
                        text = activity.getString(R.string.original_image_number, index + 1)
                        textSize = 14f; isChecked = asset.id in selectedImages; setPadding(dp(12), 0, 0, 0)
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selectedImages += asset.id else selectedImages -= asset.id
                            updateConfirm()
                        }
                    }
                    addView(check, LinearLayout.LayoutParams(0, -2, 1f))
                    image.setOnClickListener { check.isChecked = !check.isChecked }
                })
            }
        }
        music?.let { music ->
            content.addView(CheckBox(activity).apply {
                musicOption = this
                text = activity.getString(R.string.background_music); textSize = 14f; isChecked = includeMusic; minHeight = dp(48)
                setOnCheckedChangeListener { _, checked -> includeMusic = checked; updateConfirm() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            music.meta["title"]?.takeIf { it.isNotBlank() }?.let { title ->
                content.addView(label(title, 12).apply { setTextColor(MUTED); setPadding(dp(32), 0, 0, dp(8)) })
            }
        }
        if (cover != null && videos.isNotEmpty()) content.addView(CheckBox(activity).apply {
            coverOption = this
            text = activity.getString(R.string.include_cover); textSize = 14f; isChecked = includeCover; minHeight = dp(48)
            setOnCheckedChangeListener { _, checked -> includeCover = checked; updateConfirm() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(ScrollView(activity).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        actions = SelectionActions(activity) { action ->
                val selected = selectedAssets()
                task.selectedIds.clear(); task.selectedIds.addAll(selected.map { it.id })
                dialog.dismiss(); onConfirm(selected, action)
        }
        root.addView(actions.root, LinearLayout.LayoutParams(-1, -2))
        updateConfirm()
        dialog.setContentView(root); dialog.window?.setBackgroundDrawable(ColorDrawable(BACKGROUND))
        dialog.show(); dialog.window?.setLayout(-1, -1)
        return dialog
    }

    private fun selectedAssets(): List<Asset> = buildList {
        if (videos.isNotEmpty() && !musicOnly) {
            val video = videos[videoIndex]
            add(if (audioOnly) media.assets.first { it.meta["derivedFrom"] == video.id } else video)
        } else if (videos.isEmpty()) addAll(images.filter { it.id in selectedImages })
        if ((includeMusic || musicOnly) && music != null) add(music)
        if (includeCover && cover != null && videos.isNotEmpty() && !musicOnly) add(cover)
    }
    private fun updateConfirm() {
        if (!::actions.isInitialized) return
        sourceOptions?.visibility = if (musicOnly) View.GONE else View.VISIBLE
        musicOption?.visibility = if (musicOnly) View.GONE else View.VISIBLE
        coverOption?.visibility = if (musicOnly) View.GONE else View.VISIBLE
        actions.update(selectedAssets().size)
    }
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun label(text: String, size: Int, bold: Boolean = false) = TextView(activity).apply {
        this.text = text; textSize = size.toFloat(); letterSpacing = 0f; setTextColor(0xff273237.toInt())
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density + 0.5f).toInt()
    companion object {
        private val BACKGROUND = 0xfff7f8fa.toInt()
        private val ACCENT = 0xff167c68.toInt()
        private val MUTED = 0xff78838b.toInt()
    }
}
