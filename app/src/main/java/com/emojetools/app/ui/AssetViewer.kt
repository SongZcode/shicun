package com.emojetools.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.view.Gravity
import android.widget.*
import com.emojetools.app.R
import com.emojetools.app.core.AssetKind
import com.emojetools.app.core.store.StoredAsset
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

class AssetViewer(
    private val activity: Activity, private val asset: StoredAsset,
    private val preview: (ImageView, File) -> Unit,
    private val share: () -> Unit, private val save: () -> Unit
) {
    fun show(): Dialog {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var player: MediaPlayer? = null
        var video: VideoView? = null
        var videoController: MediaController? = null
        val handler = Handler(Looper.getMainLooper())
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(12)) }
        when (asset.kind) {
            AssetKind.VIDEO -> {
                val view = VideoView(activity)
                video = view
                val controller = MediaController(activity)
                videoController = controller
                controller.setAnchorView(view)
                view.setMediaController(controller)
                view.setVideoURI(asset.uri ?: Uri.fromFile(asset.file))
                view.setOnPreparedListener { view.start(); controller.show(3000) }
                view.setOnErrorListener { _, _, _ -> toastError(); true }
                body.addView(FrameLayout(activity).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                }, LinearLayout.LayoutParams(-1, dp(240)))
            }
            AssetKind.AUDIO -> {
                asset.previewFile?.takeIf { it.isFile }?.let { file ->
                    val image = ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = activity.getString(R.string.media_preview)
                    }
                    body.addView(image, LinearLayout.LayoutParams(-1, dp(180)).apply { bottomMargin = dp(12) })
                    preview(image, file)
                }
                val audio = MediaPlayer()
                player = audio
                val play = ImageButton(activity).apply {
                    setImageResource(R.drawable.ic_play); setColorFilter(0xff167c68.toInt()); setBackgroundColor(Color.TRANSPARENT)
                    contentDescription = activity.getString(R.string.play_audio); tooltipText = contentDescription; isEnabled = false
                }
                val seek = SeekBar(activity).apply { isEnabled = false }
                val clock = TextView(activity).apply {
                    gravity = Gravity.END; textSize = 12f
                    text = activity.getString(R.string.audio_playback_time, time(0), time(0))
                }
                body.addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(play, LinearLayout.LayoutParams(dp(48), dp(48)))
                    addView(seek, LinearLayout.LayoutParams(0, dp(48), 1f))
                })
                body.addView(clock)
                fun update() {
                    play.setImageResource(if (audio.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                    play.contentDescription = activity.getString(if (audio.isPlaying) R.string.pause_audio else R.string.play_audio)
                    play.tooltipText = play.contentDescription
                    seek.progress = audio.currentPosition
                    clock.text = activity.getString(R.string.audio_playback_time, time(audio.currentPosition), time(audio.duration))
                }
                play.setOnClickListener { if (audio.isPlaying) audio.pause() else audio.start(); update() }
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(view: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) audio.seekTo(progress) }
                    override fun onStartTrackingTouch(view: SeekBar?) = Unit
                    override fun onStopTrackingTouch(view: SeekBar?) = Unit
                })
                audio.setOnPreparedListener {
                    play.isEnabled = true; seek.isEnabled = true; seek.max = audio.duration
                    val ticker = object : Runnable {
                        override fun run() { update(); handler.postDelayed(this, 300) }
                    }
                    ticker.run()
                }
                audio.setOnCompletionListener { update() }
                audio.setOnErrorListener { _, _, _ ->
                    handler.removeCallbacksAndMessages(null); play.isEnabled = false; seek.isEnabled = false; toastError(); true
                }
                try { audio.setDataSource(activity, asset.uri ?: Uri.fromFile(asset.file)); audio.prepareAsync() }
                catch (_: Exception) { audio.release(); player = null; toastError() }
            }
            else -> {
                val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                body.addView(image, LinearLayout.LayoutParams(-1, dp(260)))
                if (asset.uri == null) preview(image, asset.file)
                else scope.launch {
                    try { MediaPreview.load(image, asset.uri, asset.file.extension) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { image.setImageResource(R.drawable.ic_file_image) }
                }
            }
        }
        return AlertDialog.Builder(activity).setTitle(asset.file.name).setView(body)
            .setPositiveButton(R.string.share_asset) { _, _ -> share() }
            .setNeutralButton(R.string.save_selected) { _, _ -> save() }
            .setNegativeButton(R.string.close, null).create().apply {
                setOnDismissListener { scope.cancel(); handler.removeCallbacksAndMessages(null); player?.release(); videoController?.hide(); video?.stopPlayback() }
                show()
            }
    }
    private fun toastError() { Toast.makeText(activity, R.string.media_playback_failed, Toast.LENGTH_LONG).show() }
    private fun time(ms: Int) = "%02d:%02d".format(Locale.ROOT, ms / 60000, (ms / 1000) % 60)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
