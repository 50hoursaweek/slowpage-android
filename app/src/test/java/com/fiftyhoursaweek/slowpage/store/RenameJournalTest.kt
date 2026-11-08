package com.fiftyhoursaweek.slowpage.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A rename the process did not finish is finished or left at the next start (Codex rename round 2, BLOCKING 2). */
class RenameJournalTest {
    @get:Rule val tmp = TemporaryFolder()
    private val store by lazy { DocumentStore(File(tmp.root, "documents"), 1024 * 1024) }
    private val recovery by lazy { Recovery(File(tmp.root, "recovery"), 1024 * 1024) }
    private val recentsFile get() = File(tmp.root, "recents")
    private val journalFile get() = File(tmp.root, "rename")
    private val journal by lazy { RenameJournal(journalFile) }

    private fun recents(vararg names: String) = Recents(recentsFile).apply { names.reversed().forEach(::add); persist() }

    @Test fun theDocumentMovedThenTheProcessEnded_theKeptTextAndRecentFollowIt() {
        store.write("Old.md", "on disk")
        assertNull(recovery.write("Old.md", "kept", stamp = 7))
        recents("a.md", "Old.md")
        journal.begin("Old.md", "New.md")
        store.rename("Old.md", "New.md")
        // The process ends here: the kept text is still under the old name, Recent names it.

        journal.recover(store, recovery, Recents(recentsFile))
        assertEquals(Kept("kept", 7), recovery.read("New.md"))
        assertFalse(recovery.has("Old.md"))
        assertEquals(listOf("a.md", "New.md"), Recents(recentsFile).names())
        assertFalse(journalFile.exists())
    }

    @Test fun theProcessEndedBeforeTheDocumentMoved_nothingChanges() {
        store.write("Old.md", "on disk")
        assertNull(recovery.write("Old.md", "kept", stamp = 7))
        recents("Old.md")
        journal.begin("Old.md", "New.md")

        journal.recover(store, recovery, Recents(recentsFile))
        assertEquals("on disk", store.read("Old.md"))
        assertFalse(store.exists("New.md"))
        assertEquals(Kept("kept", 7), recovery.read("Old.md"))
        assertEquals(listOf("Old.md"), Recents(recentsFile).names())
        assertFalse(journalFile.exists())
    }

    @Test fun everythingMovedButTheJournal_nothingMoreToDo() {
        store.write("Old.md", "on disk")
        recents("New.md")
        journal.begin("Old.md", "New.md")
        store.rename("Old.md", "New.md")

        journal.recover(store, recovery, Recents(recentsFile))
        assertEquals(listOf("New.md"), Recents(recentsFile).names())
        assertFalse(journalFile.exists())
    }

    @Test fun aDamagedJournalIsDroppedAndNeverNamesAPath() {
        for (content in listOf("garbage", "${RenameJournal.HEADER}\n../x.md\nNew.md\n", "${RenameJournal.HEADER}\nOld.md\n")) {
            journalFile.writeText(content)
            assertNull(content, journal.read())
            journal.recover(store, recovery, Recents(recentsFile))
            assertFalse(content, journalFile.exists())
        }
        journal.begin("Old.md", "New.md")
        assertEquals("Old.md" to "New.md", journal.read())
        journal.end()
        assertNull(journal.read())
        assertTrue(!journalFile.exists())
    }
}
