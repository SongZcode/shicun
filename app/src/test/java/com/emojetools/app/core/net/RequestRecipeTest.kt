package com.emojetools.app.core.net

import com.emojetools.app.core.*
import com.emojetools.app.core.store.Session
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RequestRecipeTest {
    @Test fun anonymousCommentsUseTheirLocalIdentityWithoutReadingTheSavedLogin() {
        val config = JSONObject(File("../bundle_src/platforms/douyin.json").readText())
        for (operation in listOf("comment", "reply")) {
            val rule = config.getJSONObject("api").getJSONObject(operation)
            val context = RequestRecipe.context(config, rule, mapOf("deviceId" to "untrusted", "postId" to "123"),
                { throw AssertionError("Anonymous requests must not read account cookies") }, { profile ->
                    assertEquals(16, profile.getJSONObject("identity").getInt("digits")); "1234567890123456"
                })
            assertEquals("1234567890123456", context.variables["deviceId"])
            assertEquals("123", context.variables["postId"])
            assertEquals("", context.session.cookies); assertEquals("", context.visitorId)
            assertFalse(context.requiresLogin)
            assertEquals(config.getJSONObject("request_profiles").getJSONObject(rule.getString("profile")).getString("ua"), context.session.userAgent)
        }
    }

    @Test fun accountAndWorkRequestsKeepTheSavedWebEnvironment() {
        val config = JSONObject(File("../bundle_src/platforms/douyin.json").readText())
        for (operation in listOf("self", "detail", "favorites", "savedWorks", "likedWorks")) {
            val saved = Session("UIFID=fixture-visitor; sessionid=fixture-session", "saved-web-ua")
            val context = RequestRecipe.context(config, config.getJSONObject("api").getJSONObject(operation), emptyMap(),
                { saved }, { throw AssertionError("Web requests must not use an anonymous identity") })
            assertEquals(saved, context.session); assertEquals("fixture-visitor", context.visitorId)
            assertTrue(context.requiresLogin); assertTrue(context.variables.isEmpty())
        }
    }

    @Test fun postFormAndHeadersReachTheWireWithoutChangingTheSignedQuery() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("ok"))
            val url = server.url("/collection/?z=3&a_bogus=one&x-secsdk-web-signature=two").toString()
            val rule = JSONObject().put("method", "POST").put("form", JSONArray().put(JSONArray(listOf("count", "$" + "count"))).put(JSONArray(listOf("cursor", "$" + "cursor"))))
                .put("headers", JSONObject().put("Origin", "https://example.invalid").put("Referer", "https://example.invalid/self").put("X-Fixture", "$" + "header"))
            val request = RequestRecipe.apply(Request.Builder().url(url).header("User-Agent", "saved-login-ua"), rule,
                mapOf("count" to "20", "cursor" to "opaque+cursor&", "header" to "configured")).build()
            OkHttpClient().newCall(request).execute().close()
            val sent = server.takeRequest()
            assertEquals("POST", sent.method)
            assertEquals("/collection/?z=3&a_bogus=one&x-secsdk-web-signature=two", sent.path)
            assertEquals("count=20&cursor=opaque%2Bcursor%26", sent.body.readUtf8())
            assertEquals("saved-login-ua", sent.getHeader("User-Agent")); assertEquals("configured", sent.getHeader("X-Fixture"))
            assertEquals("https://example.invalid", sent.getHeader("Origin"))
            assertEquals("https://example.invalid/self", sent.getHeader("Referer"))
            assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        } finally { server.shutdown() }
    }

    @Test fun productionCollectionRulesKeepPostFormFieldsOutOfTheSignatureQuery() {
        val config = JSONObject(File("../bundle_src/platforms/douyin.json").readText())
        val saved = config.getJSONObject("api").getJSONObject("savedWorks")
        val query = RequestRecipe.parameters(saved.getJSONArray("params"), emptyMap())
        assertEquals("POST", saved.getString("method")); assertEquals(1, query.length())
        assertEquals("publish_video_strategy_type", query.getJSONArray(0).getString(0))
        val form = RequestRecipe.parameters(saved.getJSONArray("form"), mapOf("count" to "2", "cursor" to "returned-cursor"))
        assertEquals("2", form.getJSONArray(0).getString(1)); assertEquals("returned-cursor", form.getJSONArray(1).getString(1))
        val liked = config.getJSONObject("api").getJSONObject("likedWorks")
        val likedQuery = RequestRecipe.parameters(liked.getJSONArray("params"), mapOf("secUid" to "self-only", "cursor" to "opaque", "count" to "20"))
        assertEquals("self-only", likedQuery.getJSONArray(0).getString(1)); assertEquals("opaque", likedQuery.getJSONArray(1).getString(1))
        assertFalse(liked.has("form"))
    }

    @Test fun invalidMethodFormAndMissingVariablesAreTypedFailures() {
        val invalid = listOf(JSONObject().put("method", "DELETE"), JSONObject().put("form", JSONArray()),
            JSONObject().put("headers", JSONObject().put("X-Fixture", "$" + "missing")))
        invalid.forEach { rule ->
            try { RequestRecipe.apply(Request.Builder().url("https://example.invalid/"), rule, emptyMap()); fail("Expected schema failure") }
            catch (e: AppFailure) { assertEquals(ErrorCode.SCHEMA, e.code) }
        }
        val get = RequestRecipe.apply(Request.Builder().url("https://example.invalid/?z=1&a_bogus=one"), JSONObject(), emptyMap()).build()
        assertEquals("GET", get.method); assertNull(get.body)
    }
}
