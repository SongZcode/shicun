package com.emojetools.app.platform.douyin

import com.emojetools.app.core.*
import com.emojetools.app.core.bundle.CanonicalJson
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.net.JsonMapper
import com.emojetools.app.core.store.CommentResultJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class CommentFetcher(
    private val config: JSONObject, private val mapper: JsonMapper,
    private val request: suspend (String, Map<String, String>) -> JSONObject,
    private val checkpoint: suspend (JSONObject) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val progress: (Int, Int) -> Unit = { _, _ -> }
) {
    suspend fun fetch(ref: PostRef, limit: Int, ledgerPath: String? = null, resume: JSONObject? = null,
        order: CommentOrder = CommentOrder.TOP_FIRST): CommentResult {
        require(limit >= 0)
        val limits = JSONObject().put("limit_top", limit)
        listOf("count_top", "count_reply", "max_pages_top", "max_pages_reply", "max_replies_total").forEach {
            limits.put(it, config.getInt(it).also { value -> require(value > 0) })
        }
        val ledger: FetchLedger
        val restored: JSONObject?
        try {
            ledger = if (resume == null) FetchLedger(ref.platform, ref.id, limits) else FetchLedger.restore(resume)
            restored = resume?.let(FetchLedger::resumeState)
            if (resume != null) {
                require(resume.getString("platform") == ref.platform && resume.getString("aweme_id") == ref.id)
                require(CanonicalJson.encode(resume.getJSONObject("limits")) == CanonicalJson.encode(limits))
                require(restored!!.optString("search_order", CommentOrder.TOP_FIRST.name) == order.name)
            }
        } catch (e: Exception) { throw AppFailure(ErrorCode.SCHEMA, "查找进度已损坏或规则已变化，请新建任务", e) }
        ledger.extras.put("limit_policy", "whole_response_page")
        ledger.extras.put("search_order", order.name)
        val parser = CommentParser(mapper, config)
        val previous = restored?.getJSONObject("result")?.let(CommentResultJson::decode)
        val candidates = previous?.comments?.toMutableList() ?: mutableListOf()
        val assets = linkedMapOf<String, Asset>().apply { previous?.assets?.forEach { put(it.id, it) } }
        val topIds = candidates.filter { it.parentId == null }.map { it.id }.toHashSet()
        val replyIds = candidates.filter { it.parentId != null }.map { it.id }.toHashSet()
        val parents = restored?.getJSONArray("parents")?.let { a -> (0 until a.length()).map { a.getString(it) }.toMutableList() } ?: mutableListOf()
        var phase = restored?.getString("phase") ?: "top"
        var cursor = restored?.getString("cursor") ?: "0"
        var topCursor = restored?.optString("top_cursor", if (phase == "top") cursor else "0") ?: "0"
        var topFinished = restored?.optBoolean("top_finished", phase != "top") ?: false
        var parentIndex = restored?.getInt("parent_index") ?: 0
        var parentPages = restored?.getInt("parent_pages") ?: 0
        var parentRows = restored?.getInt("parent_rows") ?: 0
        var topReason = restored?.getString("top_reason") ?: "server_end"
        var replyReason = restored?.getString("reply_reason") ?: "server_end"
        if (phase !in setOf("top", "reply", "done") || parentIndex !in 0..parents.size ||
            topIds.size != ledger.count("top_unique") || replyIds.size != ledger.count("reply_unique") ||
            cursor.toLongOrNull()?.let { it >= 0 } != true || topCursor.toLongOrNull()?.let { it >= 0 } != true)
            throw AppFailure(ErrorCode.SCHEMA, "查找进度不完整，请新建任务")
        ledger.top = topReason; ledger.reply = replyReason
        fun result() = CommentResult(assets.values.toList(), (ledger.totals.opt("api_total") as? Number)?.toLong(),
            (ledger.totals.opt("detail_comment_count") as? Number)?.toLong(), topIds.size, replyIds.size,
            FetchLedger.strongest(ledger.top, ledger.reply)!!, candidates.toList(), ledgerPath, ledger.top, ledger.reply)
        fun collect(parsed: Pair<CommentCandidate, List<Asset>>) {
            candidates += parsed.first
            ledger.add("assets_found", parsed.second.size)
            parsed.second.forEach { asset ->
                val old = assets[asset.id]
                if (old != null) {
                    ledger.add("assets_deduped")
                    assets[asset.id] = old.copy(urls = (old.urls + asset.urls).distinct())
                } else assets[asset.id] = asset
            }
        }
        suspend fun save(finished: Boolean = false) {
            val state = JSONObject().put("schema", 1).put("phase", phase).put("cursor", cursor)
                .put("top_cursor", topCursor).put("top_finished", topFinished).put("search_order", order.name)
                .put("parent_index", parentIndex).put("parent_pages", parentPages).put("parent_rows", parentRows)
                .put("parents", JSONArray(parents)).put("top_reason", topReason).put("reply_reason", replyReason)
                .put("result", CommentResultJson.encode(result()))
            ledger.extras.put("resume", state).put("resume_sha256", digest(state))
            ledger.extras.put("assets", JSONArray(assets.values.map { asset -> JSONObject()
                .put("id", asset.id).put("kind", asset.kind.name).put("comment_id", asset.meta["commentId"])
                .put("downloaded", false) }))
            checkpoint(ledger.snapshot(finished))
            progress(topIds.size, replyIds.size)
        }
        suspend fun page(operation: String, parent: String? = null): Page {
            val variables = mutableMapOf("postId" to ref.id, "cursor" to cursor,
                "count" to config.getInt(if (parent == null) "count_top" else "count_reply").toString())
            parent?.let { variables["parentId"] = it }
            return parsePage(request(operation, variables))
        }
        fun nextPhase() {
            phase = when {
                parentIndex < parents.size && replyIds.size < config.getInt("max_replies_total") &&
                    (topFinished || order == CommentOrder.REPLIES_FIRST) -> "reply"
                !topFinished -> "top"
                else -> "done"
            }
            cursor = if (phase == "top") topCursor else "0"
        }
        try {
            save()
            while (phase != "done") {
                if (phase == "top") {
                    if (ledger.count("top_pages") > 0) pause(config.getLong("page_delay_ms"))
                    val page = page("comment")
                    // Validate a complete page before changing counters, IDs or the resumable cursor.
                    val parsed = page.rows.map { row -> parser.parse(row, null) to parser.replyCount(row) }
                    ledger.add("top_pages"); ledger.add("top_rows", page.rows.size)
                    page.total?.let { ledger.totals.put("api_total", it) }
                    parsed.forEach { (item, replies) ->
                        if (!topIds.add(item.first.id)) ledger.add("top_duplicates")
                        else {
                            ledger.add("top_unique"); collect(item)
                            if (replies > 0) { parents += item.first.id; ledger.add("parents_with_replies") }
                        }
                    }
                    val capped = limit > 0 && topIds.size >= limit
                    val pageCap = ledger.count("top_pages") >= config.getInt("max_pages_top")
                    if (capped) ledger.caps += "limit_top"
                    if (pageCap) ledger.caps += "pages_top"
                    val reason = FetchLedger.strongest(if (page.cursor == cursor) "stalled_cursor" else null,
                        if (capped) "limit_reached" else null, if (pageCap) "page_cap" else null,
                        if (!page.more) "server_end" else null)
                    topCursor = page.cursor
                    if (reason != null) { topReason = reason; ledger.top = reason; topFinished = true }
                    nextPhase()
                    save()
                } else {
                    if (ledger.count("reply_pages") > 0) pause(config.getLong("reply_delay_ms"))
                    val parent = parents[parentIndex]
                    val page = page("reply", parent)
                    val parsed = page.rows.map { parser.parse(it, parent) }
                    if (parentPages == 0) ledger.add("parents_fetched")
                    parentPages++; parentRows += page.rows.size
                    ledger.add("reply_pages"); ledger.add("reply_rows", page.rows.size)
                    parsed.forEach { item ->
                        if (replyIds.size < config.getInt("max_replies_total") && replyIds.add(item.first.id)) {
                            ledger.add("reply_unique"); collect(item)
                        }
                    }
                    val totalCap = replyIds.size >= config.getInt("max_replies_total")
                    val parentCap = parentPages >= config.getInt("max_pages_reply")
                    if (totalCap) ledger.caps += "replies_total"
                    if (parentCap) ledger.caps += "reply_parent_cap"
                    val reason = FetchLedger.strongest(if (page.cursor == cursor) "stalled_cursor" else null,
                        if (totalCap) "replies_cap" else null, if (parentCap) "parent_cap" else null,
                        if (!page.more) "server_end" else null)
                    cursor = page.cursor
                    if (reason != null) {
                        replyReason = FetchLedger.strongest(replyReason, reason)!!; ledger.reply = replyReason
                        if (parentRows == 0) ledger.add("parents_empty")
                        parentIndex++; parentPages = 0; parentRows = 0; cursor = "0"
                        nextPhase()
                    }
                    save()
                }
            }
            return result()
        } catch (e: Exception) {
            if (phase == "top") ledger.top = "error" else ledger.reply = "error"
            if (!topFinished) ledger.top = "error"
            val failure = e as? AppFailure ?: AppFailure(ErrorCode.SCHEMA, "评论数据处理失败", e)
            ledger.errors.put(JSONObject().put("code", if (e is CancellationException) "CANCELLED" else failure.code.name)
                .put("http", failure.httpStatus ?: JSONObject.NULL)
                .put("path", config.getJSONObject("api").getJSONObject(if (phase == "top") "comment" else "reply").getString("path"))
                .put("at", Instant.now().toString()).put("msg", failure.message).put("phase", phase).put("cursor", cursor))
            if (e is CancellationException) throw e
            throw CommentFetchFailure(result(), failure)
        } finally { withContext(NonCancellable) { save(finished = true) } }
    }

    private fun digest(state: JSONObject) = CanonicalJson.sha256(CanonicalJson.encode(state).toByteArray(Charsets.UTF_8))
    private data class Page(val rows: List<JSONObject>, val cursor: String, val more: Boolean, val total: Long?)
    private fun parsePage(response: JSONObject): Page {
        val rows = mapper.required("comments", response) as? JSONArray ?: throw AppFailure(ErrorCode.SCHEMA, "Invalid comments collection")
        val cursor = CommentParser.integer(mapper.required("cursor", response), "cursor").toString()
        val more = CommentParser.integer(mapper.required("hasMore", response), "hasMore")
        if (more !in 0..1) throw AppFailure(ErrorCode.SCHEMA, "Invalid continuation flag")
        val total = mapper.optional("total", response)?.let { CommentParser.integer(it, "total") }
        return Page((0 until rows.length()).map { rows.opt(it) as? JSONObject ?: throw AppFailure(ErrorCode.SCHEMA, "Invalid comment row") }, cursor, more == 1L, total)
    }
}
