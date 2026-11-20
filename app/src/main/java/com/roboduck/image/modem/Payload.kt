package com.roboduck.image.modem

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * What actually travels as sound: the JPG, squeezed with a dictionary of the
 * standard JPEG tables (Huffman tables and quality tables) that every phone
 * already knows. A small JPG spends a big part of its bytes on those tables;
 * leaving them out of the sound makes room for a sharper picture in the same
 * time. The receiver gets back the exact same JPG bytes.
 *
 * Format (first byte): 2 = WebP picture (what the app sends now: about 25%
 * smaller than JPEG for the same quality), 1 = JPG deflated with the
 * dictionary, 0 = plain JPG.
 */
object Payload {

    private val DICT: ByteArray = Base64.getDecoder().decode(
        "/+AAEEpGSUYAAQIAAAEAAQAA/9sAQwBQNzxGPDJQRkFGWlVQX3jIgnhubnj1r7mRyP///////////////////////////////////////////////////9sAQwFVWlp4aXjrgoLr/////////////////////////////////////////////////////////////////////////8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9sAQwBJMjdANy1JQDtAUk1JVm22dm1kZG3fn6iEtv/o////6P/6///////////6/////////////////////////////9sAQwFNUlJtX23WdnbW/////////////////////////////////////////////////////////////////////////9sAQwBDLjI6MipDOjY6S0dDT2SnbGRcXGTMkpp5p/LV/vru1enl///////////l6f///////////////////////////9sAQwFHS0tkWGTEbGzE///p/////////////////////////////////////////////////////////////////////9sAQwA+Ki42LiY+NjI2RUE+SVyaZFxVVVy8h45wmt/E6+fbxNfU9v////b////U1////////////+7//////////////9sAQwFBRUVcUVy1ZGS1//7X/v///////////////////////////////////////////////////////////////////9sAQwA5JysyKyQ5Mi4yQD05RFaPXVZPT1avfYRoj8+22tbMtsjE5f///+Xz//bEyP///////////93//////////////9sAQwE9QEBWS1aoXV2o/+zI7P///////////////////////////////////////////////////////////////////9sAQwA1JSgvKCE1LysvPDk1P1CFV1BJSVCjdXthhcGqy8i+qru31fD//9Xj/+a3u////////////8///////////////9sAQwE5PDxQRlCdV1ed/9y73P///////////////////////////////////////////////////////////////////9sAQwAyIiYsJh8yLCksODUyO0t9UUtFRUuZbXRbfbWfv7yyn6+syOH/9MjV/9isr/r//f///////8Lx/////////////9sAQwE1ODhLQkuTUVGT/86vzv///////////////////////////////////////////////////////////////////9sAQwAvICMpIx0vKSYpNTIvOEd2TEdBQUeQZ21VdquWs7ColqWivNT/5bzI/8uipev/7v///////7bi/////////////9sAQwEyNTVHPkeKTEyK/8Klwv///////////////////////////////////////////////////////////////////9sAQwAsHyEnIRwsJyQnMi8sNUNvSEM9PUOIYWdRb6GOqaeejpyZssj/2bK98sCZnN7/4fL//////6zW/////////////9sAQwEvMjJDOkODSEiD/7ect////////////////////////////////////////////////////////////////////9sAQwAqHSAlIBoqJSIlLy0qMj9pRD86Oj+BXGFMaZmGoZ6WhpORqL3yzaiz5baRk9P/1eX6/////6PL///////y/////9sAQwEtLy8/Nz98RER8/66Trv//////////////////////////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QwEDAwMEBAQIBQUIEgwKDBISEhISEhISEhISEhISEhISEhISEhISEhISEhISEhISEhISEhISEhISEhISEhISEhIS/9sAQwADAgICAgIDAgICAwMDAwQGBAQEBAQIBgYFBgkICgoJCAkJCgwPDAoLDgsJCQ0RDQ4PEBAREAoMEhMSEBMPEBAQ/9sAQwEDAwMEAwQIBAQIEAsJCxAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ/9sAQwACAgICAgECAgICAwICAwMGBAMDAwMHBQUEBggHCQgIBwgICQoNCwkKDAoICAsPCwwNDg4PDgkLEBEQDhENDg4O/9sAQwECAwMDAwMHBAQHDgkICQ4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4O/9sAQwACAQECAQECAgICAgICAgMFAwMDAwMGBAQDBQcGBwcHBgcHCAkLCQgICggHBwoNCgoLDAwMDAcJDg8NDA4LDAwM/9sAQwECAgIDAwMGAwMGDAgHCAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM/9sAQwACAQEBAQECAQEBAgICAgIEAwICAgIFBAQDBAYFBgYGBQYGBgcJCAYHCQcGBggLCAkKCgoKCgYICwwLCgwJCgoK/9sAQwECAgICAgIFAwMFCgcGBwoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoK"
    )

