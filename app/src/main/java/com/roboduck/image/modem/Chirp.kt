package com.roboduck.image.modem

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sound design and framing shared by sender and receiver.
 *
 * Each symbol is a 23 ms whistle that sweeps up or down across 1.7-5.9 kHz
 * with a little warble (vibrato), starting at one of 32 points of the sweep:
 * 1 bit for the direction + 5 bits for the start point = 6 bits per whistle.
 * Data is sent in short phrases of 5-13 whistles separated by little pauses,
 * which gives the R2-D2 style "chatter". Every phrase starts with the same
 * rising "bweep" ([PHRASE_SYNC]); the receiver uses it to get back in step
 * if either phone's audio skipped or stalled for a moment.
 */
object Chirp {
    const val SAMPLE_RATE = 44100
    const val BITS = 6
    const val N = 1 shl BITS              // 64 distinct chirps
    const val L = 1024                    // samples per chirp
    const val SHIFTS = 32                 // start points per direction
    const val STEP = L / SHIFTS           // 32 samples per shift
    const val F_LOW = 1700.0
    const val BANDWIDTH = 4200.0          // sweeps 1.7 kHz <-> 5.9 kHz
    const val F_HIGH = F_LOW + BANDWIDTH
    private const val VIB_DEPTH = 90.0    // Hz of warble
    private const val VIB_RATE = 86.0     // warbles per second (2 per whistle)

    // Phrases: whistle counts and pause lengths (samples) cycle through these.
    private val PHRASE = intArrayOf(7, 11, 5, 9, 13, 6, 10, 8)
    private val PAUSE = intArrayOf(1300, 2400, 1700, 2900, 1500, 2100, 2600, 1900)
    private val PHRASE_END = IntArray(PHRASE.size).also { var c = 0; for (i in PHRASE.indices) { c += PHRASE[i]; it[i] = c } }
    private val CYCLE = PHRASE.sum()
    const val FADE = 140                  // samples of fade at each phrase edge
    const val PHRASE_SYNC = 0             // the "bweep" that opens every phrase
    const val FIRST_PAUSE = 1500          // pause between the header and the first phrase
    const val RESYNC_RANGE = 960          // how far (samples) the receiver looks for a phrase start

    // Framing
    const val PREAMBLE = 10
    const val SYNC_A = 21
    const val SYNC_B = 42
    const val MAGIC = 0x2A

    // Error correction: RS(63,47) over GF(64), 8 codewords interleaved per group
    const val PARITY = 16
    const val DATA_PER_CW = ReedSolomon64.ORDER - PARITY  // 47
    const val CW_LEN = ReedSolomon64.ORDER                // 63
    const val GROUP = 8
    const val HEADER_DATA = 10
    const val HEADER_LEN = HEADER_DATA + PARITY           // 26

    /** Instantaneous frequency of whistle [k] at sample [n]. */
    fun freq(k: Int, n: Int): Double {
        val down = k >= SHIFTS
        val shift = k and (SHIFTS - 1)
        val pos = ((n + shift * STEP) % L).toDouble() / L
        val base = if (down) F_HIGH - BANDWIDTH * pos else F_LOW + BANDWIDTH * pos
        return base + VIB_DEPTH * sin(2 * PI * VIB_RATE * n / SAMPLE_RATE)
    }

    /** Pause (in samples) after data whistle number [index], or 0 inside a phrase. */
    fun pauseAfter(index: Int): Int {
        val p = index % CYCLE + 1
        for (i in PHRASE_END.indices) if (PHRASE_END[i] == p) return PAUSE[i]
        return 0
    }

    /** Correlation templates (cos and sin) for every chirp, phase starting at 0. */
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

    /** Seconds of whistling for the data part of a [byteLen]-byte picture (for "time left"). */
    fun dataSeconds(byteLen: Int): Double {
        val n = codewordCount(byteLen) * CW_LEN
        var samples = FIRST_PAUSE.toLong() + L          // first pause + first "bweep"
        for (i in 0 until n) {
            samples += L
            val p = pauseAfter(i)
            if (p > 0 && i < n - 1) samples += p + L     // pause + next phrase's "bweep"
        }
        return samples.toDouble() / SAMPLE_RATE
    }

    fun dataSymbolCount(byteLen: Int): Int = (byteLen * 8 + BITS - 1) / BITS

    fun codewordCount(byteLen: Int): Int =
        (dataSymbolCount(byteLen) + DATA_PER_CW - 1) / DATA_PER_CW

    fun groupSize(cwCount: Int, group: Int): Int =
        minOf(GROUP, cwCount - group * GROUP)

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
