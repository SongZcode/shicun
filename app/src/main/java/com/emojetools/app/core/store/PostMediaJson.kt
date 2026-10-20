package com.emojetools.app.core.store

import com.emojetools.app.core.*
import org.json.JSONArray
import org.json.JSONObject

object PostMediaJson {
    fun encode(media: PostMedia): JSONObject = JSONObject().put("platform", media.post.ref.platform).put("id", media.post.ref.id)
        .put("ref_extra", JSONObject(media.post.ref.extra)).put("title", media.post.title).put("author", media.post.author)
        .put("author_id", media.post.authorId).put("cover", media.post.coverUrl ?: JSONObject.NULL)
        .put("duration_ms", media.post.durationMs ?: JSONObject.NULL).put("published_at", media.post.publishedAt ?: JSONObject.NULL)
        .put("stats", JSONObject(media.post.stats)).put("parts", JSONArray(media.post.parts.map { JSONObject().put("id", it.id).put("title", it.title) }))
        .put("assets", JSONArray(media.assets.map(AssetJson::encode)))
    fun decode(json: JSONObject): PostMedia {
        val extra = json.getJSONObject("ref_extra")
        val stats = json.getJSONObject("stats")
        val parts = json.getJSONArray("parts")
        val assets = json.getJSONArray("assets")
        val post = Post(PostRef(json.getString("platform"), json.getString("id"), extra.keys().asSequence().associateWith { extra.getString(it) }),
            json.getString("title"), json.getString("author"), json.getString("author_id"), if (json.isNull("cover")) null else json.getString("cover"),
            if (json.isNull("duration_ms")) null else json.getLong("duration_ms"), if (json.isNull("published_at")) null else json.getLong("published_at"),
            stats.keys().asSequence().associateWith { stats.getLong(it) }, (0 until parts.length()).map { parts.getJSONObject(it).let { part -> PostPart(part.getString("id"), part.getString("title")) } })
        return PostMedia(post, (0 until assets.length()).map { AssetJson.decode(assets.getJSONObject(it)) })
    }
}
