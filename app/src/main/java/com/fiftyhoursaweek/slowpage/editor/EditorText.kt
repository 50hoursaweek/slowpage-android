package com.fiftyhoursaweek.slowpage.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.text.style.MetricAffectingSpan
import android.text.style.UpdateAppearance
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import com.fiftyhoursaweek.slowpage.ui.Colours
import com.fiftyhoursaweek.slowpage.ui.Faces
import uniffi.hushhour.FontChoice
import uniffi.hushhour.SpanKind
import uniffi.hushhour.TextChange
import uniffi.hushhour.TextStyle
import uniffi.hushhour.Theme
import uniffi.hushhour.fontFamily
import uniffi.hushhour.palette
import uniffi.hushhour.style
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the page looks like: theme, font, size in px, line height (a multiple of the size). */
data class Look(val theme: Theme, val font: FontChoice, val sizePx: Float, val lineHeight: Float)

/** The editor's owner: the core document behind the text, and what the editor reports. */
interface EditorHost {
    /** Whether the change fits the core's limit (E13); false tells the writer, and the change is not made. */
    fun fits(start: Int, end: Int, insert: String): Boolean
    /**
     * The text changed: units `start until oldEnd` became [insert]; [text] is the new text. The
     * core records it; the result says which lines to draw again, or null when the core refused
     * it (then the host has taken [text] as the truth).
     */
    fun edited(start: Int, oldEnd: Int, insert: String, text: CharSequence): Edited?
    /** The caret moved (or the text under it changed): the lines whose spotlight look changed. */
    fun selectionChanged(start: Int, end: Int, text: CharSequence): List<IntRange>
    fun undo()
    fun redo()
    /** The core's text, to start again from when the editor finds itself out of step. */
    fun coreText(): String
}

/** What the editor does for its owner: apply the core's changes, redraw the spotlight. */
interface EditorSink {
    fun apply(change: TextChange)
    fun refreshSpotlight()
}

/** What an edit did to the lines: [line] and the next [removed] newlines became [inserted]; [redraw] changed look. */
data class Edited(val line: Int, val removed: Int, val inserted: Int, val redraw: IntRange)

/**
 * The editor (E9): a platform `EditText`, so the system keyboard, autocorrect, dictation, the
 * selection handles and a hardware keyboard all work as anywhere else on Android, including
 * the Huawei keyboard. Compose's text field is not used: it cannot draw per-span faces from
 * the core, and the IME path is less proven on non-Google keyboards.
 *
 * The text mirrors the core: every change is gated by the limit ([gate], before it lands) and
 * reported to the [EditorHost] (after), whatever made it (typing, paste, autocorrect, IME
 * composition). Undo and redo are the core's; the platform history is off (allowUndo="false"
 * in the layout, so it never competes, E12). Highlighting comes from the [HighlightModel] and
 * is drawn as spans of our own classes, only for the lines on screen; lines are marked stale
 * when an edit, the spotlight or the look changes them, and drawn again when they show.
 */
class EditorText(context: Context, attrs: AttributeSet? = null) : EditText(context, attrs), EditorSink {
    private var host: EditorHost? = null
    private var model: HighlightModel? = null
    private var faces: Faces? = null
    private var look: Look? = null
    /** Set while the editor applies text the core already has (open, undo, redo). */
    private var applying = false
    private val drawn = LineFlags()
    /** The lines that may carry spans: kept near the screen, so the text never holds many (each edit walks them all). */
    private var drawnFirst = Int.MAX_VALUE
    private var drawnLast = -1
    private val styleCache = HashMap<Pair<SpanKind, Boolean>, TextStyle>()
    /** Anchor (E11): the caret line stays at the middle of the editor. */
    var typewriter = false
        set(value) {
            if (field == value) return
            field = value
            updatePadding()
            anchor()
        }
    private var sideInset = 0
    private var topInset = 0

    /** The text a same-length change replaces: a refused change comes back as a no-op replacement. */
    private var removed: String? = null
    /** A change that would split a character, widened to whole characters; made once the no-op it became is done. */
    private var widened: Triple<Int, Int, String>? = null

