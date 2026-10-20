package com.emojetools.app.platform.douyin

import com.emojetools.app.core.*
import com.emojetools.app.core.net.JsonMapper
import org.json.JSONArray
import org.json.JSONObject

data class FavoriteStickerPage(val assets: List<Asset>, val nextCursor: String, val completed: Boolean)

class FavoriteStickerFetcher(
    private val config: JSONObject, private val mapper: JsonMapper,
    private val request: suspend (String, Map<String, String>) -> JSONObject
) {
    suspend fun page(cursor: String): FavoriteStickerPage {
        val response = request("favorites", mapOf("cursor" to cursor, "count" to config.getJSONObject("favorites").getInt("page_size").toString()))
        val resources = mapper.required("favoriteResources", response) as? JSONArray ?: invalid()
        val completed = mapper.required("favoriteCompleted", response) as? Boolean ?: invalid()
        val next = when (val value = mapper.required("favoriteCursor", response)) {
            is String -> value.takeIf { it.isNotBlank() } ?: invalid()
            is Int, is Long -> value.toString()
            else -> invalid()
        }
        val assets = mutableListOf<Asset>()
        for (i in 0 until resources.length()) {
            val resource = resources.optJSONObject(i) ?: invalid()
            val stickers = mapper.required("favoriteStickers", resource) as? JSONArray ?: invalid()
            for (j in 0 until stickers.length()) {
                val sticker = stickers.optJSONObject(j) ?: invalid()
                val id = (mapper.required("favoriteId", sticker) as? String)?.takeIf { it.isNotBlank() } ?: invalid()
                val animated = urls("favoriteAnimatedUrls", sticker)
                val still = urls("favoriteStaticUrls", sticker)
                val urls = animated.ifEmpty { still }
                if (urls.isEmpty()) invalid()
                val name = mapper.optional("favoriteName", sticker) as? String ?: ""
                val format = (mapper.optional("favoriteFormat", sticker) as? String).orEmpty().lowercase(java.util.Locale.ROOT)
                assets += Asset("favorite:$id", AssetKind.STICKER, "FAVORITE", urls, null,
                    (mapper.optional("favoriteWidth", sticker) as? Number)?.toInt(),
                    (mapper.optional("favoriteHeight", sticker) as? Number)?.toInt(), null,
                    meta = mapOf("name" to name, "format" to format,
                        "animated" to (format in setOf("gif", "apng") || (format.isEmpty() && animated.isNotEmpty())).toString()) +
                        (still.firstOrNull()?.let { mapOf("thumbnail" to it) } ?: emptyMap()))
            }
        }
        return FavoriteStickerPage(assets.distinctBy { it.id }, next, completed)
    }

    private fun urls(mapping: String, row: JSONObject): List<String> = mapper.values(mapping, row).map {
        (it as? String)?.takeIf { value -> value.startsWith("https://") } ?: invalid()
    }.distinct()

    private fun invalid(): Nothing = throw AppFailure(ErrorCode.SCHEMA, "收藏表情包响应不完整，请稍后重试")
}
