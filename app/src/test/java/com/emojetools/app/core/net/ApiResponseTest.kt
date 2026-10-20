package com.emojetools.app.core.net

import com.emojetools.app.core.AppFailure
import com.emojetools.app.core.ErrorCode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiResponseTest {
    private val config = JSONObject().put("business_errors", JSONObject())
    private val mapper = JsonMapper(JSONObject().put("statusCode", JSONArray(listOf("status_code")))
        .put("statusMessage", JSONArray(listOf("status_msg"))))

    @Test fun aSingleObjectAllowsSurroundingWhitespaceAndRetainsItsPayload() {
        val result = ApiResponse.decode(config, mapper, 200, " \n{\"status_code\":0,\"comments\":[{\"cid\":\"one\"}]}\r\n ")
        assertEquals("one", result.getJSONArray("comments").getJSONObject(0).getString("cid"))
    }

    @Test fun aSuccessPrefixCannotHideATrailingErrorOrOtherContent() {
        val success = "{\"status_code\":0,\"comments\":[],\"has_more\":0}"
        listOf("{\"status_code\":-99999}", "\n{\"status_code\":-99999}", "[]", "garbage").forEach { suffix ->
            try { ApiResponse.decode(config, mapper, 200, success + suffix); fail("Trailing content must fail") }
            catch (failure: AppFailure) { assertEquals(ErrorCode.SCHEMA, failure.code) }
        }
    }

    @Test fun emptyBodiesAndSingleBusinessErrorsKeepTheirErrorTypes() {
        listOf("" to ErrorCode.NEED_LOGIN, "{\"status_code\":-99999}" to ErrorCode.API_ERROR,
            "[]" to ErrorCode.SCHEMA).forEach { (body, code) ->
            try { ApiResponse.decode(config, mapper, 200, body); fail("Invalid response must fail") }
            catch (failure: AppFailure) { assertEquals(code, failure.code) }
        }
    }

    @Test fun anEmptyAnonymousResponseIsBlockedRatherThanARequestToLogIn() {
        try { ApiResponse.decode(config, mapper, 200, " ", requiresLogin = false); fail("Empty is not a successful list") }
        catch (failure: AppFailure) { assertEquals(ErrorCode.BLOCKED, failure.code) }
    }
}
