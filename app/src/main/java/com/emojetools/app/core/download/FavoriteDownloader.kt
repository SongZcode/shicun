package com.emojetools.app.core.download

import com.emojetools.app.core.*
import com.emojetools.app.core.bundle.CanonicalJson
import com.emojetools.app.core.store.AssetStore
import com.emojetools.app.core.store.StoredAsset
import kotlinx.coroutines.*
import java.io.File

class FavoriteDownloader(
    private val store: AssetStore, private val deliver: suspend (StoredAsset) -> Unit,
    private val download: suspend (Asset, File, String) -> DownloadedFile
) {
    suspend fun save(key: String, selected: List<Asset>, progress: (Int, Int) -> Unit): PostDownloadSummary = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty())
        val directory = store.collectionDirectory(key)
        var saved = 0
        val failures = mutableListOf<AppFailure>()
        for ((index, asset) in selected.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                val stored = store.verified(key, asset.id) ?: run {
                    val stem = "s_" + CanonicalJson.sha256(asset.id.toByteArray(Charsets.UTF_8)).take(16)
                    store.record(key, asset, download(asset, directory, stem))
                }
                deliver(stored); saved++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val failure = e as? AppFailure ?: AppFailure(ErrorCode.NETWORK, e.message ?: "表情包保存失败", e)
                failures += failure
                if (failure.code in setOf(ErrorCode.NEED_LOGIN, ErrorCode.BLOCKED, ErrorCode.RATE_LIMIT)) {
                    repeat(selected.size - index - 1) { failures += failure }; break
                }
            }
            progress(index + 1, selected.size)
        }
        PostDownloadSummary(saved, failures.size, failures)
    }
}
