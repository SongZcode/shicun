package com.emojetools.app.core.ledger

import com.emojetools.app.core.bundle.CanonicalJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

class FetchLedger(platform: String, postId: String, limits: JSONObject) {
    val counters = JSONObject().apply {
        listOf("top_pages", "top_rows", "top_unique", "top_duplicates", "parents_with_replies",
            "parents_fetched", "parents_empty", "reply_pages", "reply_rows", "reply_unique",
            "assets_found", "assets_downloaded", "assets_failed", "assets_deduped").forEach { put(it, 0) }
    }
    val totals = JSONObject().put("api_total", JSONObject.NULL).put("detail_comment_count", JSONObject.NULL)
    val errors = JSONArray()
    val caps = linkedSetOf<String>()
    var top = "server_end"
    var reply = "server_end"
    private val document = JSONObject().put("schema", 1).put("platform", platform).put("aweme_id", postId)
        .put("started_at", Instant.now().toString()).put("limits", limits)
    val extras = JSONObject()

    fun add(key: String, count: Int = 1) { counters.put(key, counters.getInt(key) + count) }
    fun count(key: String) = counters.getInt(key)
    fun snapshot(finished: Boolean = false): JSONObject {
        document.put("finished_at", if (finished) Instant.now().toString() else JSONObject.NULL)
            .put("counters", counters).put("totals", totals)
            .put("termination", JSONObject().put("top", top).put("reply", reply))
            .put("caps_hit", JSONArray(caps.toList())).put("errors", errors)
        document.put("extras", extras)
        seal(document)
        return JSONObject(document.toString())
    }

    companion object {
        val CHECKSUM_KEYS = listOf("schema", "platform", "aweme_id", "started_at", "finished_at", "limits",
            "counters", "totals", "termination", "caps_hit", "errors")
        private val priority = listOf("server_end", "parent_cap", "page_cap", "limit_reached", "replies_cap", "stalled_cursor", "error")
        fun strongest(vararg reasons: String?): String? = reasons.filterNotNull().maxByOrNull { priority.indexOf(it) }

        fun seal(document: JSONObject) {
            val stable = JSONObject()
            CHECKSUM_KEYS.forEach { stable.put(it, document.get(it)) }
            document.put("checksum_algo", "sha256/json-canonical-v1")
                .put("checksum", CanonicalJson.sha256(CanonicalJson.encode(stable).toByteArray(Charsets.UTF_8)))
        }

        fun recordDownload(file: File, id: String, sha1: String?, bytes: Long?, error: String? = null) {
            val document = JSONObject(file.readText())
            val extras = document.getJSONObject("extras")
            val downloads = extras.optJSONObject("downloads") ?: JSONObject().also { extras.put("downloads", it) }
            downloads.put(id, JSONObject().put("sha1", sha1 ?: JSONObject.NULL).put("bytes", bytes ?: JSONObject.NULL)
                .put("status", if (error == null) "saved" else "failed").put("error", error ?: JSONObject.NULL))
            val entries = downloads.keys().asSequence().map { downloads.getJSONObject(it) }.toList()
            document.getJSONObject("counters").put("assets_downloaded", entries.count { it.getString("status") == "saved" })
                .put("assets_failed", entries.count { it.getString("status") == "failed" })
            document.put("finished_at", Instant.now().toString())
            seal(document); write(file, document)
        }

        fun restore(saved: JSONObject): FetchLedger {
            val copy = JSONObject(saved.toString())
            val checksum = copy.getString("checksum")
            require(copy.getString("checksum_algo") == "sha256/json-canonical-v1")
            seal(copy)
            require(copy.getString("checksum") == checksum) { "Invalid ledger checksum" }
            return FetchLedger(copy.getString("platform"), copy.getString("aweme_id"), copy.getJSONObject("limits")).also { ledger ->
                ledger.document.put("started_at", copy.getString("started_at"))
                listOf(ledger.counters to "counters", ledger.totals to "totals", ledger.extras to "extras").forEach { (target, key) ->
                    val source = copy.getJSONObject(key)
                    source.keys().forEach { target.put(it, source.get(it)) }
                }
                val errors = copy.getJSONArray("errors")
                for (i in 0 until errors.length()) ledger.errors.put(errors.get(i))
                val caps = copy.getJSONArray("caps_hit")
                for (i in 0 until caps.length()) ledger.caps += caps.getString(i)
                ledger.top = copy.getJSONObject("termination").getString("top")
                ledger.reply = copy.getJSONObject("termination").getString("reply")
            }
        }

        fun resumeState(document: JSONObject): JSONObject {
            val extras = document.getJSONObject("extras")
            val state = extras.getJSONObject("resume")
            require(state.getInt("schema") == 1)
            require(extras.getString("resume_sha256") == CanonicalJson.sha256(CanonicalJson.encode(state).toByteArray(Charsets.UTF_8)))
            return state
        }

        fun write(file: File, document: JSONObject) {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val pending = File(file.parentFile, file.name + ".tmp")
            pending.outputStream().use { stream ->
                stream.write(document.toString(2).toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
