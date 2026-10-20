package com.emojetools.app.platform.bilibili

import com.emojetools.app.core.*
import com.emojetools.app.core.js.JsEngine
import com.emojetools.app.core.net.*
import com.emojetools.app.platform.PlatformAdapter
import com.emojetools.app.core.store.Session
import com.emojetools.app.core.bundle.CanonicalJson
import com.emojetools.app.core.store.WorkAccount
import com.emojetools.app.platform.douyin.AccountWork
import com.emojetools.app.platform.douyin.AccountWorksPage
import com.emojetools.app.platform.douyin.WorkCollectionKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class BilibiliAdapter(val config: JSONObject, private val mapper: JsonMapper, private val js: JsEngine,
    private val transport: HttpTransport, private val session: () -> Session = { Session("", config.getString("ua")) }) : PlatformAdapter {
    override val id = "bilibili"
    private val input = config.getJSONObject("input")
    private val lock = Mutex()
    private var detail: JSONObject? = null
    private var detailId = ""
    private var detailAt = 0L
    private var wbi: JSONObject? = null
    private var wbiSession = ""
    private var season: JSONObject? = null
    private var seasonId = ""
    private var seasonAt = 0L
    override fun matches(rawInput: String) = Regex(input.getString("id_regex")).matches(rawInput.trim()) ||
        Regex(input.getString("url_regex")).containsMatchIn(rawInput)

    override suspend fun resolve(rawInput: String): PostRef {
        var value = rawInput.trim()
        var page = 1
        if (!Regex(input.getString("id_regex")).matches(value)) {
            var url = Regex(input.getString("url_regex")).find(value)?.value
                ?: throw AppFailure(ErrorCode.UNSUPPORTED, "请输入 B 站视频链接或 BV 号")
            val hosts = input.getJSONArray("hosts").let { array -> (0 until array.length()).map(array::getString) }
            var found = false
            for (hop in 0..input.getInt("max_redirects")) {
                val parsed = url.toHttpUrl().newBuilder().scheme("https").build()
                url = parsed.toString()
                if (parsed.scheme != "https" || parsed.host !in hosts || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty())
                    throw AppFailure(ErrorCode.UNSUPPORTED, "不支持的 B 站链接跳转")
                val match = Regex(input.getString("post_regex")).find(parsed.encodedPath)
                    ?: input.optString("pgc_regex").takeIf(String::isNotBlank)?.let { Regex(it).find(parsed.encodedPath) }
                if (match != null) {
                    value = match.groupValues[1]
                    page = parsed.queryParameter(input.getString("page_parameter"))?.toIntOrNull() ?: 1
                    found = true; break
                }
                if (hop == input.getInt("max_redirects")) break
                url = transport.consume(Request.Builder().url(url).header("User-Agent", config.getString("ua")).build()) { response ->
                    if (response.code !in listOf(301,302,303,307,308)) throw AppFailure(ErrorCode.NOT_FOUND, "未能识别 B 站视频")
                    parsed.resolve(response.header("Location") ?: throw AppFailure(ErrorCode.SCHEMA, "跳转缺少地址"))?.toString()
                        ?: throw AppFailure(ErrorCode.SCHEMA, "无效跳转")
                }
            }
            if (!found) throw AppFailure(ErrorCode.UNSUPPORTED, "B 站链接跳转次数过多")
        }
        val pgc = input.optString("pgc_id_regex").takeIf(String::isNotBlank)?.let { Regex(it).matchEntire(value) }
        if (pgc != null) {
            val row = getSeason(pgc.groupValues[2], pgc.groupValues[1].equals("ep", true))
            val episodes = episodes(row)
            val episode = if (pgc.groupValues[1].equals("ep", true)) episodes.firstOrNull { text("episodeId", it) == pgc.groupValues[2] }
                else episodes.firstOrNull()
            return episodeRef(row, episode ?: throw AppFailure(ErrorCode.NOT_FOUND, "未找到可选正片"))
        }
        val row = getDetail(value)
        val pages = mapper.values("pages", row)
        val part = pages.getOrNull(page - 1) ?: throw AppFailure(ErrorCode.NOT_FOUND, "该分 P 不存在")
        val aid = text("aid", row); val cid = text("cid", part)
        if (!aid.matches(Regex("[0-9]+")) || !cid.matches(Regex("[0-9]+")) || text("bvid", row).isBlank())
            throw AppFailure(ErrorCode.SCHEMA, "B 站视频标识缺失")
        return PostRef(id, aid, mapOf("bvid" to text("bvid", row), "cid" to cid))
    }

    private suspend fun getSeason(value: String, episode: Boolean): JSONObject = lock.withLock {
        val pgc = config.optJSONObject("pgc") ?: throw AppFailure(ErrorCode.UNSUPPORTED, "当前规则不支持番剧和电影，请更新规则")
        val key = "$episode:$value"
        season?.takeIf { seasonId == key && System.currentTimeMillis() - seasonAt < 60_000 }?.let { return@withLock it }
        val row = request("season", mapOf("idType" to pgc.getString(if (episode) "episode_parameter" else "season_parameter"), "id" to value))
        (mapper.required("seasonResult", row) as? JSONObject ?: throw AppFailure(ErrorCode.SCHEMA, "剧集信息异常")).also {
            season = it; seasonId = key; seasonAt = System.currentTimeMillis()
        }
    }
    private fun episodes(row: JSONObject) = (mapper.values("episodes", row) + mapper.values("extraEpisodes", row)).distinctBy { text("episodeId", it) }
    private fun episodeTitle(row: Any) = listOf(text("episodeTitle", row), text("episodeLongTitle", row)).filter(String::isNotBlank).joinToString(" · ")
    private fun episodeRef(season: JSONObject, row: Any): PostRef {
        val aid = text("aid", row); val cid = text("cid", row); val episode = text("episodeId", row)
        if (listOf(aid, cid, episode, text("seasonId", season)).any { !it.matches(Regex("[0-9]+")) })
            throw AppFailure(ErrorCode.SCHEMA, "剧集标识缺失")
        return PostRef(id, aid, mapOf("bvid" to text("bvid", row), "cid" to cid, "episode" to episode, "season" to text("seasonId", season)))
    }
    suspend fun selectPart(ref: PostRef, cid: String): PostRef {
        if (!ref.extra.containsKey("episode")) return ref.copy(extra = ref.extra + ("cid" to cid))
        val row = getSeason(ref.extra.getValue("season"), false)
        return episodeRef(row, episodes(row).firstOrNull { text("cid", it) == cid }
            ?: throw AppFailure(ErrorCode.NOT_FOUND, "该集已失效"))
    }
    suspend fun resolveWork(ref: PostRef): PostRef = if (ref.extra.containsKey("cid")) ref else
        resolve(ref.extra["bvid"]?.takeIf(String::isNotBlank) ?: "av${ref.id}")

    private suspend fun getDetail(value: String): JSONObject = lock.withLock {
        detail?.takeIf { detailId == value && System.currentTimeMillis() - detailAt < 60_000 }?.let { return@withLock it }
        val bv = value.startsWith("BV", true)
        val row = request("detail", mapOf("idType" to config.getString(if (bv) "bv_parameter" else "av_parameter"),
            "id" to if (bv) "BV" + value.drop(2) else value.removePrefix("av").removePrefix("AV")))
        (mapper.required("data", row) as? JSONObject ?: throw AppFailure(ErrorCode.SCHEMA, "视频信息异常")).also {
            detail = it; detailId = text("bvid", it); detailAt = System.currentTimeMillis()
        }
    }

    override suspend fun fetchPost(ref: PostRef): Post {
        if (ref.extra.containsKey("episode")) {
            val row = getSeason(ref.extra.getValue("season"), false)
            val episodes = episodes(row)
            val episode = episodes.firstOrNull { text("episodeId", it) == ref.extra.getValue("episode") }
                ?: throw AppFailure(ErrorCode.NOT_FOUND, "该集已失效")
            return Post(ref, text("seasonTitle", row) + " · " + episodeTitle(episode), "", "",
                mediaUrl(text("episodeCover", episode).ifBlank { text("seasonCover", row) }), text("duration", episode).toLongOrNull(),
                null, emptyMap(), episodes.map { PostPart(text("cid", it), episodeTitle(it)) })
        }
        val row = getDetail(ref.extra.getValue("bvid"))
        val pages = mapper.values("pages", row)
        val part = pages.firstOrNull { text("cid", it) == ref.extra.getValue("cid") }
            ?: throw AppFailure(ErrorCode.NOT_FOUND, "分 P 已失效")
        val title = text("title", row) + if (pages.size > 1) " · " + text("partTitle", part) else ""
        return Post(ref, title, text("author", row), text("authorId", row), mediaUrl(text("cover", row)),
            text("duration", part).toLongOrNull()?.times(1000), text("published", row).toLongOrNull(), emptyMap(),
            pages.map { PostPart(text("cid", it), text("partTitle", it)) })
    }

    override suspend fun fetchAssets(ref: PostRef, prefs: QualityPrefs): List<Asset> {
        val pgc = ref.extra.containsKey("episode")
        val response = request(if (pgc) "pgcPlay" else "play", ref.extra)
        val data = mapper.required(if (pgc) "pgcVideo" else "data", response)
        if (mapper.has("drm") && text("drm", data) in listOf("true", "1"))
            throw AppFailure(ErrorCode.UNSUPPORTED, "该资源有 DRM 保护，暂不支持保存")
        if (pgc) {
            val rule = config.getJSONObject("pgc")
            fun states(name: String) = rule.getJSONArray(name).let { a -> (0 until a.length()).map(a::getString) }
            val state = text("pgcPlayState", response)
            if (text("preview", data) in listOf("true", "1") || state in states("preview_states"))
                throw AppFailure(if (session().cookies.isBlank()) ErrorCode.NEED_LOGIN else ErrorCode.UNSUPPORTED,
                    "当前账号只能试看，请确认会员或单独购买权限")
            if (state !in states("whole_states")) throw AppFailure(ErrorCode.UNSUPPORTED, "平台未返回可保存的完整正片")
        }
        val videos = mapper.values("videos", data).filter { text("codecId", it).toIntOrNull() == config.getInt("video_codec") }
            .sortedByDescending { text("bandwidth", it).toLongOrNull() ?: 0 }.distinctBy { text("streamId", it) }
        val audio = mapper.values("audios", data).filter { text("codecs", it).startsWith(config.getString("audio_codec_prefix")) }
            .maxByOrNull { text("bandwidth", it).toLongOrNull() ?: 0 }
        val result = mutableListOf<Asset>()
        if (videos.isNotEmpty() && audio != null) for (video in videos) {
            val key = "video:${ref.extra.getValue("cid")}:${text("streamId", video)}"
            val audioUrls = urls(audio)
            val format = mapper.values("formats", data).firstOrNull { text("formatQuality", it) == text("streamId", video) }
            val label = format?.let { text("formatLabel", it) }.orEmpty().ifBlank { "${text("height", video)}p" }
            result += Asset(key, AssetKind.VIDEO, "POST", urls(video), "mp4", text("width", video).toIntOrNull(),
                text("height", video).toIntOrNull(), null, meta = mapOf("transform" to "dash_mux", "audioUrls" to JSONArray(audioUrls).toString(),
                    "quality" to text("streamId", video), "qualityLabel" to label, "bitrate" to text("bandwidth", video), "platform" to id))
            result += Asset("audio:$key", AssetKind.AUDIO, "POST", audioUrls, "m4a", null, null, null,
                meta = mapOf("derivedFrom" to key, "role" to "complete_audio", "platform" to id))
        } else {
            val segments = mapper.values("segments", data)
            if (segments.size != 1) throw AppFailure(ErrorCode.UNSUPPORTED, "当前视频没有可获取的兼容音视频流")
            val row = segments.single()
            val format = mapper.values("formats", data).firstOrNull { text("formatQuality", it) == text("quality", data) }
            val label = format?.let { text("formatLabel", it) }.orEmpty()
            result += Asset("video:${ref.extra.getValue("cid")}:${text("quality", data)}", AssetKind.VIDEO, "POST",
                (listOf(text("segmentUrl", row)) + mapper.values("segmentBackups", row).map(Any::toString)).filter(String::isNotBlank).map(::mediaUrl),
                "mp4", null, null, null, meta = mapOf("quality" to text("quality", data), "platform" to id) +
                    if (label.isNotBlank()) mapOf("qualityLabel" to label) else emptyMap())
            result += Asset("audio:${result[0].id}", AssetKind.AUDIO, "POST", emptyList(), "m4a", null, null, null,
                meta = mapOf("transform" to "complete_audio", "derivedFrom" to result[0].id, "role" to "complete_audio", "platform" to id))
        }
        val cover = fetchPost(ref).coverUrl
        if (!cover.isNullOrBlank()) result += Asset("cover:${ref.id}", AssetKind.COVER, "POST", listOf(cover), null, null, null, null, meta = mapOf("platform" to id))
        return result
    }

    suspend fun workAccount(): WorkAccount {
        if (!config.has("account_works")) throw AppFailure(ErrorCode.UNSUPPORTED, "当前规则不支持账号作品列表，请更新规则")
        val row = request("nav")
        if (text("loggedIn", row) != "true") throw AppFailure(ErrorCode.NEED_LOGIN, "请登录哔哩哔哩")
        val account = text("accountId", row)
        if (!account.matches(Regex("[0-9]+"))) throw AppFailure(ErrorCode.SCHEMA, "账号信息缺失")
        return WorkAccount(text("accountName", row), account, id)
    }
    suspend fun folders(accountId: String): List<Pair<String, String>> {
        val response = request("folders", mapOf("accountId" to accountId))
        val rows = mapper.optional("folderRows", response)
        if (rows == null && text("folderCount", response) == "0") return emptyList()
        if (rows !is JSONArray) throw AppFailure(ErrorCode.SCHEMA, "收藏夹列表响应不完整，请重试")
        return (0 until rows.length()).map {
            val row = rows.get(it)
            val folder = text("folderId", row)
            if (!folder.matches(Regex("[0-9]+"))) throw AppFailure(ErrorCode.SCHEMA, "收藏夹标识缺失")
            folder to text("folderTitle", row)
        }
    }
    suspend fun accountWorks(kind: WorkCollectionKind, accountId: String, cursor: String, folderId: String,
        pageSize: Int = config.getJSONObject("account_works").getInt("page_size")): AccountWorksPage {
        val settings = config.getJSONObject("account_works")
        require(pageSize in 1..settings.getInt("page_size"))
        val page = cursor.toIntOrNull()?.takeIf { it > 0 } ?: throw AppFailure(ErrorCode.SCHEMA, "列表页码无效")
        val saved = kind == WorkCollectionKind.SAVED
        val response = request(kind.operation, mapOf("accountId" to accountId, "page" to cursor, "folderId" to folderId, "count" to pageSize.toString()))
        val rows = when (val raw = mapper.optional(if (saved) "savedRows" else "likedRows", response)) {
            is JSONArray -> (0 until raw.length()).map { raw.get(it) }
            null -> if (saved && text("savedMore", response) in listOf("false", "0")) emptyList() else
                throw AppFailure(ErrorCode.SCHEMA, "列表响应缺少作品，稍后重试")
            else -> throw AppFailure(ErrorCode.SCHEMA, "列表响应格式异常")
        }
        var unavailable = 0
        val works = rows.mapNotNull { row ->
            val aid = text(if (saved) "workId" else "workAid", row)
            val bv = text("workBvid", row)
            if (!aid.matches(Regex("[0-9]+")) || bv.isBlank() || (saved && text("workType", row) != settings.getString("video_type"))) {
                unavailable++; null
            } else AccountWork(PostRef(id, aid, mapOf("bvid" to bv)), text("workTitle", row),
                text(if (saved) "workAuthor" else "author", row),
                text(if (saved) "workCover" else "workPic", row).takeIf(String::isNotBlank)?.let { listOf(mediaUrl(it)) }.orEmpty(),
                0, text("workDuration", row).toLongOrNull()?.times(1000))
        }
        val more = if (saved) when (text("savedMore", response)) {
            "true", "1" -> true
            "false", "0" -> false
            else -> throw AppFailure(ErrorCode.SCHEMA, "收藏分页状态缺失")
        } else false
        return AccountWorksPage(works.distinctBy { it.ref.id }, (page + 1).toString(), !more, unavailable)
    }

    private fun text(key: String, row: Any) = mapper.optional(key, row)?.toString().orEmpty()
    private fun urls(row: Any): List<String> = (listOf(text("baseUrl", row), text("baseUrlAlt", row)) +
        mapper.values("backups", row).map(Any::toString) + mapper.values("backupsAlt", row).map(Any::toString))
        .filter(String::isNotBlank).map(::mediaUrl).distinct().also { if (it.isEmpty()) throw AppFailure(ErrorCode.SCHEMA, "媒体地址缺失") }

    private fun mediaUrl(value: String): String = if (value.isBlank()) "" else value.toHttpUrl().newBuilder().scheme("https").build().toString()

    private suspend fun request(operation: String, variables: Map<String, String> = emptyMap(), login: Session = session()): JSONObject {
        val rule = config.optJSONObject("api")?.optJSONObject(operation)
            ?: throw AppFailure(ErrorCode.UNSUPPORTED, "当前规则不支持此功能，请更新规则")
        val pairs = JSONArray()
        val template = rule.getJSONArray("params")
        for (i in 0 until template.length()) {
            val pair = template.getJSONArray(i)
            fun resolve(value: String) = if (value.startsWith('$')) variables.getValue(value.drop(1)) else value
            pairs.put(JSONArray().put(resolve(pair.getString(0))).put(resolve(pair.getString(1))))
        }
        val context = JSONObject().put("now", System.currentTimeMillis() / 1000)
        if (operation == "play") {
            val tag = CanonicalJson.sha256(login.cookies.toByteArray(Charsets.UTF_8))
            if (tag != wbiSession) { wbi = null; wbiSession = tag }
            val nav = wbi ?: request("nav", login = login).also { wbi = it }
            fun key(name: String) = URI(text(name, nav)).path.substringAfterLast('/').substringBefore('.')
            context.put("img", key("wbiImage")).put("sub", key("wbiSub"))
        }
        val url = js.buildUrl(rule.getString("path"), pairs, context)
        val builder = Request.Builder().url(url).header("User-Agent", login.userAgent).header("Referer", config.getString("referer"))
        val origin = config.getString("origin").toHttpUrl()
        if (builder.build().url.let { it.scheme != "https" || it.host != origin.host || it.port != origin.port })
            throw AppFailure(ErrorCode.JS_ERROR, "B 站签名地址不在配置域名内")
        if (login.cookies.isNotBlank()) builder.header("Cookie", login.cookies)
        val request = builder.build()
        if (request.url.toString() != url) throw AppFailure(ErrorCode.JS_ERROR, "签名地址发生变化")
        return transport.consume(request) { response ->
            if (!response.isSuccessful) throw AppFailure(if (response.code == 412 || response.code == 403) ErrorCode.BLOCKED else ErrorCode.NETWORK, "B 站请求失败 (${response.code})")
            val raw = response.body?.byteStream()?.use { com.emojetools.app.core.bundle.BundleManager.readBounded(it, 8 * 1024 * 1024) }
                ?: throw AppFailure(ErrorCode.NETWORK, "B 站返回空响应")
            val json = try { JSONObject(raw.toString(Charsets.UTF_8)) } catch (e: Exception) { throw AppFailure(ErrorCode.SCHEMA, "B 站响应无法识别", e) }
            val code = text("code", json).toIntOrNull() ?: throw AppFailure(ErrorCode.SCHEMA, "B 站响应缺少状态")
            if (operation != "nav" && code != 0) throw AppFailure(ErrorCode.valueOf(config.getJSONObject("error_codes").optString(code.toString(), "API_ERROR")),
                "B 站：" + text("message", json))
            json
        }
    }
    override suspend fun fetchCommentAssets(ref: PostRef, limit: Int): CommentResult = throw AppFailure(ErrorCode.UNSUPPORTED, "当前 B 站入口支持视频下载")
    override suspend fun loginState() = loginState(session())
    suspend fun loginState(candidate: Session): LoginState = if (text("loggedIn", request("nav", login = candidate)) == "true")
        LoginState.LOGGED_IN else LoginState.NEED_LOGIN
    override fun loginEntryPoint() = LoginTarget(config.getString("login_url"), config.getString("ua"))
    override fun capabilities() = Caps(true, false, true, false)
    fun close() = js.close()
}
