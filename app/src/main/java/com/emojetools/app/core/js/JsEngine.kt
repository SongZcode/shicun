package com.emojetools.app.core.js

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.emojetools.app.core.AppFailure
import com.emojetools.app.core.ErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

class JsEngine(private val context: Context) {
    private var view: WebView? = null
    private val mutex = Mutex()

    suspend fun load(script: String, platform: JSONObject) = withContext(Dispatchers.Main) {
        close()
        val ready = CompletableDeferred<Unit>()
        val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { ready.complete(Unit) }
            }
        }
        view = web
        web.loadDataWithBaseURL("https://bundle.invalid/", "<html><head><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-eval' 'unsafe-inline'\"></head></html>", "text/html", "UTF-8", null)
        withTimeout(10_000) { ready.await() }
        val result = evaluate("(function(){try {globalThis.__platform=" + platform.toString() + ";\n" + script + "\n;return typeof buildUrl === 'function';}catch(e){return false;}})()")
        if (result != "true") throw AppFailure(ErrorCode.JS_ERROR, "Signature bundle failed to initialize")
    }

    suspend fun buildUrl(path: String, params: JSONArray, context: JSONObject): String = mutex.withLock {
        withContext(Dispatchers.Main) {
            val expression = "(function(){try{return JSON.stringify({ok:true,value:buildUrl(" +
                JSONObject.quote(path) + "," + params.toString() + "," + context.toString() +
                ")});}catch(e){return JSON.stringify({ok:false,error:String(e.message)});}})()"
            val raw = withTimeout(10_000) { evaluate(expression) }
            val decoded = JSONTokener(raw).nextValue() as? String
                ?: throw AppFailure(ErrorCode.JS_ERROR, "Invalid JS response")
            val result = JSONObject(decoded)
            if (!result.getBoolean("ok")) {
                val error = result.optString("error")
                throw AppFailure(if (error == "NEED_LOGIN") ErrorCode.NEED_LOGIN else ErrorCode.JS_ERROR, error)
            }
            result.getString("value")
        }
    }

    private suspend fun evaluate(expression: String): String = suspendCancellableCoroutine { continuation ->
        val web = view ?: throw AppFailure(ErrorCode.JS_ERROR, "JS engine not initialized")
        web.evaluateJavascript(expression) { value -> if (continuation.isActive) continuation.resume(value) }
    }

    fun close() { view?.destroy(); view = null }
}
