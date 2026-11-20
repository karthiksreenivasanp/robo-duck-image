package com.roboduck.image.modem

import java.util.zip.CRC32

/**
 * Streaming decoder. Feed it microphone samples; it finds the preamble,
 * locks onto symbol timing (and tracks clock drift), decodes the header
 * and data, and reports through [Listener].
 */
class ChirpDecoder(private val listener: Listener) {

    interface Listener {
        /** A transmission was found; [bytes] is the expected image size. */
        fun onStart(bytes: Int)
        /** 0..1 fraction of the transmission received so far. */
        fun onProgress(fraction: Float)
        /** Bytes decoded so far (for a live preview). */
        fun onPartial(data: ByteArray)
        /** All data received. [ok] is false if the checksum did not match. */
        fun onDone(data: ByteArray, ok: Boolean)
        /** Signal faded out in the middle of a transmission. */
        fun onLost()
        /** Each whistle as it is heard (for the live display). */
        fun onWhistle(k: Int) {}
    }

    private enum class State { SEARCH, PRE, HEADER, DATA }

    private val t = Chirp.Templates()
    private val rs = ReedSolomon64(Chirp.PARITY)
    private var data = FloatArray(1 shl 17)
    private var base = 0
    private var len = 0
    private val end get() = base + len

    private var state = State.SEARCH
    private var pos = 0

    // search
    private var runK = -1
    private var runCount = 0

    // preamble
    private var zeros = 0
    private var sawA = false

    // header/data
    private val collected = ArrayList<Int>()
    private val confidence = ArrayList<Double>()
    private var byteLen = 0
    private var crc = 0L
    private var cwCount = 0
    private var totalSymbols = 0
    private var received = 0
    private var group = 0
    private var dataSymbols = IntArray(0)
    private var dataCount = 0
    private var weakRun = 0
    private var needResync = false

    fun feed(samples: FloatArray, count: Int = samples.size) {
        if (len + count > data.size) {
            data = data.copyOf(maxOf(data.size * 2, len + count))
        }
        System.arraycopy(samples, 0, data, len, count)
        len += count
        process()
    }

    // Diagnostics: corrected symbols and failed codewords in the last transmission.
    private var fixedSymbols = 0
    private var failedCodewords = 0
    private var totalCodewords = 0
    fun stats() = "fixed=$fixedSymbols failedCW=$failedCodewords/$totalCodewords"

    fun reset() {
        confidence.clear()
        state = State.SEARCH
        runK = -1
        runCount = 0
        collected.clear()
    }

    private fun process() {
        while (end >= pos + Chirp.L + 8 + (if (state == State.DATA && needResync) Chirp.RESYNC_RANGE + 8 else 0)) {
            when (state) {
                State.SEARCH -> search()
                State.PRE -> preamble()
                State.HEADER -> header()
                State.DATA -> dataStep()
            }
            discardBefore(pos - 2 * Chirp.L)
        }
    }

    private fun discardBefore(abs: Int) {
        val drop = minOf(abs - base, len)
        if (drop > 0 && drop > len / 2) {
            System.arraycopy(data, drop, data, 0, len - drop)
            base += drop
            len -= drop
        }
    }

    private fun energy(k: Int, start: Int): Double {
        val off = start - base
        val tb = k * Chirp.L
        val c = t.cosT
        val s = t.sinT
        var re = 0.0
        var im = 0.0
        for (n in 0 until Chirp.L) {
            val x = data[off + n]
            re += x * c[tb + n]
            im += x * s[tb + n]
        }
        return re * re + im * im
    }

    private class Result(val k: Int, val ratio: Double)

    private fun analyze(start: Int): Result {
        var best = -1
        var bestE = -1.0
        var sum = 0.0
        for (k in 0 until Chirp.N) {
            val e = energy(k, start)
            sum += e
            if (e > bestE) { bestE = e; best = k }
        }
        val rest = (sum - bestE) / (Chirp.N - 1)
        val ratio = if (rest <= 1e-12) 0.0 else bestE / rest
        return Result(best, ratio)
    }

    private fun search() {
        val r = analyze(pos)
        // The preamble is rising whistles; seen out of step they still look rising.
        val strong = r.ratio > STRONG && r.k < Chirp.SHIFTS
        if (strong && r.k == runK) {
            runCount++
        } else {
            runK = r.k
            runCount = if (strong) 1 else 0
        }
        if (runCount >= 3) {
            val s = pos - Chirp.STEP * runK
            var bestD = 0
            var bestE = -1.0
            for (d in -(Chirp.STEP / 2 + 4)..(Chirp.STEP / 2 + 4)) {
                val e = energy(0, s + d)
                if (e > bestE) { bestE = e; bestD = d }
            }
            pos = s + bestD
            state = State.PRE
            zeros = 0
            sawA = false
            runCount = 0
            runK = -1
        } else {
            pos += Chirp.L
        }
    }

