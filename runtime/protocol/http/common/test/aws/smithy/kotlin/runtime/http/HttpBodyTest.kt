/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package aws.smithy.kotlin.runtime.http

import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.io.SdkByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HttpBodyTest {
    @Test
    fun testFromByteStreamBytes() {
        val body = ByteStream.fromString("foobar").toHttpBody()
        assertIs<HttpBody.Bytes>(body)
    }

    @Test
    fun testFromByteStreamReplayable() {
        val stream = object : ByteStream.ReplayableStream() {
            override val contentLength: Long = 6
            override fun newReader(): SdkByteReadChannel = SdkByteReadChannel("foobar".encodeToByteArray())
        }

        val body = stream.toHttpBody()
        assertIs<HttpBody.Streaming>(body)
        assertTrue(body.isReplayable)
    }

    @Test
    fun testFromByteStreamOneShot() {
        val stream = object : ByteStream.OneShotStream() {
            override val contentLength: Long = 6
            override fun readFrom(): SdkByteReadChannel = SdkByteReadChannel("foobar".encodeToByteArray())
        }

        val body = stream.toHttpBody()
        assertIs<HttpBody.Streaming>(body)
        assertFalse(body.isReplayable)
        assertFailsWith<UnsupportedOperationException> {
            body.reset()
        }
    }

    @Test
    fun testReset() = runTest {
        val stream = object : ByteStream.ReplayableStream() {
            override val contentLength: Long = 6
            override fun newReader(): SdkByteReadChannel = SdkByteReadChannel("foobar".encodeToByteArray())
        }

        val body = stream.toHttpBody()
        assertIs<HttpBody.Streaming>(body)
        assertEquals("foobar", body.readAll()!!.decodeToString())
        body.reset()
        assertEquals("foobar", body.readAll()!!.decodeToString())
    }
}
