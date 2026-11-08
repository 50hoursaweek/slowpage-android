package com.fiftyhoursaweek.slowpage.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

class DocumentStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var store: DocumentStore

    @Before fun setUp() {
        store = DocumentStore(File(tmp.root, "documents"), limitBytes = 1000)
    }

    @Test fun newNeedsNoPriorFileAndTheNextOneGetsItsOwnName() {
        assertEquals("Untitled.md", store.createUntitled())
        assertEquals("Untitled 2.md", store.createUntitled())
        store.delete("Untitled.md")
        assertEquals("Untitled.md", store.createUntitled())
        assertEquals("", store.read("Untitled 2.md"))
    }

    @Test fun newNeverGoesOverAnExistingFile() {
        store.folder.mkdirs()
        File(store.folder, "Untitled.md").writeText("keep me")
        assertEquals("Untitled 2.md", store.createUntitled())
        assertEquals("keep me", store.read("Untitled.md"))
    }

    @Test fun aWriteReplacesTheTextAndLeavesNoTemporaryFile() {
        val name = store.createUntitled()
        store.write(name, "one")
        store.write(name, "two — ✓")
        assertEquals("two — ✓", store.read(name))
        assertEquals(listOf(name), store.folder.list()!!.toList())
    }

    @Test fun theListIsNewestFirstAndSkipsHiddenAndTemporaryFiles() {
        val a = store.createUntitled()
        val b = store.createUntitled()
        File(store.folder, a).setLastModified(1_000_000)
        File(store.folder, b).setLastModified(2_000_000)
        File(store.folder, ".x.partial").writeText("half")
        assertEquals(listOf(b, a), store.list().map { it.name })
        store.removeLeftovers()
        assertFalse(File(store.folder, ".x.partial").exists())
    }

    @Test fun openRefusesAFilePastTheLimitAndAcceptsOneAtIt() {
        store.folder.mkdirs()
        File(store.folder, "at.md").writeBytes(ByteArray(1000) { 'a'.code.toByte() })
        File(store.folder, "over.md").writeBytes(ByteArray(1001) { 'a'.code.toByte() })
        assertEquals(1000, store.read("at.md").length)
        assertTrue(runCatching { store.read("over.md") }.exceptionOrNull() is StoreError.TooLarge)
    }

    @Test fun textIsUtf8WithoutItsBomOrElseLatin1() {
        store.folder.mkdirs()
        File(store.folder, "bom.md").writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hé".toByteArray())
        File(store.folder, "latin.md").writeBytes(byteArrayOf('c'.code.toByte(), 0xE9.toByte()))
        assertEquals("hé", store.read("bom.md"))
        assertEquals("cé", store.read("latin.md"))
    }

    @Test fun aMissingDocumentIsNotFound() {
        assertTrue(runCatching { store.read("gone.md") }.exceptionOrNull() is StoreError.NotFound)
        assertFalse(store.delete("gone.md"))
    }

    @Test fun importCopiesTheFileUnderItsOwnNameNeverOverAnother() {
        val first = store.import("Chapter.md", ByteArrayInputStream("one".toByteArray()))
        val second = store.import("Chapter.md", ByteArrayInputStream("two".toByteArray()))
        assertEquals("Chapter.md", first)
        assertEquals("Chapter 2.md", second)
        assertEquals("one", store.read(first))
        assertEquals("two", store.read(second))
        assertEquals(setOf(first, second), store.folder.list()!!.toSet())
    }

    @Test fun importIsBoundedAndATooLargeFileLeavesNothing() {
        val big = ByteArrayInputStream(ByteArray(1001) { 'a'.code.toByte() })
        assertTrue(runCatching { store.import("big.md", big) }.exceptionOrNull() is StoreError.TooLarge)
        assertTrue(store.list().isEmpty())
        assertEquals("big.md", store.import("big.md", ByteArrayInputStream(ByteArray(1000))))
    }

    @Test fun importStoresUtf8EvenFromLatin1() {
        val name = store.import("old.txt", ByteArrayInputStream(byteArrayOf('c'.code.toByte(), 0xE9.toByte())))
        assertEquals("old.txt", name)
        assertEquals("cé", File(store.folder, name).readText(Charsets.UTF_8))
    }

    @Test fun aFailedImportReadLeavesNothing() {
        val failing = object : java.io.InputStream() {
            override fun read(): Int = throw IOException("provider gone")
        }
        assertTrue(runCatching { store.import("x.md", failing) }.exceptionOrNull() is StoreError.Io)
        assertTrue(store.folder.list().orEmpty().isEmpty())
    }

    @Test fun exportWritesTheSavedBytes() {
        val name = store.createUntitled()
        store.write(name, "saved ✓")
        val out = ByteArrayOutputStream()
        store.export(name, out)
        assertEquals("saved ✓", out.toString(Charsets.UTF_8.name()))
    }

    @Test fun aFailedExportChangesNothingInTheAppCopy() {
        val name = store.createUntitled()
        store.write(name, "keep")
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
        }
        assertTrue(runCatching { store.export(name, broken) }.exceptionOrNull() is StoreError.Io)
        assertEquals("keep", store.read(name))
    }

    // ---- Rename (R1, 1.0.1)

    @Test fun renameMovesTheTextToTheNewNameAndLeavesNothingBehind() {
        val name = store.createUntitled()
        store.write(name, "words")
        assertEquals("Notes.md", store.rename(name, "Notes.md"))
        assertEquals("words", store.read("Notes.md"))
        assertFalse(store.exists(name))
        assertEquals(listOf("Notes.md"), store.folder.list()!!.toList())
    }

    @Test fun renameNeverGoesOverAnotherDocument() {
        store.write("a.md", "A")
        store.write("b.md", "B")
        val refused = runCatching { store.rename("a.md", "b.md") }.exceptionOrNull()
        assertTrue(refused is StoreError.Exists)
        assertEquals("A document named “b” already exists here.", refused!!.message)
        assertEquals("A", store.read("a.md"))
        assertEquals("B", store.read("b.md"))
    }

    @Test fun renameToTheSameNameChangesNothingAndAMissingDocumentIsSaid() {
        store.write("a.md", "A")
        assertEquals("a.md", store.rename("a.md", "a.md"))
        assertEquals("A", store.read("a.md"))
        assertTrue(runCatching { store.rename("gone.md", "x.md") }.exceptionOrNull() is StoreError.NotFound)
        assertFalse(store.exists("x.md"))
    }

    @Test fun aChangeOfCaseIsARename() {
        store.write("notes.md", "n")
        assertEquals("Notes.md", store.rename("notes.md", "Notes.md"))
        assertEquals(listOf("Notes.md"), store.folder.list()!!.toList())
    }
}
