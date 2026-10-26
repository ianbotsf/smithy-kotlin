/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.benchmarks.hashing

import aws.smithy.kotlin.runtime.hashing.hmac
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

// Sizes straddle the 56-byte padding boundary and the 64-byte block boundary
private val sizes = listOf(0, 1, 55, 56, 57, 63, 64, 65, 127, 128, 1000, 100_003)

class PureHashFunctionTest {
    private val algorithms = listOf("crc32", "crc32c", "md5", "sha1", "sha256")

    @Test
    fun testMatchesRuntimeSingleUpdate() {
        val random = Random(1)
        for (algorithm in algorithms) {
            val runtime = hashFunction(RUNTIME, algorithm)
            val pure = hashFunction(PURE, algorithm)
            for (size in sizes) {
                val input = random.nextBytes(size)
                runtime.update(input)
                pure.update(input)
                assertContentEquals(runtime.digest(), pure.digest(), "$algorithm, size=$size")
            }
        }
    }

    @Test
    fun testMatchesRuntimeChunkedUpdatesWithOffsets() {
        val random = Random(2)
        val input = random.nextBytes(10_000)
        for (algorithm in algorithms) {
            val runtime = hashFunction(RUNTIME, algorithm)
            val pure = hashFunction(PURE, algorithm)
            var offset = 0
            while (offset < input.size) {
                val length = minOf(random.nextInt(0, 200), input.size - offset)
                runtime.update(input, offset, length)
                pure.update(input, offset, length)
                offset += length
            }
            assertContentEquals(runtime.digest(), pure.digest(), algorithm)
        }
    }

    @Test
    fun testResetDiscardsState() {
        for (algorithm in algorithms) {
            val pure = hashFunction(PURE, algorithm)
            pure.update(byteArrayOf(1, 2, 3))
            pure.reset()
            pure.update(byteArrayOf(4, 5, 6))
            val runtime = hashFunction(RUNTIME, algorithm).apply { update(byteArrayOf(4, 5, 6)) }
            assertContentEquals(runtime.digest(), pure.digest(), algorithm)
        }
    }

    @Test
    fun testHmacMatchesRuntime() {
        val random = Random(3)
        for (algorithm in listOf("md5", "sha1", "sha256")) {
            for (keySize in listOf(16, 64, 100)) {
                val key = random.nextBytes(keySize)
                val message = random.nextBytes(1000)
                assertContentEquals(
                    hmac(key, message, hashFunction(RUNTIME, algorithm)),
                    hmac(key, message, hashFunction(PURE, algorithm)),
                    "$algorithm, keySize=$keySize",
                )
            }
        }
    }
}
