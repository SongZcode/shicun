package com.emojetools.app.core.download

import com.emojetools.app.core.*
import com.emojetools.app.core.ledger.PostManifest
import com.emojetools.app.core.store.AssetStore
import com.emojetools.app.core.store.StoredAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class PostDownloadSummary(val saved: Int, val failed: Int, val failures: List<AppFailure>)

class PostDownloader(
    private val store: AssetStore, private val cacheRoot: File, private val mediaTools: MediaTools,
    private val download: suspend (Asset, File, String) -> DownloadedFile,
    private val refresh: suspend () -> List<Asset>, private val refreshAttempts: Int,
    private val deliver: suspend (StoredAsset) -> Unit = {}
) {
    suspend fun save(media: PostMedia, selected: List<Asset>, manifestFile: File,
        progress: (Int, Int, StoredAsset?) -> Unit = { _, _, _ -> }): PostDownloadSummary = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty() && selected.map { it.id }.distinct().size == selected.size)
        require(selected.all { chosen -> media.assets.any { it.id == chosen.id } })
        val storageId = media.post.ref.storageId
        val staging = File(cacheRoot, "work-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val directory = store.directory(storageId, media.post.title.ifBlank { media.post.ref.id })
        val manifest = PostManifest(manifestFile, media, selected)
        var available = media.assets.associateBy { it.id }
        val sourceFiles = mutableMapOf<String, File>()
        val failures = mutableListOf<AppFailure>()
        var refreshed = 0
        var saved = 0
        var state = "error"

        suspend fun fetch(asset: Asset, destination: File, stem: String): DownloadedFile {
            while (true) {
                val current = available[asset.id] ?: throw AppFailure(ErrorCode.NOT_FOUND, "所选内容已变化，请重新解析作品")
                try { return download(current, destination, stem) }
                catch (e: AppFailure) {
                    if (e.code != ErrorCode.NETWORK || refreshed >= refreshAttempts) throw e
                    refreshed++
                    available = refresh().associateBy { it.id }
                }
            }
        }
        fun stem(asset: Asset): String {
            store.find(storageId, asset.id)?.let { return it.file.nameWithoutExtension }
            val used = store.all().filter { it.file.parentFile == directory }.map { it.file.nameWithoutExtension }.toSet()
            var index = asset.meta["index"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            while (AssetNames.stem(asset, index) in used) index++
            return AssetNames.stem(asset, index)
        }
        suspend fun sourceVideo(asset: Asset): File {
            sourceFiles[asset.id]?.takeIf { it.isFile }?.let { return it }
            store.verified(storageId, asset.id)?.let { existing ->
                val file = if (existing.uri == null && existing.file.isFile) existing.file else File(staging, "source-${sourceFiles.size}.mp4").also { target ->
                    store.open(existing).use { input -> target.outputStream().use { input.copyTo(it) } }
                    val measured = MediaTools.measure(target, "mp4")
                    if (measured.sha1 != existing.sha1 || measured.bytes != existing.bytes)
                        throw AppFailure(ErrorCode.STORAGE, "原视频读取失败")
                }
                mediaTools.validateVideo(file); sourceFiles[asset.id] = file; return file
            }
            val downloaded = fetch(asset, staging, "source-${sourceFiles.size}")
            mediaTools.validateVideo(downloaded.file)
            sourceFiles[asset.id] = downloaded.file
            return downloaded.file
        }
        try {
            manifest.write("running")
            for ((index, asset) in selected.withIndex()) {
                currentCoroutineContext().ensureActive()
                try {
                    val existing = store.verified(storageId, asset.id)
                    val stored = existing ?: run {
                        val name = stem(asset)
                        val file = if (asset.meta["transform"] == "complete_audio") {
                            val source = available[asset.meta.getValue("derivedFrom")]
                                ?: throw AppFailure(ErrorCode.SCHEMA, "完整音频缺少原视频来源")
                            check(directory.isDirectory || directory.mkdirs())
                            mediaTools.extractCompleteAudio(sourceVideo(source), File(directory, "$name.m4a"))
                        } else {
                            val fresh = fetch(asset, directory, name)
                            try {
                                if (asset.kind == AssetKind.VIDEO) mediaTools.validateVideo(fresh.file)
                                if (asset.kind == AssetKind.AUDIO) mediaTools.validateAudio(fresh.file)
                            } catch (e: Exception) { fresh.file.delete(); throw e }
                            if (asset.kind == AssetKind.AUDIO && fresh.ext == "mp4") {
                                val audio = File(fresh.file.parentFile, fresh.file.nameWithoutExtension + ".m4a")
                                java.nio.file.Files.move(fresh.file.toPath(), audio.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                fresh.copy(file = audio, ext = "m4a")
                            } else fresh
                        }
                        store.record(storageId, asset, file)
                    }
                    if (stored.uri == null && stored.file.isFile && asset.kind == AssetKind.VIDEO) { mediaTools.validateVideo(stored.file); sourceFiles[asset.id] = stored.file }
                    if (stored.uri == null && stored.file.isFile && asset.kind == AssetKind.AUDIO) mediaTools.validateAudio(stored.file)
                    deliver(stored)
                    manifest.saved(asset, store.find(storageId, asset.id) ?: stored, existing != null)
                    saved++; progress(index + 1, selected.size, stored)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val failure = e as? AppFailure ?: AppFailure(ErrorCode.UNSUPPORTED, e.message ?: "素材保存失败", e)
                    failures += failure; manifest.failed(asset, failure)
                    progress(index + 1, selected.size, null)
                    if (failure.code in setOf(ErrorCode.NEED_LOGIN, ErrorCode.BLOCKED, ErrorCode.RATE_LIMIT)) {
                        selected.drop(index + 1).forEach { pending -> failures += failure; manifest.failed(pending, failure) }
                        break
                    }
                }
            }
            state = if (failures.isEmpty()) "done" else if (saved > 0) "partial" else "error"
            PostDownloadSummary(saved, failures.size, failures)
        } catch (e: CancellationException) { state = "cancelled"; throw e }
        finally { try { manifest.write(state) } finally { staging.deleteRecursively() } }
    }
}
