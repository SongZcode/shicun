package com.emojetools.app.core.store

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import com.emojetools.app.R
import com.emojetools.app.core.*
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MediaPublisher(private val context: Context, private val store: AssetStore) {
    private val resolver get() = context.contentResolver

    suspend fun save(asset: StoredAsset): Uri = withContext(Dispatchers.IO) {
        try {
            val previous = store.publication(asset.key)
            if (previous != null) {
                val uri = Uri.parse(previous.uri)
                if (previous.sha1 == asset.sha1 && previous.bytes == asset.bytes && matches(uri, asset)) {
                    val ready = if (uri.scheme == "file") scan(File(requireNotNull(uri.path)), mime(asset)) else uri.also {
                        if (Build.VERSION.SDK_INT >= 29) finish(it)
                    }
                    store.recordPublication(PublishedAsset(asset.key, ready.toString(), asset.sha1, asset.bytes, false))
                    store.discardLocal(asset)
                    return@withContext ready
                }
                if (previous.pending && Build.VERSION.SDK_INT >= 29 && uri.scheme == "content" && isPending(uri))
                    check(resolver.delete(uri, null, null) == 1)
                store.forgetPublication(asset.key)
            }
            val uri = if (Build.VERSION.SDK_INT >= 29) saveScoped(asset) else saveLegacy(asset)
            store.discardLocal(asset)
            uri
        } catch (e: CancellationException) { throw e }
        catch (e: AppFailure) { throw e }
        catch (e: Exception) { throw AppFailure(ErrorCode.STORAGE, context.getString(R.string.system_save_failed), e) }
    }

    @RequiresApi(29)
    private suspend fun saveScoped(asset: StoredAsset): Uri {
        val collection = when (asset.kind) {
            AssetKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            AssetKind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, asset.file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime(asset))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(asset))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (asset.kind == AssetKind.AUDIO) put(MediaStore.Audio.Media.IS_MUSIC, 1)
        }) ?: throw AppFailure(ErrorCode.STORAGE, context.getString(R.string.system_save_failed))
        var visible = false
        try {
            // Persist the pending URI before copying so an interrupted save can recover it.
            store.recordPublication(PublishedAsset(asset.key, uri.toString(), asset.sha1, asset.bytes, true))
            val output = resolver.openOutputStream(uri, "w") ?: error("Cannot open media output")
            output.use { target -> asset.file.inputStream().use { input ->
                check(digest(input, target) == (asset.sha1 to asset.bytes)) { "Source media changed during save" }
            } }
            currentCoroutineContext().ensureActive()
            finish(uri); visible = true
            store.recordPublication(PublishedAsset(asset.key, uri.toString(), asset.sha1, asset.bytes, false))
            return uri
        } catch (e: Exception) {
            if (!visible) withContext(NonCancellable + Dispatchers.IO) {
                try { resolver.delete(uri, null, null); store.forgetPublication(asset.key) }
                catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun saveLegacy(asset: StoredAsset): Uri {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            throw AppFailure(ErrorCode.STORAGE, context.getString(R.string.storage_permission_required))
        val directory = File(Environment.getExternalStorageDirectory(), relativePath(asset)).apply { check(isDirectory || mkdirs()) }
        var target = File(directory, asset.file.name)
        var suffix = 0
        while (target.exists() && !matches(Uri.fromFile(target), asset)) {
            suffix++
            target = File(directory, "${asset.file.nameWithoutExtension}_$suffix.${asset.file.extension}")
        }
        store.recordPublication(PublishedAsset(asset.key, Uri.fromFile(target).toString(), asset.sha1, asset.bytes, true))
        if (!target.isFile) {
            val temporary = File.createTempFile(".save-", ".part", directory)
            try {
                temporary.outputStream().use { output -> asset.file.inputStream().use { input ->
                    check(digest(input, output) == (asset.sha1 to asset.bytes)) { "Source media changed during save" }
                } }
                currentCoroutineContext().ensureActive()
                Files.move(temporary.toPath(), target.toPath())
            } finally { temporary.delete() }
        }
        val uri = scan(target, mime(asset))
        store.recordPublication(PublishedAsset(asset.key, uri.toString(), asset.sha1, asset.bytes, false))
        return uri
    }

    private suspend fun scan(file: File, mime: String): Uri = withTimeoutOrNull(30_000) {
        suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime)) { _, uri ->
                if (continuation.isActive) {
                    if (uri == null) continuation.resumeWithException(AppFailure(ErrorCode.STORAGE, context.getString(R.string.system_save_failed)))
                    else continuation.resume(uri)
                }
            }
        }
    } ?: throw AppFailure(ErrorCode.STORAGE, context.getString(R.string.system_save_failed))
    @RequiresApi(29)
    private fun finish(uri: Uri) {
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
    }
    @RequiresApi(29)
    private fun isPending(uri: Uri): Boolean = resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
        it.moveToFirst() && it.getInt(0) == 1
    } ?: false
    private suspend fun matches(uri: Uri, asset: StoredAsset): Boolean = try {
        resolver.openInputStream(uri)?.use { digest(it) == (asset.sha1 to asset.bytes) } == true
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { false }

    private suspend fun digest(input: InputStream, output: OutputStream? = null): Pair<String, Long> {
        val hash = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output?.write(buffer, 0, count); hash.update(buffer, 0, count); bytes += count
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } to bytes
    }
    fun relativePath(asset: StoredAsset): String {
        val root = when (asset.kind) {
            AssetKind.AUDIO -> Environment.DIRECTORY_MUSIC
            AssetKind.VIDEO -> Environment.DIRECTORY_MOVIES
            else -> Environment.DIRECTORY_PICTURES
        }
        val platform = if (asset.postId.startsWith("bilibili:")) "bilibili" else "douyin"
        return "$root/${context.getString(R.string.app_name)}/$platform/${asset.file.parentFile!!.name}/"
    }
    companion object {
        fun mime(asset: StoredAsset): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(asset.file.extension.lowercase())
            ?: when (asset.kind) { AssetKind.AUDIO -> "audio/mp4"; AssetKind.VIDEO -> "video/mp4"; else -> "image/*" }
    }
}
