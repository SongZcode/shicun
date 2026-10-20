package com.emojetools.app.platform.douyin

import com.emojetools.app.core.AppFailure
import com.emojetools.app.core.ErrorCode
import com.emojetools.app.core.net.JsonMapper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AccountIdentityTest {
    private fun parser() = CommentParser(JsonMapper(JSONObject(File("../bundle_src/parsers/douyin.json").readText())),
        JSONObject(File("../bundle_src/platforms/douyin.json").readText()))

    @Test fun selfResponseAndCommentAuthorsUseTheSameExactIdentifiers() {
        val response = JSONObject().put("user", JSONObject().put("nickname", "Same Name")
            .put("uid", 9007199254740993L).put("sec_uid", "secure-id").put("unique_id", "public-handle"))
        val identity = parser().identity(response)
        assertEquals("Same Name", identity.name)
        assertEquals(listOf("secure-id", "9007199254740993", "public-handle"), identity.ids)
    }

    @Test fun missingIdentityRemainsEmptyAndInvalidIdentifiersAreSchemaErrors() {
        assertTrue(parser().identity(JSONObject()).ids.isEmpty())
        try {
            parser().identity(JSONObject().put("user", JSONObject().put("uid", 1.5)))
            fail("Invalid identifier must fail")
        } catch (e: AppFailure) { assertEquals(ErrorCode.SCHEMA, e.code) }
    }
}
