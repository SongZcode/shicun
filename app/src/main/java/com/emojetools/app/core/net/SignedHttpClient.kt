package com.emojetools.app.core.net

import android.util.Log
import com.emojetools.app.core.*
import com.emojetools.app.core.bundle.BundleManager
import com.emojetools.app.core.bundle.CanonicalJson
import com.emojetools.app.core.js.JsEngine
import com.emojetools.app.core.store.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

class SignedHttpClient(
    private val config: JSONObject, private val mapper: JsonMapper,
    private val js: JsEngine, private val sessions: SessionStore, private val transport: HttpTransport,
    private val onBackoff: (Long) -> Unit = {}
) {
    private val requestMutex = Mutex()

    private fun context(rule: JSONObject, variables: Map<String, String>) = RequestRecipe.context(config, rule, variables,
        { sessions.read(config.getString("ua")) }, sessions::anonymousIdentity)

    fun anonymousTag(operation: String): String? {
        val rule = config.getJSONObject("api").getJSONObject(operation)
        if (!RequestRecipe.profile(config, rule).optBoolean("anonymous", false)) return null
        val request = context(rule, emptyMap())
        return CanonicalJson.sha256(CanonicalJson.encode(JSONObject(request.variables)).toByteArray(Charsets.UTF_8))
    }

    fun parameters(operation: String, variables: Map<String, String>): JSONArray {
        val template = config.getJSONObject("api").getJSONObject(operation).getJSONArray("params")
        return RequestRecipe.parameters(template, variables)
    }

    suspend fun get(operation: String, variables: Map<String, String>): JSONObject = request(operation, variables)

    suspend fun request(operation: String, variables: Map<String, String>): JSONObject = requestMutex.withLock {
        val rule = config.getJSONObject("api").getJSONObject(operation)
        val path = rule.getString("path")
        var last: AppFailure? = null
        val attempts = config.getInt("max_attempts")
        val totalAttempts = attempts + config.getInt("cooldown_recovery_attempts")
        repeat(totalAttempts) { attempt ->
            val requestContext = context(rule, variables)
            val session = requestContext.session
            val context = JSONObject().put("ua", session.userAgent)
                .put("uifid", requestContext.visitorId)
                .put("now", System.currentTimeMillis() / 1000)
            val url = js.buildUrl(path, parameters(operation, requestContext.variables), context)
            val builder = Request.Builder().url(url).header("User-Agent", session.userAgent)
                .header("Referer", config.getString("referer")).header("Accept", config.getString("accept"))
                .apply { if (session.cookies.isNotEmpty()) header("Cookie", session.cookies) }
            RequestRecipe.apply(builder, rule, requestContext.variables)
            if (!requestContext.requiresLogin) builder.removeHeader("Cookie")
            val request = builder.build()
            if (request.url.toString() != url) throw AppFailure(ErrorCode.JS_ERROR, "HTTP client changed signed URL bytes")
            try {
                return@withLock transport.consume(request) { response ->
                    val text = response.body?.byteStream()?.use { BundleManager.readBounded(it, 8 * 1024 * 1024) }?.toString(Charsets.UTF_8) ?: ""
                    Log.i("EmojeHTTP", "request operation=$operation attempt=${attempt + 1} http=${response.code} bytes=${text.toByteArray().size}")
                    ApiResponse.decode(config, mapper, response.code, text, requestContext.requiresLogin)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: AppFailure) { last = e }
            catch (e: java.io.IOException) { last = AppFailure(ErrorCode.NETWORK, "网络连接失败", e) }
            val failure = last!!
            if (failure.code in setOf(ErrorCode.NEED_LOGIN, ErrorCode.SCHEMA, ErrorCode.JS_ERROR, ErrorCode.API_ERROR, ErrorCode.NOT_FOUND)) throw failure
            if (attempt + 1 < totalAttempts) {
                val wait = if (failure.code in setOf(ErrorCode.BLOCKED, ErrorCode.RATE_LIMIT) || attempt + 1 >= attempts)
                    Random.nextLong(config.getLong("cooldown_min_ms"), config.getLong("cooldown_max_ms") + 1)
                else config.getLong("page_delay_ms")
                onBackoff(wait)
                delay(wait)
            }
        }
        throw last ?: AppFailure(ErrorCode.NETWORK, "Request failed")
    }
}
