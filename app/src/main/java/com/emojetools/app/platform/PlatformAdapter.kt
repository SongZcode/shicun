package com.emojetools.app.platform

import com.emojetools.app.core.*

interface PlatformAdapter {
    val id: String
    fun matches(rawInput: String): Boolean
    suspend fun resolve(rawInput: String): PostRef
    suspend fun fetchPost(ref: PostRef): Post
    suspend fun fetchAssets(ref: PostRef, prefs: QualityPrefs): List<Asset>
    suspend fun fetchCommentAssets(ref: PostRef, limit: Int): CommentResult
    suspend fun loginState(): LoginState
    fun loginEntryPoint(): LoginTarget
    fun capabilities(): Caps
}
