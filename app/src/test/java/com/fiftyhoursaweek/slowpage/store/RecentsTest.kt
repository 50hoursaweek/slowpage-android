package com.fiftyhoursaweek.slowpage.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecentsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file get() = File(tmp.root, "recents")

    @Test fun theListKeepsEightNewestFirstDropsDuplicatesAndPersistsSeparately() {
        val recents = Recents(file)
        for (i in 1..10) recents.add("$i.md")
        recents.add("5.md")
        assertEquals(listOf("5.md", "10.md", "9.md", "8.md", "7.md", "6.md", "4.md", "3.md"), recents.names())
        assertTrue(!file.exists())  // nothing written until persist
        assertNull(recents.persist())
        assertEquals(recents.names(), Recents(file).names())
    }

    @Test fun theFileFormatIsAHeaderThenOneNamePerLine() {
        val recents = Recents(file)
        recents.add("b.md")
        recents.add("a.md")
        recents.persist()
        assertEquals("slowpage-recents 1\na.md\nb.md\n", file.readText())
    }

    @Test fun anUnknownHeaderOrAnOversizedFileIsIgnored() {
        file.writeText("something else\na.md\n")
        assertTrue(Recents(file).names().isEmpty())
        file.writeText("slowpage-recents 1\n" + "x".repeat(StateFile.READ_LIMIT))
        assertTrue(Recents(file).names().isEmpty())
    }

    @Test fun aDamagedFileNeverYieldsPathsOrTooManyEntries() {
        file.writeText("slowpage-recents 1\n../escape.md\n/abs.md\n.hidden\nok.md\nok.md\n" +
            (1..20).joinToString("\n") { "$it.md" })
        val names = Recents(file).names()
        assertEquals("ok.md", names.first())
        assertEquals(Recents.LIMIT, names.size)
        assertTrue(names.none { '/' in it || it.startsWith(".") })
    }

    @Test fun entriesWhoseDocumentsAreGoneAreDropped() {
        val recents = Recents(file)
        recents.add("gone.md")
        recents.add("here.md")
        assertEquals(listOf("here.md"), recents.existing { it == "here.md" })
        assertEquals(listOf("here.md"), recents.names())
    }

    @Test fun recentNamesTheRenamedDocumentInTheSamePlace() {
        val recents = Recents(file)
        listOf("c.md", "b.md", "a.md").forEach(recents::add)
        recents.renamed("b.md", "B2.md")
        assertEquals(listOf("a.md", "B2.md", "c.md"), recents.names())
        // Not listed: renamed from the start screen's list, it is not added.
        recents.renamed("unlisted.md", "new.md")
        assertEquals(listOf("a.md", "B2.md", "c.md"), recents.names())
        // An entry already under the new name (its document gone) is not doubled.
        recents.renamed("a.md", "c.md")
        assertEquals(listOf("c.md", "B2.md"), recents.names())
    }

    @Test fun aPersistFailureIsReturned() {
        val recents = Recents(File(tmp.root, "ro/recents"))
        File(tmp.root, "ro").mkdirs()
        File(tmp.root, "ro").setWritable(false)
        try {
            recents.add("a.md")
            assertNotNull(recents.persist())
        } finally {
            File(tmp.root, "ro").setWritable(true)
        }
    }

    @Test fun stateProblemsAreKeptUntilTheNextSuccess() {
        val problems = StateProblems()
        problems.report(StateProblems.Kind.RECENTS, java.io.IOException("No space left on device"))
        assertEquals(listOf("The recent list could not be saved: No space left on device"), problems.lines())
        problems.report(StateProblems.Kind.RECENTS, null)
        assertTrue(problems.lines().isEmpty())
    }

    @Test fun aNameWithALineBreakIsNeitherAddedNorRead() {
        val recents = Recents(file)
        recents.add("a\nb.md")
        recents.add("c\r.md")
        recents.add("ok.md")
        assertEquals(listOf("ok.md"), recents.names())
        file.writeText("${Recents.HEADER}\nok.md\nbad\r\n")
        assertEquals(listOf("ok.md"), Recents(file).names())
    }
}
