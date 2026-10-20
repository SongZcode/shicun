package com.emojetools.app.core.ledger

import com.emojetools.app.core.*
import com.emojetools.app.core.bundle.CanonicalJson
import com.emojetools.app.core.store.StoredAsset
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

class PostManifest(private val file: File, media: PostMedia, selected: List<Asset>) {
    private val previous = if (file.isFile) JSONObject(file.readText()) else null
    private val rows = previous?.optJSONObject("assets") ?: JSONObject()
    private val errors = JSONArray()
    private val document = JSONObject().put("schema", "work-download-v1").put("platform", media.post.ref.platform)
        .put(if (media.post.ref.platform == "douyin") "aweme_id" else "post_id", media.post.ref.id).put("title", media.post.title)
        .put("ref_extra", JSONObject(media.post.ref.extra))
        .put("started_at", previous?.optString("started_at") ?: Instant.now().toString())
        .put("selected", JSONArray(selected.map { it.id })).put("assets", rows).put("errors", errors)

    fun saved(asset: Asset, stored: StoredAsset, reused: Boolean) {
        rows.put(asset.id, JSONObject().put("kind", asset.kind.name).put("status", "saved").put("path", stored.uri?.toString() ?: stored.file.absolutePath)
            .put("sha1", stored.sha1).put("bytes", stored.bytes).put("reused", reused))
        write("running")
    }
    fun failed(asset: Asset, error: Exception) {
        val failure = JSONObject().put("id", asset.id).put("code", (error as? AppFailure)?.code?.name ?: "ERROR")
            .put("msg", error.message.orEmpty()).put("at", Instant.now().toString())
        errors.put(failure)
        rows.put(asset.id, JSONObject().put("kind", asset.kind.name).put("status", "failed").put("error", failure))
        write("running")
    }
    fun write(state: String) {
        document.put("termination", state).put("finished_at", if (state == "running") JSONObject.NULL else Instant.now().toString())
        document.remove("checksum"); document.remove("checksum_algo")
        document.put("checksum", CanonicalJson.sha256(CanonicalJson.encode(document).toByteArray(Charsets.UTF_8)))
            .put("checksum_algo", "sha256/json-canonical-v1")
        FetchLedger.write(file, document)
    }
}
