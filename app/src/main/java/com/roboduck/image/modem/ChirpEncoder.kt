package com.roboduck.image.modem

import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

/** Turns bytes into a mono float waveform (range -1..1) at [Chirp.SAMPLE_RATE]. */
object ChirpEncoder {

    private const val AMP = 0.85f

    fun encode(bytes: ByteArray): FloatArray {
        require(bytes.size in 1..0x3FFFF) { "size out of range" }
        val out = Builder()

        // Friendly "hello" before the data (receiver ignores it).
        out.sweep(2600.0, 4400.0, 0.07)
        out.silence(0.03)
        out.sweep(4400.0, 3000.0, 0.06)
        out.silence(0.03)
        out.sweep(3000.0, 5000.0, 0.05)
        out.silence(0.12)

        val symbols = ArrayList<Int>()
        repeat(Chirp.PREAMBLE) { symbols.add(0) }
        symbols.add(Chirp.SYNC_A)
        symbols.add(Chirp.SYNC_B)

        val crc = CRC32().apply { update(bytes) }.value
        val len = bytes.size
        val header = IntArray(Chirp.HEADER_DATA)
        header[0] = Chirp.MAGIC
        header[1] = (len shr 12) and 63
        header[2] = (len shr 6) and 63
        header[3] = len and 63
        for (i in 0 until 6) header[4 + i] = ((crc shr (30 - 6 * i)) and 63).toInt()
        val rs = ReedSolomon64(Chirp.PARITY)
        rs.encode(header).forEach { symbols.add(it) }

        val data = Chirp.bytesToSymbols(bytes)
        val cwCount = Chirp.codewordCount(len)
        val codewords = Array(cwCount) { c ->
            val block = IntArray(Chirp.DATA_PER_CW)
            for (i in 0 until Chirp.DATA_PER_CW) {
                val idx = c * Chirp.DATA_PER_CW + i
                if (idx < data.size) block[i] = data[idx]
            }
            rs.encode(block)
        }
        val groups = (cwCount + Chirp.GROUP - 1) / Chirp.GROUP
        for (g in 0 until groups) {
            val size = Chirp.groupSize(cwCount, g)
            for (col in 0 until Chirp.CW_LEN) for (row in 0 until size) {
                symbols.add(codewords[g * Chirp.GROUP + row][col])
            }
        }

        out.chirps(symbols)

        // Little "bye" trill.
        out.silence(0.08)
        out.sweep(4800.0, 3200.0, 0.05)
        out.sweep(3200.0, 4200.0, 0.04)
        out.sweep(4200.0, 2400.0, 0.09)
        out.silence(0.1)
        return out.toArray()
    }

    private class Builder {
        private var buf = FloatArray(Chirp.SAMPLE_RATE * 4)
        private var size = 0

        private fun add(v: Float) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size++] = v
        }

        fun silence(sec: Double) = repeat((sec * Chirp.SAMPLE_RATE).toInt()) { add(0f) }

        fun sweep(f0: Double, f1: Double, sec: Double) {
            val n = (sec * Chirp.SAMPLE_RATE).toInt()
            val fade = minOf(n / 4, 200)
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / n
                val f = f0 + (f1 - f0) * t
                var env = 1.0
                if (i < fade) env = i.toDouble() / fade
                if (n - 1 - i < fade) env = (n - 1 - i).toDouble() / fade
                add((sin(phase) * env * AMP).toFloat())
                phase += 2 * PI * f / Chirp.SAMPLE_RATE
            }
        }

        fun chirps(symbols: List<Int>) {
            var phase = 0.0
            val total = symbols.size * Chirp.L
            val fade = 220
            var i = 0
            for (k in symbols) {
                for (n in 0 until Chirp.L) {
                    var env = 1.0
                    if (i < fade) env = i.toDouble() / fade
                    if (total - 1 - i < fade) env = (total - 1 - i).toDouble() / fade
                    add((sin(phase) * env * AMP).toFloat())
                    phase += 2 * PI * Chirp.freq(k, n) / Chirp.SAMPLE_RATE
                    if (phase > 2 * PI) phase -= 2 * PI
                    i++
                }
            }
        }

        fun toArray(): FloatArray = buf.copyOf(size)
    }
}
