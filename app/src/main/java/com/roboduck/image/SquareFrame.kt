package com.roboduck.image

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** A frame that is always as tall as it is wide. */
class SquareFrame(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
