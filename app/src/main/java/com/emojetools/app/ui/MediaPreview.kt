package com.emojetools.app.ui

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.net.Uri
import android.widget.ImageView
import android.media.MediaMetadataRetriever
import com.emojetools.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaPreview {
    suspend fun load(view: ImageView, file: File) = load(view, Uri.fromFile(file), file.extension)

    suspend fun load(view: ImageView, uri: Uri, extension: String, maxDimension: Int = 600) {
        if (extension.lowercase() in listOf("m4a", "mp3")) {
            view.setImageResource(R.drawable.ic_headphones); view.setColorFilter(0xff167c68.toInt()); return
        }
        val drawable = withContext(Dispatchers.IO) {
            if (extension.lowercase() == "mp4") {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(view.context, uri)
                    val bitmap = if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 600, 600)
                        else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    bitmap?.let { BitmapDrawable(view.resources, it) }
                } finally { retriever.release() }
            } else if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeDrawable(ImageDecoder.createSource(view.context.contentResolver, uri)) { decoder, info, _ ->
                val factor = minOf(1f, maxDimension.toFloat() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * factor).toInt()), maxOf(1, (info.size.height * factor).toInt()))
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                view.context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxDimension) }
                view.context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.let { BitmapDrawable(view.resources, it) }
            }
        }
        view.clearColorFilter()
        view.setImageDrawable(drawable)
        if (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable) drawable.start()
    }
}
