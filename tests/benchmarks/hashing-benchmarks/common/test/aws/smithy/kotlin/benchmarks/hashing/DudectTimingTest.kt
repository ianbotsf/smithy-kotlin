/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.benchmarks.hashing

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * dudect-style timing leak tests (Reparaz, Balasch, Verbauwhede, "Dude, is my code constant time?", 2017). Each test
 * times one operation on two classes of secret input, a fixed value and fresh random values, interleaved in random
 * order. Welch's t-test then compares the two timing distributions, both whole and cropped at several percentiles to
 * drop noisy outliers. |t| above [T_THRESHOLD] means the classes are distinguishable, i.e. a timing leak.
 *
 * Slow and machine-sensitive, so excluded from normal test runs. Run with `-Pdudect`.
 */
class DudectTimingTest {
    private val n = hexLimbs("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")
    private val p = hexLimbs("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff")
    private val one = IntArray(8).apply { this[0] = 1 }
    private val message = ByteArray(64) { it.toByte() }
    private var sink = 0

    @Test
    fun testBaseMultiplyIsConstantTime() = assertNoLeak("k * G, k = 1 vs random", 100_000, one, { randomBelow(it, n) }) {
        sink += baseMultiplyAffineX(it)[0]
    }

    @Test
    fun testBaseMultiplySparseScalarIsConstantTime() {
        // Only the top nibble set: 63 zero nibbles, at the far end of the table from k = 1
        val sparse = IntArray(8).apply { this[7] = 0x10000000 }
        assertNoLeak("k * G, k = 2^252 vs random", 100_000, sparse, { randomBelow(it, n) }) {
            sink += baseMultiplyAffineX(it)[0]
        }
    }

    @Test
    fun testScalarInverseIsConstantTime() = assertNoLeak("safegcd mod n, 1 vs random", 200_000, one, { randomBelow(it, n) }) {
        sink += invertModN(it)[0]
    }

    @Test
    fun testFieldInverseIsConstantTime() = assertNoLeak("invert mod p, 1 vs random", 100_000, one, { randomBelow(it, p) }) {
        sink += SolinasP256Field.invert(it)[0]
    }

    @Test
    fun testFieldMultiplyIsConstantTime() {
        // 0 * 0 makes every Solinas reduction term zero
        val zero = IntArray(8)
        assertNoLeak("multiply mod p, 0 vs random", 500_000, zero, { randomBelow(it, p) }) {
            sink += SolinasP256Field.multiply(it, it)[0]
        }
    }

    @Test
    fun testSignIsConstantTime() = assertNoLeak("sign, key = 1 vs random", 100_000, ByteArray(32).also { it[31] = 1 }, { randomKey(it) }) {
        sink += pureEcdsaSecp256r1(it, message)[5]
    }

    /** Positive control: variable-time double-and-add k * G must be caught, or the harness can't see leaks */
    @Test
    fun testReferenceBaseMultiplyLeaks() {
        val result = measure(20_000, one, { randomBelow(it, n) }) {
            sink += referenceBaseMultiplyAffineX(it)[0]
        }
        report("CONTROL double-and-add k * G, k = 1 vs random", result)
        assertTrue(result.maxAbsT > T_THRESHOLD, "control: expected a leak, got max |t| = ${result.maxAbsT}")
    }

    private fun <T> assertNoLeak(label: String, samples: Int, fixed: T, random: (Random) -> T, op: (T) -> Unit) {
        val result = measure(samples, fixed, random, op)
        report(label, result)
        assertTrue(result.maxAbsT < T_THRESHOLD, "$label: timing leak, max |t| = ${result.maxAbsT}")
    }

    /** Every sample gets its own input object, so the fixed class isn't favored by always hitting one cache line */
    @Suppress("UNCHECKED_CAST")
    private fun <T> copyOf(value: T): T = when (value) {
        is IntArray -> value.copyOf() as T
        is ByteArray -> value.copyOf() as T
        else -> error("unsupported input type")
    }

