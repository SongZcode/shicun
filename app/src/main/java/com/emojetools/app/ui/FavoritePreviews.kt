package com.emojetools.app.ui

import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import android.util.Log
import com.emojetools.app.R
import com.emojetools.app.core.Asset
import com.emojetools.app.core.bundle.CanonicalJson
import com.emojetools.app.core.task.TaskCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

class FavoritePreviews(private val scope: CoroutineScope, private val tasks: TaskCoordinator, private val root: File) {
    private val limit = Semaphore(3)
    private val loads = mutableMapOf<String, Deferred<File>>()

    fun load(view: ImageView, asset: Asset, thumbnail: Boolean, onFailure: () -> Unit = {}) {
        val key = asset.id + if (thumbnail) ":thumb" else ":full"
        view.tag = key
        scope.launch {
            repeat(2) { attempt ->
                try {
                    val file = loads.getOrPut(key) { scope.async {
                        limit.withPermit {
                            val source = if (thumbnail) asset.meta["thumbnail"]?.let { asset.copy(urls = listOf(it)) } ?: asset else asset
                            val stem = CanonicalJson.sha256((key + source.urls.joinToString()).toByteArray(Charsets.UTF_8))
                            val cached = withContext(Dispatchers.IO) { root.listFiles()?.firstOrNull { it.nameWithoutExtension == stem && it.extension != "part" } }
                            cached ?: tasks.downloadPreview(source, root, stem).file
                        }
                    } }.await()
                    if (view.tag == key) {
                        val decoded = ImageView(view.context)
                        MediaPreview.load(decoded, file)
                        if (view.tag == key) view.setImageDrawable(decoded.drawable)
                        else stop(decoded)
                    }
                    return@launch
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    loads.remove(key)
                    if (view.tag != key) return@launch
                    if (attempt == 0) delay(200)
                    else {
                        Log.w("FavoritePreviews", "preview_failed platform=${asset.meta["platform"].orEmpty()} type=${e.javaClass.simpleName}")
                        view.tag = "failed:$key"
                        view.setImageResource(R.drawable.ic_rotate_ccw); onFailure()
                    }
                }
            }
        }
    }

    fun stop(view: ImageView) {
        view.tag = null
        if (Build.VERSION.SDK_INT >= 28) (view.drawable as? AnimatedImageDrawable)?.stop()
    }
}
