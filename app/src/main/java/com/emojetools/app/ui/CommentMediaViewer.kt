package com.emojetools.app.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.*
import com.emojetools.app.R
import com.emojetools.app.core.Asset
import com.emojetools.app.core.AssetKind
import kotlinx.coroutines.*
import java.io.File

class CommentMediaViewer(
    private val activity: Activity, private val assets: List<Asset>, initialId: String,
    private val load: suspend (Asset) -> File, private val selected: (String) -> Boolean,
    private val onSelect: (String, Boolean) -> Unit
) {
    private var index = assets.indexOfFirst { it.id == initialId }.also { require(it >= 0) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loading: Job? = null
    private var controller: MediaController? = null
    private val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val video = VideoView(activity)
    private val progress = ProgressBar(activity)
    private val error = TextView(activity).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xff78838b.toInt()) }
    private val retry = Button(activity).apply { setText(R.string.retry); isAllCaps = false; setOnClickListener { bind() } }
    private val title = TextView(activity).apply { textSize = 16f; letterSpacing = 0f; setTextColor(0xff273237.toInt()) }
    private val check = CheckBox(activity).apply {
        setText(R.string.comment_select_asset); textSize = 14f; letterSpacing = 0f
        buttonTintList = ColorStateList.valueOf(0xff167c68.toInt())
    }
    private val previous = icon(R.drawable.ic_chevron_right, R.string.favorites_previous) { index--; bind() }.apply { rotation = 180f }
    private val next = icon(R.drawable.ic_chevron_right, R.string.favorites_next) { index++; bind() }

    fun show(): Dialog {
        val dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(0xfff7f8fa.toInt())
        }
        root.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_x, R.string.close) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(FrameLayout(activity).apply {
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(progress, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(error, LinearLayout.LayoutParams(-1, -2))
                addView(retry, LinearLayout.LayoutParams(dp(112), dp(48)))
            }, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16))
            addView(previous, LinearLayout.LayoutParams(dp(48), dp(56)))
            addView(check, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(next, LinearLayout.LayoutParams(dp(48), dp(56)))
        })
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0xfff7f8fa.toInt()))
        dialog.setOnDismissListener { scope.cancel(); stopMedia() }
        dialog.show(); dialog.window?.setLayout(-1, -1)
        bind()
        return dialog
    }

    private fun bind() {
        loading?.cancel(); stopMedia()
        val asset = assets[index]
        title.text = activity.getString(R.string.comment_preview_position, index + 1, assets.size)
        image.contentDescription = title.text; video.contentDescription = activity.getString(R.string.live_photo_motion)
        check.setOnCheckedChangeListener(null); check.isChecked = selected(asset.id)
        check.setOnCheckedChangeListener { _, checked -> onSelect(asset.id, checked) }
        previous.isEnabled = index > 0; next.isEnabled = index < assets.lastIndex
        previous.alpha = if (previous.isEnabled) 1f else 0.35f; next.alpha = if (next.isEnabled) 1f else 0.35f
        image.visibility = View.GONE; video.visibility = View.GONE
        progress.visibility = View.VISIBLE; error.visibility = View.GONE; retry.visibility = View.GONE
        loading = scope.launch {
            try {
                val file = load(asset)
                if (asset.kind == AssetKind.VIDEO) {
                    video.visibility = View.VISIBLE
                    val controls = MediaController(activity)
                    controller = controls; controls.setAnchorView(video); video.setMediaController(controls)
                    video.setOnPreparedListener { player ->
                        progress.visibility = View.GONE; player.isLooping = true; video.start(); controls.show(2500)
                    }
                    video.setOnErrorListener { _, _, _ -> showError(); true }
                    video.setVideoURI(Uri.fromFile(file))
                } else {
                    MediaPreview.load(image, Uri.fromFile(file), file.extension, maxDimension = 1600)
                    if (image.drawable == null) throw java.io.IOException("Unreadable image")
                    progress.visibility = View.GONE; image.visibility = View.VISIBLE
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { showError() }
        }
    }

    private fun showError() {
        progress.visibility = View.GONE; video.visibility = View.GONE
        error.setText(R.string.favorites_preview_failed); error.visibility = View.VISIBLE; retry.visibility = View.VISIBLE
    }

    private fun stopMedia() {
        controller?.hide(); controller = null
        video.setOnPreparedListener(null); video.setOnErrorListener(null); video.stopPlayback()
        if (Build.VERSION.SDK_INT >= 28) (image.drawable as? AnimatedImageDrawable)?.stop()
        image.setImageDrawable(null)
    }

    private fun icon(drawable: Int, label: Int, action: () -> Unit) = ImageButton(activity).apply {
        setImageResource(drawable); setColorFilter(0xff167c68.toInt()); setBackgroundColor(Color.TRANSPARENT)
        contentDescription = activity.getString(label); tooltipText = contentDescription; setOnClickListener { action() }
    }
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density + 0.5f).toInt()
}
