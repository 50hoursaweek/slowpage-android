package com.fiftyhoursaweek.slowpage.preview

import android.text.Spanned
import android.text.style.StrikethroughSpan
import com.fiftyhoursaweek.slowpage.ui.Faces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uniffi.hushhour.FontChoice
import uniffi.hushhour.PreviewKind
import uniffi.hushhour.Theme
import uniffi.hushhour.preview

/** V3–V5: the core's blocks rendered into styled text, only web and mail links, in step with the caret. */
@RunWith(RobolectricTestRunner::class)
class PreviewTest {
    private val faces = Faces(RuntimeEnvironment.getApplication().assets)
    private val look = PreviewLook(Theme.LIGHT, FontChoice.SERIF, 36f, 1.55f)

    @Test fun theBridgeReturnsTheBlocksInOrderWithLinesAndStyles() {
        val blocks = preview("# Title\n\nSome **strong** and ~~gone~~ text.\n\n> quoted\n\n- item\n\n```\ncode\n```\n\n---\n")
        assertEquals(PreviewKind.Heading(1u), blocks[0].kind)
        assertEquals(listOf(0uL, 2uL, 4uL, 6uL, 8uL, 12uL), blocks.map { it.line })
        assertTrue(blocks[1].inlines.any { it.strong && it.text == "strong" })
        assertEquals(1u, blocks[2].quoteDepth)
        assertEquals("•", blocks[3].marker)
        val rendered = PreviewRenderer.render(blocks, look, faces)
        assertEquals(blocks.size, rendered.blocks.size)
        assertEquals("Title", rendered.blocks[0].toString())
        assertEquals("• item", rendered.blocks[3].toString())
        assertEquals("code", rendered.blocks[4].toString())
        assertEquals("—   —   —", rendered.blocks[5].toString())
        val paragraph = rendered.blocks[1] as Spanned
        val gone = paragraph.toString().indexOf("gone")
        assertTrue(paragraph.getSpans(gone, gone + 1, StrikethroughSpan::class.java).isNotEmpty())
    }

    @Test fun onlyWebAndMailLinksAreAllowed() {
        assertEquals("https://example.com", PreviewLinks.allowed("https://example.com"))
        assertEquals("http://example.com/a?b", PreviewLinks.allowed(" http://example.com/a?b "))
        assertEquals("mailto:someone@example.com", PreviewLinks.allowed("mailto:someone@example.com"))
        for (bad in listOf("javascript:alert(1)", "file:///etc/passwd", "content://x/y", "intent://x#Intent;end", "mailto:", "https://", "relative/path", "HTTPS//no", "")) {
            assertNull(bad, PreviewLinks.allowed(bad))
        }
        assertEquals("HTTPS://EXAMPLE.COM", PreviewLinks.allowed("HTTPS://EXAMPLE.COM"))
    }

    @Test fun theRenderedTextLinksOnlyAllowedAddressesAndShowsImagesAsText() {
        val blocks = preview("[web](https://example.com) [bad](javascript:x) [file](file:///x) ![a cat](https://example.com/cat.png) ![](x.png)")
        val text = PreviewRenderer.render(blocks, look, faces).blocks.single() as Spanned
        val links = text.getSpans(0, text.length, PreviewLink::class.java)
        assertEquals(listOf("https://example.com"), links.map { it.address })
        assertEquals("web", text.subSequence(text.getSpanStart(links[0]), text.getSpanEnd(links[0])).toString())
        assertTrue(text.toString().contains("Image: a cat"))
        assertFalse("never loaded", text.toString().contains("cat.png"))
    }

    @Test fun theBlockUnderACaretLineIsTheLastOneStartingAtOrBeforeIt() {
        val rendered = RenderedPreview(listOf("a", "b", "c"), intArrayOf(0, 2, 7))
        assertEquals(0, rendered.blockIndex(0))
        assertEquals(0, rendered.blockIndex(1))
        assertEquals(1, rendered.blockIndex(2))
        assertEquals(1, rendered.blockIndex(6))
        assertEquals(2, rendered.blockIndex(100))
        assertNull(RenderedPreview(emptyList(), IntArray(0)).blockIndex(3))
    }

    @Test fun aMegabyteDocumentGoesThroughTheBridgeAndTheRendererLinearly() {
        val unit = "## Section\n\nA paragraph with **strong**, *emphasis*, `code` and a [link](https://example.com). It goes on " +
            "for a while so that the paragraph wraps over several lines in the preview.\n\n> a quote\n\n1. one\n2. two\n- [x] done\n\n" +
            "```\nlet x = 1\n```\n\n---\n\n\n"
        val repeats = 1_200_000 / unit.toByteArray().size + 1
        val text = unit.repeat(repeats)
        val started = System.nanoTime()
        val blocks = preview(text)
        val rendered = PreviewRenderer.render(blocks, look, faces)
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(repeats, blocks.count { it.kind == PreviewKind.Heading(2u) })
        assertEquals(blocks.size, rendered.blocks.size)
        val unitLines = unit.count { it == '\n' }
        assertEquals("lines stay exact at the end", ((repeats - 1) * unitLines).toULong(), blocks.last { it.kind == PreviewKind.Heading(2u) }.line)
        assertTrue("linear; off the main thread in the app (took $seconds s)", seconds < 30)
        assertFalse(rendered.blocks.isEmpty())
        // Each block is styled when the list first shows it; all of them stay linear too.
        val styling = System.nanoTime()
        assertTrue(rendered.text.length > text.length / 2)
        assertTrue("styled linearly", (System.nanoTime() - styling) / 1e9 < 30)
        assertTrue("styled once", rendered.blocks[3] === rendered.blocks[3])
    }
}
