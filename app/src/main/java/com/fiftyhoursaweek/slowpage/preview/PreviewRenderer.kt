package com.fiftyhoursaweek.slowpage.preview

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.view.View
import com.fiftyhoursaweek.slowpage.ui.Colours
import com.fiftyhoursaweek.slowpage.ui.Faces
import uniffi.hushhour.FontChoice
import uniffi.hushhour.PreviewBlock
import uniffi.hushhour.PreviewInline
import uniffi.hushhour.PreviewKind
import uniffi.hushhour.Theme
import uniffi.hushhour.fontFamily
import uniffi.hushhour.monoFamily
import uniffi.hushhour.palette
import java.net.URI
import kotlin.math.roundToInt

/** Only web and mail links open (V4); everything else stays text. */
object PreviewLinks {
    private val allowedSchemes = setOf("http", "https", "mailto")

    /** The address, if the preview may open it: http(s) with a host, or mailto with an address. */
    fun allowed(address: String): String? {
        val trimmed = address.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in allowedSchemes) return null
        if (scheme == "mailto") return if (trimmed.length > "mailto:".length) trimmed else null
        return if (!uri.host.isNullOrEmpty()) trimmed else null
    }
}

/** Everything a render depends on besides the blocks. */
data class PreviewLook(val theme: Theme, val font: FontChoice, val sizePx: Float, val lineHeight: Float)

/**
 * The preview as one styled text per block, shown in a list that lays out only the blocks on
 * screen: building one long text is quadratic in its spans on Android, and a TextView lays
 * out all of it at once (a 1 MB document took minutes).
 */
class RenderedPreview(
    val blocks: List<CharSequence>,
    /** For each block: its source line. */
    val lines: IntArray,
) {
    /** All blocks as one plain text (tests). */
    val text: String get() = blocks.joinToString("\n")

    /** The block to keep level with source line [line]: the last one starting at or before it (V5). */
    fun blockIndex(line: Int): Int? {
        if (lines.isEmpty()) return null
        var lo = 0
        var hi = lines.size - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid] <= line) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }
}

/** A tappable link: checked again when tapped, then opened by the browser or mail app (ACTION_VIEW only). */
class PreviewLink(val address: String, private val colour: Int) : ClickableSpan() {
    override fun onClick(widget: View) {
        val allowed = PreviewLinks.allowed(address) ?: return
        try {
            widget.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(allowed)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Log.w("SlowpagePreview", "nothing opens $allowed")
        }
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = colour
        ds.isUnderlineText = true
    }
}

/**
 * The Markdown preview as text (V3), a port of the iPad's `PreviewRenderer`: the core parses
 * (`preview`, CommonMark with task lists and strikethrough, raw HTML kept as text) and flattens
 * the blocks; this makes one styled text in the page's theme, font and size for a read-only
 * `TextView`. No web view; images show their alternative text and nothing is loaded. Safe off
 * the main thread.
 */
object PreviewRenderer {
    private val headingScale = floatArrayOf(2.0f, 1.6f, 1.35f, 1.2f, 1.1f, 1.0f)

    fun render(blocks: List<PreviewBlock>, look: PreviewLook, faces: Faces): RenderedPreview {
        val p = palette(look.theme)
        val text = Colours.argb(p.text)
        val muted = Colours.argb(Colours.secondary(p))
        val codeBackground = Colours.argb(Colours.mix(p.text, p.background, 0.92f))
        val link = Colours.argb(Colours.link(p))
        val family = fontFamily(look.font)
        val mono = monoFamily()
        val size = look.sizePx
        val indentStep = (size * 1.6f).roundToInt()
        val spacing = (size * 0.7f).roundToInt()

        val lines = IntArray(blocks.size)
        val texts = ArrayList<CharSequence>(blocks.size)
        var out = SpannableStringBuilder()

        fun paragraph(start: Int, depth: Int, marker: Boolean, before: Int, height: Int, centred: Boolean = false) {
            val end = out.length
            if (end == start) return
            val indent = depth * indentStep
            val first = if (marker) maxOf(0, indent - indentStep) else indent
            if (indent > 0 || first > 0) out.setSpan(LeadingMarginSpan.Standard(first, indent), start, end, Spanned.SPAN_PARAGRAPH)
            out.setSpan(BlockLines(height, before, spacing), start, end, Spanned.SPAN_PARAGRAPH)
            if (centred) out.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, Spanned.SPAN_PARAGRAPH)
        }

