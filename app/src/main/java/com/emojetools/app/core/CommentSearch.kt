package com.emojetools.app.core

import java.text.Normalizer
import java.util.Locale

object CommentSearch {
    private val spaces = Regex("\\s+")
    private val punctuation = Regex("[\\p{P}\\s]+")
    private val invisible = Regex("\\p{Cf}")
    private val handlePrefix = Regex("^(?:抖音号|抖音id)\\s*:?\\s*")

    fun match(comments: List<CommentCandidate>, target: CommentTarget, query: String,
        accountIds: List<String> = emptyList()): List<CommentCandidate> {
        val value = query.trim().trim('"', '\u201c', '\u201d').trim()
        require(target == CommentTarget.MINE || value.isNotEmpty())
        require(target != CommentTarget.MINE || accountIds.isNotEmpty()) { "Missing account identity" }
        val publicHandle = if (target == CommentTarget.AUTHOR_NAME) handle(value) else ""
        val copied = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val copiedParts = if (target in listOf(CommentTarget.TEXT, CommentTarget.AUTHOR_NAME) && copied.startsWith("@"))
            copied.mapIndexedNotNull { index, char ->
                if (char == ':') copied.substring(1, index).trim() to copied.substring(index + 1).trim() else null
            }.filter { (author, text) -> author.isNotEmpty() && text.isNotEmpty() }
        else emptyList()
        return comments.filter { comment -> if (copiedParts.isNotEmpty()) {
            copiedParts.any { (author, text) -> contains(comment.author, author) && contains(comment.text, text) }
        } else when (target) {
            CommentTarget.TEXT -> contains(comment.text, value)
            CommentTarget.AUTHOR_NAME -> contains(comment.author, value.removePrefix("@")) ||
                comment.authorIds.any { identifier(it) == publicHandle }
            CommentTarget.COMMENT_ID -> comment.id == value
            CommentTarget.AUTHOR_ID -> value in comment.authorIds
            CommentTarget.MINE -> comment.authorIds.any { it in accountIds }
        } }
    }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    private fun identifier(value: String) = invisible.replace(normalize(value), "").trim()
    private fun handle(value: String) = handlePrefix.replaceFirst(identifier(value), "").removePrefix("@").trim()
    private fun compact(value: String) = punctuation.replace(value, "")
    private fun contains(text: String, query: String): Boolean {
        val tokens = normalize(query).split(spaces).map(::compact).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val candidate = compact(normalize(text))
        return tokens.all { it in candidate }
    }
}