    /** Reads the symbol at [pos], nudges timing toward the energy peak, advances. */
    private fun readSymbol(): Result {
        val r = analyze(pos)
        val early = energy(r.k, pos - 3)
        val late = energy(r.k, pos + 3)
        var adj = 0
        if (late > early * 1.15) adj = 1 else if (early > late * 1.15) adj = -1
        pos += Chirp.L + adj
        return r
    }

    private fun preamble() {
        val k = readSymbol().k
        listener.onWhistle(k)
        when {
            k == 0 && !sawA -> {
                zeros++
                if (zeros > Chirp.PREAMBLE + 4) reset()
            }
            k == Chirp.SYNC_A && !sawA -> sawA = true
            k == Chirp.SYNC_B -> {
                state = State.HEADER
                collected.clear()
            }
            else -> reset()
        }
    }

    private fun header() {
        val hk = readSymbol().k
        listener.onWhistle(hk)
        collected.add(hk)
        if (collected.size < Chirp.HEADER_LEN) return
        val h = rs.decode(collected.toIntArray())
        collected.clear()
        if (h == null || h[0] != Chirp.MAGIC) { reset(); return }
        byteLen = (h[1] shl 12) or (h[2] shl 6) or h[3]
        var c = 0L
        for (i in 0 until 6) c = (c shl 6) or h[4 + i].toLong()
        crc = c and 0xFFFFFFFFL
        if (byteLen == 0) { reset(); return }
        cwCount = Chirp.codewordCount(byteLen)
        totalSymbols = cwCount * Chirp.CW_LEN
        received = 0
        group = 0
        dataSymbols = IntArray(cwCount * Chirp.DATA_PER_CW)
        dataCount = 0
        weakRun = 0
        pos += Chirp.FIRST_PAUSE
        needResync = true
        state = State.DATA
        fixedSymbols = 0; failedCodewords = 0; totalCodewords = 0
        listener.onStart(byteLen)
    }

    /** Finds the phrase-opening "bweep" near [pos] and steps past it, back in time with the sender. */
    private fun resync() {
        var bestD = 0
        var bestE = -1.0
        var d = -Chirp.RESYNC_RANGE
        while (d <= Chirp.RESYNC_RANGE) {
            val e = energy(Chirp.PHRASE_SYNC, pos + d)
            if (e > bestE) { bestE = e; bestD = d }
            d += 4
        }
        val coarse = bestD
        for (f in coarse - 3..coarse + 3) {
            val e = energy(Chirp.PHRASE_SYNC, pos + f)
            if (e > bestE) { bestE = e; bestD = f }
        }
        pos += bestD + Chirp.L
        needResync = false
    }

    private fun dataStep() {
        if (needResync) { resync(); return }
        val r = readSymbol()
        listener.onWhistle(r.k)
        collected.add(r.k)
        confidence.add(r.ratio)
        received++
        val pause = Chirp.pauseAfter(received - 1)
        if (pause > 0) { pos += pause; needResync = true }
        weakRun = if (r.ratio < WEAK) weakRun + 1 else 0
        if (weakRun >= LOST_AFTER) {
            listener.onLost()
            reset()
            return
        }
        if (received % 8 == 0 || received == totalSymbols) {
            listener.onProgress(received.toFloat() / totalSymbols)
        }
        val size = Chirp.groupSize(cwCount, group)
        if (collected.size < size * Chirp.CW_LEN) return

        for (row in 0 until size) {
            val cw = IntArray(Chirp.CW_LEN) { col -> collected[col * size + row] }
            // Least certain whistles first, as erasures (only those clearly unsure).
            val unsure = (0 until Chirp.CW_LEN)
                .filter { col -> confidence[col * size + row] < UNSURE }
                .sortedBy { col -> confidence[col * size + row] }
                .take(Chirp.PARITY)
                .toIntArray()
            val dec = rs.decode(cw, unsure)
            totalCodewords++
            if (dec == null) failedCodewords++
            else for (i in 0 until Chirp.DATA_PER_CW) if (dec[i] != cw[i]) fixedSymbols++
            val fixed = dec ?: cw.copyOf(Chirp.DATA_PER_CW)
            fixed.copyInto(dataSymbols, dataCount)
            dataCount += Chirp.DATA_PER_CW
        }
        collected.clear()
        confidence.clear()
        group++

        val needed = Chirp.dataSymbolCount(byteLen)
        val bytes = Chirp.symbolsToBytes(dataSymbols, minOf(dataCount, needed), byteLen)
        if (received >= totalSymbols) {
            val ok = bytes.size == byteLen &&
                CRC32().apply { update(bytes) }.value == crc
            listener.onDone(bytes, ok)
            reset()
        } else {
            listener.onPartial(bytes)
        }
    }

    companion object {
        private const val STRONG = 7.0
        private const val WEAK = 3.0
        private const val UNSURE = 4.0
        private const val LOST_AFTER = 40
    }
}
