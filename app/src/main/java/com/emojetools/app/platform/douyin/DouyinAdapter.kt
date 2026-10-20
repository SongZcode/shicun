package com.emojetools.app.platform.douyin

import com.emojetools.app.core.*
import com.emojetools.app.core.net.HttpTransport
import com.emojetools.app.core.net.SignedHttpClient
import com.emojetools.app.core.net.JsonMapper
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.store.SessionStore
import com.emojetools.app.core.store.CommentResultJson
import com.emojetools.app.core.store.WorkAccount
import com.emojetools.app.platform.PlatformAdapter
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DouyinAdapter(
    private val config: JSONObject, private val transport: HttpTransport,
    private val sessions: SessionStore, private val signedHttp: SignedHttpClient,
    private val mapper: JsonMapper, private val ledgerRoot: File,
    private val commentProgress: (Int, Int) -> Unit = { _, _ -> }
) : PlatformAdapter {
    override val id = "douyin"
    private val input = config.getJSONObject("input")
    private val idPattern = Regex(input.getString("id_regex"))
    private val urlPattern = Regex(input.getString("url_regex"))
    private val postPattern = Regex(input.getString("post_id_regex"))
    private val detailMutex = Mutex()
    private var cachedPost: PostMedia? = null
    private var cachedAt = 0L

    override fun matches(rawInput: String): Boolean = idPattern.matches(rawInput.trim()) || urlPattern.containsMatchIn(rawInput)

    override suspend fun resolve(rawInput: String): PostRef {
        val raw = rawInput.trim()
        if (idPattern.matches(raw)) return PostRef(id, raw)
        var url = urlPattern.find(raw)?.value?.let { if (it.startsWith("http://")) "https://" + it.drop(7) else it }
            ?: throw AppFailure(ErrorCode.UNSUPPORTED, "请输入抖音作品链接或作品 ID")
        val allowed = input.getJSONArray("allowed_hosts").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        for (hop in 0..input.getInt("max_redirects")) {
            val uri = URI(url)
            if (uri.scheme != "https" || uri.host !in allowed || uri.userInfo != null || uri.port !in listOf(-1, 443))
                throw AppFailure(ErrorCode.UNSUPPORTED, "链接跳转到了不支持的地址")
            postPattern.find(uri.path)?.groupValues?.get(1)?.let { return PostRef(id, it) }
            if (hop == input.getInt("max_redirects")) break
            transport.execute(Request.Builder().url(url).header("User-Agent", sessions.read(config.getString("ua")).userAgent)
                .header("Referer", config.getString("referer")).build()).use { response ->
                if (response.code !in setOf(301, 302, 303, 307, 308))
                    throw AppFailure(if (response.code == 404) ErrorCode.NOT_FOUND else ErrorCode.UNSUPPORTED, "未能从链接中识别作品")
                val location = response.header("Location") ?: throw AppFailure(ErrorCode.SCHEMA, "跳转响应缺少目标地址")
                url = uri.resolve(location).toString()
            }
        }
        throw AppFailure(ErrorCode.UNSUPPORTED, "链接跳转次数过多")
    }

    suspend fun probe(ref: PostRef): JSONObject = signedHttp.get("comment", mapOf(
        "postId" to ref.id, "cursor" to "0", "count" to config.getInt("count_top").toString()))

    override suspend fun loginState(): LoginState {
        val session = sessions.read(config.getString("ua"))
        val names = config.getJSONArray("session_cookie_names")
        return if ((0 until names.length()).any { session.cookie(names.getString(it)).isNotEmpty() }) LoginState.UNKNOWN else LoginState.NEED_LOGIN
    }
    override fun loginEntryPoint() = LoginTarget(config.getString("login_url"), config.getString("ua"))
    override fun capabilities() = Caps(video = true, images = true, audio = true, comments = true)

    suspend fun favoriteStickers(cursor: String): FavoriteStickerPage {
        if (!config.has("favorites")) throw AppFailure(ErrorCode.UNSUPPORTED, "当前规则暂不支持收藏表情包，请更新规则后重试")
        if (loginState() == LoginState.NEED_LOGIN) throw AppFailure(ErrorCode.NEED_LOGIN, "请先登录抖音查看收藏表情包")
        return FavoriteStickerFetcher(config, mapper, signedHttp::get).page(cursor)
    }

    suspend fun currentAccount(): AccountIdentity {
        if (loginState() == LoginState.NEED_LOGIN) throw AppFailure(ErrorCode.NEED_LOGIN, "请先登录抖音")
        val identity = CommentParser(mapper, config).identity(signedHttp.get("self", emptyMap()))
        if (identity.ids.isEmpty()) throw AppFailure(ErrorCode.NEED_LOGIN, "未能识别当前登录账号，请重新登录")
        return identity
    }

    suspend fun workAccount(): WorkAccount {
        if (!config.has("account_works")) throw AppFailure(ErrorCode.UNSUPPORTED, "当前规则暂不支持收藏和喜欢作品，请更新规则")
        if (loginState() == LoginState.NEED_LOGIN) throw AppFailure(ErrorCode.NEED_LOGIN, "请先登录抖音查看作品")
        val response = signedHttp.get("self", emptyMap())
        val secUid = (mapper.required("accountSecUid", response) as? String)?.takeIf { it.isNotBlank() }
            ?: throw AppFailure(ErrorCode.NEED_LOGIN, "未能识别当前登录账号，请重新登录")
        return WorkAccount(CommentParser(mapper, config).identity(response).name, secUid)
    }

    suspend fun accountWorks(kind: WorkCollectionKind, secUid: String, cursor: String): AccountWorksPage {
        if (loginState() == LoginState.NEED_LOGIN) throw AppFailure(ErrorCode.NEED_LOGIN, "请先登录抖音查看作品")
        return AccountWorksFetcher(config, mapper, signedHttp::request).page(kind, secUid, cursor)
    }

    override suspend fun fetchPost(ref: PostRef): Post = postMedia(ref).post
    override suspend fun fetchAssets(ref: PostRef, prefs: QualityPrefs): List<Asset> = postMedia(ref).assets.let {
        if (prefs.preferHighest) it else it.filter { asset -> asset.kind == AssetKind.VIDEO }.reversed() + it.filter { asset -> asset.kind != AssetKind.VIDEO }
    }
    private suspend fun postMedia(ref: PostRef): PostMedia = detailMutex.withLock {
        require(ref.platform == id)
        cachedPost?.takeIf { it.post.ref == ref && System.currentTimeMillis() - cachedAt < config.getLong("detail_cache_ms") }
            ?.let { return@withLock it }
        val response = signedHttp.get("detail", mapOf("postId" to ref.id))
        PostParser(mapper, config).parse(ref, response).also { cachedPost = it; cachedAt = System.currentTimeMillis() }
    }
    suspend fun refreshPostAssets(ref: PostRef): List<Asset> {
        detailMutex.withLock { cachedPost = null }
        return fetchAssets(ref, QualityPrefs())
    }
    override suspend fun fetchCommentAssets(ref: PostRef, limit: Int): CommentResult {
        return fetchCommentAssetsForTask(ref, limit, UUID.randomUUID().toString())
    }
    suspend fun fetchCommentAssetsForTask(ref: PostRef, limit: Int, taskId: String,
        order: CommentOrder = CommentOrder.TOP_FIRST,
        onPartial: (CommentResult) -> Unit = {}): CommentResult {
        require(ref.platform == id)
        require(taskId.matches(Regex("[A-Za-z0-9-]+")))
        val manifest = File(ledgerRoot, "${ref.id}/$taskId/manifest.json")
        val saved = withContext(Dispatchers.IO) { if (manifest.isFile) JSONObject(manifest.readText()) else null }
        return CommentFetcher(config, mapper, signedHttp::get, { document ->
            withContext(Dispatchers.IO) { FetchLedger.write(manifest, document) }
            onPartial(CommentResultJson.decode(document.getJSONObject("extras").getJSONObject("resume").getJSONObject("result")))
        }, progress = commentProgress).fetch(ref, limit, manifest.absolutePath, saved, order)
    }
}
