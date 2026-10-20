package com.emojetools.app.platform.douyin

import com.emojetools.app.core.*
import com.emojetools.app.core.net.JsonMapper
import com.emojetools.app.core.store.FavoriteCollection
import com.emojetools.app.core.store.FavoriteCollectionStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FavoriteStickerFetcherTest {
    private fun config() = JSONObject(File("../bundle_src/platforms/douyin.json").readText())
    private fun mappings() = JSONObject(File("../bundle_src/parsers/douyin.json").readText())
    private fun sticker(id: String, animated: Boolean = false) = JSONObject().put("id_str", id)
        .put(if (animated) "animate_url" else "static_url", JSONObject().put("url_list", JSONArray(listOf("https://cdn.example/$id", "https://mirror.example/$id"))))
    private fun page(next: String, complete: Boolean, vararg rows: JSONObject) = JSONObject().put("custom_sticker_page_list",
        JSONObject().put("resources", JSONArray().put(JSONObject().put("stickers", JSONArray(rows.toList()))))
            .put("next_cursor", next).put("is_completed", complete))
    private suspend fun parse(json: JSONObject, paths: JSONObject = mappings()) = FavoriteStickerFetcher(config(), JsonMapper(paths), { operation, vars ->
        assertEquals("favorites", operation); assertEquals("50", vars["count"]); json
    }).page("0")

    @Test fun responseCursorsDrivePaginationAndRepeatedItemsKeepSelectionAndMirrors() = runBlocking<Unit> {
        val requests = mutableListOf<String>()
        val fetcher = FavoriteStickerFetcher(config(), JsonMapper(mappings()), { _, variables ->
            requests += variables.getValue("cursor")
            when (variables["cursor"]) {
                "0" -> page("opaque-next", false, sticker("1"), sticker("2", true))
                "opaque-next" -> page("end", true, sticker("2", true), sticker("3"))
                else -> error("Must use the response cursor")
            }
        })
        var state = FavoriteCollection("session", "key", "account").append(fetcher.page("0"), 200)
        state = state.select("favorite:2", true)
        state = state.append(fetcher.page(state.cursor), 200)
        assertEquals(listOf("0", "opaque-next"), requests)
        assertEquals(listOf("favorite:1", "favorite:2", "favorite:3"), state.assets.map { it.id })
        assertEquals(setOf("favorite:2"), state.selected); assertTrue(state.completed); assertFalse(state.stopped)
        assertTrue(state.assets.all { it.urls.size == 2 && it.ext == null })
    }

    @Test fun failureDoesNotAdvanceTheCursorAndCanRetryTheSamePage() = runBlocking<Unit> {
        var attempts = 0
        val fetcher = FavoriteStickerFetcher(config(), JsonMapper(mappings()), { _, variables ->
            assertEquals("next", variables["cursor"])
            if (attempts++ == 0) throw AppFailure(ErrorCode.NETWORK, "offline")
            page("done", true, sticker("2"))
        })
        var state = FavoriteCollection("session", "key", "account").append(parse(page("next", false, sticker("1"))), 200).select("favorite:1", true)
        try { state = state.append(fetcher.page(state.cursor), 200); fail("Must expose network failure") }
        catch (e: AppFailure) { assertEquals(ErrorCode.NETWORK, e.code) }
        assertEquals("next", state.cursor); assertEquals(setOf("favorite:1"), state.selected)
        state = state.append(fetcher.page(state.cursor), 200)
        assertEquals(2, state.assets.size); assertTrue(state.completed)
    }

    @Test fun stalledAndCyclicCursorsKeepTheLastPageAndStopWithoutReportingCompletion() = runBlocking<Unit> {
        var state = FavoriteCollection("s", "k", "a").append(parse(page("next", false, sticker("1"))), 200)
        state = state.select("favorite:1", true).append(parse(page("0", false, sticker("2"))), 200)
        assertTrue(state.stopped); assertFalse(state.completed); assertEquals(2, state.assets.size)
        assertEquals(setOf("favorite:1"), state.selected)
        val capped = FavoriteCollection("s", "k", "a").append(parse(page("next", false, sticker("1"))), 1)
        assertTrue(capped.stopped); assertFalse(capped.completed)
    }

    @Test fun validEmptyCollectionIsDifferentFromMissingOrMalformedCollection() = runBlocking<Unit> {
        assertTrue(parse(page("0", true)).assets.isEmpty())
        val bad = listOf(JSONObject(), page("0", true).apply { getJSONObject("custom_sticker_page_list").remove("resources") },
            page("0", true).apply { getJSONObject("custom_sticker_page_list").put("is_completed", 1) },
            page("0", true, JSONObject().put("id_str", "1")),
            page("0", true, sticker("1").put("static_url", JSONObject().put("url_list", "invalid"))))
        bad.forEach { json ->
            try { parse(json); fail("Malformed data cannot become an empty success") }
            catch (e: AppFailure) { assertEquals(ErrorCode.SCHEMA, e.code) }
        }
    }

    @Test fun animatedSourcesStayAnimatedAndRawMappingsCanBeHotUpdated() = runBlocking<Unit> {
        val row = sticker("1", true).put("static_url", JSONObject().put("url_list", JSONArray(listOf("https://cdn.example/still"))))
        val result = parse(page("0", true, row)).assets.single()
        assertTrue(result.urls.all { "/1" in it }); assertEquals("https://cdn.example/still", result.meta["thumbnail"])
        assertEquals("true", result.meta["animated"])
        row.put("new_id", row.remove("id_str"))
        val changed = parse(page("0", true, row), mappings().put("favoriteId", JSONArray(listOf("new_id"))))
        assertEquals(result.id, changed.assets.single().id)
    }

    @Test fun cacheAndSelectionsSurviveRestorationAndAreDiscardedForAnotherSession() = runBlocking<Unit> {
        val root = Files.createTempDirectory("favorite-cache").toFile()
        try {
            val store = FavoriteCollectionStore(File(root, "collection.json"))
            val state = FavoriteCollection("session-a", "key-a", "Alice").append(parse(page("next", false, sticker("1"))), 200).select("favorite:1", true)
            store.write(state)
            assertEquals(state, store.read("session-a"))
            assertNull(store.read("session-b")); assertNull(store.read("session-a"))
        } finally { root.deleteRecursively() }
    }
}
