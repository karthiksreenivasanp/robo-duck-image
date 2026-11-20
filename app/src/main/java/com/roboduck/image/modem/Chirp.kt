package com.roboduck.image.modem

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sound design and framing shared by sender and receiver.
 *
 * Each note is a 23 ms "wee-oo" swoop: the pitch rises and falls once across
 * 1.4-4.8 kHz (a triangle shape) with a little warble. A note starts at one
 * of 128 points of that swoop, which carries 7 bits. Notes are joined by
 * 2 ms pitch glides, so a phrase is one smooth, click-free warble, and the
 * phrases are separated by short breaths, like R2-D2 talking. Every phrase
 * opens with the same note ([PHRASE_SYNC]) so the receiver can get back in
 * step if either phone's audio skipped for a moment.
 */
object Chirp {
    const val SAMPLE_RATE = 44100
    const val BITS = 7
    const val N = 1 shl BITS              // 128 different notes
    const val L = 1024                    // samples per note (23.2 ms)
    const val GUARD = 96                  // samples of pitch glide between notes (2.2 ms)
    const val PERIOD = L + GUARD
    const val STEP = L / N                // 8 samples between neighbouring start points
    const val F_LOW = 1400.0
    const val BANDWIDTH = 3400.0          // swoops between 1.4 and 4.8 kHz
    private const val VIB_DEPTH = 80.0    // Hz of warble
    private const val VIB_RATE = 86.0     // two warbles per note

    // Phrases ("sentences"): note counts and the breath after each, cycling.
    private val PHRASE = intArrayOf(12, 20, 9, 16, 24, 11, 18, 14, 22, 10)
    private val PAUSE = intArrayOf(4600, 6400, 4000, 5600, 7200, 4400, 6000, 5000, 6800, 4200)
    private val PHRASE_END = IntArray(PHRASE.size).also { var c = 0; for (i in PHRASE.indices) { c += PHRASE[i]; it[i] = c } }
    private val CYCLE = PHRASE.sum()
    const val FADE = 80                   // samples of fade at each phrase edge (1.8 ms)

    // Framing
    const val PREAMBLE = 10
    const val SYNC_A = 37
    const val SYNC_B = 90
    const val MAGIC = 0x55
    const val PHRASE_SYNC = 0             // the note that opens every phrase
    const val FIRST_PAUSE = 4000          // breath between the header and the first phrase
    const val RESYNC_RANGE = 1600         // how far (samples) the receiver looks for a phrase start

    // Error correction: RS(127,99) over GF(128): fixes 14 wrong or 28 unsure notes per block.
    const val PARITY = 28
    const val DATA_PER_CW = ReedSolomon.ORDER - PARITY    // 99
    const val CW_LEN = ReedSolomon.ORDER                  // 127
    /**
     * Blocks are sent 4 at a time, note by note across the 4 (so a burst of noise is
     * spread thin over all 4), with the data placed so that it still arrives in
     * picture order: data note j of a group belongs to block j % size, slot j / size.
     */
    const val GROUP = 4
    const val HEADER_DATA = 9
    const val HEADER_PARITY = 16
    const val HEADER_LEN = HEADER_DATA + HEADER_PARITY    // 25

    /** Instantaneous pitch of note [k] at sample [n] (0..L). */
    fun freq(k: Int, n: Int): Double {
        val u = n.toDouble() / L + k.toDouble() / N
        val f = u - Math.floor(u)
        val tri = 1.0 - Math.abs(2.0 * f - 1.0)
        return F_LOW + BANDWIDTH * tri + VIB_DEPTH * sin(2 * PI * VIB_RATE * n / SAMPLE_RATE)
    }

    /** Pitch at the start (and, since the swoop is periodic, the end) of note [k]. */
    fun edgeFreq(k: Int): Double = freq(k, 0)

    /** Breath (in samples) after data note number [index], or 0 inside a phrase. */
    fun pauseAfter(index: Int): Int {
        val p = index % CYCLE + 1
        for (i in PHRASE_END.indices) if (PHRASE_END[i] == p) return PAUSE[i]
        return 0
    }

    /** Correlation templates (cos and sin) for every note, phase starting at 0. */
    class Templates {
        val cosT = FloatArray(N * L)
        val sinT = FloatArray(N * L)

        init {
            for (k in 0 until N) {
                var phase = 0.0
                for (n in 0 until L) {
                    cosT[k * L + n] = cos(phase).toFloat()
                    sinT[k * L + n] = sin(phase).toFloat()
                    phase += 2 * PI * freq(k, n) / SAMPLE_RATE
                }
            }
        }
    }

    /** Seconds of sound for the data part of a [byteLen]-byte picture (for "time left"). */
    fun dataSeconds(byteLen: Int): Double {
        val n = codewordCount(byteLen) * CW_LEN
        var samples = FIRST_PAUSE.toLong() + PERIOD       // first breath + first phrase note
        for (i in 0 until n) {
            val p = pauseAfter(i)
            samples += if (p > 0 && i < n - 1) (L + p + PERIOD).toLong() else PERIOD.toLong()
        }
        return samples.toDouble() / SAMPLE_RATE
    }

    fun groupSize(cwCount: Int, group: Int): Int = minOf(GROUP, cwCount - group * GROUP)

    fun dataSymbolCount(byteLen: Int): Int = (byteLen * 8 + BITS - 1) / BITS

    fun codewordCount(byteLen: Int): Int =
        (dataSymbolCount(byteLen) + DATA_PER_CW - 1) / DATA_PER_CW

    fun bytesToSymbols(bytes: ByteArray): IntArray {
        val out = IntArray(dataSymbolCount(bytes.size))
        var acc = 0
        var bits = 0
        var idx = 0
        for (b in bytes) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= BITS) {
                bits -= BITS
                out[idx++] = (acc shr bits) and (N - 1)
            }
            acc = acc and ((1 shl bits) - 1)
        }
        if (bits > 0) out[idx] = (acc shl (BITS - bits)) and (N - 1)
        return out
    }

    fun symbolsToBytes(symbols: IntArray, count: Int, byteLen: Int): ByteArray {
        val out = ByteArray(byteLen)
        var acc = 0
        var bits = 0
        var idx = 0
        for (i in 0 until count) {
            if (idx >= byteLen) break
            acc = (acc shl BITS) or (symbols[i] and (N - 1))
            bits += BITS
            if (bits >= 8) {
                bits -= 8
                out[idx++] = (acc shr bits).toByte()
                acc = acc and ((1 shl bits) - 1)
            }
        }
        return if (idx == byteLen) out else out.copyOf(idx)
    }
}
