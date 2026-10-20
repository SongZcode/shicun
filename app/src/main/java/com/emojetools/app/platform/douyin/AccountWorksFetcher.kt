package com.emojetools.app.platform.douyin

import com.emojetools.app.core.*
import com.emojetools.app.core.net.JsonMapper
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

enum class WorkCollectionKind(val operation: String, val cursorMapping: String) {
    SAVED("savedWorks", "savedWorkCursor"), LIKED("likedWorks", "likedWorkCursor")
}

data class AccountWork(val ref: PostRef, val title: String, val author: String, val covers: List<String>, val imageCount: Int, val durationMs: Long?) {
    fun cover(): Asset? = covers.takeIf { it.isNotEmpty() }?.let { Asset("cover:${ref.platform}:${ref.id}", AssetKind.COVER, "POST", it, null, null, null, null,
        meta = mapOf("platform" to ref.platform)) }
}

data class AccountWorksPage(val works: List<AccountWork>, val nextCursor: String, val completed: Boolean, val unavailable: Int)

class AccountWorksFetcher(
    private val config: JSONObject, private val mapper: JsonMapper,
    private val request: suspend (String, Map<String, String>) -> JSONObject
) {
    suspend fun page(kind: WorkCollectionKind, secUid: String, cursor: String): AccountWorksPage {
        val response = request(kind.operation, mapOf("secUid" to secUid, "cursor" to cursor,
            "count" to config.getJSONObject("account_works").getInt("page_size").toString()))
        val rows = mapper.required("accountWorkRows", response) as? JSONArray ?: invalid()
        val more = when (val value = mapper.required("accountWorkMore", response)) {
            is Boolean -> value
            is Int, is Long -> when ((value as Number).toLong()) { 0L -> false; 1L -> true; else -> invalid() }
            else -> invalid()
        }
        val next = when (val value = mapper.required(kind.cursorMapping, response)) {
            is String -> value.takeIf { it.isNotBlank() } ?: invalid()
            is Int, is Long -> value.toString()
            else -> invalid()
        }
        val unavailable = mapper.optional("accountWorkUnavailable", response)?.let { count ->
            val n = CommentParser.integer(count, "accountWorkUnavailable")
            if (n > Int.MAX_VALUE || n < 0) invalid() else n.toInt()
        } ?: 0
        val works = (0 until rows.length()).map { index ->
            val row = rows.optJSONObject(index) ?: invalid()
            val id = text("accountWorkId", row, true)
            if (!id.matches(Regex(config.getJSONObject("input").getString("id_regex")))) invalid()
            val images = mapper.optional("accountWorkImages", row)?.let { it as? JSONArray ?: invalid() }
            val covers = urls("accountWorkCovers", row).ifEmpty { urls("accountWorkImageCovers", row) }
            AccountWork(PostRef("douyin", id), text("accountWorkTitle", row), text("accountWorkAuthor", row), covers,
                images?.length() ?: 0, mapper.optional("accountWorkDuration", row)?.let { CommentParser.integer(it, "accountWorkDuration") })
        }.distinctBy { it.ref.id }
        return AccountWorksPage(works, next, !more, unavailable)
    }

    private fun text(mapping: String, source: JSONObject, required: Boolean = false): String = when (val value = mapper.optional(mapping, source)) {
        null -> if (required) invalid() else ""
        is String -> value
        else -> invalid()
    }
    private fun urls(mapping: String, source: JSONObject): List<String> = mapper.values(mapping, source).map { value ->
        val url = value as? String ?: invalid()
        val uri = try { URI(url) } catch (_: Exception) { invalid() }
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) invalid()
        url
    }.distinct()
    private fun invalid(): Nothing = throw AppFailure(ErrorCode.SCHEMA, "作品列表响应不完整，请稍后重试")
}
