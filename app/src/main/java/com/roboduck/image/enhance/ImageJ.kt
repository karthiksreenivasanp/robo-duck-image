package com.roboduck.image.enhance

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Image filters ported from ImageJ 1.x (https://github.com/imagej/ImageJ),
 * which is in the public domain. The arithmetic follows ImageJ's own code
 * (class and method named on each function); only the parts that depend on
 * desktop Java (AWT windows, dialogs, ROIs, progress bars) are left out, since
 * Android does not have them. All filters work on one float channel; RGB
 * images are processed channel by channel, as ImageJ does.
 */
object ImageJ {

    // ---------------------------------------------------------------------
    // ij.plugin.filter.GaussianBlur
    // ---------------------------------------------------------------------

    /** GaussianBlur.blurGaussian(ip, sigma, sigma, accuracy); 0.002 is ImageJ's value for 8-bit and RGB. */
    fun gaussianBlur(pixels: FloatArray, width: Int, height: Int, sigma: Double, accuracy: Double = 0.002) {
        if (sigma <= 0) return
        blur1Direction(pixels, width, height, sigma, accuracy, true)
        blur1Direction(pixels, width, height, sigma, accuracy, false)
    }

    private fun blur1Direction(
        pixels: FloatArray, width: Int, height: Int,
        sigma: Double, accuracy: Double, xDirection: Boolean
    ) {
        val length = if (xDirection) width else height
        val pointInc = if (xDirection) 1 else width
        val lineInc = if (xDirection) width else 1
        val lines = if (xDirection) height else width
        val kernel = makeGaussianKernel(sigma, accuracy, length)
        val cache = FloatArray(length)
        for (line in 0 until lines) {
            val pixel0 = line * lineInc
            var p = pixel0
            for (i in 0 until length) { cache[i] = pixels[p]; p += pointInc }
            convolveLine(cache, pixels, kernel, 0, length, pixel0, pointInc)
        }
    }

    /** GaussianBlur.convolveLine: out-of-edge pixels are replaced by the nearest edge pixel. */
    private fun convolveLine(
        input: FloatArray, pixels: FloatArray, kernel: Array<FloatArray>,
        writeFrom: Int, writeTo: Int, point0: Int, pointInc: Int
    ) {
        val length = input.size
        val first = input[0]
        val last = input[length - 1]
        val kern = kernel[0]
        val kern0 = kern[0]
        val kernSum = kernel[1]
        val kRadius = kern.size
        val firstPart = if (kRadius < length) kRadius else length
        var p = point0 + writeFrom * pointInc
        var i = writeFrom
        while (i < firstPart) {
            var result = input[i] * kern0
            result += kernSum[i] * first
            if (i + kRadius > length) result += kernSum[length - i - 1] * last
            for (k in 1 until kRadius) {
                var v = 0f
                if (i - k >= 0) v += input[i - k]
                if (i + k < length) v += input[i + k]
                result += kern[k] * v
            }
            pixels[p] = result
            i++; p += pointInc
        }
        val iEndInside = if (length - kRadius < writeTo) length - kRadius else writeTo
        while (i < iEndInside) {
            var result = input[i] * kern0
            for (k in 1 until kRadius) result += kern[k] * (input[i - k] + input[i + k])
            pixels[p] = result
            i++; p += pointInc
        }
        while (i < writeTo) {
            var result = input[i] * kern0
            if (i < kRadius) result += kernSum[i] * first
            if (i + kRadius >= length) result += kernSum[length - i - 1] * last
            for (k in 1 until kRadius) {
                var v = 0f
                if (i - k >= 0) v += input[i - k]
                if (i + k < length) v += input[i + k]
                result += kern[k] * v
            }
            pixels[p] = result
            i++; p += pointInc
        }
    }

    /** GaussianBlur.makeGaussianKernel: one-sided kernel plus running sum, with smooth edge correction. */
    private fun makeGaussianKernel(sigma: Double, accuracy: Double, maxRadiusIn: Int): Array<FloatArray> {
        var kRadius = ceil(sigma * sqrt(-2 * ln(accuracy))).toInt() + 1
        val maxRadius = if (maxRadiusIn < 50) 50 else maxRadiusIn
        if (kRadius > maxRadius) kRadius = maxRadius
        val kernel = arrayOf(FloatArray(kRadius), FloatArray(kRadius))
        for (i in 0 until kRadius) kernel[0][i] = exp(-0.5 * i * i / sigma / sigma).toFloat()
        if (kRadius < maxRadius && kRadius > 3) {
            var sqrtSlope = Double.MAX_VALUE
            var r = kRadius
            while (r > kRadius / 2) {
                r--
                val a = sqrt(kernel[0][r].toDouble()) / (kRadius - r)
                if (a < sqrtSlope) sqrtSlope = a else break
            }
            for (r1 in r + 2 until kRadius) {
                kernel[0][r1] = ((kRadius - r1) * (kRadius - r1) * sqrtSlope * sqrtSlope).toFloat()
            }
        }
        val sum: Double = if (kRadius < maxRadius) {
            var s = kernel[0][0].toDouble()
            for (i in 1 until kRadius) s += 2 * kernel[0][i]
            s
        } else sigma * sqrt(2 * Math.PI)
        var rsum = 0.5 + 0.5 * kernel[0][0] / sum
        for (i in 0 until kRadius) {
            val v = kernel[0][i] / sum
            kernel[0][i] = v.toFloat()
            rsum -= v
            kernel[1][i] = rsum.toFloat()
        }
        return kernel
    }

    // ---------------------------------------------------------------------
    // ij.plugin.filter.UnsharpMask
    // ---------------------------------------------------------------------

    /** UnsharpMask.sharpenFloat: (original - weight * blurred) / (1 - weight), blur accuracy 0.01. */
    fun unsharpMask(pixels: FloatArray, width: Int, height: Int, sigma: Double, weight: Float) {
        val snapshot = pixels.copyOf()
        gaussianBlur(pixels, width, height, sigma, 0.01)
        for (p in pixels.indices) pixels[p] = (snapshot[p] - weight * pixels[p]) / (1f - weight)
    }

    // ---------------------------------------------------------------------
    // ij.process.ImageProcessor: sharpen(), smooth()
    // ---------------------------------------------------------------------

    /** ImageProcessor.sharpen(): 3x3 kernel {-1,-1,-1, -1,12,-1, -1,-1,-1}, scaled by its sum (4). */
    fun sharpen(pixels: FloatArray, width: Int, height: Int) =
        convolve3x3(pixels, width, height, intArrayOf(-1, -1, -1, -1, 12, -1, -1, -1, -1))

    /** ImageProcessor.smooth(): each pixel becomes the 3x3 neighbourhood mean (BLUR_MORE). */
    fun smooth(pixels: FloatArray, width: Int, height: Int) =
        convolve3x3(pixels, width, height, intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1))

    private fun convolve3x3(pixels: FloatArray, width: Int, height: Int, k: IntArray) {
        var scale = k.sum().toFloat()
        if (scale == 0f) scale = 1f
        val src = pixels.copyOf()
        for (y in 0 until height) {
            val ym = (y - 1).coerceAtLeast(0) * width
            val y0 = y * width
            val yp = (y + 1).coerceAtMost(height - 1) * width
            for (x in 0 until width) {
                val xm = (x - 1).coerceAtLeast(0)
                val xp = (x + 1).coerceAtMost(width - 1)
                val sum = k[0] * src[ym + xm] + k[1] * src[ym + x] + k[2] * src[ym + xp] +
                    k[3] * src[y0 + xm] + k[4] * src[y0 + x] + k[5] * src[y0 + xp] +
                    k[6] * src[yp + xm] + k[7] * src[yp + x] + k[8] * src[yp + xp]
                pixels[y0 + x] = sum / scale
            }
        }
    }

    // ---------------------------------------------------------------------
    // ij.process.FloatProcessor.resize() with BICUBIC interpolation
    // ---------------------------------------------------------------------

    private const val A = 0.5 // ImageProcessor.cubic: Catmull-Rom

    private fun cubic(xIn: Double): Double {
        val x = abs(xIn)
        return when {
            x < 1.0 -> x * x * (x * (-A + 2.0) + (A - 3.0)) + 1.0
            x < 2.0 -> -A * x * x * x + 5.0 * A * x * x - 8.0 * A * x + 4.0 * A
            else -> 0.0
        }
    }

    fun resizeBicubic(src: FloatArray, width: Int, height: Int, dstWidth: Int, dstHeight: Int): FloatArray {
        val srcCenterX = width / 2.0
        val srcCenterY = height / 2.0
        val xScale = dstWidth.toDouble() / width
        val yScale = dstHeight.toDouble() / height
        var dstCenterX = dstWidth / 2.0
        var dstCenterY = dstHeight / 2.0
        if (dstWidth != width) dstCenterX += xScale / 4.0
        if (dstHeight != height) dstCenterY += yScale / 4.0
        val out = FloatArray(dstWidth * dstHeight)
        for (y in 0 until dstHeight) {
            val ys = (y - dstCenterY) / yScale + srcCenterY
            for (x in 0 until dstWidth) {
                val xs = (x - dstCenterX) / xScale + srcCenterX
                out[y * dstWidth + x] = bicubicPixel(src, width, height, xs, ys).toFloat()
            }
        }
        return out
    }

    /** ImageProcessor.getBicubicInterpolatedPixel, falling back to bilinear at the border as ImageJ does. */
    private fun bicubicPixel(p: FloatArray, w: Int, h: Int, x0: Double, y0: Double): Double {
        val u0 = floor(x0).toInt()
        val v0 = floor(y0).toInt()
        if (u0 <= 0 || u0 >= w - 2 || v0 <= 0 || v0 >= h - 2) return bilinearPixel(p, w, h, x0, y0)
        var q = 0.0
        for (j in 0..3) {
            val v = v0 - 1 + j
            var s = 0.0
            for (i in 0..3) {
                val u = u0 - 1 + i
                s += p[v * w + u] * cubic(x0 - u)
            }
            q += s * cubic(y0 - v)
        }
        return q
    }

    private fun bilinearPixel(p: FloatArray, w: Int, h: Int, xIn: Double, yIn: Double): Double {
        val x = xIn.coerceIn(0.0, w - 1.001)
        val y = yIn.coerceIn(0.0, h - 1.001)
        val xb = x.toInt()
        val yb = y.toInt()
        val xf = x - xb
        val yf = y - yb
        val x1 = (xb + 1).coerceAtMost(w - 1)
        val y1 = (yb + 1).coerceAtMost(h - 1)
        val ll = p[yb * w + xb]
        val lr = p[yb * w + x1]
        val ul = p[y1 * w + xb]
        val ur = p[y1 * w + x1]
        val upper = ul + xf * (ur - ul)
        val lower = ll + xf * (lr - ll)
        return lower + yf * (upper - lower)
    }

    // ---------------------------------------------------------------------
    // ij.plugin.filter.RankFilters: Median, Despeckle, Remove Outliers
    // ---------------------------------------------------------------------

    /** RankFilters.makeLineRadii: the circular kernel as (dy, dxMax) pairs. */
    private fun kernelOffsets(radiusIn: Double): Pair<IntArray, Int> {
        var radius = radiusIn
        if (radius >= 1.5 && radius < 1.75) radius = 1.75
        else if (radius >= 2.5 && radius < 2.85) radius = 2.85
        val r2 = (radius * radius).toInt() + 1
        val kRadius = sqrt(r2 + 1e-10).toInt()
        val offs = ArrayList<Int>()
        for (dy in -kRadius..kRadius) {
            val dx = sqrt(r2 - dy * dy + 1e-10).toInt()
            for (x in -dx..dx) { offs.add(dy); offs.add(x) }
        }
        return offs.toIntArray() to kRadius
    }

    private fun rank(
        pixels: FloatArray, width: Int, height: Int, radius: Double,
        apply: (value: Float, median: Float) -> Float
    ) {
        val (offs, _) = kernelOffsets(radius)
        val n = offs.size / 2
        val src = pixels.copyOf()
        val win = FloatArray(n)
        for (y in 0 until height) for (x in 0 until width) {
            for (i in 0 until n) {
                val yy = (y + offs[2 * i]).coerceIn(0, height - 1)
                val xx = (x + offs[2 * i + 1]).coerceIn(0, width - 1)
                win[i] = src[yy * width + xx]
            }
            win.sort()
            val median = win[n / 2]
            pixels[y * width + x] = apply(src[y * width + x], median)
        }
    }

    /** Process > Filters > Median. Despeckle is median with radius 1. */
    fun median(pixels: FloatArray, width: Int, height: Int, radius: Double) =
        rank(pixels, width, height, radius) { _, m -> m }

    /** Process > Noise > Remove Outliers, bright and dark. */
    fun removeOutliers(pixels: FloatArray, width: Int, height: Int, radius: Double, threshold: Float) =
        rank(pixels, width, height, radius) { v, m -> if (abs(v - m) > threshold) m else v }

    // ---------------------------------------------------------------------
    // ij.plugin.ContrastEnhancer: Enhance Contrast (stretch histogram) on RGB
    // ---------------------------------------------------------------------

    /**
     * ContrastEnhancer.getMinAndMax on the RGB brightness histogram ((r+g+b)/3),
     * then ColorProcessor.setMinAndMax applied to all three channels.
     */
    fun enhanceContrast(r: FloatArray, g: FloatArray, b: FloatArray, saturated: Double) {
        val histogram = IntArray(256)
        for (i in r.indices) {
            val v = ((clamp8(r[i]) + clamp8(g[i]) + clamp8(b[i])) / 3)
            histogram[v]++
        }
        val threshold = if (saturated > 0.0) (r.size * saturated / 200.0).toInt() else 0
        var i = -1
        var count = 0
        do { i++; count += histogram[i] } while (count <= threshold && i < 255)
        val hmin = i
        i = 256
        count = 0
        do { i--; count += histogram[i] } while (count <= threshold && i > 0)
        val hmax = i
        if (hmax <= hmin) return
        val lut = FloatArray(256) { v ->
            (((v - hmin).toDouble() / (hmax - hmin)) * 256).toInt().coerceIn(0, 255).toFloat()
        }
        for (p in r.indices) {
            r[p] = lut[clamp8(r[p])]
            g[p] = lut[clamp8(g[p])]
            b[p] = lut[clamp8(b[p])]
        }
    }

    private fun clamp8(v: Float): Int = v.roundToInt().coerceIn(0, 255)
}
