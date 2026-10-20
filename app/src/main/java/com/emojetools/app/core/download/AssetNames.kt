package com.emojetools.app.core.download

import com.emojetools.app.core.Asset
import com.emojetools.app.core.AssetKind
import java.security.MessageDigest
import java.util.Locale

object AssetNames {
    fun stem(asset: Asset, sequence: Int): String {
        val index = "%02d".format(Locale.ROOT, sequence)
        val hash = MessageDigest.getInstance("SHA-1").digest(asset.id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
        return when (asset.kind) {
            AssetKind.VIDEO -> "v${index}_${asset.meta["quality"].orEmpty().replace(Regex("[^A-Za-z0-9_-]"), "").ifEmpty { "original" }}"
            AssetKind.IMAGE -> "i${index}_${hash.take(6)}"
            AssetKind.COVER -> "c$index"
            AssetKind.AUDIO -> "a${index}_${asset.meta["sourceId"].orEmpty().takeLast(6).replace(Regex("[^A-Za-z0-9_-]"), "").ifEmpty { hash.takeLast(6) }}"
            else -> throw IllegalArgumentException("Not a work asset")
        }
    }
}
