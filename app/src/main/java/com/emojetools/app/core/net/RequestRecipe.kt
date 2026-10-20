package com.emojetools.app.core.net

import com.emojetools.app.core.AppFailure
import com.emojetools.app.core.ErrorCode
import com.emojetools.app.core.store.Session
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object RequestRecipe {
    data class Context(val variables: Map<String, String>, val session: Session, val visitorId: String, val requiresLogin: Boolean)

    fun profile(config: JSONObject, rule: JSONObject): JSONObject = rule.optString("profile").takeIf { it.isNotEmpty() }
        ?.let { config.getJSONObject("request_profiles").getJSONObject(it) } ?: config

    fun context(config: JSONObject, rule: JSONObject, variables: Map<String, String>,
        readSession: () -> Session, identity: (JSONObject) -> String): Context {
        val profile = profile(config, rule)
        if (!profile.optBoolean("anonymous", false)) {
            val session = readSession()
            return Context(variables, session, session.cookie(config.getString("visitor_cookie_name")), true)
        }
        val spec = profile.getJSONObject("identity")
        val local = identity(profile)
        return Context(variables + (spec.getString("variable") to local), Session("", profile.getString("ua")), "", false)
    }

    fun parameters(template: JSONArray, variables: Map<String, String>): JSONArray = JSONArray().apply {
        for (i in 0 until template.length()) {
            val entry = template.getJSONArray(i)
            put(JSONArray().put(entry.getString(0)).put(value(entry.getString(1), variables)))
        }
    }

    fun apply(builder: Request.Builder, rule: JSONObject, variables: Map<String, String>): Request.Builder {
        rule.optJSONObject("headers")?.let { headers ->
            headers.keys().forEach { name -> builder.header(name, value(headers.getString(name), variables)) }
        }
        when (rule.optString("method", "GET")) {
            "GET" -> {
                if (rule.has("form")) throw AppFailure(ErrorCode.SCHEMA, "GET rule cannot contain a form")
                builder.get()
            }
            "POST" -> {
                val fields = parameters(rule.getJSONArray("form"), variables)
                val body = FormBody.Builder()
                for (i in 0 until fields.length()) fields.getJSONArray(i).let { body.add(it.getString(0), it.getString(1)) }
                builder.post(body.build())
            }
            else -> throw AppFailure(ErrorCode.SCHEMA, "Unsupported request method")
        }
        return builder
    }

    private fun value(template: String, variables: Map<String, String>): String = if (template.startsWith('$'))
        variables[template.drop(1)] ?: throw AppFailure(ErrorCode.SCHEMA, "Missing request variable: ${template.drop(1)}") else template
}
