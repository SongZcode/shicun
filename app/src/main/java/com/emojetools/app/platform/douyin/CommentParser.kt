package com.emojetools.app.platform.douyin

import com.emojetools.app.core.*
import com.emojetools.app.core.net.JsonMapper
import org.json.JSONObject
import java.net.URI

internal class CommentParser(private val mapper: JsonMapper, config: JSONObject) {
    private val suffix = Regex(config.getString("identity_path_suffix_regex"))

    fun id(row: JSONObject) = string("commentId", row, required = true)
    fun replyCount(row: JSONObject): Long {
        val value = mapper.optional("replyTotal", row) ?: return 0
        return integer(value, "replyTotal")
    }

    fun parse(row: JSONObject, parent: String?): Pair<CommentCandidate, List<Asset>> {
        val cid = id(row)
        val assets = mutableListOf<Asset>()
        val metadata = mapOf("commentId" to cid, "level" to if (parent == null) "T" else "R", "parentId" to parent.orEmpty())
        val sticker = mapper.optional("stickerObject", row)
        if (sticker != null && sticker !is JSONObject) throw AppFailure(ErrorCode.SCHEMA, "Invalid sticker object")
        val animated = urls("stickerAnimatedUrls", row)
        val stickerUrls = animated.ifEmpty { urls("stickerStaticUrls", row) }
        if (sticker != null && stickerUrls.isEmpty()) throw AppFailure(ErrorCode.SCHEMA, "Sticker has no media URL")
        if (stickerUrls.isNotEmpty()) {
            val identity = string("stickerId", row).ifEmpty { identity(stickerUrls.first()) }
            assets += Asset("sticker:$identity", AssetKind.STICKER, "COMMENT", stickerUrls, null,
                dimension("stickerWidth", row), dimension("stickerHeight", row), null,
                meta = metadata + ("animated" to animated.isNotEmpty().toString()))
        }
        mapper.values("commentImages", row).forEach { image ->
            if (image !is JSONObject) throw AppFailure(ErrorCode.SCHEMA, "Invalid comment image")
            val mirrors = urls("imageOriginalUrls", image)
            if (mirrors.isEmpty()) throw AppFailure(ErrorCode.SCHEMA, "Comment image has no original URL")
            val identity = string("imageUri", image).ifEmpty { identity(mirrors.first()) }
            val imageId = "image:$identity"
            assets += Asset(imageId, AssetKind.IMAGE, "COMMENT", mirrors, null,
                dimension("imageWidth", image), dimension("imageHeight", image), null,
                originUrl = mirrors.first(), meta = metadata)
            val motion = if (mapper.has("imageMotionObject")) mapper.optional("imageMotionObject", image) else null
            if (motion != null) {
                if (motion !is JSONObject) throw AppFailure(ErrorCode.SCHEMA, "Invalid image motion object")
                val motionUrls = urls("imageMotionUrls", image)
                if (motionUrls.isEmpty()) throw AppFailure(ErrorCode.SCHEMA, "Image motion has no media URL")
                val bytes = mapper.optional("imageMotionBytes", image)?.let { integer(it, "imageMotionBytes") }
                assets += Asset("live:$identity", AssetKind.VIDEO, "COMMENT", motionUrls, null,
                    dimension("imageWidth", image), dimension("imageHeight", image), bytes,
                    originUrl = motionUrls.first(), meta = metadata + mapOf("role" to "live_photo_motion", "liveImageId" to imageId))
            }
        }
        val author = identity(row)
        return CommentCandidate(cid, parent, string("commentText", row), author.name,
            author.ids, assets.map { it.id }.distinct()) to assets
    }

    fun identity(row: JSONObject): AccountIdentity {
        val authorIds = listOf("authorId", "authorNumericId", "authorDisplayId", "authorShortId").filter(mapper::has).mapNotNull {
            when (val value = mapper.optional(it, row)) {
                null -> null
                is String -> value
                is Int, is Long -> value.toString()
                else -> throw AppFailure(ErrorCode.SCHEMA, "Invalid author identifier")
            }
        }.map(String::trim).filter { it.isNotEmpty() && it != "0" }.distinct()
        return AccountIdentity(string("authorName", row), authorIds)
    }

    private fun identity(url: String): String = suffix.replace(URI(url).rawPath.trim('/'), "")
        .ifEmpty { throw AppFailure(ErrorCode.SCHEMA, "Asset URL has no identity") }
    private fun string(key: String, source: Any, required: Boolean = false): String {
        val value = mapper.optional(key, source)
        if (value == null && !required) return ""
        if (value !is String || (required && value.isBlank())) throw AppFailure(ErrorCode.SCHEMA, "Invalid mapped string: $key")
        return value
    }
    private fun dimension(key: String, source: Any): Int? = mapper.optional(key, source)?.let {
        integer(it, key).also { n -> if (n > Int.MAX_VALUE) throw AppFailure(ErrorCode.SCHEMA, "Invalid dimension") }.toInt()
    }
    private fun urls(key: String, source: Any): List<String> = mapper.values(key, source).map {
        if (it !is String) throw AppFailure(ErrorCode.SCHEMA, "Invalid mapped URL: $key")
        val uri = try { URI(it) } catch (e: Exception) { throw AppFailure(ErrorCode.SCHEMA, "Invalid asset URL", e) }
        if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null)
            throw AppFailure(ErrorCode.SCHEMA, "Invalid asset URL")
        it
    }.distinct()

    companion object {
        fun integer(value: Any, name: String): Long {
            val number = when (value) { is Int -> value.toLong(); is Long -> value; is String -> value.toLongOrNull(); else -> null }
            return number?.takeIf { it >= 0 } ?: throw AppFailure(ErrorCode.SCHEMA, "Invalid mapped integer: $name")
        }
    }
}
