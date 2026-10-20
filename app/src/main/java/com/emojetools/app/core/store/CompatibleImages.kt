package com.emojetools.app.core.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import com.emojetools.app.core.AppFailure
import com.emojetools.app.core.AssetKind
import com.emojetools.app.core.ErrorCode
import com.emojetools.app.core.download.MediaTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object CompatibleImages {
    private const val MAX_PIXELS = 16_000_000L
    fun candidate(asset: StoredAsset) = asset.kind !in setOf(AssetKind.VIDEO, AssetKind.AUDIO) &&
        asset.file.extension.lowercase() in setOf("webp", "heic", "avif")

    suspend fun prepare(context: Context, assets: List<StoredAsset>,
        directory: File = File(context.cacheDir, "materials/.compatible")): List<StoredAsset> = withContext(Dispatchers.IO) {
        val created = mutableListOf<File>()
        try {
            assets.map { asset ->
                currentCoroutineContext().ensureActive()
                if (!candidate(asset)) asset else convert(context, asset, directory)?.also { created += it.file } ?: asset
            }
        } catch (e: Exception) { created.forEach(File::delete); throw e }
    }

    private suspend fun convert(context: Context, asset: StoredAsset, directory: File): StoredAsset? {
        check(directory.isDirectory || directory.mkdirs())
        val name = "image-${UUID.randomUUID()}"
        val input = File(directory, "$name.source")
        var target: File? = null
        var bitmap: Bitmap? = null
        try {
            val source = asset.uri?.let { context.contentResolver.openInputStream(it) } ?: asset.file.inputStream()
            source.use { stream -> input.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer); if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } }
            if (asset.file.extension.equals("webp", true) && animatedWebp(input)) return null
            val header = input.inputStream().use { stream -> ByteArray(12).also { stream.read(it) } }
            if (String(header, 8, 4, Charsets.US_ASCII) in setOf("msf1", "avis")) return null
            bitmap = if (Build.VERSION.SDK_INT >= 28) {
                val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(input)) { decoder, info, _ ->
                    checkSize(info.size.width, info.size.height)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                if (drawable is AnimatedImageDrawable) { drawable.stop(); return null }
                (drawable as? BitmapDrawable)?.bitmap ?: throw AppFailure(ErrorCode.UNSUPPORTED, "图片无法转换，请选择原格式分享")
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(input.path, bounds)
                checkSize(bounds.outWidth, bounds.outHeight)
                BitmapFactory.decodeFile(input.path) ?: throw AppFailure(ErrorCode.UNSUPPORTED, "设备无法转换此图片，请选择原格式分享")
            }
            currentCoroutineContext().ensureActive()
            val alpha = bitmap.hasAlpha()
            val extension = if (alpha) "png" else "jpg"
            target = File(directory, "$name.$extension")
            target.outputStream().use { output ->
                check(bitmap.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, output))
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            val measured = MediaTools.measure(target, extension)
            return asset.copy(file = target, uri = null, bytes = measured.bytes, sha1 = measured.sha1)
        } catch (e: CancellationException) { target?.delete(); throw e }
        catch (e: OutOfMemoryError) { target?.delete(); throw AppFailure(ErrorCode.UNSUPPORTED, "图片较大，请选择原格式分享", e) }
        catch (e: Exception) {
            target?.delete()
            throw if (e is AppFailure) e else AppFailure(ErrorCode.UNSUPPORTED, "图片转换失败，请选择原格式分享", e)
        } finally { bitmap?.recycle(); input.delete() }
    }

    private fun checkSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS)
            throw AppFailure(ErrorCode.UNSUPPORTED, "图片尺寸不适合转换，请选择原格式分享")
    }

    private fun animatedWebp(file: File): Boolean = RandomAccessFile(file, "r").use { input ->
        val header = ByteArray(12); input.readFully(header)
        require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP")
        val length = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        val end = minOf(input.length(), length + 8)
        while (input.filePointer + 8 <= end) {
            val chunk = ByteArray(8); input.readFully(chunk)
            val tag = String(chunk, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            val next = input.filePointer + size + (size and 1)
            require(next <= end)
            if (tag == "ANIM" || (tag == "VP8X" && size > 0 && input.readUnsignedByte() and 2 != 0)) return@use true
            input.seek(next)
        }
        false
    }
}