    init {
        // The buffer [load] makes is the editor's own: copying it would double a large text.
        setEditableFactory(object : Editable.Factory() {
            override fun newEditable(source: CharSequence): Editable = source as? SpannableStringBuilder ?: super.newEditable(source)
        })
        background = null
        gravity = Gravity.TOP or Gravity.START
        isVerticalScrollBarEnabled = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setHorizontallyScrolling(false)
        includeFontPadding = false
        filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend -> gate(source, start, end, dest, dstart, dend) })
        addTextChangedListener(Watcher())
    }

    /** The page that scrolls the editor (the layout's ScrollView); without one the editor scrolls itself. */
    private val page: ScrollView? get() = parent as? ScrollView
    private fun viewportHeight() = page?.height ?: height
    /** The top of what shows, in the editor's own coordinates (the page's padding is the anchor's space). */
    private fun viewportTop() = page?.let { it.scrollY - it.paddingTop } ?: scrollY

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        page?.setOnScrollChangeListener { _, _, _, _, _ ->
            drawVisible()
            invalidate()  // the drawing is clipped to the screen ([onDraw]); a scroll moves the clip
        }
        page?.addOnLayoutChangeListener(pageLayout)
    }

    override fun onDetachedFromWindow() {
        page?.removeOnLayoutChangeListener(pageLayout)
        super.onDetachedFromWindow()
    }

    private val pageLayout = OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (bottom - top != oldBottom - oldTop) post { updatePadding(); drawVisible(); anchor() }
    }

    /** Where the caret line is, as a fraction of the visible height (the preview keeps level with it, V5). */
    fun caretFraction(): Float? {
        val l = layout ?: return null
        val h = viewportHeight()
        if (h == 0 || selectionEnd < 0) return null
        val top = l.getLineTop(l.getLineForOffset(selectionEnd)) + totalPaddingTop - viewportTop()
        return (top.toFloat() / h).coerceIn(0f, 1f)
    }

    fun bind(host: EditorHost, model: HighlightModel, faces: Faces) {
        this.host = host
        this.model = model
        this.faces = faces
    }

    fun unbind() {
        host = null
        model = null
        removeCallbacks(nextPiece)
        loadingText = null
        loaded = null
    }

    // ---- Text from the core

    /** Show [text] (the core's, after open or a reload); the caret at [selStart]..[selEnd]. */
    /**
     * Show [text] (the core's, after open or a reload); the caret at [selStart]..[selEnd], then
     * [then] (posted). A long text goes in a piece at a time, each ending at a line break, one
     * piece per pass of the main loop (round 1, F3 and BLOCKING 3): the platform lays out a
     * whole text at once with an object per paragraph, which ran a 19 MiB document out of
     * memory and held the main thread past the ANR limit. Until the last piece is in, the
     * editor takes no input ([loading]); highlighting and the caret come with the last piece.
     */
    fun load(text: String, selStart: Int, selEnd: Int, then: (() -> Unit)? = null) {
        removeCallbacks(nextPiece)
        val first = pieceEnd(text, 0)
        // Sized for the whole text once, so the pieces never make it grow (and copy).
        val buffer = SpannableStringBuilder(text)
        if (first < text.length) buffer.delete(first, text.length)
        applying = true
        try {
            setText(buffer, BufferType.EDITABLE)
        } finally {
            applying = false
        }
        loadingText = if (first < text.length) text else null
        loadedTo = first
        loadSelection = selStart to selEnd
        loaded = then
        if (loadingText != null) post(nextPiece) else finishLoad()
    }

    /** Whether a long text is still going in ([load]); the editor refuses input until it is. */
    val loading get() = loadingText != null
    private var loadingText: String? = null
    private var loadedTo = 0
    private var loadSelection = 0 to 0
    private var loaded: (() -> Unit)? = null

    private val nextPiece = object : Runnable {
        override fun run() {
            if (loadingText == null) return
            appendPiece()
            if (loadingText != null) post(this) else finishLoad()
        }
    }

    private fun appendPiece() {
        val text = loadingText ?: return
        val end = pieceEnd(text, loadedTo)
        applying = true
        try {
            editableText.append(text, loadedTo, end)
        } finally {
            applying = false
        }
        loadedTo = end
        if (end >= text.length) loadingText = null
    }

    /** All of a text still going in, now: a change from the core needs the whole text. */
    private fun finishLoading() {
        if (loadingText == null) return
        removeCallbacks(nextPiece)
        while (loadingText != null) appendPiece()
        finishLoad()
    }

    private fun finishLoad() {
        val length = editableText.length
        resetDrawn(model?.lines?.lineCount ?: 1)
        val (a, b) = loadSelection
        setSelection(a.coerceIn(0, length), b.coerceIn(0, length))
        val then = loaded
        loaded = null
        post {
            drawVisible()
            then?.invoke()
        }
    }

    /** The end of the piece of [text] from [start]: about [PIECE] units, just after a line break (or the end). */
    private fun pieceEnd(text: String, start: Int): Int {
        if (text.length - start <= PIECE) return text.length
        // A paragraph laid out across two pieces would be laid out again from its start.
        val back = text.lastIndexOf('\n', start + PIECE)
        if (back >= start) return back + 1
        val forward = text.indexOf('\n', start + PIECE)
        return if (forward < 0) text.length else forward + 1
    }

    /** Apply a change the core made (undo, redo): the text, the line table, the caret. */
    override fun apply(change: TextChange) {
        finishLoading()
        val e = editableText ?: return
        val start = change.utf16.start.toInt()
        val end = change.utf16.end.toInt()
        if (start < 0 || end < start || end > e.length) {
            // Never expected: the view and the core disagree. Show the core's text rather than crash.
            Log.e(TAG, "a change from the core does not fit the editor ($start..$end of ${e.length}); reloading")
            host?.let { h ->
                val m = model
                val text = h.coreText()
                m?.reset(text)
                load(text, change.caretUtf16.toInt(), change.caretUtf16.toInt())
            }
            return
        }
        applying = true
        try {
            e.replace(start, end, change.text)
        } finally {
            applying = false
        }
        val m = model ?: return
        val redraw = m.didEdit(e, start, end, change.text.length, change.line.toInt(), change.removedLines.toInt(), change.insertedLines.toInt())
        splice(Edited(change.line.toInt(), change.removedLines.toInt(), change.insertedLines.toInt(), redraw))
        setSelection(change.caretUtf16.toInt().coerceIn(0, e.length))
        refreshSpotlight()
    }

    // ---- The gate and the mirror

    private fun gate(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
        if (applying) return null
        if (loading) return dest.subSequence(dstart, dend)  // the text is not all in yet
        val inserted = source.subSequence(start, end)
        // The core keeps LF endings; paste may bring others. Half a character (a lone
        // surrogate) is never let in: the core's text cannot hold one.
        var clean = if (inserted.any { it == '\r' || it == '\u2028' || it == '\u2029' }) {
            inserted.toString().replace("\r\n", "\n").replace('\r', '\n').replace('\u2028', '\n').replace('\u2029', '\n')
        } else {
            null
        }
        if ((clean ?: inserted).any { Character.isSurrogate(it) }) clean = wholeCharacters(clean ?: inserted)
        val h = host ?: return null
        // A keyboard may delete one UTF-16 unit of an emoji (deleteSurroundingText(1, 0)): the
        // core would delete the whole character and the view keep half of it (round 1,
        // BLOCKING 1). The change becomes a no-op, and the whole-character change follows
        // from afterTextChanged. An insertion inside a pair (the caret is kept out of one,
        // [onSelectionChanged]) changes nothing, so no watcher runs: it goes after the pair, posted.
        val splitsStart = splits(dest, dstart)
        val splitsEnd = splits(dest, dend)
        if (splitsStart || splitsEnd) {
            val text = (clean ?: inserted).toString()
            if (dstart == dend) {
                post { if (splits(editableText, dstart)) editableText.insert(dstart + 1, text) }
            } else {
                widened = Triple(if (splitsStart) dstart - 1 else dstart, if (splitsEnd) dend + 1 else dend, text)
            }
            return dest.subSequence(dstart, dend)
        }
        if (!h.fits(dstart, dend, (clean ?: inserted).toString())) {
            return dest.subSequence(dstart, dend)
        }
        return clean
    }

    private inner class Watcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            removed = if (!applying && count == after) s.subSequence(start, start + count).toString() else null
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (applying) return
            val insert = s.subSequence(start, start + count).toString()
            val same = removed == insert
            removed = null
            if (same) return  // a refused change, or one that changed nothing
            val h = host ?: return
            val m = model ?: return
            val edited = h.edited(start, start + before, insert, s)
            if (edited == null) {
                // The host took the view's text as the truth and rebuilt the model.
                resetDrawn(m.lines.lineCount)
            } else {
                splice(edited)
            }
        }

        override fun afterTextChanged(s: Editable) {
            if (applying) return
            widened?.let { (start, end, text) ->
                widened = null
                // Through the gate and the mirror like any change.
                s.replace(start, end, text)
                return
            }
            // The edited line now, so nothing typed shows unstyled; the rest when it shows.
            refreshSpotlight()
        }
    }

    private fun splice(e: Edited) {
        drawn.splice(e.line, e.removed + 1, e.inserted + 1)
        drawn.clear(e.redraw)
        val delta = e.inserted - e.removed
        if (e.line < drawnFirst && drawnLast >= 0) drawnFirst += delta
        if (e.line <= drawnLast) drawnLast += delta
        drawnFirst = drawnFirst.coerceAtLeast(0)
        drawnLast = drawnLast.coerceAtMost(drawn.size - 1)
    }

    private fun resetDrawn(lines: Int) {
        drawn.reset(lines)
        drawnFirst = Int.MAX_VALUE
        drawnLast = -1
    }

    /** Whether [offset] falls between the two halves of a surrogate pair. */
    private fun splits(text: CharSequence, offset: Int) =
        offset in 1 until text.length && Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset])

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // Never a caret inside a character (a keyboard or a tap can put one there).
        val e = editableText
        if (e != null && (splits(e, selStart) || splits(e, selEnd))) {
            setSelection(if (splits(e, selStart)) selStart + 1 else selStart, if (splits(e, selEnd)) selEnd + 1 else selEnd)
            return
        }
        // While a text goes in, the caret is where it will be put back, not where it is now.
        if (!applying && !loading) refreshSpotlight()
    }

    /** The spotlight follows the caret (E10); the lines it left and reached are drawn again. */
    override fun refreshSpotlight() {
        val h = host ?: return
        val e = editableText ?: return
        val start = selectionStart
        val end = selectionEnd
        if (start < 0 || end < 0) return
        redraw(h.selectionChanged(start, end, e))
        drawVisible()
    }

    // ---- Undo, redo and paste from the menu: the core's history, plain text only

    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.undo -> { host?.undo(); true }
        android.R.id.redo -> { host?.redo(); true }
        // Pasted styles (colours, faces from another app) would mix with the highlighting.
        android.R.id.paste -> super.onTextContextMenuItem(android.R.id.pasteAsPlainText)
        else -> super.onTextContextMenuItem(id)
    }

    // ---- Look

    /** Theme, font, size or line height; [side] and [top] are the page insets in px. */
    fun setLook(new: Look, side: Int, top: Int) {
        val f = faces ?: return
        val p = palette(new.theme)
        val textColour = Colours.argb(p.text)
        val old = look
        // Whatever changes the width of the text or its metrics lays all of it out again.
        val relayout = old != null && (new.font != old.font || new.sizePx != old.sizePx || new.lineHeight != old.lineHeight || side != sideInset)
        if (new != look) {
            look = new
            styleCache.clear()
            typeface = f.of(new.font)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, new.sizePx)
            val height = (new.sizePx * new.lineHeight).roundToInt()
            if (Build.VERSION.SDK_INT >= 28) {
                lineHeight = height
            } else {
                setLineSpacing((height - paint.getFontMetricsInt(null)).toFloat(), 1f)
            }
            setTextColor(textColour)
            highlightColor = Colours.argb(Colours.selection(p))
            // Before 29 the layout's @null cursor drawable draws the caret in the text colour.
            if (Build.VERSION.SDK_INT >= 29) {
                textCursorDrawable = GradientDrawable().apply {
                    setColor(Colours.argb(Colours.caret(p)))
                    setSize((2 * resources.displayMetrics.density).roundToInt(), height)
                }
                textSelectHandle?.mutate()?.let { it.setTint(textColour); setTextSelectHandle(it) }
                textSelectHandleLeft?.mutate()?.let { it.setTint(textColour); setTextSelectHandleLeft(it) }
                textSelectHandleRight?.mutate()?.let { it.setTint(textColour); setTextSelectHandleRight(it) }
            }
            model?.setFont(new.font)
            drawn.clearAll()
            drawnFirst = Int.MAX_VALUE
            drawnLast = -1
        }
        sideInset = side
        topInset = top
        updatePadding()
        if (relayout && editableText.length > PIECE) relayoutInPieces() else drawVisible()
    }

    /**
     * A long text laid out again (a new width in Split, zoom, a font) goes back in a piece at a
     * time, as on open ([load]): laid out at once it held the main thread for seconds (round 1, F2).
     */
    private fun relayoutInPieces() {
        val pending = loadingText
        if (pending != null) {
            // Already going in: start again at the new width, with the caret and the callback it had.
            load(pending, loadSelection.first, loadSelection.second, loaded)
        } else {
            load(editableText.toString(), selectionStart, selectionEnd)
        }
    }

    /**
     * The page insets are the editor's padding; the anchor's space is the page's (drawn into,
     * clipToPadding off), as a change of a text view's padding lays out all of its text again:
     * seconds on a large document each time the keyboard came or went with the anchor on.
     */
    private fun updatePadding() {
        val anchorSpace = if (typewriter) viewportHeight() / 2 else 0
        val p = page
        val inner = if (p == null) topInset + anchorSpace else topInset
        if (p != null && (p.paddingTop != anchorSpace || p.paddingBottom != anchorSpace)) p.setPadding(0, anchorSpace, 0, anchorSpace)
        if (paddingLeft != sideInset || paddingTop != inner || paddingBottom != inner) setPadding(sideInset, inner, sideInset, inner)
    }

    /** Lines whose look changed (the spotlight moved): drawn again now if on screen. */
    fun redraw(ranges: List<IntRange>) {
        for (r in ranges) drawn.clear(r)
    }

    /**
     * Only what is near the screen is drawn. In the page the editor is as tall as its text, so
     * unclipped the platform records a display list for every block of the text (about 400
     * characters each) and looks for a free one per edit across all of them: 70 ms a key in a
     * 5 MB document (round 1, F1). The clip leaves a screen's margin above and below, so a
     * fling shows text before the next frame records it.
     */
    override fun onDraw(canvas: Canvas) {
        val p = page
        if (p == null) {
            super.onDraw(canvas)
            return
        }
        val margin = p.height
        val top = viewportTop()
        canvas.save()
        canvas.clipRect(0, top - margin, width, top + p.height + margin)
        super.onDraw(canvas)
        canvas.restore()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePadding()
        post { drawVisible(); anchor() }
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        drawVisible()
    }

    // ---- Drawing the highlighting, a screen at a time

    /** The lines on screen, plus a margin, as indices into the line table. */
    private fun visibleLines(): IntRange? {
        val m = model ?: return null
        val layout = layout ?: return null
        val e = editableText ?: return null
        if (e.length != m.lines.length) return null  // mid-change; drawn when it settles
        val top = viewportTop() - totalPaddingTop
        val first = layout.getLineStart(layout.getLineForVertical(maxOf(0, top)))
        val last = layout.getLineEnd(layout.getLineForVertical(maxOf(0, top + viewportHeight())))
        val a = (m.lines.line(first) - MARGIN_LINES).coerceAtLeast(0)
        val b = (m.lines.line(minOf(last, e.length)) + MARGIN_LINES).coerceAtMost(m.lines.lineCount - 1)
        return a..b
    }

    fun drawVisible() {
        val range = visibleLines() ?: return
        trim(range.first - KEEP_LINES, range.last + KEEP_LINES)
        for (i in range) if (!drawn[i]) drawLine(i)
        drawnFirst = minOf(drawnFirst, range.first)
        drawnLast = maxOf(drawnLast, range.last)
    }

    /** Spans of the lines outside [keepFirst]..[keepLast] go; those lines are drawn again when they show. */
    private fun trim(keepFirst: Int, keepLast: Int) {
        val m = model ?: return
        val e = editableText ?: return
        if (drawnLast < 0) return
        fun drop(first: Int, last: Int) {
            val a = first.coerceIn(0, m.lines.lineCount - 1)
            val b = last.coerceIn(0, m.lines.lineCount - 1)
            if (a > b) return
            for (span in e.getSpans(m.lines.start(a), m.lines.end(b), PageSpan::class.java)) e.removeSpan(span)
            drawn.clear(a..b)
        }
        if (drawnFirst < keepFirst) {
            drop(drawnFirst, minOf(keepFirst - 1, drawnLast))
            drawnFirst = keepFirst
        }
        if (drawnLast > keepLast) {
            drop(maxOf(keepLast + 1, drawnFirst), drawnLast)
            drawnLast = keepLast
        }
        if (drawnFirst > drawnLast) {
            drawnFirst = Int.MAX_VALUE
            drawnLast = -1
        }
    }

    private fun drawLine(index: Int) {
        val m = model ?: return
        val f = faces ?: return
        val l = look ?: return
        val e = editableText ?: return
        drawn[index] = true
        val start = m.lines.start(index)
        val end = m.lines.end(index)
        for (span in e.getSpans(start, end, PageSpan::class.java)) e.removeSpan(span)
        if (start == end) return
        val spans = m.spans(index, e) ?: return  // the core failed: the line stays plain (E14)
        val body = fontFamily(l.font)
        for (span in spans) {
            val s = styleCache.getOrPut(span.kind to span.dim) { style(span.kind, span.dim, l.font, l.theme) }
            val faced = s.bold || s.italic || s.family != body
            val coloured = span.kind != SpanKind.TEXT || span.dim
            if (!faced && !coloured) continue
            val a = (start + span.utf16Start.toInt()).coerceAtMost(end)
            val b = (start + span.utf16End.toInt()).coerceAtMost(end)
            if (a >= b) continue
            if (faced) e.setSpan(Face(f.of(s.family, s.bold, s.italic)), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (coloured) e.setSpan(Ink(Colours.argb(s.color)), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // ---- Anchor

    /** With the anchor on, the caret line goes to the middle of the editor instead of just into view. */
    override fun bringPointIntoView(offset: Int): Boolean {
        if (!typewriter) return super.bringPointIntoView(offset)
        return anchor(offset)
    }

    fun anchor(offset: Int = selectionEnd): Boolean {
        val layout = layout ?: return false
        val viewport = viewportHeight()
        if (!typewriter || viewport == 0 || offset < 0) return false
        val line = layout.getLineForOffset(offset.coerceAtMost(layout.text.length))
        val target = Anchor.scrollFor(
            lineTop = layout.getLineTop(line),
            lineBottom = layout.getLineBottom(line),
            paddingTop = totalPaddingTop + (page?.paddingTop ?: 0),
            viewHeight = viewport,
            contentHeight = layout.height + totalPaddingTop + totalPaddingBottom + (page?.let { it.paddingTop + it.paddingBottom } ?: 0),
        )
        val p = page
        if (p != null) {
            if (abs(target - p.scrollY) > 0) p.scrollTo(0, target)
        } else if (abs(target - scrollY) > 0) {
            scrollTo(0, target)
        }
        return true
    }

    /** Spans the highlighter draws; only these are ever removed. */
    private interface PageSpan

    private class Ink(private val colour: Int) : CharacterStyle(), UpdateAppearance, PageSpan {
        override fun updateDrawState(tp: TextPaint) {
            tp.color = colour
        }
    }

    private class Face(private val face: Typeface) : MetricAffectingSpan(), PageSpan {
        override fun updateDrawState(tp: TextPaint) = apply(tp)
        override fun updateMeasureState(tp: TextPaint) = apply(tp)
        private fun apply(p: Paint) {
            p.typeface = face
        }
    }

    private companion object {
        const val TAG = "SlowpageEditor"
        const val MARGIN_LINES = 20
        const val KEEP_LINES = 300
        /** A piece of a long text being loaded, in UTF-16 units: about 50 ms of layout on the emulator. */
        const val PIECE = 256 * 1024
    }
}

/** [text] with any lone surrogate (half a character) replaced by U+FFFD. */
internal fun wholeCharacters(text: CharSequence): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
            out.append(c).append(text[i + 1])
            i += 2
        } else {
            out.append(if (Character.isSurrogate(c)) '\uFFFD' else c)
            i++
        }
    }
    return out.toString()
}

