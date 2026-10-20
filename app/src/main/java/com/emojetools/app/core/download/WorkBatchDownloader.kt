package com.emojetools.app.core.download

import com.emojetools.app.core.*
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.store.*
import com.emojetools.app.platform.douyin.AccountWork
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class WorkBatchDownloader(
    private val store: AssetStore, private val cacheRoot: File, private val mediaTools: MediaTools,
    private val fetch: suspend (PostRef) -> PostMedia, private val refresh: suspend (PostRef) -> List<Asset>,
    private val download: suspend (Asset, File, String) -> DownloadedFile, private val refreshAttempts: Int,
    private val deliver: suspend (StoredAsset) -> Unit
) {
    suspend fun save(works: List<AccountWork>, root: File, progress: (Int, Int, String) -> Unit): PostDownloadSummary {
        require(works.isNotEmpty() && works.distinctBy { it.ref }.size == works.size)
        var saved = 0
        val failures = mutableListOf<AppFailure>()
        for ((index, work) in works.withIndex()) {
            currentCoroutineContext().ensureActive()
            progress(index + 1, works.size, work.title)
            try {
                require(work.ref.id.matches(Regex("[0-9]+")))
                val directory = File(root, work.ref.id)
                val mediaFile = File(directory, "media.json")
                val cached = withContext(Dispatchers.IO) {
                    mediaFile.takeIf { it.isFile }?.let { PostMediaJson.decode(JSONObject(it.readText())) }
                }
                val media = cached ?: fetch(work.ref).also { fresh ->
                    withContext(Dispatchers.IO) { FetchLedger.write(mediaFile, PostMediaJson.encode(fresh)) }
                }
                if (media.post.ref.platform != work.ref.platform || media.post.ref.id != work.ref.id ||
                    work.ref.extra.any { (key, value) -> media.post.ref.extra[key] != value })
                    throw AppFailure(ErrorCode.SCHEMA, "作品记录与选择不一致")
                val selected = defaultAssets(media)
                val result = PostDownloader(store, cacheRoot, mediaTools, download, { refresh(media.post.ref) }, refreshAttempts, deliver)
                    .save(media, selected, File(directory, "manifest.json"))
                saved += result.saved; failures += result.failures
                val blocked = result.failures.firstOrNull { it.code in setOf(ErrorCode.NEED_LOGIN, ErrorCode.BLOCKED, ErrorCode.RATE_LIMIT) }
                if (blocked != null) {
                    repeat(works.size - index - 1) { failures += blocked }; break
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val failure = e as? AppFailure ?: AppFailure(ErrorCode.NETWORK, e.message ?: "作品处理失败", e)
                failures += failure
                if (failure.code in setOf(ErrorCode.NEED_LOGIN, ErrorCode.BLOCKED, ErrorCode.RATE_LIMIT)) {
                    repeat(works.size - index - 1) { failures += failure }; break
                }
            }
        }
        return PostDownloadSummary(saved, failures.size, failures)
    }

    companion object {
        fun defaultAssets(media: PostMedia): List<Asset> {
            val video = media.assets.firstOrNull { it.kind == AssetKind.VIDEO }
            val selected = video?.let(::listOf) ?: media.assets.filter { it.kind == AssetKind.IMAGE }.distinctBy { it.id }
            if (selected.isEmpty()) throw AppFailure(ErrorCode.UNSUPPORTED, "作品暂无可下载的视频或图片")
            return selected
        }
    }
}
