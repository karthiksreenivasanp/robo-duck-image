package com.roboduck.image

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.roboduck.image.modem.Payload
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

object ImageCodec {

    /** Bytes that travel as sound: keeps a transfer around 1.5 minutes. */
    private const val BUDGET = 1600

    fun load(cr: ContentResolver, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(cr, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = 512f / max(w, h)
            if (scale < 1f) {
                decoder.setTargetSize(
                    (w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1)
                )
            }
        }
    }

    /**
     * Picks the largest, cleanest JPG whose packed size fits the budget:
     * tries big sizes first and only lowers the JPG quality down to 50
     * before trying a smaller size. Returns the JPG and what to send.
     */
    fun compress(src: Bitmap): Pair<ByteArray, ByteArray> {
        var fallback: Pair<ByteArray, ByteArray>? = null
        for (side in intArrayOf(160, 144, 128, 112, 96, 80, 64)) {
            val scaled = fit(src, side)
            for (q in intArrayOf(85, 78, 70, 62, 55, 50, 40, 30)) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
                val jpg = out.toByteArray()
                val packed = Payload.pack(jpg)
                if (packed.size <= BUDGET) {
                    if (q >= 50) return jpg to packed
                    if (fallback == null) fallback = jpg to packed
                    break
                }
            }
        }
        return fallback ?: run {
            val out = ByteArrayOutputStream()
            fit(src, 48).compress(Bitmap.CompressFormat.JPEG, 30, out)
            val jpg = out.toByteArray()
            jpg to Payload.pack(jpg)
        }
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
        return out
    }

    /** Saves the exact received bytes as a JPG in Pictures/RoboDuck. */
    fun save(context: Context, bytes: ByteArray, suffix: String = ""): Boolean {
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
            cr.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }
}