/** The anchor's scroll position (E11): the caret line's middle at the middle of the view, within the content. */
object Anchor {
    fun scrollFor(lineTop: Int, lineBottom: Int, paddingTop: Int, viewHeight: Int, contentHeight: Int): Int {
        val middle = paddingTop + (lineTop + lineBottom) / 2
        val target = middle - viewHeight / 2
        return target.coerceIn(0, maxOf(0, contentHeight - viewHeight))
    }
}

/** One flag per line, kept in step with the line table by [splice]. */
class LineFlags {
    private var flags = BooleanArray(16)
    var size = 1
        private set

    fun reset(lines: Int) {
        flags = BooleanArray(maxOf(16, lines))
        size = lines
    }

    operator fun get(i: Int) = i in 0 until size && flags[i]
    operator fun set(i: Int, value: Boolean) {
        if (i in 0 until size) flags[i] = value
    }

    /** Lines `first until first + removed` became [inserted] lines, all unset. */
    fun splice(first: Int, removed: Int, inserted: Int) {
        val gone = minOf(removed, size - first).coerceAtLeast(0)
        val newSize = size - gone + inserted
        val out = BooleanArray(maxOf(16, newSize))
        System.arraycopy(flags, 0, out, 0, first.coerceAtMost(size))
        val tail = size - first - gone
        if (tail > 0) System.arraycopy(flags, first + gone, out, first + inserted, tail)
        flags = out
        size = newSize
    }

    fun clear(range: IntRange) {
        for (i in maxOf(0, range.first)..minOf(size - 1, range.last)) flags[i] = false
    }

    fun clearAll() = flags.fill(false)
}
