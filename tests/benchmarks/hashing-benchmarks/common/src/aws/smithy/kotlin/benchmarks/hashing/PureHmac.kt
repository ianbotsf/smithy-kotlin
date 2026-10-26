/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.benchmarks.hashing

import kotlin.experimental.xor

/**
 * HMAC (RFC 2104) over a pure [MerkleDamgardHash]. The inner (key xor ipad) and outer (key xor opad) blocks are
 * compressed once at construction; each [mac] call restores those saved states instead of recompressing them, saving
 * two block compressions per call versus runtime-core's `hmac`. Keys longer than the block size are hashed first.
 *
 * Not thread-safe: holds mutable per-instance state. It also retains key-derived hash state for its lifetime.
 */
internal class PureHmac(key: ByteArray, hashSupplier: () -> MerkleDamgardHash) {
    private val innerState: MerkleDamgardHash
    private val outerState: MerkleDamgardHash
    private val work: MerkleDamgardHash

    init {
        val hash = hashSupplier()
        val blockSize = hash.blockSizeBytes
        val hashedKey = if (key.size > blockSize) hash.apply { update(key) }.digest() else key
        val blockKey = hashedKey.copyOf(blockSize)

        innerState = hash.apply { update(ByteArray(blockSize) { blockKey[it] xor 0x36 }) }
        outerState = hashSupplier().apply { update(ByteArray(blockSize) { blockKey[it] xor 0x5c }) }
        work = innerState.copy()
    }

    fun mac(message: ByteArray): ByteArray {
        work.restoreFrom(innerState)
        work.update(message)
        val innerHash = work.digest()

        work.restoreFrom(outerState)
        work.update(innerHash)
        return work.digest()
    }
}
