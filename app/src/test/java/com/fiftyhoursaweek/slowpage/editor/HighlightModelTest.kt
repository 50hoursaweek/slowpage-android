package com.fiftyhoursaweek.slowpage.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.hushhour.Document
import uniffi.hushhour.FocusMode
import uniffi.hushhour.FontChoice
import uniffi.hushhour.SpanKind

/** E8, E10, E14: highlighting through the core, per line with fence state, never a crash. */
class HighlightModelTest {
    @Test fun fencesAreKeptPerLineAndAnEditReDrawsTheLinesWhoseStateChanged() {
        HighlightModel(FontChoice.MONO).use { m ->
            var text = "# Title\ntext\ncode?\nmore"
            m.reset(text)
            assertEquals(SpanKind.MARKER, m.spans(0, text)!!.first().kind)
            assertTrue(m.spans(2, text)!!.none { it.kind == SpanKind.CODE_BLOCK })
            // Opening a fence on line 1 turns the lines after it into code: they are redrawn.
            Document().use { doc ->
                doc.load(text)
                val at = text.indexOf("text")
                val r = doc.editUtf16(at.toULong(), (at + 4).toULong(), "```")
                text = doc.text()
                val redraw = m.didEdit(text, at, at + 4, 3, r.line.toInt(), r.removedLines.toInt(), r.insertedLines.toInt())
                assertEquals(1, redraw.first)
                assertTrue("the fence state moved down to the end: $redraw", redraw.last >= 3)
                assertTrue(m.spans(2, text)!!.all { it.kind == SpanKind.CODE_BLOCK })
            }
        }
    }

    @Test fun theSpotlightRedrawsTheParagraphsItLeftAndReached() {
        HighlightModel(FontChoice.SERIF).use { m ->
            val text = "One.\nTwo.\n\nThree. Four."
            m.reset(text)
            assertEquals(listOf(0 until 4), m.spotlight(FocusMode.PARAGRAPH, text, 0))
            assertTrue("same paragraph, nothing to redraw", m.spotlight(FocusMode.PARAGRAPH, text, 6).isEmpty())
            assertEquals(listOf(0 until 2, 3 until 4), m.spotlight(FocusMode.PARAGRAPH, text, text.length))
            assertTrue(m.spans(0, text)!!.all { it.dim })
            assertTrue(m.spans(3, text)!!.none { it.dim })
            // Sentence: only the line it is on, and the other sentence on it dims.
            m.spotlight(FocusMode.SENTENCE, text, text.length - 2)
            val spans = m.spans(3, text)!!
            assertTrue(spans.any { it.dim } && spans.any { !it.dim })
        }
    }

    /** The crash in build 72 (iPad testAutocorrectAndTypingInSentenceSpotlightNeverCrashTheHighlighter). */
    @Test fun autocorrectAndTypingInSentenceSpotlightNeverCrashTheHighlighter() {
        HighlightModel(FontChoice.SERIF).use { m ->
            Document().use { doc ->
                var text = "Un cafe\ndont"
                doc.load(text)
                m.reset(text)
                fun replace(start: Int, end: Int, insert: String, caret: Int) {
                    val r = doc.editUtf16(start.toULong(), end.toULong(), insert)
                    text = doc.text()
                    // The editor rescans before it moves the spotlight: the sentence range is stale.
                    m.didEdit(text, start, end, insert.length, r.line.toInt(), r.removedLines.toInt(), r.insertedLines.toInt())
                    m.spotlight(FocusMode.SENTENCE, text, caret)
                    for (i in 0 until m.lines.lineCount) assertTrue(m.spans(i, text) != null)
                }
                m.spotlight(FocusMode.SENTENCE, text, 12)
                replace(8, 12, "don’t", 13)
                replace(3, 7, "café", 7)
                replace(2, 2, "x", 3)
                assertEquals("Unx café\ndon’t", text)
            }
        }
    }

    /** iPad testRandomTypingInEverySpotlightModeKeepsEverythingInStep. */
    @Test fun randomTypingInEverySpotlightModeKeepsEverythingInStep() {
        val pieces = listOf("a", "b", "z", " ", ". ", "\n", "\n\n", "*", "`", "```", "# ", "- ", "é", "’", "café", "😀")
        val random = kotlin.random.Random(0x9E3779B9)
        for (focus in FocusMode.entries) {
            HighlightModel(FontChoice.MONO).use { m ->
                Document().use { doc ->
                    var text = "# Start\n\nSome text."
                    doc.load(text)
                    m.reset(text)
                    repeat(600) {
                        val at = text.offsetByCodePoints(0, random.nextInt(text.codePointCount(0, text.length) + 1))
                        val end = when (random.nextInt(4)) {
                            0 -> if (at < text.length) text.offsetByCodePoints(at, 1) else at
                            1 -> text.indexOf(' ', at).let { if (it < 0) at else it }  // a word, as autocorrect replaces it
                            else -> at
                        }
                        val insert = if (random.nextInt(5) == 0) "" else pieces.random(random)
                        val r = doc.editUtf16(at.toULong(), end.toULong(), insert)
                        text = doc.text()
                        m.didEdit(text, at, end, insert.length, r.line.toInt(), r.removedLines.toInt(), r.insertedLines.toInt())
                        m.spotlight(focus, text, at + insert.length)
                        assertEquals(LineTable(text).starts(), m.lines.starts())
                        val line = m.lines.line(at)
                        val spans = m.spans(line, text)!!
                        val length = m.lineText(text, line).length
                        assertTrue(spans.isEmpty() || spans.last().utf16End.toInt() == length)
                    }
                }
            }
        }
    }
}
