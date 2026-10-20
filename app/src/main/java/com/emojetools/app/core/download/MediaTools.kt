package com.emojetools.app.core.download

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.emojetools.app.core.AppFailure
import com.emojetools.app.core.ErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class MediaTracks(val video: Boolean, val audio: Boolean)

class MediaTools {
    fun validateImage(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val factor = minOf(1f, 600f / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(maxOf(1, (info.size.width * factor).toInt()), maxOf(1, (info.size.height * factor).toInt()))
                }
                if (drawable is BitmapDrawable) drawable.bitmap.recycle()
                if (drawable is AnimatedImageDrawable) drawable.stop()
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Image bounds are unreadable")
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                    inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 600)
                }) ?: throw IOException("Image pixels are unreadable")
                bitmap.recycle()
            }
        } catch (e: RuntimeException) { throw IOException("Image cannot be decoded on this device", e) }
    }

    fun tracks(file: File): MediaTracks {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
            return MediaTracks(types.any { it.startsWith("video/") }, types.any { it.startsWith("audio/") })
        } catch (e: Exception) { throw AppFailure(ErrorCode.UNSUPPORTED, "媒体内容无法识别", e) }
        finally { extractor.release() }
    }

    fun validateVideo(file: File) {
        val tracks = tracks(file)
        if (!tracks.video || !tracks.audio) throw AppFailure(ErrorCode.UNSUPPORTED, "平台提供的视频缺少画面或完整音轨")
    }

    fun validateAudio(file: File) {
        val tracks = tracks(file)
        if (!tracks.audio || tracks.video) throw AppFailure(ErrorCode.UNSUPPORTED, "平台未返回独立音频资源")
    }

    suspend fun muxTracks(video: File, audio: File, target: File): DownloadedFile = withContext(Dispatchers.IO) {
        val temporary = File(target.parentFile, target.name + ".part")
        val videoInput = MediaExtractor()
        val audioInput = MediaExtractor()
        var output: MediaMuxer? = null
        try {
            videoInput.setDataSource(video.absolutePath); audioInput.setDataSource(audio.absolutePath)
            fun track(extractor: MediaExtractor, prefix: String): Int = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
            } ?: throw AppFailure(ErrorCode.UNSUPPORTED, "音视频流缺少必要轨道")
            val videoTrack = track(videoInput, "video/"); val audioTrack = track(audioInput, "audio/")
            val muxer = MediaMuxer(temporary.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            output = muxer
            val videoFormat = videoInput.getTrackFormat(videoTrack)
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
            val outputs = listOf(muxer.addTrack(videoFormat), muxer.addTrack(audioInput.getTrackFormat(audioTrack)))
            muxer.start()
            for ((index, pair) in listOf(videoInput to videoTrack, audioInput to audioTrack).withIndex()) {
                val (extractor, inputTrack) = pair
                extractor.selectTrack(inputTrack)
                val format = extractor.getTrackFormat(inputTrack)
                val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                if (size > 16 * 1024 * 1024) throw AppFailure(ErrorCode.UNSUPPORTED, "采样块过大")
                val buffer = ByteBuffer.allocate(maxOf(1024 * 1024, size))
                val info = MediaCodec.BufferInfo()
                var packets = 0
                while (true) {
                    currentCoroutineContext().ensureActive(); buffer.clear()
                    val count = extractor.readSampleData(buffer, 0)
                    if (count < 0) break
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0)
                        throw AppFailure(ErrorCode.UNSUPPORTED, "不支持加密音视频")
                    info.set(0, count, maxOf(0, extractor.sampleTime),
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(outputs[index], buffer, info); packets++
                    if (!extractor.advance()) break
                }
                if (packets == 0) throw AppFailure(ErrorCode.UNSUPPORTED, "音视频流为空")
            }
            muxer.stop(); muxer.release(); output = null
            validateVideo(temporary); currentCoroutineContext().ensureActive()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            measure(target, "mp4")
        } finally {
            try { output?.release() } finally { videoInput.release(); audioInput.release(); temporary.delete() }
        }
    }

    suspend fun extractCompleteAudio(source: File, target: File): DownloadedFile = withContext(Dispatchers.IO) {
        val temporary = File(target.parentFile, target.name + ".part")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            currentCoroutineContext().ensureActive()
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AppFailure(ErrorCode.UNSUPPORTED, "这份视频没有可提取的音轨")
            val format = extractor.getTrackFormat(track)
            if (format.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm")
                throw AppFailure(ErrorCode.UNSUPPORTED, "当前音轨不支持无损导出为 M4A")
            extractor.selectTrack(track)
            val output = MediaMuxer(temporary.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = output
            val outputTrack = output.addTrack(format)
            output.start(); started = true
            val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            if (maxSize > 16 * 1024 * 1024) throw AppFailure(ErrorCode.UNSUPPORTED, "音频采样块过大")
            val buffer = ByteBuffer.allocate(maxOf(1024 * 1024, maxSize))
            val info = MediaCodec.BufferInfo()
            val firstTime = extractor.sampleTime
            var packets = 0
            // Copy every compressed sample from the original audio track, without decoding or source separation.
            while (true) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                    throw AppFailure(ErrorCode.UNSUPPORTED, "不支持加密音轨")
                val sampleFlags = extractor.sampleFlags
                var codecFlags = 0
                if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0)
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                info.set(0, size, extractor.sampleTime - firstTime, codecFlags)
                output.writeSampleData(outputTrack, buffer, info)
                packets++
                if (!extractor.advance()) break
            }
            if (packets == 0) throw AppFailure(ErrorCode.UNSUPPORTED, "音轨没有可用采样")
            output.stop(); started = false; output.release(); muxer = null
            validateAudio(temporary)
            currentCoroutineContext().ensureActive()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            measure(target, "m4a")
        } catch (e: CancellationException) { throw e }
        catch (e: AppFailure) { throw e }
        catch (e: Exception) { throw AppFailure(ErrorCode.UNSUPPORTED, "完整音频导出失败", e) }
        finally {
            if (started) try { muxer?.stop() } catch (_: IllegalStateException) { }
            muxer?.release(); extractor.release(); temporary.delete()
        }
    }

    companion object {
        fun measure(file: File, ext: String): DownloadedFile {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return DownloadedFile(file, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, file.length(), ext)
        }
    }
}
