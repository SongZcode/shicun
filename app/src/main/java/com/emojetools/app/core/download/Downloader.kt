package com.emojetools.app.core.download

import com.emojetools.app.core.*
import com.emojetools.app.core.net.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

data class DownloadedFile(val file: File, val sha1: String, val bytes: Long, val ext: String)

class Downloader(private val transport: HttpTransport, private val validate: (File, String) -> Unit = { _, _ -> }) {
    suspend fun download(asset: Asset, directory: File, stem: String, ua: String, referer: String,
        maxBytes: Long, progress: (Long) -> Unit = {}): DownloadedFile = withContext(Dispatchers.IO) {
        require(stem.matches(Regex("[A-Za-z0-9._-]+")))
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "$stem.part")
        val downloadContext = currentCoroutineContext()
        var failure: Exception? = null
        val urls = asset.urls.distinct()
        val formats = urls.associateWith { it.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.substringAfterLast('.')?.lowercase(Locale.ROOT) }
        // Prefer an existing JPEG original when the same image also has device-sensitive HEIF/AVIF mirrors.
        val staticImage = asset.kind in setOf(AssetKind.IMAGE, AssetKind.COVER) ||
            (asset.kind == AssetKind.STICKER && asset.meta["animated"] == "false")
        val mirrors = if (staticImage && formats.values.any { it in setOf("heic", "heif", "avif") })
            urls.sortedBy { if (formats[it] in setOf("jpg", "jpeg")) 0 else 1 } else urls
        for (url in mirrors) {
            currentCoroutineContext().ensureActive()
            try {
                val downloaded = transport.consume(Request.Builder().url(url).header("User-Agent", ua).header("Referer", referer).build()) { response ->
                    if (!response.isSuccessful) throw IOException("CDN HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty CDN body")
                    if (body.contentLength() > maxBytes) throw IOException("Asset exceeds size limit")
                    val digest = MessageDigest.getInstance("SHA-1")
                    var bytes = 0L
                    body.byteStream().use { input -> temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            downloadContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > maxBytes) throw IOException("Asset exceeds size limit")
                            output.write(buffer, 0, count); digest.update(buffer, 0, count)
                            progress(bytes)
                        }
                        output.fd.sync()
                    } }
                    if (body.contentLength() >= 0 && body.contentLength() != bytes) throw IOException("Incomplete CDN body")
                    val ext = detect(temporary.inputStream().use { input ->
                        val header = ByteArray(32)
                        var size = 0
                        while (size < header.size) { val n = input.read(header, size, header.size - size); if (n < 0) break; size += n }
                        header.copyOf(size)
                    })
                        ?: throw IOException("Unrecognized media content")
                    val valid = when (asset.kind) {
                        AssetKind.VIDEO -> ext == "mp4"
                        AssetKind.AUDIO -> ext in setOf("mp3", "m4a", "mp4")
                        else -> ext in setOf("gif", "png", "jpg", "webp", "heic", "avif")
                    }
                    if (!valid) throw IOException("Unexpected media content")
                    downloadContext.ensureActive()
                    validate(temporary, ext)
                    val target = File(directory, "$stem.$ext")
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    DownloadedFile(target, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, bytes, ext)
                }
                return@withContext downloaded
            } catch (e: CancellationException) { temporary.delete(); throw e }
            catch (e: IOException) { failure = e; temporary.delete() }
        }
        throw AppFailure(ErrorCode.NETWORK, "所有素材镜像均下载失败", failure)
    }

    companion object {
        fun detect(bytes: ByteArray): String? {
            fun ascii(start: Int, length: Int) = if (bytes.size >= start + length) String(bytes, start, length, Charsets.US_ASCII) else ""
            return when {
                ascii(0, 6) in listOf("GIF87a", "GIF89a") -> "gif"
                bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "png"
                ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> "webp"
                bytes.size >= 3 && bytes[0].toInt() == -1 && bytes[1].toInt() == -40 && bytes[2].toInt() == -1 -> "jpg"
                ascii(0, 3) == "ID3" -> "mp3"
                bytes.size >= 3 && bytes[0].toInt() == -1 && (bytes[1].toInt() and 0xe0) == 0xe0 &&
                    (bytes[1].toInt() and 0x06) != 0 && (bytes[2].toInt() and 0xf0) !in setOf(0, 0xf0) -> "mp3"
                ascii(4, 4) == "ftyp" -> when (ascii(8, 4)) {
                    "heic", "heix", "hevc", "hevx", "mif1", "msf1" -> "heic"
                    "avif", "avis" -> "avif"
                    "M4A ", "M4B " -> "m4a"
                    else -> "mp4"
                }
                else -> null
            }
        }
    }
}
