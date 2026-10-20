package com.emojetools.app.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.emojetools.app.BuildConfig
import com.emojetools.app.R
import com.emojetools.app.TaskApplication
import com.emojetools.app.core.net.BrowserProfile
import com.emojetools.app.core.store.SessionStore
import com.emojetools.app.core.LoginState
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URI

class LoginActivity : Activity() {
    private val openedAt = SystemClock.elapsedRealtime()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var web: WebView
    private lateinit var config: JSONObject
    private lateinit var sessions: SessionStore
    private lateinit var status: LinearLayout
    private lateinit var message: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var retry: Button
    private var script = ""
    private var resumed = false
    private var finished = false
    private var pageFailed = false
    private var injected = false
    private var panelReady = false
    private var pageGeneration = 0
    private var started = 0L
    private var initialSession: String? = null
    private var poll: Job? = null
    private val platform get() = intent.getStringExtra(PLATFORM) ?: "douyin"
    private var rejectedSession: String? = null
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        buildScreen()
        scope.launch {
            try {
                val coordinator = (application as TaskApplication).tasks
                coordinator.initialize()
                config = coordinator.loginConfig(platform)
                script = config.getJSONObject("login_ui").optString("script").takeIf(String::isNotBlank)
                    ?.let { coordinator.manager.active.text(it) }.orEmpty()
                sessions = coordinator.loginSessions(platform)
                initialSession = if (savedInstanceState?.containsKey("initial_session") == true)
                    savedInstanceState.getString("initial_session") else sessions.webSessionFingerprint(config)
                configureWeb()
                if (initialSession != null && !intent.getBooleanExtra(FORCE_LOGIN, false) && complete(false)) return@launch
                started = SystemClock.elapsedRealtime()
                if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) reload() else startPolling()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { showFailure(R.string.login_rules_failed, false) }
        }
    }

    private fun buildScreen() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(BACKGROUND)
        }
        layout.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(12), 0)
            addView(ImageButton(this@LoginActivity).apply {
                setImageResource(R.drawable.ic_x); contentDescription = getString(R.string.close)
                setColorFilter(INK); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(label(if (platform == "bilibili") R.string.login_bilibili else R.string.login, 18, INK).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@LoginActivity).apply {
                setText(R.string.login_done); textSize = 13f; letterSpacing = 0f; isAllCaps = false
                setTextColor(ACCENT); setBackgroundColor(Color.TRANSPARENT); minWidth = 0
                setOnClickListener { scope.launch { complete(true) } }
            }, LinearLayout.LayoutParams(-2, dp(48)))
        }, LinearLayout.LayoutParams(-1, dp(60)))
        layout.addView(FrameLayout(this).apply {
            web = WebView(this@LoginActivity).apply { setBackgroundColor(BACKGROUND) }
            addView(web, FrameLayout.LayoutParams(-1, -1))
            status = LinearLayout(this@LoginActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(28), dp(20), dp(28), dp(20))
                isClickable = true
                setBackgroundColor(BACKGROUND)
                spinner = ProgressBar(this@LoginActivity)
                addView(spinner, LinearLayout.LayoutParams(dp(32), dp(32)))
                message = label(R.string.login_opening, 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(16)) }
                addView(message, LinearLayout.LayoutParams(-1, -2))
                retry = Button(this@LoginActivity).apply {
                    setText(R.string.login_retry); isAllCaps = false; visibility = View.GONE
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_rotate_ccw, 0, 0, 0)
                    setOnClickListener { reload() }
                }
                addView(retry)
            }
            addView(status, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
    }

    private fun configureWeb() {
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            useWideViewPort = true; loadWithOverviewMode = false
            setSupportMultipleWindows(false)
        }
        Log.i("EmojeLogin", "browser_profile hints_applied=${BrowserProfile.apply(web.settings, config.optJSONObject("login_browser") ?: config)}")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme != "https"
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageGeneration += 1
                injected = false; panelReady = false; pageFailed = false; showLoading()
            }
            override fun onPageCommitVisible(view: WebView, url: String) { injected = false; if (script.isEmpty()) reveal() }
            override fun onPageFinished(view: WebView, url: String) { injected = false; if (script.isEmpty()) reveal() }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { pageFailed = true; showFailure(R.string.login_load_failed, true) }
            }
        }
    }
    private fun reload() {
        if (!::config.isInitialized) return
        injected = false; panelReady = false; pageFailed = false; started = SystemClock.elapsedRealtime()
        showLoading(); web.loadUrl(config.getString("login_url")); startPolling()
    }
    private fun showLoading() {
        // Keep WebView rendering behind the opaque loading view while the SDK initializes.
        web.visibility = View.VISIBLE; status.visibility = View.VISIBLE; spinner.visibility = View.VISIBLE
        message.setText(R.string.login_opening); retry.visibility = View.GONE
    }
    private fun reveal() {
        if (!pageFailed) { panelReady = true; web.visibility = View.VISIBLE; status.visibility = View.GONE }
    }
    private fun showFailure(text: Int, canRetry: Boolean) {
        status.visibility = View.VISIBLE; spinner.visibility = View.GONE; message.setText(text)
        retry.visibility = if (canRetry) View.VISIBLE else View.GONE
    }

    private fun startPolling() {
        poll?.cancel()
        poll = scope.launch {
            while (isActive && !finished) {
                if (resumed) {
                    val current = sessions.webSessionFingerprint(config)
                    if (current != null && current != initialSession && current != rejectedSession && complete(false)) break
                    if (script.isNotEmpty() && !pageFailed && isLoginOrigin(web.url)) {
                        if (!injected) {
                            web.evaluateJavascript(script, null); injected = true
                        }
                        val generation = pageGeneration
                        web.evaluateJavascript("window.__emojeLogin ? window.__emojeLogin.step() : 'opening'") { result ->
                            if (!isFinishing && !isDestroyed && !pageFailed && generation == pageGeneration && result == "\"ready\"") {
                                if (!panelReady) Log.i("EmojeLogin", "panel_ready mode=phone elapsed_ms=${SystemClock.elapsedRealtime() - openedAt}")
                                reveal()
                            }
                        }
                    }
                    if (!pageFailed && !panelReady && SystemClock.elapsedRealtime() - started > config.getJSONObject("login_ui").getLong("ready_timeout_ms")) {
                        pageFailed = true; showFailure(R.string.login_panel_failed, true)
                    }
                }
                delay(config.getJSONObject("login_ui").getLong(if (panelReady || pageFailed) "poll_ms" else "opening_poll_ms"))
            }
        }
    }
    private fun isLoginOrigin(url: String?): Boolean = try {
        val actual = URI(url ?: "")
        val expected = URI(config.getString("login_url"))
        actual.scheme == "https" && actual.host == expected.host && actual.port == expected.port
    } catch (_: Exception) { false }

    private suspend fun complete(manual: Boolean): Boolean {
        if (!::sessions.isInitialized || finished || checking) return finished
        checking = true
        try {
            val candidate = sessions.webSession(config)
            if (platform == "bilibili" && (sessions.webSessionFingerprint(config) == null ||
                    (application as TaskApplication).tasks.bilibili?.loginState(candidate) != LoginState.LOGGED_IN)) {
                rejectedSession = sessions.webSessionFingerprint(config)
                if (manual) Toast.makeText(this, R.string.login_not_finished, Toast.LENGTH_LONG).show()
                return false
            }
            sessions.capture(config, candidate); finished = true
            Log.i("EmojeLogin", "session_saved automatic=${!manual}")
            Toast.makeText(this, R.string.login_saved, Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK); finish(); return true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (platform == "bilibili") {
                rejectedSession = sessions.webSessionFingerprint(config)
                Toast.makeText(this, R.string.login_verify_failed, Toast.LENGTH_LONG).show()
                return false
            }
            if (sessions.webSessionFingerprint(config) == null) {
                if (manual) Toast.makeText(this, R.string.login_not_finished, Toast.LENGTH_LONG).show()
            } else {
                poll?.cancel(); showFailure(R.string.login_save_failed, true)
            }
            return false
        } finally { checking = false }
    }
    override fun onResume() { super.onResume(); resumed = true; if (::web.isInitialized) web.onResume() }
    override fun onPause() { resumed = false; if (::web.isInitialized) web.onPause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("initial_session", initialSession)
        if (::web.isInitialized) web.saveState(outState)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { poll?.cancel(); scope.cancel(); web.stopLoading(); web.destroy(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: Int, size: Int, color: Int) = TextView(this).apply {
        setText(text); textSize = size.toFloat(); letterSpacing = 0f; setTextColor(color)
    }
    companion object {
        const val FORCE_LOGIN = "force_login"
        const val PLATFORM = "platform"
        private const val ACCENT = 0xff167c68.toInt()
        private const val INK = 0xff1e272b.toInt()
        private const val MUTED = 0xff7d8b94.toInt()
        private const val BACKGROUND = 0xfff7f8fa.toInt()
    }
}
