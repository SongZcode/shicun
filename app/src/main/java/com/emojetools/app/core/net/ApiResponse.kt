package com.emojetools.app.core.net

import com.emojetools.app.core.AppFailure
import com.emojetools.app.core.ErrorCode
import org.json.JSONObject
import org.json.JSONTokener

object ApiResponse {
    fun decode(config: JSONObject, mapper: JsonMapper, code: Int, text: String, requiresLogin: Boolean = true): JSONObject {
        if (code == 429) throw AppFailure(ErrorCode.RATE_LIMIT, "请求过于频繁，请稍后再试", httpStatus = code)
        if (code == 403) {
            val rules = config.getJSONArray("error_patterns")
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                if (text.contains(rule.getString("contains"))) throw AppFailure(ErrorCode.valueOf(rule.getString("code")), "平台拒绝了请求", httpStatus = code)
            }
            throw AppFailure(ErrorCode.BLOCKED, "平台暂时限制访问，请稍后再试", httpStatus = code)
        }
        if (code !in 200..299) throw AppFailure(if (code == 404) ErrorCode.NOT_FOUND else ErrorCode.NETWORK, "HTTP $code", httpStatus = code)
        if (text.isBlank()) throw if (requiresLogin) AppFailure(ErrorCode.NEED_LOGIN, "登录态可能失效，请登录抖音", httpStatus = code)
            else AppFailure(ErrorCode.BLOCKED, "平台暂未返回内容，请稍后重试", httpStatus = code)
        val json = try {
            val source = JSONTokener(text)
            val value = source.nextValue()
            require(value is JSONObject && source.nextClean() == '\u0000') { "Expected one JSON object" }
            value
        } catch (e: Exception) { throw AppFailure(ErrorCode.SCHEMA, "平台响应格式异常", e) }
        val status = mapper.required("statusCode", json)
        if (status !is Int && status !is Long) throw AppFailure(ErrorCode.SCHEMA, "Invalid status type")
        if ((status as Number).toLong() != 0L) {
            val errorName = config.getJSONObject("business_errors").optString(status.toString(), "API_ERROR")
            throw AppFailure(ErrorCode.valueOf(errorName), "平台业务错误 $status: ${mapper.optional("statusMessage", json) ?: ""}", httpStatus = code)
        }
        return json
    }
}
