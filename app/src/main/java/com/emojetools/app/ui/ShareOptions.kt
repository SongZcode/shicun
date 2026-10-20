package com.emojetools.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import com.emojetools.app.R
import com.emojetools.app.core.store.CompatibleImages
import com.emojetools.app.core.store.MediaSharing
import com.emojetools.app.core.store.StoredAsset
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object ShareOptions {
    suspend fun intent(activity: Activity, assets: List<StoredAsset>): Intent? {
        if (assets.none(CompatibleImages::candidate)) return MediaSharing.intent(activity, assets)
        val compatible = suspendCancellableCoroutine<Boolean?> { continuation ->
            var selected = 0
            val dialog = AlertDialog.Builder(activity).setTitle(R.string.share_image_format)
                .setSingleChoiceItems(arrayOf(activity.getString(R.string.share_original_format), activity.getString(R.string.share_compatible_images)), 0) { _, index -> selected = index }
                .setPositiveButton(R.string.share_asset) { _, _ -> if (continuation.isActive) continuation.resume(selected == 1) }
                .setNegativeButton(R.string.close) { _, _ -> if (continuation.isActive) continuation.resume(null) }
                .setOnCancelListener { if (continuation.isActive) continuation.resume(null) }.create()
            continuation.invokeOnCancellation { activity.runOnUiThread { dialog.dismiss() } }
            dialog.show()
        } ?: return null
        return MediaSharing.intent(activity, if (compatible) CompatibleImages.prepare(activity, assets) else assets)
    }
}
