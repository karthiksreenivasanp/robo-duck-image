package com.roboduck.image.modem

import java.util.zip.CRC32

/**
 * Remembers the blocks of a picture that arrived correctly, so that if a
 * transfer ends with gaps, sending the same picture again only has to fill
 * the gaps. Keep one instance for as long as the receiving screen is open.
 */
class GapMemory {
    internal var key = -1L
    internal var blocks: Array<MutableList<Candidate>> = emptyArray()

    fun clear() {
        key = -1L
        blocks = emptyArray()
    }
}

/** One possible decoding of a block; lower [trust] = more trustworthy. */
internal class Candidate(val data: IntArray, val trust: Int)

/**
 * Streaming decoder. Feed it microphone samples; it finds the preamble,
 * locks onto note timing (re-locking at every phrase and tracking clock
 * drift), decodes the header and data, and reports through [Listener].
 */
class ChirpDecoder(
    private val listener: Listener,
    private val memory: GapMemory = GapMemory()
) {

    interface Listener {
        /** A transmission was found; [bytes] is the expected size. */
        fun onStart(bytes: Int)
        /** 0..1 fraction of the transmission received so far. */
        fun onProgress(fraction: Float)
        /** Bytes decoded so far (for a live preview). */
        fun onPartial(data: ByteArray)
        /** All data received. [ok] is false if the checksum did not match. */
        fun onDone(data: ByteArray, ok: Boolean)
        /** Signal faded out in the middle of a transmission. */
        fun onLost()
        /** Each note as it is heard (for the live display). */
        fun onWhistle(k: Int) {}
    }

    private enum class State { SEARCH, PRE, HEADER, DATA }

    private val t = Chirp.Templates()
    private val rs = ReedSolomon(Chirp.PARITY)
    private val rsHeader = ReedSolomon(Chirp.HEADER_PARITY)
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
    private var totalNotes = 0
    private var received = 0
    private var group = 0
    private var groupStart = 0          // index of the group's first data note in picture order
    private var groupSizeNow = 0        // blocks in the current group
    private var dataSymbols = IntArray(0)
    private var weakRun = 0
    private var needResync = false
    private var raw: Array<IntArray> = emptyArray()
    private val syncEnergies = DoubleArray(8)
    private var syncCount = 0
    private var wideNext = false

    // Confidence: recent note scores, and whether this phrase looks out of step.
    private val recent = DoubleArray(32)
    private var recentCount = 0
    private var phraseSuspect = false
    private var lastUnsure = false

    private fun typicalScore(): Double {
        val n = minOf(recentCount, recent.size)
        if (n < 8) return 0.0
        val sorted = recent.copyOf(n).also { it.sort() }
        return sorted[n / 2]
    }

    /** After a transfer: blocks taken from an earlier try, and blocks still missing. */
    var filledFromEarlier = 0
        private set
    var stillMissing = 0
        private set

    // Diagnostics for tests.
    var debugNotes: MutableList<Pair<Int, Double>>? = null
    var debugSearch: MutableList<Pair<Int, Double>>? = null
    var debugConf: MutableList<Double>? = null
    var debugResync: MutableList<Triple<Int, Int, Double>>? = null
    var debugSharp: MutableList<Double>? = null
    private var fixedSymbols = 0
    private var failedBlocks = 0
    fun stats() = "fixed=$fixedSymbols failedBlocks=$failedBlocks/$cwCount filledFromEarlier=$filledFromEarlier"

    fun feed(samples: FloatArray, count: Int = samples.size) {
        if (len + count > data.size) {
            data = data.copyOf(maxOf(data.size * 2, len + count))
        }
        System.arraycopy(samples, 0, data, len, count)
        len += count
        process()
    }

    fun reset() {
        state = State.SEARCH
        runK = -1
        runCount = 0
        collected.clear()
        confidence.clear()
    }

    private fun lookahead(): Int = Chirp.L + 160 +
        if (state == State.DATA && needResync) 2 * Chirp.RESYNC_RANGE else 0

    private fun process() {
        while (end >= pos + lookahead()) {
            when (state) {
                State.SEARCH -> search()
                State.PRE -> preamble()
                State.HEADER -> header()
                State.DATA -> dataStep()
            }
            discardBefore(pos - 2 * Chirp.PERIOD - 2 * Chirp.RESYNC_RANGE)
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
        if (off < 0 || off + Chirp.L > len) return 0.0
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
        debugSearch?.add(r.k to r.ratio)
        val strong = r.ratio > STRONG
        if (strong && r.k == runK) {
            runCount++
        } else {
            runK = r.k
            runCount = if (strong) 1 else 0
        }
        if (runCount >= 3) {
            // Seen out of step, the repeated opening note looks like a shifted note;
            // the shift tells roughly where the notes start. Then fine-tune.
            val s = pos - Chirp.STEP * runK
            val range = Chirp.GUARD + 2 * Chirp.STEP + 8
            var bestD = 0
            var bestE = -1.0
            for (d in -range..range) {
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
            pos += Chirp.PERIOD
        }
    }

    /** Reads the note at [pos], nudges timing toward the energy peak, then moves on by [step]. */
    private fun read(step: Int): Result {
        val r = analyze(pos)
        val early = energy(r.k, pos - 3)
        val late = energy(r.k, pos + 3)
        var adj = 0
        if (late > early * 1.15) adj = 1 else if (early > late * 1.15) adj = -1
        pos += step + adj
        listener.onWhistle(r.k)
        return r
    }

    private fun preamble() {
        val k = read(Chirp.PERIOD).k
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
        val lastNote = collected.size == Chirp.HEADER_LEN - 1
        collected.add(read(if (lastNote) Chirp.L + Chirp.FIRST_PAUSE else Chirp.PERIOD).k)
        if (collected.size < Chirp.HEADER_LEN) return
        val h = rsHeader.decode(collected.toIntArray())
        collected.clear()
        if (h == null || h[0] != Chirp.MAGIC) { reset(); return }
        byteLen = (h[1] shl 14) or (h[2] shl 7) or h[3]
        var c = 0L
        for (i in 0 until 5) c = (c shl 7) or h[4 + i].toLong()
        crc = c and 0xFFFFFFFFL
        if (byteLen == 0) { reset(); return }
        cwCount = Chirp.codewordCount(byteLen)
        totalNotes = cwCount * Chirp.CW_LEN
        received = 0
        group = 0
        groupStart = 0
        groupSizeNow = Chirp.groupSize(cwCount, 0)
        dataSymbols = IntArray(cwCount * Chirp.DATA_PER_CW)
        weakRun = 0
        fixedSymbols = 0
        failedBlocks = 0
        filledFromEarlier = 0
        stillMissing = 0
        confidence.clear()
        val key = (byteLen.toLong() shl 32) or crc
        if (memory.key != key || memory.blocks.size != cwCount) {
            memory.key = key
            memory.blocks = Array(cwCount) { ArrayList<Candidate>() }
        }
        raw = Array(cwCount) { IntArray(Chirp.DATA_PER_CW) }
        needResync = true
        syncCount = 0
        wideNext = false
        recentCount = 0
        phraseSuspect = false
        lastUnsure = false
        state = State.DATA
        listener.onStart(byteLen)
    }

    /** Finds the phrase-opening note near [pos] and steps past it, back in time with the sender. */
    private fun resync() {
        val range = if (wideNext) 2 * Chirp.RESYNC_RANGE else Chirp.RESYNC_RANGE
        val count = range / 2 + 1
        val es = DoubleArray(count)
        var maxE = 0.0
        for (i in 0 until count) {
            es[i] = energy(Chirp.PHRASE_SYNC, pos - range + 4 * i)
            if (es[i] > maxE) maxE = es[i]
        }
        // The phrase opener always comes right after a breath, so take the EARLIEST strong
        // match: a later match can be the phrase's next note (if it happens to be the same
        // note) or an echo, never something before the opener.
        var bestD = 0
        for (i in 0 until count) {
            if (es[i] >= 0.45 * maxE) {
                var j = i
                while (j + 1 < count && es[j + 1] > es[j]) j++   // climb to that match's peak
                bestD = -range + 4 * j
                break
            }
        }
        var bestE = energy(Chirp.PHRASE_SYNC, pos + bestD)
        val coarse = bestD
        for (f in coarse - 3..coarse + 3) {
            val e = energy(Chirp.PHRASE_SYNC, pos + f)
            if (e > bestE) { bestE = e; bestD = f }
        }
        // A real opener stands out sharply from everything around it; in loud noise nothing
        // does. If the best match doesn't stand out (or is far weaker than a usual opener),
        // it was probably drowned: keep to the expected timing, and look wider next time.
        val sortedEs = es.copyOf().also { it.sort() }
        val median = maxOf(sortedEs[sortedEs.size / 2], 1e-12)
        val sharp = bestE / median
        val usual = typicalSync()
        debugSharp?.add(sharp)
        if (sharp < SHARP || (usual > 0.0 && bestE < 0.3 * usual)) {
            bestD = 0
            wideNext = true
        } else {
            syncEnergies[syncCount % syncEnergies.size] = bestE
            syncCount++
            wideNext = false
        }
        listener.onWhistle(Chirp.PHRASE_SYNC)
        debugResync?.add(Triple(received, bestD, bestE / maxOf(maxE, 1e-12)))
        pos += bestD + Chirp.PERIOD
        needResync = false
        phraseSuspect = false
        lastUnsure = false
    }

    private fun typicalSync(): Double {
        val n = minOf(syncCount, syncEnergies.size)
        if (n < 3) return 0.0
        val sorted = syncEnergies.copyOf(n).also { it.sort() }
        return sorted[n / 2]
    }

    private fun dataStep() {
        if (needResync) { resync(); return }
        val index = received
        val pause = if (index < totalNotes - 1) Chirp.pauseAfter(index) else 0
        val r = read(if (pause > 0) Chirp.L + pause else Chirp.PERIOD)
        if (pause > 0) needResync = true
        debugNotes?.add(r.k to r.ratio)
        // A note much weaker than usual is "unsure". Two unsure notes in a row mean the
        // audio probably skipped: every later note of this phrase is out of step too, so
        // treat them all as unknown until the next phrase re-locks.
        val typical = typicalScore()
        val unsureNow = r.ratio < maxOf(UNSURE, 0.45 * typical)
        if (unsureNow && lastUnsure) phraseSuspect = true
        lastUnsure = unsureNow
        if (!unsureNow) { recent[recentCount % recent.size] = r.ratio; recentCount++ }
        val t = collected.size                      // position inside the current group
        collected.add(r.k)
        confidence.add(if (phraseSuspect || unsureNow) 0.0 else r.ratio)
        debugConf?.add(confidence.last())
        // The group's picture data arrives first and in picture order: show it right away.
        if (t < Chirp.DATA_PER_CW * groupSizeNow) dataSymbols[groupStart + t] = r.k
        received++
        weakRun = if (r.ratio < WEAK) weakRun + 1 else 0
        if (weakRun >= LOST_AFTER) {
            listener.onLost()
            reset()
            return
        }
        if (received % 8 == 0 || received == totalNotes) {
            listener.onProgress(received.toFloat() / totalNotes)
        }
        val needed = Chirp.dataSymbolCount(byteLen)
        if (collected.size < Chirp.CW_LEN * groupSizeNow) {
            if (received % PREVIEW_EVERY == 0) {
                val shown = minOf(groupStart + minOf(t + 1, Chirp.DATA_PER_CW * groupSizeNow), needed)
                listener.onPartial(Chirp.symbolsToBytes(dataSymbols, shown, byteLen))
            }
            return
        }

        // Group complete: repair each block, then put the repaired data back in place.
        val size = groupSizeNow
        for (r in 0 until size) {
            val b = group * Chirp.GROUP + r
            val cw = IntArray(Chirp.CW_LEN) { p -> collected[p * size + r] }
            // Clearly unsure notes become erasures (they cost half as much to repair).
            val unsure = (0 until Chirp.CW_LEN)
                .filter { p -> confidence[p * size + r] <= 0.0 }
                .toIntArray()
            val found = rs.decodeAll(cw, unsure)
            val list = memory.blocks[b]
            for ((d, trust) in found) {
                if (list.none { it.data.contentEquals(d) }) list.add(Candidate(d, trust))
            }
            list.sortBy { it.trust }
            cw.copyInto(raw[b], 0, 0, Chirp.DATA_PER_CW)
            if (found.isEmpty()) {
                failedBlocks++
                if (list.isNotEmpty()) filledFromEarlier++ else stillMissing++
            } else {
                val best = found.first().first
                for (i in 0 until Chirp.DATA_PER_CW) if (best[i] != cw[i]) fixedSymbols++
            }
            place(dataSymbols, b, list.firstOrNull()?.data ?: raw[b])
        }
        collected.clear()
        confidence.clear()
        groupStart += Chirp.DATA_PER_CW * size
        group++
        if (group * Chirp.GROUP < cwCount) groupSizeNow = Chirp.groupSize(cwCount, group)

        var bytes = Chirp.symbolsToBytes(dataSymbols, minOf(groupStart, needed), byteLen)
        if (received >= totalNotes) {
            var ok = checks(bytes)
            if (!ok) {
                // Some block may hold a wrong "repair": try the other possible decodings.
                val alt = searchAlternatives(needed)
                if (alt != null) { bytes = alt; ok = true }
            }
            if (ok) memory.clear()
            listener.onDone(bytes, ok)
            reset()
        } else {
            listener.onPartial(bytes)
        }
    }

    /** Writes block [b]'s data into picture order (see [Chirp.GROUP]). */
    private fun place(symbols: IntArray, b: Int, d: IntArray) {
        val g = b / Chirp.GROUP
        val r = b % Chirp.GROUP
        val size = Chirp.groupSize(cwCount, g)
        val start = g * Chirp.GROUP * Chirp.DATA_PER_CW
        for (p in 0 until Chirp.DATA_PER_CW) symbols[start + p * size + r] = d[p]
    }

    private fun checks(bytes: ByteArray) =
        bytes.size == byteLen && CRC32().apply { update(bytes) }.value == crc

    /** Tries combinations of alternative block decodings until the checksum matches. */
    private fun searchAlternatives(needed: Int): ByteArray? {
        val choices = memory.blocks.indices.filter { memory.blocks[it].size > 1 }
        if (choices.isEmpty()) return null
        val radix = choices.map { memory.blocks[it].size }
        var combos = 1L
        for (r in radix) { combos *= r; if (combos > MAX_TRIES) break }
        val tries = minOf(combos, MAX_TRIES.toLong())
        val pick = IntArray(choices.size)
        val symbols = IntArray(cwCount * Chirp.DATA_PER_CW)
        for (t in 1 until tries) {
            // next combination (mixed radix counter)
            var i = 0
            while (i < pick.size) {
                pick[i]++
                if (pick[i] < radix[i]) break
                pick[i] = 0
                i++
            }
            for (b in 0 until cwCount) {
                val ci = choices.indexOf(b)
                val list = memory.blocks[b]
                val d = if (ci >= 0) list[pick[ci]].data else list.firstOrNull()?.data ?: raw[b]
                place(symbols, b, d)
            }
            val bytes = Chirp.symbolsToBytes(symbols, needed, byteLen)
            if (checks(bytes)) return bytes
        }
        return null
    }

    companion object {
        private const val MAX_TRIES = 4096
        private const val PREVIEW_EVERY = 32
        private const val SHARP = 18.0
        private const val STRONG = 5.5
        private const val WEAK = 3.0
        private const val UNSURE = 6.0
        private const val LOST_AFTER = 40
    }
}
