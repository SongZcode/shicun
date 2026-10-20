package com.emojetools.app.core

import org.junit.Assert.*
import org.junit.Test

class CommentSearchTest {
    private fun comment(id: String, author: String, text: String, user: String = "user-$id") =
        CommentCandidate(id, null, text, author, listOf(user), listOf("asset-$id"))
    private fun matching(rows: List<CommentCandidate>, type: CommentTarget, query: String, ids: List<String> = emptyList()) =
        CommentSearch.match(rows, type, query, ids).map { it.id }

    @Test fun nicknameFragmentsIgnorePunctuationSpacingAndWidth() {
        val rows = listOf(comment("1", "Long\u3000Name\u3001\uff21", "text"), comment("2", "Other", "text"))
        assertEquals(listOf("1"), matching(rows, CommentTarget.AUTHOR_NAME, "@name a"))
        assertEquals(listOf("1"), matching(rows, CommentTarget.AUTHOR_NAME, "Long"))
        assertEquals(listOf("2"), matching(rows, CommentTarget.AUTHOR_NAME, "user-2"))
        assertTrue(matching(rows, CommentTarget.AUTHOR_NAME, "user-").isEmpty())
    }

    @Test fun copiedCommentUsesBothAuthorAndBodyIncludingColonsInNickname() {
        val rows = listOf(comment("1", "Alice:Wonder\u3001", "Is this the officer from Big Bang Theory?"),
            comment("2", "Bob", "Is this the officer from Big Bang Theory?"))
        assertEquals(listOf("1"), matching(rows, CommentTarget.TEXT, "@Alice:Wonder\u3001: Is this the officer from Big Bang Theory?"))
        assertEquals(listOf("1"), matching(rows, CommentTarget.TEXT, "\u201c@Alice:Wonder\uff1a Big Bang Theory\u201d"))
        assertEquals(listOf("1", "2"), matching(rows, CommentTarget.TEXT, "theory officer"))
    }

    @Test fun aFuzzyHitDoesNotDiscardOtherCandidatesAndRepliesAreIncluded() {
        val rows = listOf(comment("1", "Alice", "Matching one"), comment("2", "Alice Two", "Matching two").copy(parentId = "parent"))
        assertEquals(listOf("1", "2"), matching(rows, CommentTarget.AUTHOR_NAME, "Ali"))
        assertEquals(listOf("1", "2"), matching(rows, CommentTarget.TEXT, "match"))
    }

    @Test fun ownCommentsUseExactAccountIdsAndNeverUseSameNickname() {
        val rows = listOf(comment("1", "Same Name", "", "9007199254740993"), comment("2", "Same Name", "", "9007199254740994"))
        assertEquals(listOf("1"), matching(rows, CommentTarget.MINE, "", listOf("9007199254740993")))
        assertEquals(listOf("1"), matching(rows, CommentTarget.AUTHOR_ID, "9007199254740993"))
        assertTrue(matching(rows, CommentTarget.AUTHOR_ID, "900719925474099").isEmpty())
    }

    @Test fun emojiQueriesSurviveNormalizationAndPunctuationCannotMatchEverything() {
        val rows = listOf(comment("1", "Alice", "Hello \ud83d\ude00"))
        assertEquals(listOf("1"), matching(rows, CommentTarget.TEXT, "\ud83d\ude00"))
        assertTrue(matching(rows, CommentTarget.TEXT, "!!!").isEmpty())
    }

    @Test fun publicHandlesAcceptCopiedPrefixesWidthCaseAndInvisibleCharacters() {
        val rows = listOf(comment("1", "Alice", "", "Ab_123.45"), comment("2", "Bob", "", "ab12345"))
        listOf("AB_123.45", "@Ab_123.45", "抖音号：Ab_123.45", "抖音ID: @Ab_123.45",
            "Ａｂ＿１２３．４５", "\u200bAb_123.45\uFEFF").forEach { query ->
            assertEquals(query, listOf("1"), matching(rows, CommentTarget.AUTHOR_NAME, query))
        }
        assertTrue(matching(rows, CommentTarget.AUTHOR_NAME, "Ab_123").isEmpty())
        assertEquals(listOf("2"), matching(rows, CommentTarget.AUTHOR_NAME, "ab12345"))
    }

    @Test fun copiedDouyinCommentIsRecognizedFromBothSearchEntriesAndMatchesAuthorAndContentTogether() {
        val rows = listOf(comment("1", "小刘刷抖音", "工作做完了吗就跳舞").copy(parentId = "parent"),
            comment("2", "其他人", "工作做完了吗就跳舞"), comment("3", "小刘刷抖音", "其他内容"))
        listOf(CommentTarget.TEXT, CommentTarget.AUTHOR_NAME).forEach { target ->
            listOf("@小刘刷抖音: 工作做完了吗就跳舞", "＠小刘刷抖音： 工作做完了吗就跳舞",
                "\u201c@小刘刷抖音: 工作做完了吗就跳舞\u201d").forEach { query ->
                assertEquals(listOf("1"), matching(rows, target, query))
            }
        }
        assertEquals(listOf("1", "3"), matching(rows, CommentTarget.AUTHOR_NAME, "小刘"))
        assertEquals(listOf("1", "2"), matching(rows, CommentTarget.TEXT, "工作做完"))
    }

    @Test(expected = IllegalArgumentException::class) fun ownCommentsRequireAnIdentifiedAccount() {
        matching(emptyList(), CommentTarget.MINE, "")
    }
}
