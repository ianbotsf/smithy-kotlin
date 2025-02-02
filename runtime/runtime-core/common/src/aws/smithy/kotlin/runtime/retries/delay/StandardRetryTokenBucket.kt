/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package aws.smithy.kotlin.runtime.retries.delay

import aws.smithy.kotlin.runtime.retries.policy.RetryErrorType
import aws.smithy.kotlin.runtime.time.Clock
import aws.smithy.kotlin.runtime.time.epochMilliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

private const val MS_PER_S = 1_000

/**
 * The standard implementation of a [RetryTokenBucket].
 * @param options The configuration to use for this bucket.
 * @param clock A clock to use for time calculations.
 */
class StandardRetryTokenBucket(
    val options: StandardRetryTokenBucketOptions,
    private val clock: Clock = Clock.System,
) : RetryTokenBucket {
    internal var capacity = options.maxCapacity
        private set

    private var lastTimestamp = now()
    private val mutex = Mutex()

    /**
     * Acquire a token from the token bucket. This method should be called before the initial retry attempt for a block
     * of code. This method may delay if there are already insufficient tokens in the bucket due to prior retry
     * failures or large numbers of simultaneous requests.
     */
    override suspend fun acquireToken(): RetryToken {
        checkoutCapacity(options.initialTryCost)
        return StandardRetryToken(options.initialTrySuccessIncrement)
    }

    private suspend fun checkoutCapacity(size: Int): Unit = mutex.withLock {
        refillCapacity()

        if (size <= capacity) {
            capacity -= size
        } else {
            if (options.circuitBreakerMode) {
                throw RetryCapacityExceededException("Insufficient capacity to attempt another retry")
            }

            val extraRequiredCapacity = size - capacity
            val delayMs = ceil(extraRequiredCapacity.toDouble() / options.refillUnitsPerSecond * MS_PER_S).toLong()
            delay(delayMs)
            capacity = 0
        }

        lastTimestamp = now()
    }

    private fun now(): Long = clock.now().epochMilliseconds

    private fun refillCapacity() {
        val refillMs = now() - lastTimestamp
        val refillSize = floor(options.refillUnitsPerSecond.toDouble() / MS_PER_S * refillMs).toInt()
        capacity = min(options.maxCapacity, capacity + refillSize)
    }

    private suspend fun returnCapacity(size: Int): Unit = mutex.withLock {
        refillCapacity()

        capacity = min(options.maxCapacity, capacity + size)
        lastTimestamp = now()
    }

    /**
     * A standard implementation of a [RetryToken].
     * @param returnSize The amount of capacity to return to the bucket on a successful try.
     */
    inner class StandardRetryToken(val returnSize: Int) : RetryToken {
        /**
         * Completes this token because retrying has been abandoned. This implementation doesn't actually increment any
         * capacity upon failure...capacity just refills based on time.
         */
        override suspend fun notifyFailure() {
            // Do nothing
        }

        /**
         * Completes this token because the previous retry attempt was successful.
         */
        override suspend fun notifySuccess() {
            returnCapacity(returnSize)
        }

        /**
         * Completes this token and requests another one because the previous retry attempt was unsuccessful.
         */
        override suspend fun scheduleRetry(reason: RetryErrorType): RetryToken {
            val size = when (reason) {
                RetryErrorType.Timeout, RetryErrorType.Throttling -> options.timeoutRetryCost
                else -> options.retryCost
            }
            checkoutCapacity(size)
            return StandardRetryToken(size)
        }
    }
}

/**
 * The configuration options for a [StandardRetryTokenBucket].
 * @param maxCapacity The maximum capacity for the bucket.
 * @param refillUnitsPerSecond The amount of capacity to return per second.
 * @param circuitBreakerMode When true, indicates that attempts to acquire tokens or schedule retries should fail if all
 * capacity has been depleted. When false, calls to acquire tokens or schedule retries will delay until sufficient
 * capacity is available.
 * @param retryCost The amount of capacity to decrement for standard retries.
 * @param timeoutRetryCost The amount of capacity to decrement for timeout or throttling retries.
 * @param initialTryCost The amount of capacity to decrement for the initial try.
 * @param initialTrySuccessIncrement The amount of capacity to return if the initial try is successful.
 */
data class StandardRetryTokenBucketOptions(
    val maxCapacity: Int,
    val refillUnitsPerSecond: Int,
    val circuitBreakerMode: Boolean,
    val retryCost: Int,
    val timeoutRetryCost: Int,
    val initialTryCost: Int,
    val initialTrySuccessIncrement: Int,
) {
    companion object {
        /**
         * The default configuration for a [StandardRetryTokenBucket].
         */
        val Default = StandardRetryTokenBucketOptions(
            maxCapacity = 500,
            refillUnitsPerSecond = 10,
            circuitBreakerMode = false,
            retryCost = 5,
            timeoutRetryCost = 10,
            initialTryCost = 0,
            initialTrySuccessIncrement = 1,
        )
    }
}
