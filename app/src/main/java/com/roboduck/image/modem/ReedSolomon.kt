package com.roboduck.image.modem

/**
 * Reed-Solomon codec over GF(128) (7-bit symbols), so one whistle maps to
 * exactly one code symbol. Primitive polynomial x^7 + x^3 + 1.
 * Codeword length (data + parity) must be <= 127.
 */
class ReedSolomon(private val nsym: Int) {

    private val generator: IntArray

    init {
        var g = intArrayOf(1)
        for (i in 0 until nsym) g = polyMul(g, intArrayOf(1, pow(2, i)))
        generator = g
    }

    fun encode(data: IntArray): IntArray {
        require(data.size + nsym <= ORDER) { "codeword too long" }
        val out = IntArray(data.size + nsym)
        data.copyInto(out)
        for (i in data.indices) {
            val coef = out[i]
            if (coef != 0) {
                for (j in 1 until generator.size) {
                    out[i + j] = out[i + j] xor mul(generator[j], coef)
                }
            }
        }
        data.copyInto(out)
        return out
    }

    /**
     * Returns corrected data symbols, or null if uncorrectable.
     * [erasures] are positions known to be unreliable; each costs one parity
     * symbol instead of two, so up to [nsym] erasures (or [nsym]/2 errors) can be fixed.
     */
    fun decode(codeword: IntArray, erasures: IntArray = IntArray(0)): IntArray? {
        val result = decodeWith(codeword, erasures)
        if (result != null || erasures.isEmpty()) return result
        return decodeWith(codeword, IntArray(0))
    }

    /**
     * All plausible decodings, most trustworthy first, as (data, trust) with
     * lower trust = better: errors-only decoding (almost never wrong), then
     * using all [erasures], then using only the least certain half of them.
     * Heavy erasure use leaves little room to catch a wrong "repair", so the
     * caller should confirm with a checksum when it can.
     */
    fun decodeAll(codeword: IntArray, erasures: IntArray): List<Pair<IntArray, Int>> {
        val out = ArrayList<Pair<IntArray, Int>>()
        fun addIf(d: IntArray?, trust: Int) {
            if (d != null && out.none { it.first.contentEquals(d) }) out.add(d to trust)
        }
        addIf(decodeWith(codeword, IntArray(0)), 0)
        if (erasures.isNotEmpty()) {
            addIf(decodeWith(codeword, erasures), 1)
            if (erasures.size > 1) addIf(decodeWith(codeword, erasures.copyOf(erasures.size / 2)), 2)
        }
        return out
    }

    private fun decodeWith(codeword: IntArray, erasuresIn: IntArray): IntArray? {
        val erasures = if (erasuresIn.size > nsym - MARGIN) erasuresIn.copyOf(nsym - MARGIN) else erasuresIn
        val msg = codeword.copyOf()
        for (e in erasures) msg[e] = 0
        val synd = syndromes(msg)
        if (synd.all { it == 0 }) return msg.copyOf(msg.size - nsym)
        return try {
            val fsynd = forneySyndromes(synd, erasures, msg.size)
            val errLoc = findErrorLocator(fsynd, erasures.size)
            val errPos = findErrors(errLoc.reversedArray(), msg.size) ?: return null
            // With erasures, keep a few parity symbols unused as a safety margin: using
            // all of them would always "succeed", even on a block that is beyond repair.
            if (erasures.isNotEmpty() && 2 * errPos.size + erasures.size > nsym - MARGIN) return null
            val all = (erasures.toList() + errPos.toList()).distinct().toIntArray()
            val fixed = correctErrata(msg, synd, all)
            if (syndromes(fixed).any { it != 0 }) null else fixed.copyOf(fixed.size - nsym)
        } catch (e: IllegalStateException) {
            null
        }
    }

    /** Syndromes with the erasures' contribution removed (leading 0 kept, like [syndromes]). */
    private fun forneySyndromes(synd: IntArray, erasures: IntArray, n: Int): IntArray {
        val f = synd.copyOfRange(1, synd.size)
        for (p in erasures) {
            val x = pow(2, n - 1 - p)
            for (j in 0 until f.size - 1) f[j] = mul(f[j], x) xor f[j + 1]
        }
        return intArrayOf(0) + f
    }

    private fun syndromes(msg: IntArray): IntArray {
        val s = IntArray(nsym + 1)
        for (i in 0 until nsym) s[i + 1] = polyEval(msg, pow(2, i))
        return s
    }

