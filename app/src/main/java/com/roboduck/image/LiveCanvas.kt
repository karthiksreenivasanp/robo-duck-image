package com.roboduck.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * What the picture card shows while a transfer runs.
 *
 * Receiving: a pixel-style live picture of the incoming whistles (each one
 * shows up as a little rising or falling line), with the picture building up
 * from the top as it is decoded and a lime scan line at the edge.
 * Sending: the picture being sent, with the part not yet sent dimmed and the
 * scan line moving down as it goes.
 */
class LiveCanvas(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class Mode { RECEIVE, SEND }

    var mode = Mode.RECEIVE
        set(value) { field = value; invalidate() }

    private val cols = 180
    private val wf = Bitmap.createBitmap(cols, Spectrum.ROWS, Bitmap.Config.ARGB_8888)
    private val wfPixels = IntArray(cols * Spectrum.ROWS)
    private val palette = IntArray(64)

    // Whistles heard so far, drawn as little pixel swoops (newest on the right).
    private val slots = 6
    private val slotW = 18
    private val gh = 36
    private val glyph = Bitmap.createBitmap(slots * slotW, gh, Bitmap.Config.ARGB_8888)
    private val glyphPixels = IntArray(slots * slotW * gh)
    private val whistles = ArrayDeque<Int>()
    private var showWhistles = false
    private val surface = context.getColor(R.color.surface)
    private val fresh = context.getColor(R.color.accent_deep)
    private val old = context.getColor(R.color.accent)
    private val ink = context.getColor(R.color.ink)

    private var image: Bitmap? = null
    private var revealTarget = 0f
    private var reveal = 0f
    private var scanTarget = 0f
    private var scan = 0f
    private var scanVisible = false

    private val pixelPaint = Paint().apply { isFilterBitmap = false }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = context.getColor(R.color.surface); alpha = 170 }
    private val scanPaint = Paint().apply { color = context.getColor(R.color.accent_deep) }
    private val scanEdge = Paint().apply { color = context.getColor(R.color.ink) }
    private val d = resources.displayMetrics.density
    private val fit = RectF()
    private val src = Rect()
    private val dst = RectF()

    init {
        val bg = context.getColor(R.color.surface)
        val mid = context.getColor(R.color.accent)
        val top = context.getColor(R.color.accent_deep)
        for (i in palette.indices) {
            val t = i / (palette.size - 1f)
            palette[i] = if (t < 0.6f) blend(bg, mid, t / 0.6f) else blend(mid, top, (t - 0.6f) / 0.4f)
        }
        clear()
    }

    fun clear() {
        whistles.clear()
        showWhistles = false
        drawGlyphs()
        wfPixels.fill(palette[0])
        wf.setPixels(wfPixels, 0, cols, 0, 0, cols, Spectrum.ROWS)
        image = null
        reveal = 0f; revealTarget = 0f
        scan = 0f; scanTarget = 0f
        scanVisible = false
        invalidate()
    }

    /** Adds new spectrum columns (each [Spectrum.ROWS] values 0..1, row 0 = lowest pitch). */
    fun pushColumns(columns: List<FloatArray>) {
        val n = minOf(columns.size, cols)
        for (r in 0 until Spectrum.ROWS) {
            val row = r * cols
            System.arraycopy(wfPixels, row + n, wfPixels, row, cols - n)
        }
        for (c in 0 until n) {
            val col = columns[columns.size - n + c]
            val x = cols - n + c
            for (r in 0 until Spectrum.ROWS) {
                val v = col[r].coerceIn(0f, 1f)
                wfPixels[(Spectrum.ROWS - 1 - r) * cols + x] = palette[(v * (palette.size - 1)).toInt()]
            }
        }
        wf.setPixels(wfPixels, 0, cols, 0, 0, cols, Spectrum.ROWS)
        invalidate()
    }

    /** A whistle was heard: switch from the ambient spectrum to the swoops. */
    fun pushWhistle(k: Int) {
        showWhistles = true
        whistles.addLast(k)
        while (whistles.size > slots) whistles.removeFirst()
        drawGlyphs()
        invalidate()
    }

    private fun drawGlyphs() {
        glyphPixels.fill(surface)
        val w = slots * slotW
        val start = slots - whistles.size
        whistles.forEachIndexed { i, k ->
            val slot = start + i
            val age = (whistles.size - 1 - i).toFloat() / slots
            val color = if (i == whistles.size - 1) ink else blend(fresh, old, age * 1.6f)
            var prevY = -1
            for (n in 0 until slotW - 1) {
                val f = com.roboduck.image.modem.Chirp.freq(k, n * com.roboduck.image.modem.Chirp.L / (slotW - 2))
                val t = ((f - com.roboduck.image.modem.Chirp.F_LOW) / com.roboduck.image.modem.Chirp.BANDWIDTH).toFloat()
                val y = ((1f - t.coerceIn(0f, 1f)) * (gh - 3) + 1).toInt()
                val x = slot * slotW + n
                // Join to the previous point unless the sweep wrapped around.
                if (prevY >= 0 && abs(y - prevY) < gh / 2) {
                    for (yy in minOf(y, prevY)..maxOf(y, prevY)) { plot(x, yy, w, color); plot(x, yy + 1, w, color) }
                } else { plot(x, y, w, color); plot(x, y + 1, w, color) }
                prevY = y
            }
        }
        glyph.setPixels(glyphPixels, 0, w, 0, 0, w, gh)
    }

    private fun plot(x: Int, y: Int, w: Int, c: Int) {
        if (x in 0 until w && y in 0 until gh) glyphPixels[y * w + x] = c
    }

    /** Picture so far; [fraction] of it (from the top) is real data. */
    fun setImage(bmp: Bitmap?, fraction: Float) {
        if (bmp != null && image == null) { reveal = 0f }
        image = bmp
        revealTarget = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    /** Overall progress 0..1, drives the scan line. */
    fun setProgress(fraction: Float) {
        scanVisible = true
        scanTarget = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Ease the scan line and reveal toward their targets so movement is smooth.
        reveal += (revealTarget - reveal) * 0.12f
        scan += (scanTarget - scan) * 0.12f
        val moving = abs(revealTarget - reveal) > 0.001f || abs(scanTarget - scan) > 0.001f

        if (mode == Mode.RECEIVE && !showWhistles) {
            dst.set(0f, 0f, w, h)
            canvas.drawBitmap(wf, null, dst, pixelPaint)
        }

        val img = image
        if (img != null) {
            val scale = minOf(w / img.width, h / img.height)
            val iw = img.width * scale
            val ih = img.height * scale
            fit.set((w - iw) / 2f, (h - ih) / 2f, (w + iw) / 2f, (h + ih) / 2f)
            if (mode == Mode.RECEIVE) {
                val rows = (img.height * reveal).toInt().coerceIn(0, img.height)
                if (rows > 0) {
                    src.set(0, 0, img.width, rows)
                    dst.set(fit.left, fit.top, fit.right, fit.top + rows * scale)
                    canvas.drawBitmap(img, src, dst, imagePaint)
                }
            } else {
                canvas.drawBitmap(img, null, fit, imagePaint)
                val y = fit.top + fit.height() * scan
                canvas.drawRect(fit.left, y, fit.right, fit.bottom, dimPaint)
            }
        } else {
            fit.set(0f, 0f, w, h)
        }

        if (mode == Mode.RECEIVE && showWhistles) {
            // Swoops keep their shape: whole-pixel scaling, centred in the space below the picture.
            val top = if (img != null) fit.top + fit.height() * reveal else 0f
            val room = h - top
            val px = maxOf(1f, kotlin.math.floor(minOf(w / glyph.width, room / glyph.height * 0.9f)))
            val gw = glyph.width * px
            val gH = glyph.height * px
            if (room > gH * 0.5f) {
                val left = (w - gw) / 2f
                val y0 = top + (room - gH) / 2f
                dst.set(left, y0, left + gw, y0 + gH)
                canvas.save()
                canvas.clipRect(0f, top, w, h)
                canvas.drawBitmap(glyph, null, dst, pixelPaint)
                canvas.restore()
            }
        }

        if (scanVisible) {
            val y = if (mode == Mode.RECEIVE && img != null) fit.top + fit.height() * reveal
            else fit.top + fit.height() * scan
            val t = 3f * d
            canvas.drawRect(0f, y - t / 2f - d, w, y + t / 2f + d, scanEdge)
            canvas.drawRect(0f, y - t / 2f, w, y + t / 2f, scanPaint)
        }
        if (moving) postInvalidateOnAnimation()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt()
        )
    }
}

