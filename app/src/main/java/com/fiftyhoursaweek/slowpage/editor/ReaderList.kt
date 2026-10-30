package com.fiftyhoursaweek.slowpage.editor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fiftyhoursaweek.slowpage.ui.Colours
import com.fiftyhoursaweek.slowpage.ui.Faces
import uniffi.hushhour.palette
import kotlin.math.roundToInt

/**
 * A document over the paragraph cap, read-only (ruling B): its [ReadOnlyText] parts, each laid
 * out only when it shows, in the page's look, plain (no highlighting) and selectable for copy.
 */
@SuppressLint("ViewConstructor")
class ReaderList(context: Context, private val faces: Faces) : RecyclerView(context) {
    private val parts = Parts()
    private var text: ReadOnlyText? = null
    private var look: Look? = null
    private var side = 0

    init {
        layoutManager = LinearLayoutManager(context)
        adapter = parts
        clipToPadding = false
        isVerticalScrollBarEnabled = true
        itemAnimator = null
        contentDescription = "Read-only text"
    }

    fun show(new: ReadOnlyText, look: Look, side: Int, top: Int) {
        if (top != paddingTop) setPadding(0, top, 0, top)
        if (new === text && look == this.look && side == this.side) return
        text = new
        this.look = look
        this.side = side
        parts.notifyDataSetChanged()
    }

    private inner class Parts : Adapter<Holder>() {
        override fun getItemCount() = text?.size ?: 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(Part(parent.context))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val view = holder.itemView as TextView
            val l = look ?: return
            val p = palette(l.theme)
            view.setPadding(side, 0, side, 0)
            view.typeface = faces.of(l.font)
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, l.sizePx)
            val height = (l.sizePx * l.lineHeight).roundToInt()
            if (Build.VERSION.SDK_INT >= 28) {
                view.lineHeight = height
            } else {
                view.setLineSpacing((height - view.paint.getFontMetricsInt(null)).toFloat(), 1f)
            }
            view.setTextColor(Colours.argb(p.text))
            view.highlightColor = Colours.argb(Colours.selection(p))
            view.text = text?.part(position) ?: ""
        }
    }

    private class Holder(view: TextView) : ViewHolder(view)

    @SuppressLint("ViewConstructor")
    private class Part(context: Context) : TextView(context) {
        init {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setTextIsSelectable(true)
            includeFontPadding = false
        }
    }
}
