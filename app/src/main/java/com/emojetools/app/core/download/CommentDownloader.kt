package com.emojetools.app.core.download

import com.emojetools.app.core.*
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.store.AssetStore
import com.emojetools.app.core.store.StoredAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class CommentDownloader(private val store: AssetStore, private val deliver: suspend (StoredAsset) -> Unit = {},
    private val download: suspend (Asset, File, String) -> DownloadedFile) {
    suspend fun save(postId: String, selected: List<Asset>, result: CommentResult,
        progress: (Int, Int) -> Unit): PostDownloadSummary = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty())
        var saved = 0
        val failures = mutableListOf<AppFailure>()
        fun record(asset: Asset, sha1: String?, bytes: Long?, error: String? = null) {
            result.ledgerPath?.let { FetchLedger.recordDownload(File(it), asset.id, sha1, bytes, error) }
        }
        for ((index, asset) in selected.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                val stored = store.verified(postId, asset.id) ?: run {
                    val entries = store.all().filter { it.postId == postId }
                    val stem = entries.firstOrNull { it.assetId == asset.id }?.file?.nameWithoutExtension ?: run {
                        val next = (entries.mapNotNull { it.file.name.substringBefore('_').toIntOrNull() }.maxOrNull() ?: 0) + 1
                        "%02d_%s_%s".format(Locale.ROOT, next, asset.meta.getValue("level"), asset.meta.getValue("commentId").takeLast(6))
                    }
                    store.record(postId, asset, download(asset, store.directory(postId, postId), stem))
                }
                deliver(stored)
                record(asset, stored.sha1, stored.bytes); saved++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val failure = e as? AppFailure ?: AppFailure(ErrorCode.NETWORK, e.message ?: "素材保存失败", e)
                failures += failure; record(asset, null, null, failure.message)
                if (failure.code in setOf(ErrorCode.NEED_LOGIN, ErrorCode.BLOCKED, ErrorCode.RATE_LIMIT)) {
                    selected.drop(index + 1).forEach { pending -> failures += failure; record(pending, null, null, failure.message) }
                    break
                }
            }
            progress(index + 1, selected.size)
        }
        PostDownloadSummary(saved, failures.size, failures)
    }
}