    private class Result(val tValues: List<Pair<String, Double>>, val medianNanos: Long) {
        val maxAbsT: Double get() = tValues.maxOf { abs(it.second) }
    }

    private fun report(label: String, result: Result) {
        val ts = result.tValues.joinToString(" ") { (name, t) -> "$name=${t.format()}" }
        println("DUDECT | $label | median ${result.medianNanos} ns | max |t| ${result.maxAbsT.format()} | $ts")
    }

    private fun <T> measure(samples: Int, fixed: T, randomInput: (Random) -> T, op: (T) -> Unit): Result {
        val random = Random(0xdec7)
        // Inputs are generated up front so their cost isn't timed
        val classes = BooleanArray(samples) { random.nextBoolean() }
        val inputs = List(samples) { if (classes[it]) copyOf(fixed) else randomInput(random) }

        // Warm up (JIT) on both classes
        repeat(minOf(samples, 5_000)) { op(inputs[it]) }

        val times = LongArray(samples)
        for (i in 0 until samples) {
            val start = TimeSource.Monotonic.markNow()
            op(inputs[i])
            times[i] = start.elapsedNow().inWholeNanoseconds
        }

        // As in dudect: test the whole set plus sets cropped at percentiles of the pooled timings
        val sorted = times.sorted()
        val cutoffs = listOf("all" to Long.MAX_VALUE) +
            listOf(50, 75, 90, 95, 99).map { pct -> "p$pct" to sorted[sorted.size * pct / 100] }
        val tValues = cutoffs.map { (name, cutoff) ->
            val fixedStats = Welford()
            val randomStats = Welford()
            for (i in 0 until samples) {
                if (times[i] > cutoff) continue
                if (classes[i]) fixedStats.add(times[i].toDouble()) else randomStats.add(times[i].toDouble())
            }
            name to welchT(fixedStats, randomStats)
        }
        return Result(tValues, sorted[sorted.size / 2])
    }

    /** Running mean and variance */
    private class Welford {
        var count = 0
        var mean = 0.0
        var m2 = 0.0

        fun add(x: Double) {
            count++
            val delta = x - mean
            mean += delta / count
            m2 += delta * (x - mean)
        }

        val variance: Double get() = if (count > 1) m2 / (count - 1) else 0.0
    }

    private fun welchT(a: Welford, b: Welford): Double {
        val se = sqrt(a.variance / a.count + b.variance / b.count)
        return if (se == 0.0) 0.0 else (a.mean - b.mean) / se
    }

    private fun Double.format(): String = (kotlin.math.round(this * 100) / 100).toString()

    private fun randomKey(random: Random): ByteArray = randomBelow(random, n).let { limbs ->
        ByteArray(32).also { out ->
            for (i in 0 until 8) {
                val limb = limbs[7 - i]
                for (j in 0 until 4) out[4 * i + j] = (limb ushr (24 - 8 * j)).toByte()
            }
        }
    }

    /** Uniform nonzero value below [modulus] */
    private fun randomBelow(random: Random, modulus: IntArray): IntArray {
        while (true) {
            val candidate = IntArray(8) { random.nextInt() }
            if (candidate.any { it != 0 } && lessThan(candidate, modulus)) return candidate
        }
    }

    private fun lessThan(a: IntArray, b: IntArray): Boolean {
        for (i in 7 downTo 0) {
            if (a[i] != b[i]) return a[i].toUInt() < b[i].toUInt()
        }
        return false
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun hexLimbs(hex: String): IntArray {
        val bytes = hex.hexToByteArray()
        return IntArray(8) { i ->
            val o = 28 - 4 * i
            ((bytes[o].toInt() and 0xff) shl 24) or ((bytes[o + 1].toInt() and 0xff) shl 16) or
                ((bytes[o + 2].toInt() and 0xff) shl 8) or (bytes[o + 3].toInt() and 0xff)
        }
    }

    private companion object {
        /** dudect's threshold for "probably not constant time" */
        const val T_THRESHOLD = 4.5
    }
}
