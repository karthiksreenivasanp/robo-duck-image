package com.roboduck.image.enhance

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** An RGB picture as three float channels (0..255), the way ImageJ filters RGB. */
class RgbImage(val width: Int, val height: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray) {

    fun copy() = RgbImage(width, height, r.copyOf(), g.copyOf(), b.copyOf())

    inline fun eachChannel(op: (FloatArray) -> Unit) { op(r); op(g); op(b) }

    fun toArgb(): IntArray = IntArray(width * height) {
        (0xFF shl 24) or (c(r[it]) shl 16) or (c(g[it]) shl 8) or c(b[it])
    }

    private fun c(v: Float) = v.roundToInt().coerceIn(0, 255)

    companion object {
        fun fromArgb(argb: IntArray, width: Int, height: Int): RgbImage {
            val n = width * height
            val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
            for (i in 0 until n) {
                val p = argb[i]
                r[i] = ((p shr 16) and 0xFF).toFloat()
                g[i] = ((p shr 8) and 0xFF).toFloat()
                b[i] = (p and 0xFF).toFloat()
            }
            return RgbImage(width, height, r, g, b)
        }
    }
}

/**
 * The picture tools behind the buttons. ImageJ filters come from [ImageJ];
 * the JPEG deblocking and bilateral smoothing in Auto are this app's own.
 * They make a picture look clearer; they cannot recover detail that was never sent.
 */
object Enhancer {

    /** Received pictures are about 128 px; tools work on a 4x larger copy. */
    const val SCALE = 4

    /** Image > Scale (bicubic), unless the picture is already large. */
    fun upscale(img: RgbImage): RgbImage {
        if (img.width >= 300 || img.height >= 300) return img.copy()
        val w = img.width * SCALE
        val h = img.height * SCALE
        return RgbImage(
            w, h,
            ImageJ.resizeBicubic(img.r, img.width, img.height, w, h),
            ImageJ.resizeBicubic(img.g, img.width, img.height, w, h),
            ImageJ.resizeBicubic(img.b, img.width, img.height, w, h)
        )
    }

    /** One-tap clean-up of the original received picture. */
    fun auto(original: RgbImage): RgbImage {
        val small = original.copy()
        small.eachChannel { ImageJ.removeOutliers(it, small.width, small.height, 1.0, 40f) }
        small.eachChannel { deblock(it, small.width, small.height) }
        val big = upscale(small)
        val s = bilateral(big.r, big.g, big.b, big.width, big.height, radius = SCALE, sigmaS = SCALE * 0.6f, sigmaR = 14f)
        val out = RgbImage(big.width, big.height, s[0], s[1], s[2])
        out.eachChannel { ImageJ.unsharpMask(it, out.width, out.height, 0.4 * SCALE, 0.3f) }
        gentleContrast(out)
        return out
    }

    /** Process > Filters > Unsharp Mask (ImageJ's default weight 0.6). */
    fun sharpen(img: RgbImage): RgbImage = img.copy().also { o ->
        o.eachChannel { ImageJ.unsharpMask(it, o.width, o.height, 2.0, 0.6f) }
    }

    /** Process > Filters > Gaussian Blur. */
    fun smooth(img: RgbImage): RgbImage = img.copy().also { o ->
        o.eachChannel { ImageJ.gaussianBlur(it, o.width, o.height, 1.5) }
    }

    /** Process > Enhance Contrast, 0.35% saturated (ImageJ's default). */
    fun contrast(img: RgbImage): RgbImage = img.copy().also { o ->
        ImageJ.enhanceContrast(o.r, o.g, o.b, 0.35)
    }

    /** Process > Noise > Remove Outliers, then Process > Filters > Median. */
    fun denoise(img: RgbImage): RgbImage = img.copy().also { o ->
        o.eachChannel {
            ImageJ.removeOutliers(it, o.width, o.height, 2.0, 30f)
            ImageJ.median(it, o.width, o.height, 2.0)
        }
    }

