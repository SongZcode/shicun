package com.emojetools.app.core.store

import com.emojetools.app.core.*
import com.emojetools.app.core.bundle.CanonicalJson
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.platform.douyin.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WorkAccount(val name: String, val secUid: String, val platform: String = "douyin") {
    init { require(secUid.isNotBlank()) }
    val key: String get() = (if (platform == "douyin") "works-" else "$platform-works-") + CanonicalJson.sha256(secUid.toByteArray(Charsets.UTF_8))
}

data class AccountWorksCollection(
    val kind: WorkCollectionKind, val sessionTag: String, val account: WorkAccount,
    val works: List<AccountWork> = emptyList(), val selected: Set<String> = emptySet(),
    val cursor: String = "0", val cursors: Set<String> = emptySet(), val completed: Boolean = false,
    val stopped: Boolean = false, val unavailable: Int = 0, val updatedAt: Long = 0, val folderId: String = ""
) {
    fun append(page: AccountWorksPage, maxPages: Int): AccountWorksCollection {
        val combined = linkedMapOf<String, AccountWork>()
        works.forEach { combined[it.ref.id] = it }
        page.works.forEach { combined[it.ref.id] = it }
        val visited = cursors + cursor
        return copy(works = combined.values.toList(), cursor = page.nextCursor, cursors = visited, completed = page.completed,
            stopped = !page.completed && (page.nextCursor in visited || visited.size >= maxPages ||
                (page.works.isNotEmpty() && page.works.all { fresh -> works.any { it.ref.id == fresh.ref.id } })),
            unavailable = unavailable + page.unavailable, updatedAt = System.currentTimeMillis())
    }
    fun select(id: String, checked: Boolean): AccountWorksCollection {
        require(works.any { it.ref.id == id })
        return copy(selected = if (checked) selected + id else selected - id)
    }
    fun restart() = copy(works = works.filter { it.ref.id in selected }, cursor = "0", cursors = emptySet(),
        completed = false, stopped = false, unavailable = 0, updatedAt = 0)
    fun encode(): JSONObject = JSONObject().put("kind", kind.name).put("session", sessionTag).put("account", account.name).put("sec_uid", account.secUid)
        .put("works", JSONArray(works.map(::encodeWork))).put("selected", JSONArray(selected.toList())).put("cursor", cursor)
        .put("cursors", JSONArray(cursors.toList())).put("completed", completed).put("stopped", stopped)
        .put("unavailable", unavailable).put("updated_at", updatedAt).put("platform", account.platform).put("folder", folderId)

    companion object {
        fun encodeWork(work: AccountWork): JSONObject = JSONObject().put("id", work.ref.id).put("title", work.title).put("author", work.author)
            .put("covers", JSONArray(work.covers)).put("image_count", work.imageCount).put("duration", work.durationMs ?: JSONObject.NULL)
            .put("platform", work.ref.platform).put("extra", JSONObject(work.ref.extra))
        fun decodeWork(json: JSONObject) = AccountWork(PostRef(json.optString("platform", "douyin"), json.getString("id"),
            json.optJSONObject("extra")?.let { extra -> extra.keys().asSequence().associateWith { extra.getString(it) } }.orEmpty()), json.getString("title"), json.getString("author"),
            json.getJSONArray("covers").let { a -> (0 until a.length()).map { a.getString(it) } }, json.getInt("image_count"),
            if (json.isNull("duration")) null else json.getLong("duration"))
        fun decode(json: JSONObject): AccountWorksCollection {
            fun strings(key: String) = json.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val works = json.getJSONArray("works").let { a -> (0 until a.length()).map { decodeWork(a.getJSONObject(it)) } }
            return AccountWorksCollection(WorkCollectionKind.valueOf(json.getString("kind")), json.getString("session"),
                WorkAccount(json.getString("account"), json.getString("sec_uid"), json.optString("platform", "douyin")), works,
                strings("selected").intersect(works.map { it.ref.id }.toSet()), json.getString("cursor"), strings("cursors"),
                json.getBoolean("completed"), json.getBoolean("stopped"), json.getInt("unavailable"), json.getLong("updated_at"), json.optString("folder"))
        }
    }
}

class AccountWorksStore(private val directory: File) {
    private fun file(kind: WorkCollectionKind, folder: String = ""): File {
        require(folder.isEmpty() || folder.matches(Regex("[0-9]+")))
        return File(directory, "${kind.name}${if (folder.isEmpty()) "" else "_$folder"}.json")
    }
    fun read(kind: WorkCollectionKind, session: String, folder: String = ""): AccountWorksCollection? {
        val file = file(kind, folder)
        if (!file.isFile) return null
        val state = try { AccountWorksCollection.decode(JSONObject(file.readText())) }
        catch (_: org.json.JSONException) { file.delete(); return null }
        catch (_: IllegalArgumentException) { file.delete(); return null }
        if (state.sessionTag == session && state.kind == kind && state.folderId == folder) return state
        file.delete(); return null
    }
    fun write(state: AccountWorksCollection) = FetchLedger.write(file(state.kind, state.folderId), state.encode())
    fun clear() { directory.listFiles()?.filter { it.extension == "json" }?.forEach { it.delete() } }
}
