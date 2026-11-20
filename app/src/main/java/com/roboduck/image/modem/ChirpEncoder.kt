package com.roboduck.image.modem

import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

/** Turns bytes into a mono float waveform (range -1..1) at [Chirp.SAMPLE_RATE]. */
object ChirpEncoder {

    private const val AMP = 0.75f

    fun encode(bytes: ByteArray): FloatArray {
        require(bytes.size in 1..0x3FFFF) { "size out of range" }
        val out = Builder()

        // A little R2-D2 style "hello" before the data (the receiver ignores it).
        out.sweep(2300.0, 3600.0, 0.06, vibrato = 0.0)
        out.silence(0.02)
        out.sweep(3600.0, 2700.0, 0.07, vibrato = 120.0)
        out.silence(0.03)
        out.sweep(4300.0, 4300.0, 0.08, vibrato = 260.0)
        out.silence(0.02)
        out.sweep(2400.0, 6400.0, 0.13, vibrato = 60.0)
        out.silence(0.15)

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
        // Data in phrases: a "bweep" first, then the whistles, then a pause.
        val data2 = ArrayList<Int>()
        val groups = (cwCount + Chirp.GROUP - 1) / Chirp.GROUP
        for (g in 0 until groups) {
            val size = Chirp.groupSize(cwCount, g)
            for (col in 0 until Chirp.CW_LEN) for (row in 0 until size) {
                data2.add(codewords[g * Chirp.GROUP + row][col])
            }
        }
        val pauseList = ArrayList<Int>()
        repeat(symbols.size - 1) { pauseList.add(0) }
        pauseList.add(Chirp.FIRST_PAUSE)          // after the last header symbol
        var phraseOpen = false
        for (i in data2.indices) {
            if (!phraseOpen) {
                symbols.add(Chirp.PHRASE_SYNC)
                pauseList.add(0)
                phraseOpen = true
            }
            symbols.add(data2[i])
            val pause = Chirp.pauseAfter(i)
            pauseList.add(pause)
            if (pause > 0) phraseOpen = false
        }
        val pauses = pauseList.toIntArray()
        out.chirps(symbols, pauses)

        // A happy "done" whistle.
        out.silence(0.1)
        out.sweep(5200.0, 2600.0, 0.12, vibrato = 140.0)
        out.silence(0.02)
        out.sweep(3000.0, 3800.0, 0.05, vibrato = 0.0)
        out.sweep(3800.0, 2000.0, 0.14, vibrato = 90.0)
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

        fun sweep(f0: Double, f1: Double, sec: Double, vibrato: Double = 0.0) {
            val n = (sec * Chirp.SAMPLE_RATE).toInt()
            val fade = minOf(n / 4, 200)
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / n
                val f = f0 + (f1 - f0) * t + vibrato * sin(2 * PI * 38.0 * i / Chirp.SAMPLE_RATE)
                var env = 1.0
                if (i < fade) env = i.toDouble() / fade
                if (n - 1 - i < fade) env = (n - 1 - i).toDouble() / fade
                add((sin(phase) * env * AMP).toFloat())
                phase += 2 * PI * f / Chirp.SAMPLE_RATE
            }
        }

        /** Whistles back to back; [pauses] gives silence (samples) after each one. */
        fun chirps(symbols: List<Int>, pauses: IntArray) {
            var phase = 0.0
            var start = 0 // index of the first whistle in the current phrase
            for (i in symbols.indices) {
                val k = symbols[i]
                val phraseStart = i == start
                val phraseEnd = pauses[i] > 0 || i == symbols.size - 1
                for (n in 0 until Chirp.L) {
                    var env = 1.0
                    if (phraseStart && n < Chirp.FADE) env = n.toDouble() / Chirp.FADE
                    if (phraseEnd && Chirp.L - 1 - n < Chirp.FADE) env = (Chirp.L - 1 - n).toDouble() / Chirp.FADE
                    add((sin(phase) * env * AMP).toFloat())
                    phase += 2 * PI * Chirp.freq(k, n) / Chirp.SAMPLE_RATE
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                if (pauses[i] > 0) {
                    repeat(pauses[i]) { add(0f) }
                    start = i + 1
                }
            }
        }

        fun toArray(): FloatArray = buf.copyOf(size)
    }
}