    /** Like Enhance Contrast, but the stretch is capped so colours stay natural. */
    private fun gentleContrast(img: RgbImage) {
        val hist = IntArray(256)
        for (i in img.r.indices) {
            val lum = (0.299f * img.r[i] + 0.587f * img.g[i] + 0.114f * img.b[i]).roundToInt().coerceIn(0, 255)
            hist[lum]++
        }
        val clip = (img.r.size * 0.35f / 200f).toInt()
        var lo = 0
        var acc = 0
        while (lo < 255 && acc + hist[lo] <= clip) { acc += hist[lo]; lo++ }
        var hi = 255
        acc = 0
        while (hi > 0 && acc + hist[hi] <= clip) { acc += hist[hi]; hi-- }
        if (hi - lo < 16) return
        val s = minOf(255f / (hi - lo), 1.15f)
        if (s <= 1.001f) return
        val mid = (hi + lo) / 2f
        img.eachChannel { ch -> for (i in ch.indices) ch[i] = mid + (ch[i] - mid) * s }
    }

    /** Smooths small steps across 8 px JPEG block borders; real edges are left alone. */
    fun deblock(p: FloatArray, w: Int, h: Int, threshold: Float = 22f) {
        fun pass(len: Int, lines: Int, at: (line: Int, i: Int) -> Int) {
            var edge = 8
            while (edge + 2 < len) {
                for (line in 0 until lines) {
                    val a = p[at(line, edge - 3)]
                    val p1 = p[at(line, edge - 2)]
                    val p0 = p[at(line, edge - 1)]
                    val q0 = p[at(line, edge)]
                    val q1 = p[at(line, edge + 1)]
                    val z = p[at(line, edge + 2)]
                    val step = q0 - p0
                    if (abs(step) < threshold && abs(p0 - p1) < threshold / 2 && abs(q1 - q0) < threshold / 2) {
                        p[at(line, edge - 2)] = a + (z - a) * 1f / 5f
                        p[at(line, edge - 1)] = a + (z - a) * 2f / 5f
                        p[at(line, edge)] = a + (z - a) * 3f / 5f
                        p[at(line, edge + 1)] = a + (z - a) * 4f / 5f
                    }
                }
                edge += 8
            }
        }
        pass(w, h) { line, i -> line * w + i }
        pass(h, w) { line, i -> i * w + line }
    }

    /** Edge-preserving smoothing on all three channels together. */
    fun bilateral(
        r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int,
        radius: Int, sigmaS: Float, sigmaR: Float
    ): Array<FloatArray> {
        val spatial = FloatArray((2 * radius + 1) * (2 * radius + 1))
        for (dy in -radius..radius) for (dx in -radius..radius) {
            spatial[(dy + radius) * (2 * radius + 1) + dx + radius] =
                exp(-(dx * dx + dy * dy) / (2f * sigmaS * sigmaS))
        }
        val rangeLut = FloatArray(256 * 3 + 1) { exp(-(it.toFloat() * it) / (2f * sigmaR * sigmaR * 3f)) }
        val or = FloatArray(w * h)
        val og = FloatArray(w * h)
        val ob = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = y * w + x
            var sw = 0f; var sr = 0f; var sg = 0f; var sb = 0f
            for (dy in -radius..radius) {
                val yy = (y + dy).coerceIn(0, h - 1)
                for (dx in -radius..radius) {
                    val n = yy * w + (x + dx).coerceIn(0, w - 1)
                    val diff = (abs(r[n] - r[c]) + abs(g[n] - g[c]) + abs(b[n] - b[c])).toInt().coerceIn(0, 768)
                    val wt = spatial[(dy + radius) * (2 * radius + 1) + dx + radius] * rangeLut[diff]
                    sw += wt; sr += r[n] * wt; sg += g[n] * wt; sb += b[n] * wt
                }
            }
            or[c] = sr / sw; og[c] = sg / sw; ob[c] = sb / sw
        }
        return arrayOf(or, og, ob)
    }
}
