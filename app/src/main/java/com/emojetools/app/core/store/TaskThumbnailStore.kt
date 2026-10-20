package com.emojetools.app.core.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.emojetools.app.core.Asset
import com.emojetools.app.core.download.DownloadedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TaskThumbnailStore(private val context: Context, private val store: AssetStore,
    private val download: suspend (Asset, File, String) -> DownloadedFile) {
    private val root = File(context.filesDir, "task_previews")
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun ensure(id: Long, asset: Asset?, stored: StoredAsset?, cover: File?): File? = withContext(Dispatchers.IO) {
        locks.getOrPut(id) { Mutex() }.withLock {
            val source = asset?.id ?: stored?.assetId ?: cover?.absolutePath ?: return@withLock null
            val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            val directory = File(root, id.toString())
            val output = File(directory, "$hash.jpg")
            if (output.isFile && output.length() > 0) return@withLock output
            check(directory.isDirectory || directory.mkdirs())
            val staging = File(directory, "source-${UUID.randomUUID()}")
            val pending = File(directory, "$hash.part")
            try {
                val local = stored?.takeIf(store::available)
                val downloaded = if (cover == null && local == null && asset != null) download(asset, staging, "source") else null
                val uri = when {
                    cover != null -> Uri.fromFile(cover)
                    local != null -> local.uri ?: Uri.fromFile(local.file)
                    downloaded != null -> Uri.fromFile(downloaded.file)
                    else -> return@withLock null
                }
                val extension = cover?.extension ?: local?.file?.extension ?: downloaded!!.ext
                val bitmap = decode(uri, extension) ?: return@withLock null
                try { pending.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it)) } }
                finally { bitmap.recycle() }
                Files.move(pending.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                directory.listFiles()?.filter { it != output && it.extension == "jpg" }?.forEach { it.delete() }
                output
            } finally { staging.deleteRecursively(); pending.delete() }
        }
    }

    suspend fun remove(id: Long) = withContext(Dispatchers.IO) {
        locks.getOrPut(id) { Mutex() }.withLock { File(root, id.toString()).deleteRecursively() }
    }

    private fun decode(uri: Uri, extension: String): Bitmap? {
        if (extension.equals("mp4", true)) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val frame = if (Build.VERSION.SDK_INT >= 27)
                    retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
                else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                return frame?.let(::scale)
            } finally { retriever.release() }
        }
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val factor = minOf(1f, 160f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * factor).toInt()), maxOf(1, (info.size.height * factor).toInt()))
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 160) }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.let(::scale)
    }
    private fun scale(bitmap: Bitmap): Bitmap {
        val factor = minOf(1f, 160f / maxOf(bitmap.width, bitmap.height))
        if (factor == 1f) return bitmap
        val resized = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * factor).toInt()), maxOf(1, (bitmap.height * factor).toInt()), true)
        if (resized !== bitmap) bitmap.recycle()
        return resized
    }
}
