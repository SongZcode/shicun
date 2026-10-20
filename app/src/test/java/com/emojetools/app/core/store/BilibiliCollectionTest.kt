package com.emojetools.app.core.store

import com.emojetools.app.core.PostRef
import com.emojetools.app.platform.douyin.AccountWork
import com.emojetools.app.platform.douyin.AccountWorksPage
import com.emojetools.app.platform.douyin.WorkCollectionKind
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class BilibiliCollectionTest {
    private val work = AccountWork(PostRef("bilibili", "170001", mapOf("bvid" to "BV17x411w7KC")),
        "Title", "Author", listOf("https://example.invalid/cover.jpg"), 0, 12000)

    @Test fun platformReferencesFolderSelectionsAndAccountSessionsStayIsolated() {
        val root = Files.createTempDirectory("bili-collections").toFile()
        try {
            val store = AccountWorksStore(root)
            val account = WorkAccount("A", "123", "bilibili")
            assertNotEquals(WorkAccount("A", "123").key, account.key)
            val first = AccountWorksCollection(WorkCollectionKind.SAVED, "session-a", account, cursor = "1", folderId = "11")
                .append(AccountWorksPage(listOf(work), "2", false, 0), 200).select(work.ref.id, true)
            val second = first.copy(folderId = "22", selected = emptySet())
            store.write(first); store.write(second)
            assertEquals(work.ref, store.read(first.kind, "session-a", "11")!!.works.single().ref)
            assertEquals(setOf(work.ref.id), store.read(first.kind, "session-a", "11")!!.selected)
            assertTrue(store.read(first.kind, "session-a", "22")!!.selected.isEmpty())
            assertNull(store.read(first.kind, "session-b", "11"))
            assertNotNull(store.read(first.kind, "session-a", "22"))
        } finally { root.deleteRecursively() }
    }

    @Test fun ignoredPageParameterStopsWithoutLosingAlreadyVisibleWorksOrSelection() {
        val first = AccountWorksCollection(WorkCollectionKind.LIKED, "s", WorkAccount("A", "123", "bilibili"), cursor = "1")
            .append(AccountWorksPage(listOf(work), "2", false, 0), 200).select(work.ref.id, true)
        val repeated = first.append(AccountWorksPage(listOf(work), "3", false, 0), 200)
        assertTrue(repeated.stopped); assertFalse(repeated.completed)
        assertEquals(listOf(work), repeated.works); assertEquals(first.selected, repeated.selected)
    }
}
