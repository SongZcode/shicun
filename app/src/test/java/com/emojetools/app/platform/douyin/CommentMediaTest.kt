package com.emojetools.app.platform.douyin

import com.emojetools.app.core.*
import com.emojetools.app.core.net.JsonMapper
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CommentMediaTest {
    private fun config() = JSONObject(File("../bundle_src/platforms/douyin.json").readText())
    private fun mapping() = JSONObject(File("../bundle_src/parsers/douyin.json").readText())
    private fun row() = JSONObject(File("src/test/resources/comments/rich-media.json").readText())
    private fun parser(mappings: JSONObject = mapping()) = CommentParser(JsonMapper(mappings), config())

    @Test fun stickerMotionComesFromMappedSourcesRatherThanItsFilename() {
        val still = JSONObject().put("cid", "1").put("sticker", JSONObject().put("id_str", "s")
            .put("static_url", JSONObject().put("url_list", JSONArray(listOf("https://cdn.example/s.gif~original.heif")))))
        val staticAsset = parser().parse(still, null).second.single()
        assertEquals("false", staticAsset.meta["animated"])
        val moving = JSONObject(still.toString()).apply { getJSONObject("sticker")
            .put("animate_url", JSONObject().put("url_list", JSONArray(listOf("https://cdn.example/moving")))) }
        val animatedAsset = parser().parse(moving, null).second.single()
        assertEquals("true", animatedAsset.meta["animated"])
        assertEquals(listOf("https://cdn.example/moving"), animatedAsset.urls)
    }

    @Test fun multipleImagesAndLiveMotionStayAssociatedWithTheSameComment() {
        val (comment, assets) = parser().parse(row(), "parent")
        assertEquals(listOf(AssetKind.IMAGE, AssetKind.VIDEO, AssetKind.IMAGE), assets.map { it.kind })
        assertEquals(assets.map { it.id }, comment.assetIds)
        assertEquals("parent", comment.parentId)
        assertTrue(assets.all { it.meta["level"] == "R" && it.meta["commentId"] == comment.id })
        val motion = assets[1]
        assertEquals(assets[0].id, motion.meta["liveImageId"])
        assertEquals("live_photo_motion", motion.meta["role"])
        assertEquals(41331L, motion.bytes)
        assertEquals(2, motion.urls.size)
        assertEquals(1080, motion.width); assertEquals(1208, motion.height)
        assertEquals(2160, assets[2].width); assertEquals(2880, assets[2].height)
        assertTrue(assets.flatMap { it.urls }.none { "watermark" in it })
    }

    @Test fun sharedLiveMediaDedupesWithoutLosingItsCommentAssociations(): Unit = runBlocking {
        val first = row(); val second = row().put("cid", "8000000000000000002")
        second.getJSONArray("image_list").getJSONObject(0).getJSONObject("live_video").getJSONArray("url_list")
            .put("https://backup.example.invalid/motion-1.mp4")
        val page = JSONObject().put("comments", JSONArray().put(first).put(second)).put("cursor", 50).put("has_more", 0).put("total", 2)
        val result = CommentFetcher(config(), JsonMapper(mapping()), { _, _ -> page }, {}, {}).fetch(PostRef("douyin", "123"), 0)
        assertEquals(3, result.assets.size); assertEquals(2, result.comments.size)
        assertTrue(result.comments.all { it.assetIds == result.assets.map(Asset::id) })
        assertEquals(3, result.assets.single { it.kind == AssetKind.VIDEO }.urls.size)
    }

    @Test fun malformedMotionDoesNotSilentlyBecomeAStillImage() {
        listOf<Any>("changed-schema", JSONObject(), JSONObject().put("url_list", JSONArray()).put("size", 1)).forEach { value ->
            val row = row().apply { getJSONArray("image_list").getJSONObject(0).put("live_video", value) }
            try { parser().parse(row, null); fail("Invalid live media must fail") }
            catch (e: AppFailure) { assertEquals(ErrorCode.SCHEMA, e.code) }
        }
    }

    @Test fun motionFieldPathsCanChangeInTheBundle() {
        val mappings = mapping().put("imageMotionObject", JSONArray(listOf("motion")))
            .put("imageMotionUrls", JSONArray(listOf("motion", "mirrors", "*")))
            .put("imageMotionBytes", JSONArray(listOf("motion", "bytes")))
        val row = row()
        val image = row.getJSONArray("image_list").getJSONObject(0)
        val motion = image.remove("live_video") as JSONObject
        image.put("motion", JSONObject().put("mirrors", motion.getJSONArray("url_list")).put("bytes", 41331))
        assertEquals(2, parser(mappings).parse(row, null).second.single { it.kind == AssetKind.VIDEO }.urls.size)
    }

    @Test fun rollbackToAnOlderParserBundlePreservesStaticImages() {
        val mappings = mapping().apply { listOf("imageMotionObject", "imageMotionUrls", "imageMotionBytes").forEach(::remove) }
        assertEquals(listOf(AssetKind.IMAGE, AssetKind.IMAGE), parser(mappings).parse(row(), null).second.map { it.kind })
    }
}
