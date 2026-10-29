package com.fiftyhoursaweek.slowpage.editor

import android.util.Log
import uniffi.hushhour.ByteRange
import uniffi.hushhour.FocusMode
import uniffi.hushhour.FontChoice
import uniffi.hushhour.HighlightSettings
import uniffi.hushhour.Highlighter
import uniffi.hushhour.ScanResult
import uniffi.hushhour.Span
import uniffi.hushhour.sentenceAt
import uniffi.hushhour.utf16ToUtf8

/**
 * Markdown highlighting state for one open document (E8, E10): the core's `Highlighter` with its
 * per-line fence states, and the [LineTable] that maps the text's UTF-16 offsets to lines. It
 * holds no text: every call gets the text as it is now (the editor's, which mirrors the core).
 * A port of the iPad's `HighlightController` minus the drawing, which [EditorText] does.
 *
 * A failure inside the core is logged and the line keeps its old look, never a crash (E14,
 * the crash in build 72).
 */
class HighlightModel(font: FontChoice) : AutoCloseable {
    val lines = LineTable()
    var settings = HighlightSettings(font, FocusMode.OFF, ByteRange(0u, 1u), 0u, ByteRange(0u, 0u))
        private set
    private val highlighter = Highlighter(settings)

    /** A new text: rebuild the table and scan every line. Blocking; any thread, before the editor shows it. */
    fun reset(text: CharSequence) {
        lines.rebuild(text)
        highlighter.reset(lines.lineCount.toULong())
        for (i in 0 until lines.lineCount) scan(i, text) ?: break
    }

    /**
     * Units `start until oldEnd` became [insertedLength] units of [text] (the new text), as the
     * core reported it. Returns the lines whose look may have changed: the edited ones, and on
     * while the fence state kept changing.
     */
    fun didEdit(text: CharSequence, start: Int, oldEnd: Int, insertedLength: Int, line: Int, removedLines: Int, insertedLines: Int): IntRange {
        lines.apply(start, oldEnd, insertedLength, line, removedLines, insertedLines, text)
        highlighter.splice(line.toULong(), (removedLines + 1).toULong(), (insertedLines + 1).toULong())
        val count = insertedLines + 1
        var index = line
        var scanned = 0
        while (index < lines.lineCount) {
            val result = scan(index, text) ?: break
            scanned++
            index++
            if (scanned >= count && !result.nextChanged) break
        }
        return line until maxOf(index, line + count).coerceAtMost(lines.lineCount)
    }

    // Exception, not only HighlightException: a failure on the way into the core (UniFFI
    // refusing what it cannot encode, round 1 BLOCKING 1) leaves the line plain too.
    private fun scan(index: Int, text: CharSequence): ScanResult? = try {
        highlighter.scan(index.toULong(), lineText(text, index))
    } catch (e: Exception) {
        Log.e(TAG, "highlighting line $index failed", e)
        null
    }

    /** Spans of line [index] under the current settings; null when the core failed (the line stays plain). */
    fun spans(index: Int, text: CharSequence): List<Span>? = try {
        highlighter.spans(index.toULong(), lineText(text, index))
    } catch (e: Exception) {
        Log.e(TAG, "rendering line $index failed", e)
        null
    }

    fun lineText(text: CharSequence, index: Int): String = text.subSequence(lines.start(index), lines.end(index)).toString()

    private fun isBlank(text: CharSequence, index: Int): Boolean {
        for (i in lines.start(index) until lines.end(index)) if (text[i] != ' ' && text[i] != '\t') return false
        return true
    }

    fun setFont(font: FontChoice) {
        if (font == settings.font) return
        settings = settings.copy(font = font)
        highlighter.update(settings)
    }

    /**
     * The spotlight for the caret at [caret] (the paragraph and sentence under it, as on Linux).
     * Returns the lines to draw again: all of them on a mode change, else the old and new
     * paragraph or sentence line; empty when nothing changed.
     */
    fun spotlight(focus: FocusMode, text: CharSequence, caret: Int): List<IntRange> {
        val line = lines.line(caret.coerceIn(0, lines.length))
        val old = settings
        settings = if (focus == FocusMode.OFF) {
            old.copy(focus = focus, paragraph = ByteRange(0u, 0u), cursorLine = line.toULong(), sentence = ByteRange(0u, 0u))
        } else {
            var start = line
            while (start > 0 && !isBlank(text, start - 1)) start--
            var end = line + 1
            while (end < lines.lineCount && !isBlank(text, end)) end++
            val lineText = lineText(text, line)
            val column = utf16ToUtf8(lineText, (caret - lines.start(line)).coerceAtLeast(0).toULong())
            old.copy(
                focus = focus,
                paragraph = ByteRange(start.toULong(), end.toULong()),
                cursorLine = line.toULong(),
                sentence = sentenceAt(lineText, column),
            )
        }
        if (settings == old) return emptyList()
        highlighter.update(settings)
        if (old.focus != settings.focus) return listOf(0 until lines.lineCount)
        return when (focus) {
            FocusMode.OFF -> emptyList()
            // Paragraph dims by paragraph only: a caret move inside it changes nothing drawn.
            FocusMode.PARAGRAPH -> if (old.paragraph == settings.paragraph) emptyList() else listOf(old.paragraph.start.toInt() until old.paragraph.end.toInt(), settings.paragraph.start.toInt() until settings.paragraph.end.toInt())
            FocusMode.SENTENCE -> listOf(old.cursorLine.toInt()..old.cursorLine.toInt(), line..line)
        }
    }

    override fun close() = highlighter.close()

    private companion object {
        const val TAG = "SlowpageHighlight"
    }
}
