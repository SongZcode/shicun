package com.emojetools.app.core.store

import com.emojetools.app.core.*
import org.json.JSONArray
import org.json.JSONObject

object CommentResultJson {
    fun encode(result: CommentResult) = JSONObject().put("provided", result.providedTotal ?: JSONObject.NULL)
        .put("authoritative", result.authoritativeTotal ?: JSONObject.NULL).put("top", result.fetchedTopLevel)
        .put("replies", result.fetchedReplies).put("termination", result.termination)
        .put("topTermination", result.topTermination).put("replyTermination", result.replyTermination)
        .put("ledger", result.ledgerPath ?: JSONObject.NULL)
        .put("assets", JSONArray(result.assets.map(AssetJson::encode)))
        .put("comments", JSONArray(result.comments.map { comment -> JSONObject().put("id", comment.id)
            .put("parent", comment.parentId ?: JSONObject.NULL).put("text", comment.text).put("author", comment.author)
            .put("authorIds", JSONArray(comment.authorIds)).put("assetIds", JSONArray(comment.assetIds)) }))

    fun decode(json: JSONObject): CommentResult {
        val assets = json.getJSONArray("assets").objects().map(AssetJson::decode)
        val comments = json.getJSONArray("comments").objects().map { comment -> CommentCandidate(
            comment.getString("id"), comment.nullString("parent"), comment.getString("text"), comment.getString("author"),
            comment.getJSONArray("authorIds").strings(), comment.getJSONArray("assetIds").strings()) }
        return CommentResult(assets, json.nullLong("provided"), json.nullLong("authoritative"), json.getInt("top"), json.getInt("replies"),
            json.getString("termination"), comments, json.nullString("ledger"), json.getString("topTermination"), json.getString("replyTermination"))
    }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONObject.nullString(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.nullLong(key: String) = if (isNull(key)) null else getLong(key)
}
