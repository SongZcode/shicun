package com.emojetools.app.core.store

import com.emojetools.app.core.Asset
import com.emojetools.app.core.AccountIdentity
import com.emojetools.app.core.AppFailure
import com.emojetools.app.core.ErrorCode
import com.emojetools.app.core.bundle.CanonicalJson
import com.emojetools.app.platform.douyin.FavoriteStickerPage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.emojetools.app.core.ledger.FetchLedger

data class FavoriteCollection(
    val sessionTag: String, val key: String, val accountName: String,
    val assets: List<Asset> = emptyList(), val selected: Set<String> = emptySet(),
    val cursor: String = "0", val cursors: Set<String> = emptySet(),
    val completed: Boolean = false, val stopped: Boolean = false, val updatedAt: Long = 0
) {
    fun append(page: FavoriteStickerPage, maxPages: Int, now: Long = System.currentTimeMillis()): FavoriteCollection {
        val combined = linkedMapOf<String, Asset>()
        assets.forEach { combined[it.id] = it }
        page.assets.forEach { asset ->
            val old = combined[asset.id]
            combined[asset.id] = if (old == null) asset else asset.copy(urls = (asset.urls + old.urls).distinct())
        }
        val visited = cursors + cursor
        return copy(assets = combined.values.toList(), cursor = page.nextCursor, cursors = visited,
            completed = page.completed, stopped = !page.completed && (page.nextCursor in visited || visited.size >= maxPages), updatedAt = now)
    }

    fun select(id: String, checked: Boolean): FavoriteCollection {
        require(assets.any { it.id == id })
        return copy(selected = if (checked) selected + id else selected - id)
    }

    fun encode(): JSONObject = JSONObject().put("session", sessionTag).put("key", key).put("account", accountName)
        .put("assets", JSONArray(assets.map(AssetJson::encode))).put("selected", JSONArray(selected.toList()))
        .put("cursor", cursor).put("cursors", JSONArray(cursors.toList())).put("completed", completed)
        .put("stopped", stopped).put("updated_at", updatedAt)

    companion object {
        fun accountKey(account: AccountIdentity): String {
            val id = account.ids.firstOrNull() ?: throw AppFailure(ErrorCode.NEED_LOGIN, "未能识别当前登录账号，请重新登录")
            return "favorites-" + CanonicalJson.sha256(id.toByteArray(Charsets.UTF_8))
        }
        fun decode(json: JSONObject): FavoriteCollection {
            fun strings(key: String) = json.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val assets = json.getJSONArray("assets").let { a -> (0 until a.length()).map { AssetJson.decode(a.getJSONObject(it)) } }
            return FavoriteCollection(json.getString("session"), json.getString("key"), json.getString("account"), assets,
                strings("selected").intersect(assets.map { it.id }.toSet()), json.getString("cursor"), strings("cursors"),
                json.getBoolean("completed"), json.getBoolean("stopped"), json.getLong("updated_at"))
        }
    }
}

class FavoriteCollectionStore(private val file: File) {
    fun read(sessionTag: String): FavoriteCollection? {
        if (!file.isFile) return null
        val state = try { FavoriteCollection.decode(JSONObject(file.readText())) }
            catch (_: org.json.JSONException) { file.delete(); return null }
            catch (_: IllegalArgumentException) { file.delete(); return null }
        if (state.sessionTag == sessionTag) return state
        file.delete()
        return null
    }
    fun write(state: FavoriteCollection) = FetchLedger.write(file, state.encode())
    fun clear() { file.delete() }
}
