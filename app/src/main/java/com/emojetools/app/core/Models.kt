package com.emojetools.app.core

data class PostRef(val platform: String, val id: String, val extra: Map<String, String> = emptyMap())
val PostRef.storageId: String get() = if (platform == "douyin") id else "$platform:$id:${extra.getValue("cid")}"
data class PostPart(val id: String, val title: String)
data class Post(
    val ref: PostRef, val title: String, val author: String, val authorId: String,
    val coverUrl: String?, val durationMs: Long?, val publishedAt: Long?,
    val stats: Map<String, Long>, val parts: List<PostPart>
)
enum class AssetKind { VIDEO, IMAGE, STICKER, AUDIO, COVER }
enum class OutputAction { SAVE, SHARE }
data class Asset(
    val id: String, val kind: AssetKind, val source: String, val urls: List<String>,
    val ext: String?, val width: Int?, val height: Int?, val bytes: Long?,
    val originUrl: String? = null, val meta: Map<String, String> = emptyMap()
)
data class QualityPrefs(val preferHighest: Boolean = true)
data class PostMedia(val post: Post, val assets: List<Asset>)
data class CommentResult(
    val assets: List<Asset>, val providedTotal: Long?, val authoritativeTotal: Long?,
    val fetchedTopLevel: Int, val fetchedReplies: Int, val termination: String,
    val comments: List<CommentCandidate> = emptyList(), val ledgerPath: String? = null,
    val topTermination: String = termination, val replyTermination: String = "server_end"
)

data class CommentCandidate(
    val id: String, val parentId: String?, val text: String, val author: String,
    val authorIds: List<String>, val assetIds: List<String>
)

data class AccountIdentity(val name: String, val ids: List<String>)

enum class CommentTarget { TEXT, COMMENT_ID, AUTHOR_ID, AUTHOR_NAME, MINE }
enum class CommentOrder { TOP_FIRST, REPLIES_FIRST }

fun CommentResult.matching(target: CommentTarget, query: String, accountIds: List<String> = emptyList()): List<CommentCandidate> =
    CommentSearch.match(comments, target, query, accountIds)
enum class LoginState { LOGGED_IN, NEED_LOGIN, UNKNOWN }
data class LoginTarget(val url: String, val userAgent: String)
data class Caps(val video: Boolean, val images: Boolean, val audio: Boolean, val comments: Boolean)
enum class ErrorCode { NEED_LOGIN, BLOCKED, RATE_LIMIT, NOT_FOUND, UNSUPPORTED, NETWORK, SCHEMA, INVALID_BUNDLE, BUNDLE_INCOMPATIBLE, JS_ERROR, API_ERROR, STORAGE }
open class AppFailure(val code: ErrorCode, message: String, cause: Throwable? = null, val httpStatus: Int? = null) : Exception(message, cause)
class CommentFetchFailure(val partial: CommentResult, failure: AppFailure) : AppFailure(failure.code, failure.message.orEmpty(), failure, failure.httpStatus)
