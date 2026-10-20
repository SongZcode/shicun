package com.emojetools.app.core.bundle

import com.emojetools.app.core.AppFailure
import com.emojetools.app.core.ErrorCode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BundleVerifierTest {
    private fun fixture(name: String): Pair<String, File> {
        val text = javaClass.getResourceAsStream("/$name/manifest.json")!!.bufferedReader().use { it.readText() }
        val zip = File.createTempFile("bundle-test", ".zip").apply { deleteOnExit() }
        javaClass.getResourceAsStream("/$name/bundle_v1.zip")!!.use { input -> zip.outputStream().use { input.copyTo(it) } }
        return text to zip
    }
    private fun verifier() = BundleVerifier(javaClass.getResourceAsStream("/demo_pubkey.b64")!!.bufferedReader().use { it.readText() }, 1)

    @Test fun normalPublishedFixturePasses() {
        val (text, zip) = fixture("bundle_demo")
        val verified = verifier().verify(text, zip)
        assertEquals(1, verified.version)
        assertEquals(setOf("sign/douyin.js", "platforms/douyin.json", "parsers/douyin.json"), verified.files.keys)
    }
    @Test fun archiveWithAdditionalFileFails() {
        val (text, zip) = fixture("bundle_bad1")
        val error = assertThrows(AppFailure::class.java) { verifier().verify(text, zip) }
        assertEquals(ErrorCode.INVALID_BUNDLE, error.code)
    }
    @Test fun modifiedFileManifestFails() {
        val (text, zip) = fixture("bundle_bad2")
        assertThrows(AppFailure::class.java) { verifier().verify(text, zip) }
    }
    @Test fun manifestMetadataIsCoveredBySignature() {
        val (text, zip) = fixture("bundle_demo")
        val changed = JSONObject(text).put("bundle_version", 42).toString()
        assertThrows(AppFailure::class.java) { verifier().verify(changed, zip) }
    }
    @Test fun invalidMetadataIsRejectedBeforeArchiveIsNeeded() {
        val (text, _) = fixture("bundle_demo")
        val changed = JSONObject(text).put("url", "https://example.test/changed.zip").toString()
        assertThrows(AppFailure::class.java) { verifier().verifyManifest(changed) }
    }
    @Test fun httpTransportPreservesSignedQueryBytes() {
        val url = "https://example.test/a/?value=%E4%B8%AD%E6%96%87%20%2B%2F~*&token=a%3D%3D&timestamp=1700000000"
        assertEquals(url, okhttp3.Request.Builder().url(url).build().url.toString())
    }
    @Test fun canonicalJsonMatchesPythonForUnicodeAndEscaping() {
        val json = JSONObject().put("z", "中文😀\n\u0001</").put("a", JSONArray().put(true).put(JSONObject.NULL).put(100))
        assertEquals("{\"a\":[true,null,100],\"z\":\"中文😀\\n\\u0001</\"}", CanonicalJson.encode(json))
    }
    @Test fun canonicalJsonUsesUnicodeCodePointKeyOrder() {
        val json = JSONObject().put("😀", 2).put("\uE000", 1)
        assertEquals("{\"\uE000\":1,\"😀\":2}", CanonicalJson.encode(json))
    }
    @Test fun nonIntegralSchemaValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CanonicalJson.encode(JSONObject().put("bytes", 1.5)) }
    }
}
