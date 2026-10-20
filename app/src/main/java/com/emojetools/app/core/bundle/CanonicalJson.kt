package com.emojetools.app.core.bundle

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object CanonicalJson {
    fun encode(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sortedWith { a, b -> compareKeys(a, b) }
            .joinToString(",", "{", "}") { quote(it) + ":" + encode(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { encode(value.get(it)) }
        is String -> quote(value)
        is Boolean, is Int, is Long -> value.toString()
        else -> throw IllegalArgumentException("Canonical schema permits integers, strings, booleans and null")
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun compareKeys(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i); val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x); j += Character.charCount(y)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun quote(text: String): String = buildString {
        append('"')
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    require(!c.isLowSurrogate()) { "Unpaired surrogate" }
                    if (c.isHighSurrogate()) {
                        require(i + 1 < text.length && text[i + 1].isLowSurrogate()) { "Unpaired surrogate" }
                        append(c); append(text[++i])
                    } else if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
                }
            }
            i++
        }
        append('"')
    }
}
