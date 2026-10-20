package com.emojetools.app.core.store

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider

object MediaSharing {
    fun uri(context: Context, asset: StoredAsset) = asset.uri ?: FileProvider.getUriForFile(context, "${context.packageName}.files", asset.file)
    fun intent(context: Context, assets: List<StoredAsset>): Intent {
        require(assets.isNotEmpty())
        val uris = assets.map { uri(context, it) }
        val types = assets.map(MediaPublisher::mime).distinct()
        val families = types.map { it.substringBefore('/') }.distinct()
        val mime = if (types.size == 1) types.single() else if (families.size == 1) "${families.single()}/*" else "*/*"
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newUri(context.contentResolver, "media", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
