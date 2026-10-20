package com.emojetools.app.core.store

import com.emojetools.app.core.Asset
import com.emojetools.app.core.AssetKind
import org.json.JSONArray
import org.json.JSONObject

object AssetJson {
    fun encode(asset: Asset): JSONObject = JSONObject().put("id", asset.id).put("kind", asset.kind.name)
        .put("source", asset.source).put("urls", JSONArray(asset.urls)).put("ext", asset.ext ?: JSONObject.NULL)
        .put("width", asset.width ?: JSONObject.NULL).put("height", asset.height ?: JSONObject.NULL)
        .put("bytes", asset.bytes ?: JSONObject.NULL).put("origin", asset.originUrl ?: JSONObject.NULL).put("meta", JSONObject(asset.meta))
    fun decode(asset: JSONObject): Asset {
        val urls = asset.getJSONArray("urls")
        val meta = asset.getJSONObject("meta")
        return Asset(asset.getString("id"), AssetKind.valueOf(asset.getString("kind")), asset.getString("source"),
            (0 until urls.length()).map { urls.getString(it) }, asset.optionalString("ext"), asset.optionalInt("width"),
            asset.optionalInt("height"), if (asset.isNull("bytes")) null else asset.getLong("bytes"), asset.optionalString("origin"),
            meta.keys().asSequence().associateWith { meta.getString(it) })
    }
    private fun JSONObject.optionalString(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.optionalInt(key: String) = if (isNull(key)) null else getInt(key)
}
