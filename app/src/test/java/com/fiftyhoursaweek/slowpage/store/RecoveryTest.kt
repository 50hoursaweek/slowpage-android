package com.fiftyhoursaweek.slowpage.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
