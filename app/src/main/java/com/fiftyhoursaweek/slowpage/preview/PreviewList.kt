package com.fiftyhoursaweek.slowpage.preview

import android.annotation.SuppressLint
import android.content.Context
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fiftyhoursaweek.slowpage.editor.Shortcuts

/**
 * The preview (V3–V6): one read-only, selectable text per block, laid out only when it shows;
 * no web view. Links are checked again when tapped ([PreviewLink]). A key press or a tap that
 * is not on a link calls [onHint], never nothing.
 */
@SuppressLint("ViewConstructor")
class PreviewList(context: Context, private val onHint: () -> Unit) : RecyclerView(context) {
    private val adapter = Blocks()
    val lines = LinearLayoutManager(context)
    var rendered: RenderedPreview? = null
        private set
    /** The side insets of the text, in px. */
    private var side = 0
    /** The block the list was last kept level at, and where: the list is moved only when that changes. */
    private var kept: Pair<Int, Int>? = null

    init {
        layoutManager = lines
        setAdapter(adapter)
        clipToPadding = false
        isFocusable = true
        isFocusableInTouchMode = true
        isVerticalScrollBarEnabled = true
        itemAnimator = null
        contentDescription = "Preview"
    }

    fun show(new: RenderedPreview?, side: Int, top: Int) {
        if (top != paddingTop) setPadding(0, top, 0, top)
        if (side != this.side) {
            this.side = side
            adapter.notifyDataSetChanged()
        }
        if (new === rendered) return
        rendered = new
        // The first block on screen stays where it is while the text changes under it.
        val first = lines.findFirstVisibleItemPosition()
        val offset = lines.findViewByPosition(first)?.top ?: 0
        adapter.notifyDataSetChanged()
        if (first != NO_POSITION) lines.scrollToPositionWithOffset(first, offset - paddingTop)
    }

    /** Put block [index] at [fraction] of the height (V5), unless the finger is on the list. */
    fun keepLevel(index: Int, fraction: Float) {
        if (scrollState != SCROLL_STATE_IDLE || height == 0) return
        val offset = (fraction * height).toInt() - paddingTop
        if (kept == index to offset) return
        kept = index to offset
        lines.scrollToPositionWithOffset(index, offset)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (Shortcuts.previewHint(event)) onHint()
        return super.onKeyDown(keyCode, event)
    }

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onHint()
            return false
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Below the last block: still the preview.
        taps.onTouchEvent(e)
        return super.onTouchEvent(e)
    }

    private inner class Blocks : Adapter<Holder>() {
        override fun getItemCount() = rendered?.blocks?.size ?: 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(Block(parent.context, onHint))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val view = holder.itemView as Block
            view.setPadding(side, 0, side, 0)
            view.text = rendered?.blocks?.getOrNull(position) ?: ""
        }
    }

    private class Holder(view: TextView) : ViewHolder(view)

    /** One block: selectable, links tappable, everything else gets the hint. */
    @SuppressLint("ViewConstructor")
    private class Block(context: Context, private val onHint: () -> Unit) : TextView(context) {
        private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!onLink(e)) onHint()
                return false
            }
        })

        init {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
            includeFontPadding = false
        }

        private fun onLink(e: MotionEvent): Boolean {
            val s = text as? Spanned ?: return false
            val offset = getOffsetForPosition(e.x, e.y)
            return offset >= 0 && s.getSpans(offset, offset, ClickableSpan::class.java).isNotEmpty()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            taps.onTouchEvent(event)
            return super.onTouchEvent(event)
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (Shortcuts.previewHint(event)) onHint()
            return super.onKeyDown(keyCode, event)
        }
    }
}
