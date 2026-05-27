package dev.guber.markdownviewer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentStateStoreTest {

    @Test
    fun `saves and restores recent documents in most recent first order`() {
        val store = DocumentStateStore(InMemoryDocumentStatePersistence())

        store.recordOpen("uri-1", "Doc 1")
        store.recordOpen("uri-2", "Doc 2")
        store.recordOpen("uri-1", "Doc 1 updated")

        val recent = store.getRecentDocuments()
        assertEquals(2, recent.size)
        assertEquals("uri-1", recent[0].uriString)
        assertEquals("Doc 1 updated", recent[0].title)
        assertEquals("uri-2", recent[1].uriString)
    }

    @Test
    fun `keeps only configured recent history limit`() {
        val store = DocumentStateStore(InMemoryDocumentStatePersistence(), maxRecentDocuments = 3)

        (1..5).forEach { index ->
            store.recordOpen("uri-$index", "Doc $index")
        }

        val recent = store.getRecentDocuments()
        assertEquals(listOf("uri-5", "uri-4", "uri-3"), recent.map { doc -> doc.uriString })
    }

    @Test
    fun `saves and restores reading position by document`() {
        val store = DocumentStateStore(InMemoryDocumentStatePersistence())

        store.saveReadingPosition("uri-1", 420)

        assertEquals(420, store.getReadingPosition("uri-1"))
        assertNull(store.getReadingPosition("missing"))
    }

    @Test
    fun `resume candidates only include docs with saved positions`() {
        val store = DocumentStateStore(InMemoryDocumentStatePersistence())

        store.recordOpen("uri-1", "Doc 1")
        store.recordOpen("uri-2", "Doc 2")
        store.saveReadingPosition("uri-2", 900)

        val resumable = store.getRecentDocuments().filter { doc -> doc.lastScrollY != null }
        assertEquals(1, resumable.size)
        assertEquals("uri-2", resumable.first().uriString)
        assertTrue(resumable.first().lastScrollY == 900)
    }

    @Test
    fun `clear all removes recent docs and positions`() {
        val store = DocumentStateStore(InMemoryDocumentStatePersistence())
        store.recordOpen("uri-1", "Doc 1")
        store.saveReadingPosition("uri-1", 321)

        store.clearAll()

        assertTrue(store.getRecentDocuments().isEmpty())
        assertNull(store.getReadingPosition("uri-1"))
    }
}
