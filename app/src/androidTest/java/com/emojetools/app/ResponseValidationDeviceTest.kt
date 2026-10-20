package com.emojetools.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emojetools.app.core.AppFailure
import com.emojetools.app.core.ErrorCode
import com.emojetools.app.core.net.ApiResponse
import com.emojetools.app.core.net.JsonMapper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponseValidationDeviceTest {
    private val config = JSONObject().put("business_errors", JSONObject())
    private val mapper = JsonMapper(JSONObject().put("statusCode", JSONArray(listOf("status_code"))))

    @Test fun nativeJsonParserRejectsAnEmptySuccessFollowedByAnError() {
        val success = "{\"has_more\":0,\"status_code\":0,\"min_cursor\":0,\"comments\":[]}"
        listOf("{\"status_code\":-99999,\"status_msg\":\"\"}", "\n[]", "trailing").forEach { suffix ->
            try { ApiResponse.decode(config, mapper, 200, success + suffix); fail("Must not report zero comments") }
            catch (failure: AppFailure) { assertEquals(ErrorCode.SCHEMA, failure.code) }
        }
    }

    @Test fun nativeJsonParserAcceptsOneObjectAndKeepsEmptyBodyLoginErrors() {
        assertEquals(0, ApiResponse.decode(config, mapper, 200, " \n{\"status_code\":0}\r\n ").getInt("status_code"))
        try { ApiResponse.decode(config, mapper, 200, ""); fail("Empty body must require login") }
        catch (failure: AppFailure) { assertEquals(ErrorCode.NEED_LOGIN, failure.code) }
    }
}