        blocks.forEachIndexed { i, block ->
            out = SpannableStringBuilder()
            val start = 0
            lines[i] = block.line.toInt()
            val depth = (block.quoteDepth + block.listDepth).toInt()
            val colour = if (block.quoteDepth > 0u) muted else text
            block.marker?.let { marker ->
                // The marker sits in the first line's hanging indent; a space keeps it apart.
                styled(out, "$marker ", faces.of(family), size, muted)
            }
            when (val kind = block.kind) {
                is PreviewKind.Heading -> {
                    val level = kind.level.toInt().coerceIn(1, 6)
                    val headingSize = (size * headingScale[level - 1]).roundToInt().toFloat()
                    inlines(out, block.inlines, faces, family, mono, headingSize, true, colour, muted, link, codeBackground)
                    paragraph(start, depth, block.marker != null, (size * 0.6f).roundToInt(), (headingSize * 1.3f).roundToInt())
                }
                PreviewKind.Paragraph -> {
                    inlines(out, block.inlines, faces, family, mono, size, false, colour, muted, link, codeBackground)
                    paragraph(start, depth, block.marker != null, 0, (size * look.lineHeight).roundToInt())
                }
                is PreviewKind.Code -> {
                    val codeSize = (size * 0.9f).roundToInt().toFloat()
                    val body = block.code.removeSuffix("\n").ifEmpty { " " }
                    val from = out.length
                    out.append(body)
                    out.setSpan(Face(faces.of(mono)), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(AbsoluteSizeSpan(codeSize.roundToInt()), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(colour), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(codeBackground), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    paragraph(start, depth, block.marker != null, 0, (codeSize * 1.45f).roundToInt())
                }
                PreviewKind.Rule -> {
                    styled(out, "—   —   —", faces.of(family), size, muted)
                    paragraph(start, 0, false, 0, (size * look.lineHeight).roundToInt(), centred = true)
                }
            }
            texts += out
        }
        return RenderedPreview(texts, lines)
    }

    private fun styled(out: SpannableStringBuilder, s: String, face: Typeface, size: Float, colour: Int) {
        val from = out.length
        out.append(s)
        out.setSpan(Face(face), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(AbsoluteSizeSpan(size.roundToInt()), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(colour), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun inlines(
        out: SpannableStringBuilder, runs: List<PreviewInline>, faces: Faces, family: String, mono: String, size: Float,
        bold: Boolean, colour: Int, muted: Int, link: Int, codeBackground: Int,
    ) {
        val begin = out.length
        for (run in runs) {
            if (run.isBreak) {
                out.append('\n')
                continue
            }
            val shown = if (run.image) "Image: " + run.text.ifEmpty { "(no description)" } else run.text
            if (shown.isEmpty()) continue
            val from = out.length
            out.append(shown)
            val to = out.length
            fun span(what: Any) = out.setSpan(what, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (run.code) {
                span(Face(faces.of(mono, bold || run.strong)))
                span(AbsoluteSizeSpan((size * 0.92f).roundToInt()))
                span(BackgroundColorSpan(codeBackground))
            } else {
                span(Face(faces.of(family, bold || run.strong, run.emphasis || run.image)))
                span(AbsoluteSizeSpan(size.roundToInt()))
            }
            span(ForegroundColorSpan(if (run.image) muted else colour))
            if (run.strike) span(StrikethroughSpan())
            val address = run.link?.takeIf { !run.image }?.let(PreviewLinks::allowed)
            if (address != null) span(PreviewLink(address, link))
        }
        if (out.length == begin) styled(out, " ", faces.of(family), size, colour)
    }

    private class Face(private val face: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = apply(tp)
        override fun updateMeasureState(tp: TextPaint) = apply(tp)
        private fun apply(p: Paint) {
            p.typeface = face
        }
    }

    /** A fixed line height for a block, with space before its first line and after its last. */
    private class BlockLines(private val height: Int, private val before: Int, private val after: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            val spanned = text as Spanned
            val natural = fm.descent - fm.ascent
            if (natural > 0 && height > natural) {
                val extra = height - natural
                fm.ascent -= extra / 2
                fm.descent += extra - extra / 2
            }
            if (start == spanned.getSpanStart(this)) fm.ascent -= before
            if (end == spanned.getSpanEnd(this)) fm.descent += after
            fm.top = minOf(fm.top, fm.ascent)
            fm.bottom = maxOf(fm.bottom, fm.descent)
        }
    }
}
