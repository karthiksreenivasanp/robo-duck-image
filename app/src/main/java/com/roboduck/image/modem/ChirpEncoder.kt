package com.roboduck.image.modem

import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

/** Turns bytes into a mono float waveform (range -1..1) at [Chirp.SAMPLE_RATE]. */
object ChirpEncoder {

    /** Comfortable level: loud enough across a table, not shouty. */
    private const val AMP = 0.6f

    fun encode(bytes: ByteArray): FloatArray {
        require(bytes.size in 1..0x1FFFFF) { "size out of range" }
        val out = Builder()

        // A little R2-D2 style "hello" (the receiver ignores it).
        out.sweep(1900.0, 3300.0, 0.07)
        out.silence(0.03)
        out.sweep(3300.0, 2500.0, 0.06, vibrato = 110.0, vibRate = 22.0)
        out.silence(0.03)
        out.sweep(2700.0, 2700.0, 0.09, vibrato = 320.0, vibRate = 34.0)
        out.silence(0.03)
        out.sweep(2100.0, 4300.0, 0.14, vibrato = 60.0, vibRate = 18.0)
        out.silence(0.2)

        // Opening run: preamble, sync pair, header (then a breath).
        val notes = ArrayList<Int>()
        val pauses = ArrayList<Int>()
        fun note(k: Int, pause: Int = 0) { notes.add(k); pauses.add(pause) }

        repeat(Chirp.PREAMBLE) { note(0) }
        note(Chirp.SYNC_A)
        note(Chirp.SYNC_B)

        val crc = CRC32().apply { update(bytes) }.value
        val len = bytes.size
        val header = IntArray(Chirp.HEADER_DATA)
        header[0] = Chirp.MAGIC
        header[1] = (len shr 14) and 127
        header[2] = (len shr 7) and 127
        header[3] = len and 127
        for (i in 0 until 5) header[4 + i] = ((crc shr (28 - 7 * i)) and 127).toInt()
        val headerCw = ReedSolomon(Chirp.HEADER_PARITY).encode(header)
        headerCw.forEachIndexed { i, k -> note(k, if (i == headerCw.size - 1) Chirp.FIRST_PAUSE else 0) }

        // Data: groups of up to 4 RS(127,99) blocks, sent across the blocks note by note
        // (picture data first, in picture order, then the repair notes), as phrases that
        // each open with the sync note.
        val rs = ReedSolomon(Chirp.PARITY)
        val data = Chirp.bytesToSymbols(bytes)
        val cwCount = Chirp.codewordCount(len)
        val stream = ArrayList<Int>(cwCount * Chirp.CW_LEN)
        var g0 = 0
        var g = 0
        while (g * Chirp.GROUP < cwCount) {
            val size = Chirp.groupSize(cwCount, g)
            val blocks = Array(size) { r ->
                val block = IntArray(Chirp.DATA_PER_CW) { p ->
                    val idx = g0 + p * size + r
                    if (idx < data.size) data[idx] else 0
                }
                rs.encode(block)
            }
            for (p in 0 until Chirp.CW_LEN) for (r in 0 until size) stream.add(blocks[r][p])
            g0 += Chirp.DATA_PER_CW * size
            g++
        }
        var phraseOpen = false
        for (i in stream.indices) {
            if (!phraseOpen) { note(Chirp.PHRASE_SYNC); phraseOpen = true }
            val p = Chirp.pauseAfter(i)
            note(stream[i], if (i == stream.size - 1) 0 else p)
            if (p > 0) phraseOpen = false
        }
        out.notes(notes, pauses.toIntArray())

        // A happy little "done".
        out.silence(0.15)
        out.sweep(4300.0, 2500.0, 0.11, vibrato = 120.0, vibRate = 24.0)
        out.silence(0.02)
        out.sweep(2700.0, 3400.0, 0.06)
        out.sweep(3400.0, 1700.0, 0.16, vibrato = 80.0, vibRate = 16.0)
        out.silence(0.12)
        return out.toArray()
    }

    private class Builder {
        private var buf = FloatArray(Chirp.SAMPLE_RATE * 8)
        private var size = 0

        private fun add(v: Float) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size++] = v
        }

        fun silence(sec: Double) = repeat((sec * Chirp.SAMPLE_RATE).toInt()) { add(0f) }

        fun silenceSamples(n: Int) = repeat(n) { add(0f) }

        /** A decorative whistle: pitch glides f0 -> f1 with an optional slow warble. */
        fun sweep(f0: Double, f1: Double, sec: Double, vibrato: Double = 0.0, vibRate: Double = 20.0) {
            val n = (sec * Chirp.SAMPLE_RATE).toInt()
            val fade = minOf(n / 4, 300)
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / n
                val ease = t * t * (3 - 2 * t)
                val f = f0 + (f1 - f0) * ease + vibrato * sin(2 * PI * vibRate * i / Chirp.SAMPLE_RATE)
                var env = 1.0
                if (i < fade) env = i.toDouble() / fade
                if (n - 1 - i < fade) env = (n - 1 - i).toDouble() / fade
                add((sin(phase) * env * AMP).toFloat())
                phase += 2 * PI * f / Chirp.SAMPLE_RATE
            }
        }

        /**
         * Data notes. Inside a phrase each note is followed by a short smooth
         * pitch glide to the next one; [pauses] gives the breath after a note
         * (and ends the phrase).
         */
        fun notes(symbols: List<Int>, pauses: IntArray) {
            var phase = 0.0
            var phraseStart = true
            for (i in symbols.indices) {
                val k = symbols[i]
                val last = i == symbols.size - 1
                val phraseEnd = last || pauses[i] > 0
                for (n in 0 until Chirp.L) {
                    var env = 1.0
                    if (phraseStart && n < Chirp.FADE) env = n.toDouble() / Chirp.FADE
                    if (phraseEnd && Chirp.L - 1 - n < Chirp.FADE) env = (Chirp.L - 1 - n).toDouble() / Chirp.FADE
                    add((sin(phase) * env * AMP).toFloat())
                    phase += 2 * PI * Chirp.freq(k, n) / Chirp.SAMPLE_RATE
                }
                if (phraseEnd) {
                    if (!last) silenceSamples(pauses[i])
                    phase = 0.0
                    phraseStart = true
                } else {
                    val f0 = Chirp.freq(k, Chirp.L)
                    val f1 = Chirp.edgeFreq(symbols[i + 1])
                    for (g in 0 until Chirp.GUARD) {
                        val t = g.toDouble() / Chirp.GUARD
                        val f = f0 + (f1 - f0) * t * t * (3 - 2 * t)
                        add((sin(phase) * AMP).toFloat())
                        phase += 2 * PI * f / Chirp.SAMPLE_RATE
                    }
                    phraseStart = false
                }
                if (phase > 2 * PI) phase -= 2 * PI * Math.floor(phase / (2 * PI))
            }
        }

        fun toArray(): FloatArray = buf.copyOf(size)
    }
}
