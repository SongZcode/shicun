package com.emojetools.app.core.store

import com.emojetools.app.core.Asset
import com.emojetools.app.core.AssetKind
import com.emojetools.app.core.PostMedia
import com.emojetools.app.core.storageId
import com.emojetools.app.core.download.DownloadedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AudioCoverStore(private val store: AssetStore,
    private val download: suspend (Asset, File, String) -> DownloadedFile) {
    private val mutex = Mutex()

    suspend fun ensure(media: PostMedia): File? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val postId = media.post.ref.storageId
            store.previewFile(postId)?.let { return@withLock it }
            val cover = media.assets.firstOrNull { it.kind == AssetKind.COVER } ?: return@withLock null
            val destination = store.previewDirectory(postId).apply { check(isDirectory || mkdirs()) }
            val existing = store.verified(postId, cover.id)
            if (existing == null) return@withLock download(cover, destination, "cover").file
            val temporary = File(destination, "cover.part")
            try {
                store.open(existing).use { input -> temporary.outputStream().use { input.copyTo(it) } }
                val file = File(destination, "cover.${existing.file.extension}")
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                file
            } finally { temporary.delete() }
        }
    }
}
