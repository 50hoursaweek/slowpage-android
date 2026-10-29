package com.fiftyhoursaweek.slowpage.store

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NamesTest {
    private fun refused(input: String): NameProblem {
        try {
            Names.validated(input)
        } catch (e: NameException) {
            return e.problem
        }
        fail("accepted “$input”")
        error("unreachable")
    }

    @Test fun aNameIsTrimmedAndKeepsTheExtensionWithoutDoublingIt() {
        assertEquals("Notes.md", Names.validated("  Notes "))
        assertEquals("Notes.md", Names.validated("Notes.md"))
        assertEquals("Notes.md", Names.validated("Notes .MD"))
    }

    @Test fun anEmptyNameSlashColonLeadingDotOrTooLongIsRefused() {
        assertEquals(NameProblem.EMPTY, refused("   "))
        assertEquals(NameProblem.EMPTY, refused(".md"))
        assertEquals(NameProblem.INVALID_CHARACTER, refused("a/b"))
        assertEquals(NameProblem.INVALID_CHARACTER, refused("a:b"))
        assertEquals(NameProblem.LEADING_DOT, refused(".hidden"))
        assertEquals(NameProblem.TOO_LONG, refused("x".repeat(253)))
        assertEquals("x".repeat(252) + ".md", Names.validated("x".repeat(252)))
    }

    @Test fun importNamesKeepTextExtensionsAndMakeTheRestAcceptable() {
        assertEquals("Draft" to "md", Names.forImport("Draft.md"))
        assertEquals("notes" to "txt", Names.forImport("notes.txt"))
        assertEquals("report.pdf" to "md", Names.forImport("report.pdf"))
        assertEquals("a-b-c" to "md", Names.forImport("a/b:c.md"))
        assertEquals("env" to "md", Names.forImport(".env"))
        assertEquals("Imported" to "md", Names.forImport(null))
        assertEquals("Imported" to "md", Names.forImport("  "))
    }

    @Test fun aLongImportNameIsCutAtACharacterAndLeavesRoomForANumber() {
        val (base, ext) = Names.forImport("é".repeat(300) + "😀.md")
        val longest = Names.numbered(base, ext, 99_999)
        assert(longest.toByteArray().size <= 255)
        assertEquals(base, String(base.toByteArray(), Charsets.UTF_8))  // no broken character
    }

    @Test fun lineBreaksAndOtherControlCharactersInAnImportNameAreReplaced() {
        // Codex phase 3 MINOR 5: a provider's display name must not split the recent list.
        val (base, ext) = Names.forImport("Two\r\nlines\tand\u0085more.md")
        assertEquals("Two--lines-and-more" to "md", base to ext)
    }
}
