package com.emojetools.app.platform.douyin

import com.emojetools.app.core.*
import com.emojetools.app.core.net.JsonMapper
import org.json.JSONObject
import java.net.URI

class PostParser(private val mapper: JsonMapper, config: JSONObject) {
    private val suffix = Regex(config.getString("identity_path_suffix_regex"))

    fun parse(ref: PostRef, response: JSONObject, prefs: QualityPrefs = QualityPrefs()): PostMedia {
        if (mapper.required("detail", response) !is JSONObject) fail("Invalid work detail")
        if (text("postId", response, true) != ref.id) fail("Work ID does not match request")
        val title = text("title", response, true)
        val images = mapper.values("galleryImages", response).mapIndexed { index, value ->
            val image = value as? JSONObject ?: fail("Invalid original image")
            val urls = urls("galleryUrls", image)
            if (urls.isEmpty()) fail("Original image has no URL")
            val identity = text("galleryUri", image).ifBlank { identity(urls.first()) }
            Asset("image:$identity", AssetKind.IMAGE, "POST", urls, null, dimension("galleryWidth", image),
                dimension("galleryHeight", image), null, urls.first(), mapOf("index" to (index + 1).toString()))
        }
        val videos = if (images.isNotEmpty()) emptyList() else {
            val rates = mapper.values("videoRates", response).map { value ->
                val rate = value as? JSONObject ?: fail("Invalid video rendition")
                val mirrors = urls("rateUrls", rate)
                if (mirrors.isEmpty()) fail("Video rendition has no URL")
                val gear = text("rateName", rate, true)
                val bitrate = number("rateBitrate", rate) ?: fail("Missing video bitrate")
                val uri = text("rateUri", rate).ifBlank { identity(mirrors.first()) }
                val codec = number("rateCodec", rate)?.toString().orEmpty()
                video("${ref.id}:$gear:$bitrate:$codec:$uri", mirrors, dimension("rateWidth", rate),
                    dimension("rateHeight", rate), number("rateBytes", rate), gear, bitrate)
            }
            if (rates.isNotEmpty()) rates else {
                val mirrors = urls("defaultVideoUrls", response)
                if (mirrors.isEmpty()) fail("Work contains no downloadable media")
                val uri = text("defaultVideoUri", response).ifBlank { identity(mirrors.first()) }
                listOf(video("${ref.id}:$uri", mirrors, dimension("defaultVideoWidth", response),
                    dimension("defaultVideoHeight", response), number("defaultVideoBytes", response), "original", 0))
            }
        }.groupBy { it.id }.values.map { same -> same.first().copy(urls = same.flatMap { it.urls }.distinct()) }
            .sortedWith(compareByDescending<Asset> { (it.width ?: 0).toLong() * (it.height ?: 0) }
            .thenByDescending { it.meta.getValue("bitrate").toLong() }).let { if (prefs.preferHighest) it else it.reversed() }
        val covers = urls("coverUrls", response).ifEmpty { images.firstOrNull()?.urls.orEmpty() }
        val cover = covers.takeIf { it.isNotEmpty() }?.let {
            Asset("cover:${ref.id}", AssetKind.COVER, "POST", it, null, null, null, null)
        }
        val soundtracks = videos.map { source ->
            Asset("audio:${source.id}", AssetKind.AUDIO, "POST", source.urls, "m4a", null, null, null,
                meta = mapOf("derivedFrom" to source.id, "transform" to "complete_audio", "role" to "complete_audio", "sourceId" to ref.id))
        }
        val musicUrls = urls("musicUrls", response)
        val musicId = text("musicId", response).ifBlank { number("musicNumericId", response)?.toString().orEmpty() }
        val music = musicUrls.takeIf { it.isNotEmpty() }?.let { mirrors ->
            val id = musicId.ifBlank { text("musicUri", response).ifBlank { identity(mirrors.first()) } }
            Asset("music:$id", AssetKind.AUDIO, "POST", mirrors, null, null, null, null,
                meta = mapOf("role" to "background_music", "sourceId" to id, "title" to text("musicTitle", response)))
        }
        val stats = number("detailCommentCount", response)?.let { mapOf("comments" to it) }.orEmpty()
        val post = Post(ref, title, text("postAuthor", response), text("postAuthorId", response), covers.firstOrNull(),
            number("postDurationMs", response), number("postPublishedAt", response), stats, listOf(PostPart(ref.id, title)))
        return PostMedia(post, videos + images + soundtracks + listOfNotNull(music, cover))
    }

    private fun video(id: String, urls: List<String>, width: Int?, height: Int?, bytes: Long?, gear: String, bitrate: Long): Asset {
        val quality = if (width != null && height != null) "${minOf(width, height)}p" else "original"
        return Asset("video:$id", AssetKind.VIDEO, "POST", urls, "mp4", width, height, bytes,
            meta = mapOf("gear" to gear, "quality" to quality, "bitrate" to bitrate.toString()))
    }
    private fun text(key: String, source: Any, required: Boolean = false): String = when (val value = mapper.optional(key, source)) {
        null -> if (required) fail("Missing mapped field: $key") else ""
        is String -> value
        else -> fail("Invalid mapped text: $key")
    }
    private fun number(key: String, source: Any): Long? = mapper.optional(key, source)?.let { CommentParser.integer(it, key) }
    private fun dimension(key: String, source: Any): Int? = number(key, source)?.let {
        if (it > Int.MAX_VALUE) fail("Invalid media dimension")
        it.toInt().takeIf { value -> value > 0 }
    }
    private fun urls(key: String, source: Any): List<String> = mapper.values(key, source).map {
        val value = it as? String ?: fail("Invalid mapped URL: $key")
        val uri = try { URI(value) } catch (_: Exception) { fail("Invalid media URL") }
        if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) fail("Invalid media URL")
        value
    }.distinct()
    private fun identity(url: String): String = suffix.replace(URI(url).rawPath.trim('/'), "")
        .ifBlank { fail("Missing media identity") }
    private fun fail(message: String): Nothing = throw AppFailure(ErrorCode.SCHEMA, message)
}
