package com.emojetools.app.platform.douyin

import com.emojetools.app.core.*
import com.emojetools.app.core.download.WorkBatchDownloader
import com.emojetools.app.core.net.JsonMapper
import com.emojetools.app.core.store.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AccountWorksFetcherTest {
    private fun config() = JSONObject(File("../bundle_src/platforms/douyin.json").readText())
    private fun mappings() = JSONObject(File("../bundle_src/parsers/douyin.json").readText())
    private fun id(n: Int) = (7000000000000000000L + n).toString()
    private fun row(n: Int) = JSONObject().put("aweme_id", id(n)).put("desc", "Work $n").put("author", JSONObject().put("nickname", "Author $n"))
        .put("video", JSONObject().put("duration", 12000).put("cover", JSONObject().put("url_list", JSONArray(listOf("https://cdn.example/$n.jpg", "https://mirror.example/$n.jpg")))))
    private fun page(cursor: String, more: Int, vararg rows: JSONObject) = JSONObject().put("aweme_list", JSONArray(rows.toList()))
        .put("cursor", cursor).put("has_more", more).put("invalid_item_count", 0)
    private suspend fun parse(json: JSONObject, kind: WorkCollectionKind = WorkCollectionKind.SAVED, paths: JSONObject = mappings()): AccountWorksPage =
        AccountWorksFetcher(config(), JsonMapper(paths), { operation, vars ->
            assertEquals(kind.operation, operation); assertEquals("self-sec", vars["secUid"]); assertEquals("20", vars["count"]); json
        }).page(kind, "self-sec", "0")

    @Test fun savedWorksUseResponseCursorsAndRetainSelectionAcrossDeduplicatedPages() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val fetcher = AccountWorksFetcher(config(), JsonMapper(mappings()), { operation, vars ->
            assertEquals("savedWorks", operation); calls += vars.getValue("cursor")
            when (vars["cursor"]) {
                "0" -> page("opaque-next", 1, row(1), row(2)).put("invalid_item_count", 3)
                "opaque-next" -> page("0", 0, row(2), row(3)).put("invalid_item_count", 1)
                else -> error("Only response cursors are valid")
            }
        })
        var state = AccountWorksCollection(WorkCollectionKind.SAVED, "session", WorkAccount("Alice", "self-sec"))
        state = state.append(fetcher.page(state.kind, state.account.secUid, state.cursor), 200).select(id(2), true)
        state = state.append(fetcher.page(state.kind, state.account.secUid, state.cursor), 200)
        assertEquals(listOf("0", "opaque-next"), calls)
        assertEquals(listOf(id(1), id(2), id(3)), state.works.map { it.ref.id })
        assertEquals(setOf(id(2)), state.selected); assertEquals(4, state.unavailable); assertTrue(state.completed); assertFalse(state.stopped)
    }

    @Test fun likedWorksUseTheirOwnCursorMappingRatherThanTheSavedWorksCursor() = runBlocking<Unit> {
        val json = page("wrong-cursor", 1, row(1)).put("max_cursor", "liked-cursor")
        val liked = parse(json, WorkCollectionKind.LIKED)
        assertEquals("liked-cursor", liked.nextCursor); assertFalse(liked.completed)
        assertEquals(2, liked.works.single().covers.size)
    }

    @Test fun failedPageCanRetryTheSameCursorWithItsPartialListAndSelectionIntact() = runBlocking<Unit> {
        var state = AccountWorksCollection(WorkCollectionKind.SAVED, "s", WorkAccount("Alice", "self-sec"))
            .append(parse(page("next", 1, row(1))), 200).select(id(1), true)
        var attempts = 0
        val fetcher = AccountWorksFetcher(config(), JsonMapper(mappings()), { _, vars ->
            assertEquals("next", vars["cursor"])
            if (attempts++ == 0) throw AppFailure(ErrorCode.NETWORK, "Offline fixture")
            page("0", 0, row(2))
        })
        try { state = state.append(fetcher.page(state.kind, "self-sec", state.cursor), 200); fail("Must expose failure") }
        catch (e: AppFailure) { assertEquals(ErrorCode.NETWORK, e.code) }
        assertEquals("next", state.cursor); assertEquals(setOf(id(1)), state.selected)
        state = state.append(fetcher.page(state.kind, "self-sec", state.cursor), 200)
        assertEquals(2, state.works.size); assertTrue(state.completed)
    }

    @Test fun emptyUnavailableAndMalformedResponsesAreNotConfused() = runBlocking<Unit> {
        val empty = parse(page("0", 0).put("invalid_item_count", 2))
        assertTrue(empty.works.isEmpty()); assertEquals(2, empty.unavailable); assertTrue(empty.completed)
        val invalid = listOf(JSONObject(), page("0", 0).put("aweme_list", JSONObject.NULL), page("0", 3),
            page("0", 0, row(1).put("aweme_id", "invented")), page("0", 0).put("invalid_item_count", -1))
        invalid.forEach { json ->
            try { parse(json); fail("Invalid response must fail") }
            catch (e: AppFailure) { assertEquals(ErrorCode.SCHEMA, e.code) }
        }
    }

    @Test fun cyclicCursorsAndCollectionSpecificCapsKeepTheLastPageAndSelection() = runBlocking<Unit> {
        var state = AccountWorksCollection(WorkCollectionKind.SAVED, "s", WorkAccount("A", "self-sec"))
            .append(parse(page("next", 1, row(1))), 200).select(id(1), true)
        state = state.append(parse(page("0", 1, row(2))), 200)
        assertTrue(state.stopped); assertFalse(state.completed); assertEquals(2, state.works.size); assertEquals(setOf(id(1)), state.selected)
        assertTrue(AccountWorksCollection(WorkCollectionKind.SAVED, "s", state.account).append(parse(page("next", 1, row(1))), 1).stopped)
    }

    @Test fun blankTitlesAndImageCoverFallbackAreSupportedWithHotUpdatedMappings() = runBlocking<Unit> {
        val row = row(1).put("desc", "").put("images", JSONArray().put(JSONObject().put("url_list", JSONArray(listOf("https://cdn.example/image.jpg")))))
        row.getJSONObject("video").remove("cover")
        row.put("renamed_id", row.remove("aweme_id"))
        val work = parse(page("0", 0, row), paths = mappings().put("accountWorkId", JSONArray(listOf("renamed_id")))).works.single()
        assertEquals("", work.title); assertEquals(1, work.imageCount); assertEquals("https://cdn.example/image.jpg", work.cover()!!.urls.single())
    }

    @Test fun twoTabsRestoreIndependentlyAndOtherSessionsCannotReuseTheirLists() = runBlocking<Unit> {
        val root = Files.createTempDirectory("account-work-cache").toFile()
        try {
            val store = AccountWorksStore(root)
            val saved = AccountWorksCollection(WorkCollectionKind.SAVED, "session-a", WorkAccount("A", "sec-a"))
                .append(parse(page("next", 1, row(1))), 200).select(id(1), true)
            val liked = AccountWorksCollection(WorkCollectionKind.LIKED, "session-a", saved.account,
                works = listOf(saved.works.single()), completed = true)
            store.write(saved); store.write(liked)
            assertEquals(saved, store.read(WorkCollectionKind.SAVED, "session-a"))
            assertTrue(store.read(WorkCollectionKind.LIKED, "session-a")!!.selected.isEmpty())
            assertNull(store.read(WorkCollectionKind.SAVED, "session-b")); assertNull(store.read(WorkCollectionKind.SAVED, "session-a"))
            assertNotEquals(saved.account.key, WorkAccount("A", "sec-b").key)
            assertEquals(saved.account.key, WorkAccount("Renamed", "sec-a").key)
            assertEquals(saved.selected, saved.restart().selected)
        } finally { root.deleteRecursively() }
    }

    @Test fun batchDefaultsKeepAllImagesOrTheHighestVideoWithoutAddingAudioAndCovers() {
        val ref = PostRef("douyin", id(1))
        val post = Post(ref, "", "", "", null, null, null, emptyMap(), emptyList())
        fun asset(id: String, kind: AssetKind) = Asset(id, kind, "POST", emptyList(), null, null, null, null)
        val videos = listOf(asset("high", AssetKind.VIDEO), asset("low", AssetKind.VIDEO), asset("music", AssetKind.AUDIO), asset("cover", AssetKind.COVER))
        assertEquals(listOf("high"), WorkBatchDownloader.defaultAssets(PostMedia(post, videos)).map { it.id })
        val images = listOf(asset("1", AssetKind.IMAGE), asset("2", AssetKind.IMAGE), asset("cover", AssetKind.COVER), asset("music", AssetKind.AUDIO))
        assertEquals(listOf("1", "2"), WorkBatchDownloader.defaultAssets(PostMedia(post, images)).map { it.id })
        try { WorkBatchDownloader.defaultAssets(PostMedia(post, emptyList())); fail("Must not succeed with no downloadable content") }
        catch (e: AppFailure) { assertEquals(ErrorCode.UNSUPPORTED, e.code) }
    }
}
