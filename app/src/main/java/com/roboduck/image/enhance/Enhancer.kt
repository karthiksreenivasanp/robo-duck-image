package com.roboduck.image.enhance

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * "Clear image": the same steps you would run by hand in ImageJ, applied
 * in order to a received picture (plain Kotlin, no Android dependencies).
 *
 *  1. Process > Noise > Remove Outliers (radius 1, threshold 40, bright + dark)
 *     removes speckles left by audio errors.
 *  2. Image > Scale with bicubic interpolation (4x) gives a smooth, larger image.
 *     (before scaling, a JPEG deblocking pass smooths small steps on the 8 px grid)
 *  3. Bilateral filter (ImageJ plugin) smooths flat areas, keeps edges.
 *  4. Process > Filters > Unsharp Mask (gentle) brings edges back.
 *  5. Process > Enhance Contrast (0.35% saturated), capped so colours stay true.
 *
 * It makes the picture look clearer; it cannot recover detail that was
 * never sent.
 */
object Enhancer {

    class Result(val pixels: IntArray, val width: Int, val height: Int)

    fun enhance(argb: IntArray, w: Int, h: Int, scale: Int = 4): Result {
        var r = FloatArray(w * h)
        var g = FloatArray(w * h)
        var b = FloatArray(w * h)
        for (i in argb.indices) {
            val p = argb[i]
            r[i] = ((p shr 16) and 0xFF).toFloat()
            g[i] = ((p shr 8) and 0xFF).toFloat()
            b[i] = (p and 0xFF).toFloat()
        }

        r = removeOutliers(r, w, h, 40f)
        g = removeOutliers(g, w, h, 40f)
        b = removeOutliers(b, w, h, 40f)

        deblock(r, w, h); deblock(g, w, h); deblock(b, w, h)

        val ow = w * scale
        val oh = h * scale
        r = bicubic(r, w, h, ow, oh)
        g = bicubic(g, w, h, ow, oh)
        b = bicubic(b, w, h, ow, oh)

        val smoothed = bilateral(r, g, b, ow, oh, radius = scale, sigmaS = scale * 0.6f, sigmaR = 14f)
        r = smoothed[0]; g = smoothed[1]; b = smoothed[2]

        val sharpSigma = 0.4f * scale
        r = unsharp(r, ow, oh, sharpSigma, 0.3f)
        g = unsharp(g, ow, oh, sharpSigma, 0.3f)
        b = unsharp(b, ow, oh, sharpSigma, 0.3f)

        stretchContrast(r, g, b, 0.35f)

        val out = IntArray(ow * oh)
        for (i in out.indices) {
            out[i] = (0xFF shl 24) or (clamp(r[i]) shl 16) or (clamp(g[i]) shl 8) or clamp(b[i])
        }
        return Result(out, ow, oh)
    }

    private fun clamp(v: Float): Int = v.roundToInt().coerceIn(0, 255)

    /** Replaces a pixel by its 3x3 median when it differs by more than [threshold]. */
    fun removeOutliers(src: FloatArray, w: Int, h: Int, threshold: Float): FloatArray {
        val out = src.copyOf()
        val win = FloatArray(9)
        for (y in 0 until h) for (x in 0 until w) {
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                win[n++] = src[yy * w + xx]
            }
            win.sort()
            val median = win[4]
            val v = src[y * w + x]
            if (abs(v - median) > threshold) out[y * w + x] = median
        }
        return out
    }

    private fun cubic(t: Float): Float {
        // Catmull-Rom style kernel, a = -0.5 (as ImageJ's bicubic).
        val a = -0.5f
        val x = abs(t)
        return when {
            x <= 1f -> (a + 2f) * x * x * x - (a + 3f) * x * x + 1f
            x < 2f -> a * x * x * x - 5f * a * x * x + 8f * a * x - 4f * a
            else -> 0f
        }
    }

    fun bicubic(src: FloatArray, w: Int, h: Int, ow: Int, oh: Int): FloatArray {
        val out = FloatArray(ow * oh)
        val sx = w.toFloat() / ow
        val sy = h.toFloat() / oh
        for (y in 0 until oh) {
            val fy = (y + 0.5f) * sy - 0.5f
            val iy = floor(fy).toInt()
            val ty = fy - iy
            for (x in 0 until ow) {
                val fx = (x + 0.5f) * sx - 0.5f
                val ix = floor(fx).toInt()
                val tx = fx - ix
                var sum = 0f
                for (m in -1..2) {
                    val wy = cubic(m - ty)
                    val yy = (iy + m).coerceIn(0, h - 1)
                    for (n in -1..2) {
                        val xx = (ix + n).coerceIn(0, w - 1)
                        sum += src[yy * w + xx] * wy * cubic(n - tx)
                    }
                }
                out[y * ow + x] = sum
            }
        }
        return out
    }

    fun gaussian(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        if (sigma <= 0f) return src.copyOf()
        val radius = ceil(sigma * 3).toInt().coerceAtLeast(1)
        val k = FloatArray(radius * 2 + 1)
        var total = 0f
        for (i in -radius..radius) {
            k[i + radius] = exp(-(i * i) / (2f * sigma * sigma))
            total += k[i + radius]
        }
        for (i in k.indices) k[i] /= total
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in -radius..radius) s += src[y * w + (x + i).coerceIn(0, w - 1)] * k[i + radius]
            tmp[y * w + x] = s
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in -radius..radius) s += tmp[(y + i).coerceIn(0, h - 1) * w + x] * k[i + radius]
            out[y * w + x] = s
        }
        return out
    }

    /** ImageJ's unsharp mask: (original - weight * blurred) / (1 - weight). */
    fun unsharp(src: FloatArray, w: Int, h: Int, sigma: Float, weight: Float): FloatArray {
        val blur = gaussian(src, w, h, sigma)
        return FloatArray(src.size) { (src[it] - weight * blur[it]) / (1f - weight) }
    }

    /** ImageJ "Enhance Contrast": saturate [saturated]% of pixels, split between both ends. */
    fun stretchContrast(r: FloatArray, g: FloatArray, b: FloatArray, saturated: Float) {
        val hist = IntArray(256)
        for (i in r.indices) {
            val lum = (0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]).roundToInt().coerceIn(0, 255)
            hist[lum]++
        }
        val clip = (r.size * saturated / 200f).toInt()
        var lo = 0
        var acc = 0
        while (lo < 255 && acc + hist[lo] <= clip) { acc += hist[lo]; lo++ }
        var hi = 255
        acc = 0
        while (hi > 0 && acc + hist[hi] <= clip) { acc += hist[hi]; hi-- }
        if (hi - lo < 16) return
        val s = minOf(255f / (hi - lo), 1.15f)
        val mid = (hi + lo) / 2f
        if (s <= 1.001f) return
        for (i in r.indices) {
            r[i] = mid + (r[i] - mid) * s
            g[i] = mid + (g[i] - mid) * s
            b[i] = mid + (b[i] - mid) * s
        }
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
