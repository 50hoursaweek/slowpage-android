package com.fiftyhoursaweek.slowpage.editor

import com.fiftyhoursaweek.slowpage.store.StoreError
import com.fiftyhoursaweek.slowpage.store.DocumentStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uniffi.hushhour.Persistence
import java.io.File

/** The core document over the store, through the real bindings (host build of hushhour-ipad). */
class EditorSessionTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var store: DocumentStore

    @Before fun setUp() {
        store = DocumentStore(File(tmp.root, "documents"), limitBytes = 20L * 1024 * 1024)
    }

    @Test fun typingMakesTheDocumentDirtyAndASaveWritesItAndCleansIt() {
        val name = store.createUntitled()
        EditorSession.open(store, name).use { s ->
            assertEquals(Persistence.Clean, s.persistence())
            assertTrue(s.change("Hello") is EditorSession.Change.Applied)
            assertTrue(s.change("Hello, page ✓") is EditorSession.Change.Applied)
            assertEquals(Persistence.Dirty, s.persistence())
            assertEquals(Persistence.Clean, s.save())
            assertEquals("Hello, page ✓", store.read(name))
            assertFalse(s.isDirty())
        }
    }

    @Test fun openLoadsTheFileAndNormalisesLineEndings() {
        store.folder.mkdirs()
        File(store.folder, "crlf.md").writeText("a\r\nb\r\n")
        EditorSession.open(store, "crlf.md").use { s ->
            assertEquals("a\nb\n", s.text)
        }
    }

    @Test fun aPastedCrlfIsKeptAsTheCoreKeepsItAndNormalisedOnTheNextOpen() {
        // The core normalises line endings on load only; an edit is kept as given.
        val name = store.createUntitled()
        EditorSession.open(store, name).use { s ->
            assertTrue(s.change("x\r\ny") is EditorSession.Change.Applied)
            assertEquals("x\r\ny", s.text)
            assertEquals(s.coreText(), s.text)
            s.save()
        }
        EditorSession.open(store, name).use { s -> assertEquals("x\ny", s.text) }
    }

    @Test fun anEditPastTheLimitIsRefusedAndNothingChanges() {
        val small = DocumentStore(File(tmp.root, "small"), limitBytes = 20L * 1024 * 1024)
        val name = small.createUntitled()
        EditorSession.open(small, name).use { s ->
            val big = "a".repeat(20 * 1024 * 1024)
            assertTrue(s.change(big) is EditorSession.Change.Applied)  // at the limit: inclusive
            val refused = s.change(big + "b")
            assertTrue(refused is EditorSession.Change.Refused)
            assertEquals(big.length, s.text.length)
            assertTrue(s.change(big.dropLast(1)) is EditorSession.Change.Applied)  // deletions always fit
        }
    }

    @Test fun theQuickFitCheckAgreesWithTheCoreAtTheLimit() {
        val name = store.createUntitled()
        EditorSession.open(store, name).use { s ->
            val limit = 20 * 1024 * 1024
            val nearly = "a".repeat(limit - 2)
            assertTrue(s.change(nearly) is EditorSession.Change.Applied)
            assertTrue("two bytes fit", s.fits(nearly.length, nearly.length, "é"))
            assertFalse("three do not", s.fits(nearly.length, nearly.length, "✓"))
            assertTrue("a replacement that does not grow fits", s.fits(0, 1, "b"))
            assertTrue(s.edit(nearly.length, nearly.length, "é") is EditorSession.Change.Applied)
            assertFalse("at the limit nothing more fits", s.fits(0, 0, "a"))
        }
    }

    @Test fun jobsThatOutliveTheDocumentGetNothingOnceItIsClosed() {
        val s = EditorSession.open(store, store.createUntitled())
        s.change("one two")
        assertEquals(2uL, s.statsUnlessClosed()!!.words)
        s.close()
        assertEquals(null, s.statsUnlessClosed())
        assertEquals(null, s.textUnlessClosed())
        s.close()  // twice is harmless
    }

    @Test fun aFailedSaveIsShownWithItsReasonAndTheTextIsKeptForTheNextTry() {
        val name = store.createUntitled()
        EditorSession.open(store, name).use { s ->
            s.change("keep this")
            store.folder.setWritable(false)
            val failed = try { s.save() } finally { store.folder.setWritable(true) }
            assertTrue(failed is Persistence.Failed)
            assertTrue(s.isDirty())
            assertEquals("keep this", s.text)
            assertEquals(Persistence.Clean, s.save())
            assertEquals("keep this", store.read(name))
        }
    }

    @Test fun emojiEditsNeverSplitACharacter() {
        val name = store.createUntitled()
        EditorSession.open(store, name).use { s ->
            for (step in listOf("😀", "😀😁", "😁😁", "😁", "")) {
                assertTrue(step, s.change(step) is EditorSession.Change.Applied)
                assertEquals(step, s.text)
                assertEquals(s.coreText(), s.text)
            }
        }
    }

    @Test fun randomTypingKeepsTheFieldAndTheCoreInStep() {
        val name = store.createUntitled()
        val pieces = listOf("a", "b", " ", "\n", "😀", "é", "# ", "**", "\r\n")
        val random = kotlin.random.Random(72)
        EditorSession.open(store, name).use { s ->
            var current = ""
            repeat(2000) {
                val at = random.nextInt(current.codePointCount(0, current.length) + 1)
                val i = current.offsetByCodePoints(0, at)
                current = if (random.nextInt(4) == 0 && i < current.length) {
                    current.removeRange(i, current.offsetByCodePoints(i, 1))
                } else {
                    current.substring(0, i) + pieces.random(random) + current.substring(i)
                }
                assertTrue(s.change(current) is EditorSession.Change.Applied)
                assertEquals(current, s.text)
                if (it % 97 == 0) assertEquals(s.coreText(), s.text)
            }
            assertEquals(s.coreText(), s.text)
            s.save()
            assertEquals(current, store.read(name))
        }
    }

    // ---- Rename (R1, 1.0.1)

    @Test fun renameSavesTheUnsavedTextFirstAndLaterSavesGoToTheNewName() {
        val name = store.createUntitled()
        EditorSession.open(store, name).use { s ->
            assertTrue(s.change("Not saved yet") is EditorSession.Change.Applied)
            val moved = mutableListOf<String>()
            assertEquals(null, s.rename("Notes.md") { old -> moved += old; store.rename(old, "Notes.md") })
            assertEquals(listOf(name), moved)
            assertEquals("Notes.md", s.name)
            assertEquals("Not saved yet", store.read("Notes.md"))
            assertTrue(s.change("Not saved yet, and more") is EditorSession.Change.Applied)
            s.save()
            assertEquals("Not saved yet, and more", store.read("Notes.md"))
            assertFalse(store.exists(name))
        }
    }

    @Test fun whenTheSaveFailsNothingMoves() {
        val name = store.createUntitled()
        EditorSession.open(store, name).use { s ->
            assertTrue(s.change("Unsaved") is EditorSession.Change.Applied)
            store.folder.setWritable(false)
            try {
                var moved = false
                val failed = s.rename("Notes.md") { moved = true }
                assertTrue(failed is Persistence.Failed)
                assertFalse(moved)
                assertEquals(name, s.name)
            } finally {
                store.folder.setWritable(true)
            }
            assertEquals("Unsaved", s.text)
            assertTrue(s.isDirty())
        }
    }

    @Test fun aRefusedMoveKeepsTheName() {
        val name = store.createUntitled()
        store.write("Taken.md", "other")
        EditorSession.open(store, name).use { s ->
            val refused = runCatching { s.rename("Taken.md") { old -> store.rename(old, "Taken.md") } }.exceptionOrNull()
            assertTrue(refused is StoreError.Exists)
            assertEquals(name, s.name)
            assertEquals("other", store.read("Taken.md"))
        }
    }
}
