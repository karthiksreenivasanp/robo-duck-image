package com.roboduck.image

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.FrameLayout

/**
 * Shared drawing for the chunky "outlined with a hard shadow" look:
 * a filled rounded shape with a thick ink border and a solid shadow
 * offset down and to the right. Pressing slides the face onto the shadow.
 */
private class NeoPainter(context: Context, var fill: Int, val offset: Float, val radius: Float) {
    val stroke = 2.5f * context.resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.shadow) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = context.getColor(R.color.ink)
    }
    val face = RectF()
    private val shadow = RectF()

    fun layout(width: Int, height: Int, pressed: Boolean) {
        val o = if (pressed) offset else 0f
        val h = stroke / 2f
        face.set(h + o, h + o, width - offset - h + o, height - offset - h + o)
        shadow.set(face)
        shadow.offset(offset, offset)
    }

    fun cornerRadius(): Float = if (radius < 0) face.height() / 2f else radius

    fun drawBack(canvas: Canvas, pressed: Boolean) {
        val r = cornerRadius()
        if (!pressed && offset > 0) canvas.drawRoundRect(shadow, r, r, shadowPaint)
        fillPaint.color = fill
        canvas.drawRoundRect(face, r, r, fillPaint)
    }

    fun drawBorder(canvas: Canvas) {
        val r = cornerRadius()
        canvas.drawRoundRect(face, r, r, strokePaint)
    }
}

/** A button drawn in the neo style. Defaults to a pill; set neoRadius for chips. */
class NeoButton(context: Context, attrs: AttributeSet?) : Button(context, attrs) {

    private val painter: NeoPainter

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.NeoButton)
        val d = resources.displayMetrics.density
        painter = NeoPainter(
            context,
            a.getColor(R.styleable.NeoButton_neoFill, context.getColor(R.color.surface)),
            a.getDimension(R.styleable.NeoButton_neoOffset, 4 * d),
            a.getDimension(R.styleable.NeoButton_neoRadius, -1f)
        )
        a.recycle()
        background = null
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        val off = painter.offset.toInt()
        setPadding(paddingLeft, paddingTop, paddingRight + off, paddingBottom + off)
        includeFontPadding = false
    }

    var fillColor: Int
        get() = painter.fill
        set(value) { painter.fill = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val pressed = isPressed && isEnabled
        painter.layout(width, height, pressed)
        painter.drawBack(canvas, pressed)
        painter.drawBorder(canvas)
        val shift = if (pressed) painter.offset else 0f
        canvas.save()
        canvas.translate(shift, shift)
        super.onDraw(canvas)
        canvas.restore()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }
}

/** A square card in the neo style; children are clipped to the rounded face. */
class NeoFrame(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    private val painter: NeoPainter
    private val clip = Path()

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.NeoFrame)
        val d = resources.displayMetrics.density
        painter = NeoPainter(
            context,
            a.getColor(R.styleable.NeoFrame_neoFill, context.getColor(R.color.surface)),
            a.getDimension(R.styleable.NeoFrame_neoOffset, 6 * d),
            a.getDimension(R.styleable.NeoFrame_neoRadius, 22 * d)
        )
        a.recycle()
        setWillNotDraw(false)
        val s = painter.stroke.toInt()
        val off = painter.offset.toInt()
        setPadding(s, s, s + off, s + off)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }

    override fun dispatchDraw(canvas: Canvas) {
        painter.layout(width, height, false)
        painter.drawBack(canvas, false)
        val r = painter.cornerRadius()
        clip.reset()
        clip.addRoundRect(painter.face, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restore()
        painter.drawBorder(canvas)
    }
}

/** A chunky outlined progress bar with a lime fill; can also run as a moving stripe. */
class NeoProgress(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val stroke = 2.5f * d
    private val box = RectF()
    private val bar = RectF()
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.surface) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = context.getColor(R.color.ink)
    }
    private val clip = Path()

    var progress = 0
        set(value) { field = value.coerceIn(0, 100); invalidate() }

    var indeterminate = false
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val h = stroke / 2f
        box.set(h, h, width - h, height - h)
        val r = box.height() / 2f
        canvas.drawRoundRect(box, r, r, back)
        clip.reset()
        clip.addRoundRect(box, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        if (indeterminate) {
            val w = box.width() * 0.3f
            val t = (System.currentTimeMillis() % 1400L) / 1400f
            val x = box.left - w + (box.width() + w) * t
            bar.set(x, box.top, x + w, box.bottom)
            canvas.drawRect(bar, fill)
            postInvalidateOnAnimation()
        } else if (progress > 0) {
            bar.set(box.left, box.top, box.left + box.width() * progress / 100f, box.bottom)
            canvas.drawRect(bar, fill)
        }
        canvas.restore()
        canvas.drawRoundRect(box, r, r, border)
    }
}
