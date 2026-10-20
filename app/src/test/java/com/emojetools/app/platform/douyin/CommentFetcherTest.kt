package com.emojetools.app.platform.douyin

import com.emojetools.app.core.*
import com.emojetools.app.core.bundle.CanonicalJson
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.net.ApiResponse
import com.emojetools.app.core.net.JsonMapper
import com.emojetools.app.core.store.CommentResultJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CommentFetcherTest {
    private fun config() = JSONObject(File("../bundle_src/platforms/douyin.json").readText())
    private fun mapper() = JsonMapper(JSONObject(File("../bundle_src/parsers/douyin.json").readText()))
    private fun row(id: String, replies: Int = 0, sticker: String? = null): JSONObject = JSONObject()
        .put("cid", id).put("text", "目标$id").put("reply_comment_total", replies)
        .put("user", JSONObject().put("nickname", "测试作者").put("sec_uid", "author-sec").put("uid", 9007199254740993L))
        .apply { if (sticker != null) put("sticker", JSONObject().put("id_str", sticker).put("width", 180).put("height", 242)
            .put("animate_url", JSONObject().put("url_list", JSONArray().put("https://p3.example.com/$sticker.gif?token=a").put("https://p9.example.com/$sticker.gif?token=b")))
            .put("static_url", JSONObject().put("url_list", JSONArray().put("https://example.com/static.png")))) }
    private fun page(cursor: Int, more: Int, vararg rows: JSONObject) = JSONObject().put("comments", JSONArray(rows.toList()))
        .put("cursor", cursor).put("has_more", more).put("total", 677)
    private data class Run(val result: CommentResult, val ledger: JSONObject, val calls: List<Pair<String, Map<String, String>>>, val delays: List<Long>)
    private fun run(pages: List<JSONObject>, cfg: JSONObject = config(), limit: Int = 0,
        order: CommentOrder = CommentOrder.TOP_FIRST): Run = runBlocking {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val delays = mutableListOf<Long>()
        var ledger = JSONObject()
        val result = CommentFetcher(cfg, mapper(), { op, vars -> calls += op to vars; pages[calls.lastIndex] },
            { ledger = it }, { delays += it }).fetch(PostRef("douyin", "7697489379418420475"), limit, order = order)
        Run(result, ledger, calls, delays)
    }

    @Test fun responseCursorAndReplyCountsAreUsedAndAssociationsSurviveDedupe() {
        val run = run(listOf(page(39, 1, row("1", 2, "s1")), page(81, 0, row("1"), row("2")),
            page(7, 1, row("3", sticker = "s1")), page(15, 0, row("4", sticker = "s2"))))
        assertEquals(listOf("0", "39", "0", "7"), run.calls.map { it.second["cursor"] })
        assertEquals(listOf("50", "50", "20", "20"), run.calls.map { it.second["count"] })
        assertEquals(listOf(400L, 250L), run.delays)
        assertEquals(2, run.result.fetchedTopLevel); assertEquals(2, run.result.fetchedReplies)
        assertEquals(2, run.result.assets.size)
        assertEquals(2, run.result.comments.count { "sticker:s1" in it.assetIds })
        assertEquals("1", run.result.comments.first { it.id == "3" }.parentId)
        assertEquals(1, run.ledger.getJSONObject("counters").getInt("top_duplicates"))
        assertEquals(1, run.ledger.getJSONObject("counters").getInt("assets_deduped"))
        assertEquals(677L, run.result.providedTotal)
        assertTrue(run.result.assets.first().urls.all { !it.contains("static") })
        assertEquals(2, run.result.assets.first().urls.size)
    }

    @Test fun uniqueLimitCountsWholePageAndDoesNotStopOnDuplicateRows() {
        val run = run(listOf(page(50, 1, row("1"), row("1")), page(100, 1, row("2"), row("3"))), limit = 2)
        assertEquals(2, run.calls.size)
        assertEquals(3, run.result.fetchedTopLevel)
        assertEquals("limit_reached", run.result.topTermination)
        assertEquals(4, run.ledger.getJSONObject("counters").getInt("top_rows"))
        assertEquals(listOf("limit_top"), run.ledger.getJSONArray("caps_hit").let { (0 until it.length()).map(it::getString) })
    }

    @Test fun responseTerminationPriorityAndCapsAreRetained() {
        val cfg = config().put("max_pages_top", 1)
        val run = run(listOf(page(0, 0, row("1"))), cfg, 1)
        assertEquals("stalled_cursor", run.result.topTermination)
        assertTrue(run.ledger.getJSONArray("caps_hit").toString().contains("limit_top"))
        assertTrue(run.ledger.getJSONArray("caps_hit").toString().contains("pages_top"))
        assertEquals("limit_reached", run(listOf(page(50, 0, row("1"))), cfg, 1).result.topTermination)
        assertEquals("page_cap", run(listOf(page(50, 0, row("1"))), cfg).result.topTermination)
    }

    @Test fun parentCapDoesNotDiscardOtherParentsAndReplyCapIsIndependent() {
        val cfg = config().put("max_pages_reply", 1).put("max_replies_total", 2)
        val run = run(listOf(page(50, 0, row("1", 10), row("2", 10), row("3", 10)),
            page(20, 1, row("11")), page(20, 0, row("12"))), cfg)
        assertEquals(3, run.calls.size)
        assertEquals("replies_cap", run.result.replyTermination)
        assertEquals("server_end", run.result.topTermination)
        assertEquals(2, run.ledger.getJSONObject("counters").getInt("parents_fetched"))
        assertTrue(run.ledger.getJSONArray("caps_hit").toString().contains("reply_parent_cap"))
        assertTrue(run.ledger.getJSONArray("caps_hit").toString().contains("replies_total"))
    }

    @Test fun emptyReplyParentIsRecorded() {
        val run = run(listOf(page(50, 0, row("1", 1)), page(1, 0)))
        assertEquals(1, run.ledger.getJSONObject("counters").getInt("parents_empty"))
        assertEquals(0, run.result.fetchedReplies)
    }

    @Test fun originalImagesAreGroupedAndDedupedAcrossCdnHosts() {
        fun image(host: String) = JSONObject().put("origin_url", JSONObject().put("url_list", JSONArray()
            .put("https://$host/tos/image123~tplv.webp?token=1").put("https://backup.example.com/tos/image123~tplv.webp?token=2")))
            .put("download_url", JSONObject().put("url_list", JSONArray().put("https://example.com/watermark.jpg")))
        val a = row("1").put("image_list", JSONArray().put(image("p3.example.com")))
        val b = row("2").put("image_list", JSONArray().put(image("p9.example.com")))
        val run = run(listOf(page(50, 0, a, b)))
        assertEquals(1, run.result.assets.size)
        assertEquals("image:tos/image123", run.result.assets.single().id)
        assertEquals(3, run.result.assets.single().urls.size)
        assertTrue(run.result.assets.single().urls.none { it.contains("watermark") })
        assertEquals(2, run.result.comments.size)
    }

    @Test fun textAndExactIdsMatchRepliesAndNumericIdsDoNotLosePrecision() {
        val run = run(listOf(page(50, 0, row("1", 1)), page(20, 0, row("123", sticker = "s"))))
        assertEquals(listOf("123"), run.result.matching(CommentTarget.COMMENT_ID, "123").map { it.id })
        assertEquals(0, run.result.matching(CommentTarget.COMMENT_ID, "12").size)
        assertEquals(2, run.result.matching(CommentTarget.AUTHOR_ID, "9007199254740993").size)
        assertEquals(1, run.result.matching(CommentTarget.TEXT, "目标123").size)
        assertEquals(run.result, CommentResultJson.decode(CommentResultJson.encode(run.result)))
    }

    @Test fun emptyHttpBodyIsNeedLoginAndIsWrittenToLedger() = runBlocking {
        var ledger = JSONObject()
        val fetcher = CommentFetcher(config(), mapper(), { _, _ -> ApiResponse.decode(config(), mapper(), 200, "") }, { ledger = it }, {})
        try { fetcher.fetch(PostRef("douyin", "123"), 0); fail("Must fail") }
        catch (e: CommentFetchFailure) { assertEquals(ErrorCode.NEED_LOGIN, e.code); assertEquals(0, e.partial.fetchedTopLevel) }
        assertEquals("error", ledger.getJSONObject("termination").getString("top"))
        assertEquals("NEED_LOGIN", ledger.getJSONArray("errors").getJSONObject(0).getString("code"))
        assertFalse(ledger.isNull("finished_at"))
    }

    @Test fun invalidSchemaFailsAndPreservesAlreadyFetchedCandidates() = runBlocking {
        var ledger = JSONObject(); var count = 0
        val fetcher = CommentFetcher(config(), mapper(), { _, _ -> if (count++ == 0) page(50, 1, row("1", sticker = "s")) else page(100, 0).put("has_more", "invalid") }, { ledger = it }, {})
        try { fetcher.fetch(PostRef("douyin", "123"), 0); fail("Must fail") }
        catch (e: CommentFetchFailure) { assertEquals(ErrorCode.SCHEMA, e.code); assertEquals(1, e.partial.assets.size) }
        assertEquals("error", ledger.getJSONObject("termination").getString("top"))
    }

    @Test fun cancellationAlwaysFinalizesLedger() = runBlocking {
        var ledger = JSONObject()
        val fetcher = CommentFetcher(config(), mapper(), { _, _ -> throw CancellationException("cancel") }, { ledger = it }, {})
        try { fetcher.fetch(PostRef("douyin", "123"), 0); fail("Must cancel") } catch (_: CancellationException) { }
        assertEquals("CANCELLED", ledger.getJSONArray("errors").getJSONObject(0).getString("code"))
        assertFalse(ledger.isNull("finished_at"))
    }

    @Test fun checksumWhitelistsOnlyStableFieldsAndIncludesUnicode() {
        val ledger = run(listOf(page(50, 0, row("1")))).ledger
        val stable = JSONObject()
        FetchLedger.CHECKSUM_KEYS.forEach { stable.put(it, ledger.get(it)) }
        assertEquals(CanonicalJson.sha256(CanonicalJson.encode(stable).toByteArray(Charsets.UTF_8)), ledger.getString("checksum"))
        val original = ledger.getString("checksum")
        ledger.getJSONObject("extras").put("自定义", "不会影响校验")
        FetchLedger.seal(ledger)
        assertEquals(original, ledger.getString("checksum"))
        ledger.getJSONObject("counters").put("top_unique", 999)
        FetchLedger.seal(ledger)
        assertNotEquals(original, ledger.getString("checksum"))
    }

    @Test fun parserMappingsCanChangeWithoutKotlinChanges() {
        val mapping = JSONObject(File("../bundle_src/parsers/douyin.json").readText()).put("commentId", JSONArray().put("new_id"))
        val parser = CommentParser(JsonMapper(mapping), config())
        assertEquals("9007199254740993", parser.id(JSONObject().put("new_id", "9007199254740993")))
    }

    @Test fun malformedMediaSchemaDoesNotBecomeAnEmptySuccessfulResult() {
        val parser = CommentParser(mapper(), config())
        listOf(row("1").put("image_list", JSONObject()), row("2").put("sticker", JSONObject()),
            row("3", sticker = "s").apply { getJSONObject("sticker").getJSONObject("animate_url").put("url_list", "changed-schema") })
            .forEach { invalid ->
                try { parser.parse(invalid, null); fail("Must report schema failure") }
                catch (e: AppFailure) { assertEquals(ErrorCode.SCHEMA, e.code) }
            }
    }

    @Test fun resumeStartsAtTheNextTopCursorAndPreservesDedupeAndAudit(): Unit = runBlocking {
        var saved = JSONObject()
        val ref = PostRef("douyin", "123")
        try {
            CommentFetcher(config(), mapper(), { _, vars ->
                if (vars.getValue("cursor") == "0") page(39, 1, row("1", sticker = "shared")) else throw CancellationException()
            }, { saved = JSONObject(it.toString()) }, {}).fetch(ref, 0)
            fail("Must stop")
        } catch (_: CancellationException) { }
        val started = saved.getString("started_at")
        val calls = mutableListOf<String>()
        val result = CommentFetcher(config(), mapper(), { op, vars ->
            calls += "$op:${vars.getValue("cursor")}"
            page(81, 0, row("1"), row("2", sticker = "shared"))
        }, { saved = it }, {}).fetch(ref, 0, resume = saved)
        assertEquals(listOf("comment:39"), calls)
        assertEquals(2, result.fetchedTopLevel); assertEquals(1, result.assets.size)
        assertEquals(3, saved.getJSONObject("counters").getInt("top_rows"))
        assertEquals(1, saved.getJSONObject("counters").getInt("top_duplicates"))
        assertEquals(started, saved.getString("started_at"))
        assertEquals(1, saved.getJSONArray("errors").length())
        assertEquals("server_end", result.termination)
        assertEquals("done", saved.getJSONObject("extras").getJSONObject("resume").getString("phase"))
    }

    @Test fun resumeInsideAReplyPreservesParentPagesAndContinuesOtherParents(): Unit = runBlocking {
        val cfg = config().put("max_pages_reply", 2)
        val ref = PostRef("douyin", "123")
        var saved = JSONObject()
        try {
            CommentFetcher(cfg, mapper(), { op, vars -> when {
                op == "comment" -> page(50, 0, row("1", 10), row("2", 1))
                vars.getValue("cursor") == "0" -> page(7, 1, row("11", sticker = "shared"))
                else -> throw AppFailure(ErrorCode.NETWORK, "interrupted")
            } }, { saved = JSONObject(it.toString()) }, {}).fetch(ref, 0)
            fail("Must fail")
        } catch (_: CommentFetchFailure) { }
        val calls = mutableListOf<String>()
        val result = CommentFetcher(cfg, mapper(), { op, vars ->
            calls += "$op:${vars.getValue("parentId")}:${vars.getValue("cursor")}"
            if (vars.getValue("parentId") == "1") page(14, 1, row("11"), row("12", sticker = "shared")) else page(20, 0, row("21"))
        }, { saved = it }, {}).fetch(ref, 0, resume = saved)
        assertEquals(listOf("reply:1:7", "reply:2:0"), calls)
        assertEquals(2, result.fetchedTopLevel); assertEquals(3, result.fetchedReplies)
        assertEquals("parent_cap", result.replyTermination)
        assertEquals(2, saved.getJSONObject("counters").getInt("parents_fetched"))
        assertEquals(3, saved.getJSONObject("counters").getInt("reply_pages"))
        assertEquals(2, result.comments.count { "sticker:shared" in it.assetIds })
    }

    @Test fun aCompletedCheckpointMakesNoMoreRequests(): Unit = runBlocking {
        var saved = JSONObject()
        val ref = PostRef("douyin", "123")
        val original = CommentFetcher(config(), mapper(), { _, _ -> page(50, 0, row("1")) }, { saved = it }, {}).fetch(ref, 0)
        val restored = CommentFetcher(config(), mapper(), { _, _ -> error("Unexpected HTTP request") }, {}, {}).fetch(ref, 0, resume = saved)
        assertEquals(original, restored)
    }

    @Test fun corruptOrIncompatibleCheckpointsFailBeforeMakingRequests(): Unit = runBlocking {
        var original = JSONObject()
        val ref = PostRef("douyin", "123")
        CommentFetcher(config(), mapper(), { _, _ -> page(50, 0, row("1")) }, { original = it }, {}).fetch(ref, 0)
        val corruptState = JSONObject(original.toString()).apply { getJSONObject("extras").getJSONObject("resume").put("cursor", "888") }
        val corruptLedger = JSONObject(original.toString()).apply { getJSONObject("counters").put("top_unique", 99) }
        for ((state, cfg, target) in listOf(Triple(corruptState, config(), ref), Triple(corruptLedger, config(), ref),
            Triple(original, config().put("max_pages_top", 1), ref), Triple(original, config(), PostRef("douyin", "456")))) {
            try {
                CommentFetcher(cfg, mapper(), { _, _ -> error("Must not request") }, { error("Must not overwrite checkpoint") }, {}).fetch(target, 0, resume = state)
                fail("Must reject")
            } catch (e: AppFailure) { assertEquals(ErrorCode.SCHEMA, e.code) }
        }
    }

    @Test fun malformedPageIsNotPartiallyCommittedAndCanBeRetried(): Unit = runBlocking {
        var saved = JSONObject()
        val ref = PostRef("douyin", "123")
        try {
            CommentFetcher(config(), mapper(), { _, _ -> page(50, 0, row("1"), row("2").put("sticker", JSONObject())) },
                { saved = it }, {}).fetch(ref, 0)
            fail("Must fail")
        } catch (e: CommentFetchFailure) { assertEquals(0, e.partial.fetchedTopLevel) }
        val result = CommentFetcher(config(), mapper(), { _, vars ->
            assertEquals("0", vars["cursor"]); page(50, 0, row("1"), row("2"))
        }, { saved = it }, {}).fetch(ref, 0, resume = saved)
        assertEquals(2, result.fetchedTopLevel)
        assertEquals(1, saved.getJSONObject("counters").getInt("top_pages"))
    }

    @Test fun thePageCapIsCumulativeAcrossRestarts(): Unit = runBlocking {
        val cfg = config().put("max_pages_top", 2)
        var saved = JSONObject()
        val ref = PostRef("douyin", "123")
        try {
            CommentFetcher(cfg, mapper(), { _, vars -> if (vars["cursor"] == "0") page(50, 1, row("1")) else throw CancellationException() },
                { saved = it }, {}).fetch(ref, 0)
        } catch (_: CancellationException) { }
        var calls = 0
        val result = CommentFetcher(cfg, mapper(), { _, vars ->
            assertEquals("50", vars["cursor"]); calls++; page(100, 1, row("2"))
        }, { saved = it }, {}).fetch(ref, 0, resume = saved)
        assertEquals(1, calls); assertEquals("page_cap", result.topTermination)
        assertEquals(2, saved.getJSONObject("counters").getInt("top_pages"))
    }

    @Test fun duplicateRepliesDoNotDiscardNewRowsBeforeTheReplyCap() {
        val result = run(listOf(page(50, 0, row("1", 10)), page(20, 1, row("11"), row("11"), row("12"))),
            config().put("max_replies_total", 2))
        assertEquals(2, result.result.fetchedReplies)
        assertEquals("replies_cap", result.result.replyTermination)
    }

    @Test fun repliesFirstDrainsDiscoveredParentsBeforeTheNextTopPageWithoutLosingEitherCursor() {
        val run = run(listOf(page(39, 1, row("1", 2), row("2", 1)), page(7, 1, row("11")),
            page(15, 0, row("12")), page(8, 0, row("21")), page(81, 0, row("3", 1)), page(9, 0, row("31"))),
            order = CommentOrder.REPLIES_FIRST)
        assertEquals(listOf("comment", "reply", "reply", "reply", "comment", "reply"), run.calls.map { it.first })
        assertEquals(listOf("0", "0", "7", "0", "39", "0"), run.calls.map { it.second["cursor"] })
        assertEquals(listOf(null, "1", "1", "2", null, "3"), run.calls.map { it.second["parentId"] })
        assertEquals(listOf("50", "20", "20", "20", "50", "20"), run.calls.map { it.second["count"] })
        assertEquals(listOf(250L, 250L, 400L, 250L), run.delays)
        assertEquals(3, run.result.fetchedTopLevel); assertEquals(4, run.result.fetchedReplies)
        assertEquals("server_end", run.result.termination)
        assertEquals("REPLIES_FIRST", run.ledger.getJSONObject("extras").getString("search_order"))
    }

    @Test fun repliesFirstStillChecksWholeTopPagesAndEveryDiscoveredParentsRepliesAtTheTopLimit() {
        val run = run(listOf(page(39, 1, row("1", 1), row("2", 1)), page(5, 0, row("11")), page(8, 0, row("21"))),
            limit = 1, order = CommentOrder.REPLIES_FIRST)
        assertEquals(listOf("comment", "reply", "reply"), run.calls.map { it.first })
        assertEquals(2, run.result.fetchedTopLevel); assertEquals(2, run.result.fetchedReplies)
        assertEquals("limit_reached", run.result.topTermination)
    }

    @Test fun reachingTheReplyCapDoesNotDiscardRemainingTopPagesInRepliesFirstMode() {
        val run = run(listOf(page(39, 1, row("1", 3), row("2", 1)), page(9, 1, row("11")),
            page(81, 0, row("3", 2))), config().put("max_replies_total", 1), order = CommentOrder.REPLIES_FIRST)
        assertEquals(listOf("comment", "reply", "comment"), run.calls.map { it.first })
        assertEquals("39", run.calls.last().second["cursor"])
        assertEquals(3, run.result.fetchedTopLevel); assertEquals(1, run.result.fetchedReplies)
        assertEquals("server_end", run.result.topTermination); assertEquals("replies_cap", run.result.replyTermination)
    }

    @Test fun repliesFirstSkipsParentsWithoutRepliesAndContinuesAfterAStalledParent() {
        val run = run(listOf(page(39, 1, row("1")), page(81, 1, row("2", 1)),
            page(0, 1, row("21")), page(120, 0, row("3", 1)), page(7, 0, row("31"))),
            order = CommentOrder.REPLIES_FIRST)
        assertEquals(listOf("comment", "comment", "reply", "comment", "reply"), run.calls.map { it.first })
        assertEquals(3, run.result.fetchedTopLevel); assertEquals(2, run.result.fetchedReplies)
        assertEquals("stalled_cursor", run.result.replyTermination)
    }

    @Test fun resumingRepliesFirstPreservesThePendingTopCursorAndDoesNotRefetchCompletedReplies(): Unit = runBlocking {
        var saved = JSONObject()
        val ref = PostRef("douyin", "123")
        try {
            CommentFetcher(config(), mapper(), { op, vars -> when {
                op == "comment" -> page(39, 1, row("1", 2))
                vars["cursor"] == "0" -> page(7, 1, row("11"))
                else -> throw AppFailure(ErrorCode.NETWORK, "interrupted")
            } }, { saved = JSONObject(it.toString()) }, {}).fetch(ref, 0, order = CommentOrder.REPLIES_FIRST)
            fail("Must fail")
        } catch (e: CommentFetchFailure) {
            assertEquals(1, e.partial.fetchedReplies)
            assertEquals("error", e.partial.topTermination); assertEquals("error", e.partial.replyTermination)
        }
        val calls = mutableListOf<String>()
        val result = CommentFetcher(config(), mapper(), { op, vars ->
            calls += "$op:${vars.getValue("cursor")}"
            if (op == "reply") page(15, 0, row("12")) else page(81, 0, row("2"))
        }, { saved = it }, {}).fetch(ref, 0, resume = saved, order = CommentOrder.REPLIES_FIRST)
        assertEquals(listOf("reply:7", "comment:39"), calls)
        assertEquals(2, result.fetchedTopLevel); assertEquals(2, result.fetchedReplies)
        assertEquals("server_end", result.termination)
        assertEquals("done", saved.getJSONObject("extras").getJSONObject("resume").getString("phase"))
        val restored = CommentFetcher(config(), mapper(), { _, _ -> error("Unexpected request") }, {}, {})
            .fetch(ref, 0, resume = saved, order = CommentOrder.REPLIES_FIRST)
        assertEquals(result, restored)
        try {
            CommentFetcher(config(), mapper(), { _, _ -> error("Unexpected request") }, {}, {}).fetch(ref, 0, resume = saved)
            fail("Must reject a different order")
        } catch (e: AppFailure) { assertEquals(ErrorCode.SCHEMA, e.code) }
    }

    @Test fun aShortPublicHandleIsSearchableWhenThePreferredFieldIsEmptyAndMappingsRemainHotUpdatable() {
        val data = row("1", sticker = "s").apply {
            getJSONObject("user").put("unique_id", "").put("short_id", "731234567")
        }
        val run = run(listOf(page(50, 0, data)))
        assertEquals(listOf("1"), run.result.matching(CommentTarget.AUTHOR_NAME, "抖音号：731234567").map { it.id })
        assertEquals(run.result, CommentResultJson.decode(CommentResultJson.encode(run.result)))
        val mappings = JSONObject(File("../bundle_src/parsers/douyin.json").readText())
            .put("authorShortId", JSONArray().put("user").put("alternate_handle"))
        val remapped = JSONObject(data.toString()).apply { getJSONObject("user").remove("short_id"); getJSONObject("user").put("alternate_handle", "Ab_123") }
        assertTrue("Ab_123" in CommentParser(JsonMapper(mappings), config()).identity(remapped).ids)
        mappings.remove("authorShortId")
        assertFalse("Ab_123" in CommentParser(JsonMapper(mappings), config()).identity(remapped).ids)
    }
}
