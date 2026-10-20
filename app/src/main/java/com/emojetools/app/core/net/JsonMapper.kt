package com.emojetools.app.core.net

import com.emojetools.app.core.AppFailure
import com.emojetools.app.core.ErrorCode
import org.json.JSONArray
import org.json.JSONObject

class JsonMapper(private val mappings: JSONObject) {
    fun has(name: String) = mappings.has(name)

    fun values(name: String, source: Any): List<Any> {
        val path = mappings.getJSONArray(name)
        var nodes = listOf(source)
        for (i in 0 until path.length()) {
            val token = path.get(i)
            nodes = nodes.flatMap { node ->
                when {
                    token == "*" -> {
                        if (node !is JSONArray) throw AppFailure(ErrorCode.SCHEMA, "Mapped collection is not an array: $name")
                        (0 until node.length()).map { node.get(it) }
                    }
                    token is Number && node is JSONArray -> listOfNotNull(node.opt(token.toInt()))
                    node is JSONObject -> listOfNotNull(node.opt(token.toString()))
                    else -> emptyList()
                }.filter { it != JSONObject.NULL }
            }
        }
        return nodes
    }
    fun optional(name: String, source: Any): Any? = values(name, source).firstOrNull()
    fun required(name: String, source: Any): Any = optional(name, source)
        ?: throw AppFailure(ErrorCode.SCHEMA, "Missing mapped field: $name")
}
