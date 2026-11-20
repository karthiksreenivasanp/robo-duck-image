package com.roboduck.image

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.roboduck.image.modem.Payload
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

object ImageCodec {

    /** Bytes that travel as sound: about one minute of chirping. */
    const val BUDGET = 1200

    /** Picture sizes (longest side, px) to try; the sharpest result wins. */
    private val SIDES = intArrayOf(224, 192, 160, 144, 128, 112, 96, 80)

    /** Compared at this size to judge which candidate looks most like the original. */
    private const val REF_SIDE = 256

    fun load(cr: ContentResolver, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(cr, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = 768f / max(w, h)
            if (scale < 1f) {
                decoder.setTargetSize(
                    (w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1)
                )
            }
        }
    }

    class Prepared(val preview: Bitmap, val payload: ByteArray, val side: Int, val quality: Int)

    /**
     * Makes the WebP that fits the budget and looks most like the original:
     * for each size, the highest quality that fits; then the size whose result
     * is closest to the original (SSIM on brightness) wins. The receiver gets
     * exactly this picture, so [Prepared.preview] is what they will see.
     */
    fun compress(src: Bitmap): Prepared {
        val ref = fit(src, REF_SIDE)
        val refLum = luminance(ref)
        var best: Prepared? = null
        var bestScore = -1.0
        for (side in SIDES) {
            if (side > max(src.width, src.height)) continue
            val small = fit(src, side)
            var lo = 5
            var hi = 92
            var found: ByteArray? = null
            var foundQ = 0
            while (lo <= hi) {
                val q = (lo + hi) / 2
                val d = webp(small, q)
                if (d.size + 1 <= BUDGET) { found = d; foundQ = q; lo = q + 1 } else hi = q - 1
            }
            val data = found ?: continue
            val decoded = BitmapFactory.decodeByteArray(data, 0, data.size) ?: continue
            val up = Bitmap.createScaledBitmap(decoded, ref.width, ref.height, true)
            val score = ssim(luminance(up), refLum, ref.width, ref.height)
            if (score > bestScore) {
                bestScore = score
                best = Prepared(decoded, Payload.packWebp(data), side, foundQ)
            }
        }
        return best ?: run {
            val tiny = fit(src, 48)
            val d = webp(tiny, 20)
            Prepared(BitmapFactory.decodeByteArray(d, 0, d.size), Payload.packWebp(d), 48, 20)
        }
    }

    @Suppress("DEPRECATION")
    private fun webp(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP
        bmp.compress(format, quality, out)
        return out.toByteArray()
    }

    private fun fit(src: Bitmap, side: Int): Bitmap {
        val scale = side.toFloat() / max(src.width, src.height)
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, null, Rect(0, 0, w, h), paint)
        out.setHasAlpha(false) // opaque: no transparency data in the WebP
        return out
    }

    private fun luminance(b: Bitmap): FloatArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return FloatArray(px.size) {
            val p = px[it]
            0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
    }

    /** Mean SSIM with 7x7 windows (summed-area tables). 1 = identical. */
    fun ssim(a: FloatArray, b: FloatArray, w: Int, h: Int): Double {
        val r = 3
        if (w <= 2 * r || h <= 2 * r) return 0.0
        val sa = table(w, h) { a[it].toDouble() }
        val sb = table(w, h) { b[it].toDouble() }
        val saa = table(w, h) { (a[it] * a[it]).toDouble() }
        val sbb = table(w, h) { (b[it] * b[it]).toDouble() }
        val sab = table(w, h) { (a[it] * b[it]).toDouble() }
        val c1 = (0.01 * 255) * (0.01 * 255)
        val c2 = (0.03 * 255) * (0.03 * 255)
        val n = (2 * r + 1) * (2 * r + 1).toDouble()
        var total = 0.0
        var count = 0
        for (y in r until h - r) for (x in r until w - r) {
            fun box(t: DoubleArray): Double {
                val x0 = x - r; val y0 = y - r; val x1 = x + r + 1; val y1 = y + r + 1
                val W = w + 1
                return t[y1 * W + x1] - t[y0 * W + x1] - t[y1 * W + x0] + t[y0 * W + x0]
            }
            val ma = box(sa) / n
            val mb = box(sb) / n
            val va = box(saa) / n - ma * ma
            val vb = box(sbb) / n - mb * mb
            val cov = box(sab) / n - ma * mb
            total += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2))
            count++
        }
        return total / count
    }

    private inline fun table(w: Int, h: Int, v: (Int) -> Double): DoubleArray {
        val W = w + 1
        val t = DoubleArray(W * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += v(y * w + x)
                t[(y + 1) * W + x + 1] = t[y * W + x + 1] + row
            }
        }
        return t
    }

    /**
     * Decodes as much of a picture as has arrived (for the live preview).
     * Rows not yet received stay empty.
     */
    fun decodePartial(bytes: ByteArray): Bitmap? {
        try {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { true } // keep what decoded so far
            }
        } catch (_: Exception) {
        }
        return try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Exception) { null }
    }

    /** The received picture as a JPG file (high quality, so it looks the same). */
    fun toJpeg(bytes: ByteArray): ByteArray? {
        if (bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return bytes
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        return out.toByteArray()
    }

    /** Saves a JPG in Pictures/RoboDuck. */
    fun save(context: Context, jpg: ByteArray, suffix: String = ""): Boolean {
        val cr = context.contentResolver
        val name = "roboduck_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + suffix + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RoboDuck")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return try {
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            cr.openOutputStream(uri)?.use { it.write(jpg) } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }
}
