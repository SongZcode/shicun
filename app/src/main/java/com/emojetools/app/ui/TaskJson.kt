package com.emojetools.app.ui

import android.content.res.Resources
import com.emojetools.app.R
import com.emojetools.app.core.CommentTarget
import com.emojetools.app.core.CommentOrder
import com.emojetools.app.core.OutputAction
import org.json.JSONArray
import org.json.JSONObject

object TaskJson {
    fun encode(task: UiTask, resources: Resources): JSONObject = JSONObject().put("id", task.id).put("source", task.source)
        .put("mode", task.mode.name).put("format_name", resources.getResourceEntryName(task.format))
        .put("target", task.target).put("target_type", task.targetType().name).put("stage", task.stage.name)
        .put("post", task.postId).put("detail", task.detail).put("selected", JSONArray(task.selectedIds.toList()))
        .put("error_code", task.errorCode)
        .put("title", task.title)
        .put("operation", task.operation)
        .put("account_ids", JSONArray(task.accountIds)).put("account_name", task.accountName)
        .put("session_tag", task.sessionTag).put("comments_fetched_at", task.commentsFetchedAt)
        .put("cache_used", task.cacheUsed).put("force_refresh", task.forceRefresh)
        .put("output_action", task.outputAction.name).put("outputs", JSONObject(task.outputs.toMap()))
        .put("comment_order", task.commentOrder.name)
        .put("retained_comment_ids", JSONArray(task.retainedCommentIds))
        .put("collection_key", task.collectionKey)

    fun decode(item: JSONObject, resources: Resources): UiTask {
        val mode = DownloadMode.valueOf(item.getString("mode"))
        require(item.getString("format_name") == resources.getResourceEntryName(R.string.format_original))
        val type = when (CommentTarget.valueOf(item.getString("target_type"))) {
            CommentTarget.COMMENT_ID -> R.string.target_id
            CommentTarget.AUTHOR_ID -> R.string.target_author
            CommentTarget.AUTHOR_NAME -> R.string.target_author_name
            CommentTarget.MINE -> R.string.target_mine
            CommentTarget.TEXT -> R.string.target_text
        }
        val selected = item.getJSONArray("selected")
        val rawStage = TaskStage.valueOf(item.getString("stage"))
        val stage = if (rawStage.isRunning()) TaskStage.INTERRUPTED else rawStage
        return UiTask(item.getLong("id"), item.getString("source"), mode, R.string.format_original, item.getString("target"), stage,
            item.getString("post"), item.getString("detail"), type,
            (0 until selected.length()).map { selected.getString(it) }.toMutableSet(), item.getString("error_code"), item.getString("title"),
            item.getString("operation"),
            item.getJSONArray("account_ids").let { ids -> (0 until ids.length()).map { ids.getString(it) } },
            item.getString("account_name"), item.getString("session_tag"), item.getLong("comments_fetched_at"),
            item.getBoolean("cache_used"), item.getBoolean("force_refresh"),
            OutputAction.valueOf(item.getString("output_action")),
            item.getJSONObject("outputs").let { output -> output.keys().asSequence().associateWith { output.getString(it) }.toMutableMap() },
            CommentOrder.valueOf(item.getString("comment_order")),
            item.getJSONArray("retained_comment_ids").let { ids -> (0 until ids.length()).map { ids.getString(it) } },
            if (mode in listOf(DownloadMode.FAVORITES, DownloadMode.ACCOUNT_WORKS)) item.getString("collection_key") else "")
    }
}