    private fun findErrorLocator(synd: IntArray, eraseCount: Int = 0): IntArray {
        var errLoc = intArrayOf(1)
        var oldLoc = intArrayOf(1)
        val shift = synd.size - nsym
        for (i in 0 until nsym - eraseCount) {
            val k = i + shift
            var delta = synd[k]
            for (j in 1 until errLoc.size) {
                delta = delta xor mul(errLoc[errLoc.size - 1 - j], synd[k - j])
            }
            oldLoc = oldLoc + 0
            if (delta != 0) {
                if (oldLoc.size > errLoc.size) {
                    val newLoc = polyScale(oldLoc, delta)
                    oldLoc = polyScale(errLoc, inverse(delta))
                    errLoc = newLoc
                }
                errLoc = polyAdd(errLoc, polyScale(oldLoc, delta))
            }
        }
        var start = 0
        while (start < errLoc.size && errLoc[start] == 0) start++
        errLoc = errLoc.copyOfRange(start, errLoc.size)
        val errs = errLoc.size - 1
        check(errs * 2 + eraseCount <= nsym) { "too many errors" }
        return errLoc
    }

    private fun findErrors(errLoc: IntArray, n: Int): IntArray? {
        val errs = errLoc.size - 1
        val pos = ArrayList<Int>()
        for (i in 0 until n) {
            if (polyEval(errLoc, pow(2, i)) == 0) pos.add(n - 1 - i)
        }
        if (pos.size != errs) return null
        return pos.toIntArray()
    }

    private fun correctErrata(msg: IntArray, synd: IntArray, errPos: IntArray): IntArray {
        val coefPos = IntArray(errPos.size) { msg.size - 1 - errPos[it] }
        var errataLoc = intArrayOf(1)
        for (p in coefPos) errataLoc = polyMul(errataLoc, polyAdd(intArrayOf(1), intArrayOf(pow(2, p), 0)))
        val errEval = errorEvaluator(synd.reversedArray(), errataLoc, errataLoc.size - 1).reversedArray()
        val x = IntArray(coefPos.size) { pow(2, -(ORDER - coefPos[it])) }
        val e = IntArray(msg.size)
        for (i in x.indices) {
            val xiInv = inverse(x[i])
            var locPrime = 1
            for (j in x.indices) if (j != i) locPrime = mul(locPrime, 1 xor mul(xiInv, x[j]))
            check(locPrime != 0) { "singular" }
            var y = polyEval(errEval.reversedArray(), xiInv)
            y = mul(x[i], y)
            e[errPos[i]] = div(y, locPrime)
        }
        return polyAdd(msg, e)
    }

    private fun errorEvaluator(synd: IntArray, errLoc: IntArray, n: Int): IntArray {
        val divisor = IntArray(n + 2).also { it[0] = 1 }
        val dividend = polyMul(synd, errLoc)
        val out = dividend.copyOf()
        for (i in 0 until dividend.size - (divisor.size - 1)) {
            val coef = out[i]
            if (coef != 0) for (j in 1 until divisor.size) {
                if (divisor[j] != 0) out[i + j] = out[i + j] xor mul(divisor[j], coef)
            }
        }
        val sep = divisor.size - 1
        return out.copyOfRange(out.size - sep, out.size)
    }

    companion object {
        const val ORDER = 127
        private const val MARGIN = 4
        private const val PRIM = 0x89 // x^7 + x^3 + 1
        private val EXP = IntArray(ORDER * 2)
        private val LOG = IntArray(ORDER + 1)

        init {
            var x = 1
            for (i in 0 until ORDER) {
                EXP[i] = x
                LOG[x] = i
                x = x shl 1
                if (x and 0x80 != 0) x = x xor PRIM
            }
            for (i in ORDER until ORDER * 2) EXP[i] = EXP[i - ORDER]
        }

        fun mul(a: Int, b: Int): Int =
            if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

        fun div(a: Int, b: Int): Int {
            if (b == 0) throw IllegalStateException("div by zero")
            if (a == 0) return 0
            return EXP[(LOG[a] + ORDER - LOG[b]) % ORDER]
        }

        fun pow(x: Int, p: Int): Int = EXP[Math.floorMod(LOG[x] * p, ORDER)]

        fun inverse(x: Int): Int = EXP[ORDER - LOG[x]]

        fun polyScale(p: IntArray, x: Int) = IntArray(p.size) { mul(p[it], x) }

        fun polyAdd(p: IntArray, q: IntArray): IntArray {
            val r = IntArray(maxOf(p.size, q.size))
            for (i in p.indices) r[i + r.size - p.size] = p[i]
            for (i in q.indices) r[i + r.size - q.size] = r[i + r.size - q.size] xor q[i]
            return r
        }

        fun polyMul(p: IntArray, q: IntArray): IntArray {
            val r = IntArray(p.size + q.size - 1)
            for (j in q.indices) for (i in p.indices) r[i + j] = r[i + j] xor mul(p[i], q[j])
            return r
        }

        fun polyEval(p: IntArray, x: Int): Int {
            var y = p[0]
            for (i in 1 until p.size) y = mul(y, x) xor p[i]
            return y
        }
    }
}
