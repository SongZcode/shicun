package com.emojetools.app.platform.douyin

import com.emojetools.app.core.*
import com.emojetools.app.core.download.AssetNames
import com.emojetools.app.core.net.JsonMapper
import com.emojetools.app.core.store.PostMediaJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PostParserTest {
    private fun mappings() = JSONObject(File("../bundle_src/parsers/douyin.json").readText())
    private fun parser(mapping: JSONObject = mappings()) = PostParser(JsonMapper(mapping), JSONObject(File("../bundle_src/platforms/douyin.json").readText()))
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResourceAsStream("post/$name.json")!!.bufferedReader().use { it.readText() })
    private val videoRef = PostRef("douyin", "1000000000000000001")
    private val imageRef = PostRef("douyin", "1000000000000000002")

    @Test fun renditionsAreSortedAndFullSoundtrackUsesTheOriginalVideoNotMusic() {
        val media = parser().parse(videoRef, fixture("video"))
        val videos = media.assets.filter { it.kind == AssetKind.VIDEO }
        assertEquals(listOf(160, 128, 80), videos.map { it.width })
        assertEquals(2, videos.first().urls.size)
        val complete = media.assets.first { it.meta["derivedFrom"] == videos.first().id }
        assertEquals(videos.first().urls, complete.urls)
        assertEquals("complete_audio", complete.meta["role"])
        val music = media.assets.single { it.meta["role"] == "background_music" }
        assertNotEquals(complete.urls, music.urls)
        assertTrue(music.id.endsWith("1000000000000000099"))
        assertEquals(3000L, media.post.durationMs)
        assertEquals(151L, media.post.stats["comments"])
        assertEquals(listOf(PostPart(videoRef.id, media.post.title)), media.post.parts)
        assertEquals(media, PostMediaJson.decode(PostMediaJson.encode(media)))
    }

    @Test fun galleryUsesAllOriginalImagesAndDoesNotTreatPreviewAsVideo() {
        val media = parser().parse(imageRef, fixture("images"))
        assertEquals(4, media.assets.count { it.kind == AssetKind.IMAGE })
        assertFalse(media.assets.any { it.kind == AssetKind.VIDEO || it.meta["role"] == "complete_audio" })
        assertEquals(1078, media.assets.first().width)
        assertEquals(1747, media.assets.first().height)
        assertEquals(2, media.assets.first().urls.size)
        assertTrue(media.assets.any { it.meta["role"] == "background_music" })
    }

    @Test fun syntheticThirteenRatesPreserveAllRenditionsAndChooseLargestPixels() {
        val json = fixture("video")
        val rates = JSONArray()
        repeat(13) { n -> rates.put(JSONObject().put("gear_name", "fixture_$n").put("bit_rate", 100000 + n)
            .put("play_addr", JSONObject().put("uri", "fixture-$n").put("width", 320 + n * 10).put("height", 240)
                .put("url_list", JSONArray().put("https://example.invalid/$n.mp4")))) }
        rates.getJSONObject(5).put("gear_name", "normal_1080_0").getJSONObject("play_addr").put("width", 1440).put("height", 1080)
        json.getJSONObject("aweme_detail").getJSONObject("video").put("bit_rate", rates)
        val videos = parser().parse(videoRef, json).assets.filter { it.kind == AssetKind.VIDEO }
        assertEquals(13, videos.size)
        assertEquals("normal_1080_0", videos.first().meta["gear"])
        assertEquals("v01_1080p", AssetNames.stem(videos.first(), 1))
    }

    @Test fun missingRenditionsUseConfiguredDefaultAddressAndMissingCoverIsOptional() {
        val json = fixture("video")
        val video = json.getJSONObject("aweme_detail").getJSONObject("video")
        val address = video.getJSONArray("bit_rate").getJSONObject(1).getJSONObject("play_addr")
        video.remove("bit_rate"); video.remove("cover"); video.put("play_addr", address)
        val media = parser().parse(videoRef, json)
        assertEquals(1, media.assets.count { it.kind == AssetKind.VIDEO })
        assertNull(media.post.coverUrl)
    }

    @Test fun duplicateRenditionsMergeMirrorsByUnderlyingIdentity() {
        val json = fixture("video")
        val rates = json.getJSONObject("aweme_detail").getJSONObject("video").getJSONArray("bit_rate")
        val duplicate = JSONObject(rates.getJSONObject(0).toString())
        duplicate.getJSONObject("play_addr").put("url_list", JSONArray().put("https://example.invalid/extra-mirror.mp4"))
        rates.put(duplicate)
        val videos = parser().parse(videoRef, json).assets.filter { it.kind == AssetKind.VIDEO }
        assertEquals(3, videos.size)
        assertTrue(videos.any { "https://example.invalid/extra-mirror.mp4" in it.urls && it.urls.size > 1 })
    }

    @Test fun unavailableMusicDoesNotInventASeparateSource() {
        val json = fixture("video")
        json.getJSONObject("aweme_detail").remove("music")
        assertFalse(parser().parse(videoRef, json).assets.any { it.meta["role"] == "background_music" })
    }

    @Test fun malformedOrMismatchedDetailsFailRatherThanReturningAnEmptySuccess() {
        val invalid = listOf(fixture("video").apply { getJSONObject("aweme_detail").put("aweme_id", "wrong") },
            fixture("video").apply { getJSONObject("aweme_detail").getJSONObject("video").put("bit_rate", JSONObject()) },
            fixture("video").apply { getJSONObject("aweme_detail").remove("video") })
        invalid.forEach { json ->
            try { parser().parse(videoRef, json); fail("Must fail") } catch (e: AppFailure) { assertEquals(ErrorCode.SCHEMA, e.code) }
        }
    }

    @Test fun mappedFieldNamesCanChangeAndIdsKeepStringPrecision() {
        val json = fixture("video")
        json.getJSONObject("aweme_detail").put("new_title", "Renamed source field")
        val mapping = mappings().put("title", JSONArray().put("aweme_detail").put("new_title"))
        val media = parser(mapping).parse(videoRef, json)
        assertEquals("Renamed source field", media.post.title)
        assertEquals(videoRef.id, media.post.ref.id)
        assertTrue(AssetNames.stem(media.assets.first { it.meta["role"] == "background_music" }, 1).matches(Regex("[A-Za-z0-9._-]+")))
    }
}
