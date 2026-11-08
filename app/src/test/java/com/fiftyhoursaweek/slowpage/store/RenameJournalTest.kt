package com.fiftyhoursaweek.slowpage.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A rename the process did not finish, finished at the next start (Codex rename rounds 2 and
 * 3): at every point the process can end, nothing is deleted, and running it again changes
 * nothing more.
 */
class RenameJournalTest {
    @get:Rule val tmp = TemporaryFolder()
    private val store by lazy { DocumentStore(File(tmp.root, "documents"), 1024 * 1024) }
    private val recovery by lazy { Recovery(File(tmp.root, "recovery"), 1024 * 1024) }
    private val recentsFile get() = File(tmp.root, "recents")
    private val journalFile get() = File(tmp.root, "rename")
    private val journal by lazy { RenameJournal(journalFile) }

    private fun recents(vararg names: String) = Recents(recentsFile).apply { names.reversed().forEach(::add); persist() }

    /** "Old.md" with kept text, in Recent after "a.md", and the journal written: the rename has begun. */
    private fun begun() {
        store.write("Old.md", "on disk")
        assertNull(recovery.write("Old.md", "kept", stamp = 7))
        recents("a.md", "Old.md")
        journal.begin("Old.md", "New.md", store.file("Old.md").lastModified())
    }

    /** Recover twice, as two starts would: the second changes nothing. */
    private fun recoverTwice() {
        repeat(2) { assertNull(journal.recover(store, recovery, Recents(recentsFile))) }
    }

    private fun assertFinished() {
        assertEquals("on disk", store.read("New.md"))
        assertFalse(store.exists("Old.md"))
        assertEquals(Kept("kept", 7), recovery.read("New.md"))
        assertFalse(recovery.has("Old.md"))
        assertEquals(listOf("a.md", "New.md"), Recents(recentsFile).names())
        assertFalse(journalFile.exists())
    }

    @Test fun endedBeforeTheDocumentMoved_nothingChanges() {
        begun()
        recoverTwice()
        assertEquals("on disk", store.read("Old.md"))
        assertFalse(store.exists("New.md"))
        assertEquals(Kept("kept", 7), recovery.read("Old.md"))
        assertEquals(listOf("a.md", "Old.md"), Recents(recentsFile).names())
        assertFalse(journalFile.exists())
    }

    @Test fun endedAfterTheDocumentMoved_theKeptTextAndRecentFollow() {
        begun()
        store.rename("Old.md", "New.md")
        recoverTwice()
        assertFinished()
    }

    /** Codex round 2 verification, NEW-B1: the copy that already moved is kept, not deleted. */
    @Test fun endedAfterTheKeptTextMoved_itStays() {
        begun()
        store.rename("Old.md", "New.md")
        assertTrue(recovery.rename("Old.md", "New.md"))
        recoverTwice()
        assertFinished()
    }

    @Test fun endedAfterRecentWasWritten_nothingMoreToDo() {
        begun()
        store.rename("Old.md", "New.md")
        assertTrue(recovery.rename("Old.md", "New.md"))
        recents("a.md", "New.md")
        recoverTwice()
        assertFinished()
    }

    /** NEW-B2: a copy that cannot move is never deleted or replaced; the journal stays to try again. */
    @Test fun aCopyThatCannotMoveStaysAndSoDoesTheJournal() {
        begun()
        store.rename("Old.md", "New.md")
        assertNull(recovery.write("New.md", "another", stamp = 9))
        recoverTwice()
        assertEquals(Kept("kept", 7), recovery.read("Old.md"))
        assertEquals(Kept("another", 9), recovery.read("New.md"))
        assertTrue(journalFile.exists())
        // Once the place is free, the next start finishes it.
        assertTrue(recovery.moveIfAbsent("New.md", "Elsewhere.md"))
        recoverTwice()
        assertFinished()
    }

    /** NEW-M1: a journal about other files (the old document gone, another under the new name) changes nothing. */
    @Test fun aJournalAboutOtherFilesChangesNothing() {
        begun()
        store.delete("Old.md")
        store.write("New.md", "another document")
        store.file("New.md").setLastModified(1_000)
        recoverTwice()
        assertEquals(Kept("kept", 7), recovery.read("Old.md"))
        assertFalse(recovery.has("New.md"))
        assertEquals(listOf("a.md", "Old.md"), Recents(recentsFile).names())
        assertFalse(journalFile.exists())
    }

    /** NEW-M3: Recent that cannot be written is reported, and the journal stays to try again. */
    @Test fun aRecentThatCannotBeWrittenIsReportedAndTried() {
        begun()
        store.rename("Old.md", "New.md")
        // A folder, not empty, where the file should be: it cannot be written over.
        val unwritable = Recents(store.folder).apply { add("Old.md") }
        assertNotNull(journal.recover(store, recovery, unwritable))
        assertTrue(journalFile.exists())
        assertEquals(Kept("kept", 7), recovery.read("New.md"))
        recoverTwice()
        assertFinished()
    }

    /** NEW-M2: only exactly what begin wrote is used; anything else is dropped. */
    @Test fun aDamagedJournalIsDroppedAndNeverNamesAPath() {
        val h = RenameJournal.HEADER
        for (content in listOf(
            "garbage",
            "$h\nOld.md\nNew.md\n",  // no time
            "$h\nOld.md\nNew.md\n12",  // cut short
            "$h\nOld.md\nNew.md\n12\nmore\n",  // trailing garbage
            "$h\nOld.md\nNew.md\nlater\n",
            "$h\n../x.md\nNew.md\n12\n",
            "$h\nOld.md\nOld.md\n12\n",
        )) {
            journalFile.writeText(content)
            assertNull(content, journal.read())
            assertNull(journal.recover(store, recovery, Recents(recentsFile)))
            assertFalse(content, journalFile.exists())
        }
        journal.begin("Old.md", "New.md", 12)
        assertEquals(RenameJournal.Entry("Old.md", "New.md", 12), journal.read())
        journal.end()
        assertNull(journal.read())
    }
}
