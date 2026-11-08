package com.fiftyhoursaweek.slowpage.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val recovery get() = Recovery(File(tmp.root, "recovery"), limitBytes = 1024 * 1024)

    @Test fun aCopyKeepsItsTextAndStamp() {
        assertNull(recovery.write("a.md", "line one\nline two ✓", stamp = 42))
        assertEquals(Kept("line one\nline two ✓", 42), recovery.read("a.md"))
        recovery.clear("a.md")
        assertNull(recovery.read("a.md"))
    }

    @Test fun aFileWithoutTheHeaderIsNotACopy() {
        File(tmp.root, "recovery").mkdirs()
        File(tmp.root, "recovery/a.md").writeText("just text")
        assertNull(recovery.read("a.md"))
        File(tmp.root, "recovery/a.md").writeText("${Recovery.HEADER} notanumber\ntext")
        assertNull(recovery.read("a.md"))
    }

    @Test fun theNewestCopyNewerThanTheDiskWins() {
        val a = Kept("A", 10)
        val b = Kept("B", 20)
        assertEquals(b, Recovery.newest(5, a, b))
        assertEquals(b, Recovery.newest(5, b, a))
        assertEquals(b, Recovery.newest(15, a, b))
        assertNull(Recovery.newest(20, a, b))
        assertNull(Recovery.newest(5, null, null))
    }

    @Test fun packedTextComesBack() {
        val text = "Accents é, emoji 🙂, " + "x".repeat(10_000)
        assertEquals(text, Recovery.unpack(Recovery.pack(text)))
        assertNull(Recovery.unpack(byteArrayOf(1, 2, 3)))
    }

    @Test fun aRenamedDocumentTakesItsKeptTextAlongAndAStaleCopyUnderTheNewNameGoes() {
        val r = recovery
        assertNull(r.write("old.md", "kept", stamp = 7))
        assertNull(r.write("new.md", "stale, from a deleted document", stamp = 3))
        r.rename("old.md", "new.md")
        assertEquals(Kept("kept", 7), r.read("new.md"))
        assertNull(r.read("old.md"))
        assertTrue(r.rename("none.md", "other.md"))  // nothing kept: nothing to do
        assertNull(r.read("other.md"))
    }

    /** A change of case keeps the copy, on a disk that ignores case too (Codex rename round 1, BLOCKING 2). */
    @Test fun aChangeOfCaseKeepsTheCopy() {
        val r = recovery
        assertNull(r.write("draft.md", "kept", stamp = 7))
        assertTrue(r.rename("draft.md", "Draft.md"))
        assertEquals(Kept("kept", 7), r.read("Draft.md"))
        assertEquals(listOf("Draft.md"), r.folder.list()!!.toList())
    }

    /** A copy that cannot follow stays where it was and is never deleted (Codex rename round 1, BLOCKING 2). */
    @Test fun aCopyThatCannotFollowStaysUnderTheOldName() {
        val r = recovery
        assertNull(r.write("old.md", "kept", stamp = 7))
        // Something the copy cannot replace: a folder that is not empty.
        File(r.folder, "new.md/inside").apply { parentFile!!.mkdirs(); writeText("x") }
        assertFalse(r.rename("old.md", "new.md"))
        assertEquals(Kept("kept", 7), r.read("old.md"))
    }
}
