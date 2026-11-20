package com.roboduck.image.modem

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Chirp spread spectrum parameters shared by sender and receiver.
 *
 * Each symbol is a 23 ms upward pitch sweep across ~2.1-4.9 kHz, cyclically
 * shifted by one of 64 offsets, so each chirp carries 6 bits. Back to back
 * they sound like a small robot chattering.
 */
object Chirp {
    const val SAMPLE_RATE = 44100
    const val BITS = 6
    const val N = 1 shl BITS              // 64 distinct chirps
    const val L = 1024                    // samples per chirp
    const val STEP = L / N                // 16 samples per shift
    const val BANDWIDTH = SAMPLE_RATE.toDouble() * N / L // 2756.25 Hz
    const val CENTER = 3500.0
    const val F_LOW = CENTER - BANDWIDTH / 2

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

    /** Instantaneous frequency of chirp [k] at sample [n]. */
    fun freq(k: Int, n: Int): Double {
        val pos = ((n + k * STEP) % L).toDouble() / L
        return F_LOW + BANDWIDTH * pos
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