    const val WEBP = 2

    fun packWebp(webp: ByteArray): ByteArray = byteArrayOf(WEBP.toByte()) + webp

    fun isWebp(data: ByteArray) = data.isNotEmpty() && data[0].toInt() == WEBP

    fun pack(jpg: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setDictionary(DICT)
        d.setInput(jpg)
        d.finish()
        val out = ByteArrayOutputStream()
        out.write(1)
        val buf = ByteArray(4096)
        while (!d.finished()) {
            val n = d.deflate(buf)
            out.write(buf, 0, n)
        }
        d.end()
        val packed = out.toByteArray()
        return if (packed.size < jpg.size + 1) packed else byteArrayOf(0) + jpg
    }

    /**
     * Returns the JPG (or as much of it as [data] holds, for a live preview).
     * Null if nothing can be recovered yet.
     */
    fun unpack(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        if (data[0].toInt() == 0 || data[0].toInt() == WEBP) return data.copyOfRange(1, data.size)
        if (data[0].toInt() != 1) return null
        val inf = Inflater(true)
        return try {
            inf.setDictionary(DICT)     // raw deflate: the dictionary is set up front
            inf.setInput(data, 1, data.size - 1)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = inf.inflate(buf)
                if (n > 0) { out.write(buf, 0, n); continue }
                break
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            inf.end()
        }
    }

    /**
     * Roughly how much of the picture (top to bottom) [partialJpg] can show:
     * the share of the compressed picture data received so far.
     */
    fun visibleFraction(partialJpg: ByteArray, payloadReceived: Int, payloadTotal: Int): Float {
        if (isRiffWebp(partialJpg)) return webpVisibleFraction(partialJpg.size, partialJpg, payloadTotal - 1)
        var h = -1
        var i = 2
        while (i + 3 < partialJpg.size) {
            if (partialJpg[i].toInt() and 0xFF != 0xFF) break
            val marker = partialJpg[i + 1].toInt() and 0xFF
            val len = ((partialJpg[i + 2].toInt() and 0xFF) shl 8) or (partialJpg[i + 3].toInt() and 0xFF)
            if (marker == 0xDA) { h = i + 2 + len; break }
            i += 2 + len
        }
        if (h < 0 || partialJpg.size <= h) return 0f
        val totalJpg = partialJpg.size + (payloadTotal - payloadReceived)
        return ((partialJpg.size - h).toFloat() / (totalJpg - h).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    private fun isRiffWebp(b: ByteArray) = b.size >= 12 &&
        b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte()

    /**
     * Lossy WebP decodes in rows of 16 px, but only after its "first partition"
     * (the picture's layout) has fully arrived; the rest is row data in order.
     */
    private fun webpVisibleFraction(have: Int, b: ByteArray, total: Int): Float {
        // RIFF(12) + "VP8 " chunk header(8) + frame tag(3) + start code and size(7) = 30
        if (b.size < 23 || b[12] != 'V'.code.toByte() || b[15] != ' '.code.toByte()) return 0f
        val tag = (b[20].toInt() and 0xFF) or ((b[21].toInt() and 0xFF) shl 8) or ((b[22].toInt() and 0xFF) shl 16)
        val firstPart = (tag shr 5) and 0x7FFFF
        val rowsStart = 30 + firstPart
        if (have <= rowsStart || total <= rowsStart) return 0f
        return ((have - rowsStart).toFloat() / (total - rowsStart)).coerceIn(0f, 1f)
    }
}
