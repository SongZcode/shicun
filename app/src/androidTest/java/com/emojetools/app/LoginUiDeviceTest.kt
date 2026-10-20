package com.emojetools.app

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emojetools.app.core.store.SessionStore
import com.emojetools.app.core.net.BrowserProfile
import com.emojetools.app.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class LoginUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun bundleText(path: String): String {
        val manifest = JSONObject(context.assets.open("bundle/manifest.json").bufferedReader().use { it.readText() })
        val archive = java.net.URI(manifest.getString("url")).path.substringAfterLast('/')
        ZipInputStream(context.assets.open("bundle/$archive")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: error("Login script missing")
                if (entry.name == path) return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
    }
    private fun loginScript() = bundleText("ui/douyin-login.js")
    private suspend fun evaluate(web: WebView, js: String): String {
        val result = CompletableDeferred<String>()
        instrumentation.runOnMainSync { web.evaluateJavascript(js) { result.complete(it) } }
        return withTimeout(5000) { result.await() }
    }
    private fun page(width: Int, openPanel: Boolean, phoneOnly: Boolean = false, responsive: Boolean = false, test: suspend (WebView) -> Unit) = runBlocking {
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/.ui.MainActivity").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ready = CompletableDeferred<Unit>()
            lateinit var web: WebView
            scenario.onActivity { activity ->
                web = WebView(activity).apply {
                    settings.javaScriptEnabled = true; settings.useWideViewPort = true; settings.blockNetworkLoads = true
                    BrowserProfile.apply(settings, JSONObject(bundleText("platforms/douyin.json")))
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { ready.complete(Unit) }
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(web, FrameLayout.LayoutParams((width * activity.resources.displayMetrics.density).toInt(), -1))
                })
                val panel = """<div id="portal"><div style="position:fixed;inset:0;display:flex;align-items:center;justify-content:center"><div id="login-panel-new"><div><div id="douyin-login-new-id"><div><article id="douyin_login_comp_flat_panel"><picture></picture><div><div>Official title</div><div><div id="douyin_login_landing_flat_container"><div><div>Scan</div><div id="douyin_login_comp_scan_code"><canvas width="180" height="180"></canvas></div></div><div><div><span>&#39564;&#35777;&#30721;&#30331;&#24405;</span><div id="login-divider">|</div><span id="password-tab">Password</span></div><div id="douyin_login_comp_mobile_code"><input id="phone" type="tel" placeholder="Phone"><button>Code</button></div></div></div></div></div></article></div></div></div></div></div></div>"""
                val h5 = """<div><div style="position:fixed;inset:0"><div id="login-panel-new" style="transform:scale(2)"><div id="douyin-login-new-id"><div><article id="douyin_login_comp_h5_panel"><picture></picture><div><div>Phone login</div><div><input type="tel"></div></div></article></div></div></div></div></div>"""
                val chosen = if (phoneOnly) JSONObject.quote(h5) else if (responsive)
                    "((innerWidth < 768 || navigator.userAgentData?.mobile) ? ${JSONObject.quote(h5)} : ${JSONObject.quote(panel)})" else JSONObject.quote(panel)
                val handler = if (openPanel) "document.body.insertAdjacentHTML('beforeend', $chosen);" else ""
                val html = """<html><head><style>body{min-width:1024px}#login-panel-new{width:726px}#douyin_login_comp_flat_panel{width:726px}#douyin_login_landing_flat_container{display:flex;width:726px}#douyin_login_landing_flat_container>div{width:50%}</style></head><body><div id="root"><button id="entry">&#30331;&#24405;</button></div><script>window.clicks=0;document.getElementById('entry').onclick=function(){window.clicks++;$handler};</script></body></html>"""
                web.loadDataWithBaseURL("https://login-fixture.invalid/", html, "text/html", "UTF-8", null)
            }
            withTimeout(10000) { ready.await() }
            try { evaluate(web, loginScript()); test(web) }
            finally { instrumentation.runOnMainSync { web.destroy() } }
        }
    }

    @Test fun automaticallyShowsOnlyPhoneLoginAndPreservesInput() {
        listOf(320, 394).forEach { width -> page(width, true) { web ->
            repeat(2) { evaluate(web, "window.__emojeLogin.step()") }
            assertEquals("\"ready\"", evaluate(web, "window.__emojeLogin.step()"))
            val phone = JSONObject(evaluate(web, """({qr:document.querySelector('#douyin_login_comp_scan_code').getBoundingClientRect().width,right:document.querySelector('#phone').getBoundingClientRect().right,width:document.querySelector('#phone').getBoundingClientRect().width})"""))
            assertEquals(0.0, phone.getDouble("qr"), 0.1); assertTrue(phone.getDouble("width") > 0)
            assertTrue(phone.toString(), phone.getDouble("right") <= width + 1)
            assertEquals("\"hidden\"", evaluate(web, "getComputedStyle(document.getElementById('root')).visibility"))
            assertEquals("1", evaluate(web, "window.clicks"))
            assertEquals("false", evaluate(web, "!!window.__emojeLogin.qrImage || !!document.getElementById('emoje-save-qr')"))
            assertEquals("\"none\"", evaluate(web, "getComputedStyle(document.getElementById('password-tab')).display"))
            assertEquals("\"none\"", evaluate(web, "getComputedStyle(document.getElementById('login-divider')).display"))
            evaluate(web, "document.getElementById('phone').value='fixture-number'")
            evaluate(web, loginScript())
            repeat(3) { evaluate(web, "window.__emojeLogin.step()") }
            assertEquals("\"fixture-number\"", evaluate(web, "document.querySelector('#phone').value"))
            assertEquals("1", evaluate(web, "document.querySelectorAll('#emoje-login-style').length"))
        } }
    }

    @Test fun configuredUaStaysConsistentAndTheFirstStepUsesMobileWidth() = page(394, true, responsive = true) { web ->
        val hints = JSONObject(evaluate(web, "({ua:navigator.userAgent,mobile:navigator.userAgentData?.mobile||false,platform:navigator.userAgentData?.platform||''})"))
        assertEquals(JSONObject(bundleText("platforms/douyin.json")).getString("ua"), hints.getString("ua"))
        assertFalse(hints.getBoolean("mobile"))
        evaluate(web, "window.__emojeLogin.step()")
        assertEquals("\"width=device-width, initial-scale=1\"", evaluate(web, "document.querySelector('meta[name=viewport]').content"))
        assertEquals("1", evaluate(web, "window.clicks"))
        assertEquals("\"ready\"", evaluate(web, "window.__emojeLogin.step()"))
    }

    @Test fun phoneOnlyOfficialLayoutFitsWithoutWaitingForQr() = page(320, true, phoneOnly = true) { web ->
        repeat(2) { evaluate(web, "window.__emojeLogin.step()") }
        assertEquals("\"ready\"", evaluate(web, "window.__emojeLogin.step()"))
        val bounds = JSONObject(evaluate(web, "({right:document.getElementById('login-panel-new').getBoundingClientRect().right,width:document.querySelector('input[type=tel]').getBoundingClientRect().width})"))
        assertTrue(bounds.toString(), bounds.getDouble("right") <= 321)
        assertTrue(bounds.getDouble("width") > 0)
    }

    @Test fun missingPhoneInputCannotBeReportedAsReady() = page(320, true) { web ->
        repeat(2) { evaluate(web, "window.__emojeLogin.step()") }
        evaluate(web, "document.getElementById('phone').remove()")
        assertEquals("\"opening\"", evaluate(web, "window.__emojeLogin.step()"))
        assertEquals("1", evaluate(web, "window.clicks"))
        evaluate(web, "const phone=document.createElement('input');phone.type='tel';document.getElementById('douyin_login_comp_mobile_code').appendChild(phone)")
        assertEquals("\"ready\"", evaluate(web, "window.__emojeLogin.step()"))
    }

    @Test fun absentDialogHasBoundedClicksAndRepeatedInjectionIsIdempotent() = page(320, false) { web ->
        evaluate(web, "Date.now = (() => {let now=10000; return () => (now+=2000);})()")
        repeat(10) { assertEquals("\"opening\"", evaluate(web, "window.__emojeLogin.step()")) }
        assertEquals("3", evaluate(web, "window.clicks"))
        evaluate(web, loginScript())
        assertEquals("1", evaluate(web, "document.querySelectorAll('#emoje-login-style').length"))
        evaluate(web, "window.__emojeLogin.step()")
        assertEquals("3", evaluate(web, "window.clicks"))
    }

    @Test fun visitorCookiesCannotCompleteLoginAndSessionChangesAreDetected() = runBlocking {
        val config = JSONObject().put("login_url", "https://login-fixture.invalid/").put("ua", "fixture")
            .put("session_cookie_names", org.json.JSONArray(listOf("fixture_session", "fixture_session_ss")))
        suspend fun cookie(value: String) {
            val done = CompletableDeferred<Unit>()
            instrumentation.runOnMainSync { CookieManager.getInstance().setCookie(config.getString("login_url"), value) { done.complete(Unit) } }
            withTimeout(5000) { done.await() }
        }
        val store = SessionStore(context)
        try {
            cookie("fixture_visitor=visitor; Path=/; Secure")
            assertNull(store.webSessionFingerprint(config))
            cookie("fixture_session=first; Path=/; Secure; HttpOnly")
            val first = store.webSessionFingerprint(config)
            assertNotNull(first)
            cookie("fixture_visitor=updated; Path=/; Secure")
            assertEquals(first, store.webSessionFingerprint(config))
            cookie("fixture_session=second; Path=/; Secure; HttpOnly")
            assertNotEquals(first, store.webSessionFingerprint(config))
        } finally {
            listOf("fixture_visitor", "fixture_session", "fixture_session_ss").forEach { cookie("$it=; Max-Age=0; Path=/; Secure") }
        }
    }
}