/** Short-time spectrum of the microphone, in the whistles' pitch range. */
class Spectrum {
    companion object {
        const val N = 256                 // 5.8 ms windows: fast enough to see each sweep
        const val ROWS = 28
        private const val F_LO = 1500.0
        private const val F_HI = 6300.0
    }

    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private var peakDb = -60f

    /** One column per [N] samples of [samples] (first [count]). */
    fun columns(samples: FloatArray, count: Int): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var start = 0
        while (start + N <= count) {
            for (i in 0 until N) { re[i] = samples[start + i] * window[i]; im[i] = 0f }
            fft(re, im)
            val col = FloatArray(ROWS)
            for (r in 0 until ROWS) {
                val f0 = F_LO + (F_HI - F_LO) * r / ROWS
                val f1 = F_LO + (F_HI - F_LO) * (r + 1) / ROWS
                val b0 = (f0 * N / 44100).toInt()
                val b1 = max(b0 + 1, (f1 * N / 44100).toInt())
                var m = 0f
                for (b in b0 until b1) m = max(m, re[b] * re[b] + im[b] * im[b])
                val db = 10f * log10(m + 1e-12f)
                if (db > peakDb) peakDb = db
                col[r] = (db - (peakDb - 40f)) / 40f
            }
            peakDb -= 0.05f
            out.add(col)
            start += N
        }
        return out
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
