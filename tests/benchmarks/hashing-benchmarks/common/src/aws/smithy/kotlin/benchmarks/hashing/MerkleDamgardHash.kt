/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.benchmarks.hashing

import aws.smithy.kotlin.runtime.hashing.HashFunction

/**
 * Block buffering and length padding shared by MD5, SHA-1, and SHA-256, which all use 64-byte blocks and an 8-byte
 * message-length trailer. They differ only in the compression function and the byte order of the trailer/digest.
 *
 * All whole blocks of one [update] call are handed to the subclass in a single [processBlocks] call, so it can keep
 * its chaining state in locals across blocks. [copy] and [restoreFrom] snapshot the full state for [PureHmac].
 */
internal abstract class MerkleDamgardHash(private val bigEndianLength: Boolean) : HashFunction {
    final override val blockSizeBytes: Int = 64

    private val buffer = ByteArray(64)
    private var bufferLength = 0
    private var totalBytes = 0L

    /** Compresses [count] consecutive 64-byte blocks starting at [offset] */
    protected abstract fun processBlocks(input: ByteArray, offset: Int, count: Int)
    protected abstract fun resetState()
    protected abstract fun writeDigest(out: ByteArray)

    /** Returns a new instance of the same concrete type, in its initial state */
    protected abstract fun newInstance(): MerkleDamgardHash

    /** Copies the subclass's chaining state from [other], which is always the same concrete type as `this` */
    protected abstract fun copyChainingStateFrom(other: MerkleDamgardHash)

    override fun update(input: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= input.size) {
            "invalid offset/length: offset=$offset, length=$length, size=${input.size}"
        }
        totalBytes += length
        var off = offset
        var len = length

        if (bufferLength > 0) {
            val n = minOf(64 - bufferLength, len)
            input.copyInto(buffer, bufferLength, off, off + n)
            bufferLength += n
            off += n
            len -= n
            if (bufferLength == 64) {
                processBlocks(buffer, 0, 1)
                bufferLength = 0
            }
        }

        // Compress all whole blocks straight from the input in one call
        val blocks = len ushr 6
        if (blocks > 0) {
            processBlocks(input, off, blocks)
            off += blocks shl 6
            len -= blocks shl 6
        }

        if (len > 0) {
            input.copyInto(buffer, 0, off, off + len)
            bufferLength = len
        }
    }

    override fun digest(): ByteArray {
        val bitLength = totalBytes * 8
        buffer[bufferLength++] = 0x80.toByte()
        if (bufferLength > 56) {
            buffer.fill(0, bufferLength, 64)
            processBlocks(buffer, 0, 1)
            bufferLength = 0
        }
        buffer.fill(0, bufferLength, 56)
        for (i in 0 until 8) {
            val shift = if (bigEndianLength) 56 - 8 * i else 8 * i
            buffer[56 + i] = (bitLength ushr shift).toByte()
        }
        processBlocks(buffer, 0, 1)

        val out = ByteArray(digestSizeBytes)
        writeDigest(out)
        reset()
        return out
    }

    override fun reset() {
        bufferLength = 0
        totalBytes = 0
        resetState()
    }

    /** Overwrites this hash's entire state with [other]'s, without allocating. [other] must be the same concrete type. */
    fun restoreFrom(other: MerkleDamgardHash) {
        other.buffer.copyInto(buffer, 0, 0, other.bufferLength)
        bufferLength = other.bufferLength
        totalBytes = other.totalBytes
        copyChainingStateFrom(other)
    }

    /** Returns an independent hash in the same state as this one */
    fun copy(): MerkleDamgardHash = newInstance().also { it.restoreFrom(this) }
}

internal fun ByteArray.getIntBe(i: Int): Int = (this[i].toInt() shl 24) or
    ((this[i + 1].toInt() and 0xff) shl 16) or
    ((this[i + 2].toInt() and 0xff) shl 8) or
    (this[i + 3].toInt() and 0xff)

internal fun ByteArray.getIntLe(i: Int): Int = (this[i].toInt() and 0xff) or
    ((this[i + 1].toInt() and 0xff) shl 8) or
    ((this[i + 2].toInt() and 0xff) shl 16) or
    (this[i + 3].toInt() shl 24)

internal fun ByteArray.putIntBe(i: Int, value: Int) {
    this[i] = (value ushr 24).toByte()
    this[i + 1] = (value ushr 16).toByte()
    this[i + 2] = (value ushr 8).toByte()
    this[i + 3] = value.toByte()
}

internal fun ByteArray.putIntLe(i: Int, value: Int) {
    this[i] = value.toByte()
    this[i + 1] = (value ushr 8).toByte()
    this[i + 2] = (value ushr 16).toByte()
    this[i + 3] = (value ushr 24).toByte()
}
