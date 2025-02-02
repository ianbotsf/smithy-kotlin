/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package aws.smithy.kotlin.runtime.serde.json

import aws.smithy.kotlin.runtime.serde.DeserializationException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import kotlin.test.*

class JsonStreamReaderTest {

    object KitchenSink {
        // serialized JSON document
        val payload: String = """
        {
            "num": 1,
            "str": "string",
            "list": [1,2.3456,"3"],
            "nested": {
                "l2": [
                    {
                        "x": "x",
                        "bool": true
                    }
                ],
                "falsey": false
            },
            "null": null
        }
        """.trimIndent()

        // expected tokens for "content"
        val tokens: List<JsonToken> = listOf(
            JsonToken.BeginObject,
            JsonToken.Name("num"),
            JsonToken.Number("1"),
            JsonToken.Name("str"),
            JsonToken.String("string"),
            JsonToken.Name("list"),
            JsonToken.BeginArray,
            JsonToken.Number("1"),
            JsonToken.Number("2.3456"),
            JsonToken.String("3"),
            JsonToken.EndArray,
            JsonToken.Name("nested"),
            JsonToken.BeginObject,
            JsonToken.Name("l2"),
            JsonToken.BeginArray,
            JsonToken.BeginObject,
            JsonToken.Name("x"),
            JsonToken.String("x"),
            JsonToken.Name("bool"),
            JsonToken.Bool(true),
            JsonToken.EndObject,
            JsonToken.EndArray,
            JsonToken.Name("falsey"),
            JsonToken.Bool(false),
            JsonToken.EndObject,
            JsonToken.Name("null"),
            JsonToken.Null,
            JsonToken.EndObject,
            JsonToken.EndDocument
        )
    }

    @Test
    fun itDeserializesObjects() {
        // language=JSON
        val actual = """ 
            {
                "x": 1,
                "y": "2"
            }
        """.allTokens()

        actual.shouldContainExactly(
            JsonToken.BeginObject,
            JsonToken.Name("x"),
            JsonToken.Number("1"),
            JsonToken.Name("y"),
            JsonToken.String("2"),
            JsonToken.EndObject,
            JsonToken.EndDocument
        )
    }

    @Test
    fun isDeserializesArrays() {
        // language=JSON
        val actual = """[ "hello", "world" ]""".allTokens()

        actual.shouldContainExactly(
            JsonToken.BeginArray,
            JsonToken.String("hello"),
            JsonToken.String("world"),
            JsonToken.EndArray,
            JsonToken.EndDocument
        )
    }

    @Test
    fun itFailsOnUnclosedArrays() {
        assertFailsWith<DeserializationException> {
            """[ "hello", "world" """.allTokens()
        }.message.shouldContain("expected one of `,`, `]`")
    }

    @Test
    fun itFailsOnNaN() {
        assertFailsWith<DeserializationException>("Invalid number") {
            // language=JSON
            """[NaN]""".allTokens()
        }
    }

    @Test
    fun itFailsOnMissingComma() {
        assertFailsWith<DeserializationException> {
            """[3[4]]""".allTokens()
        }.message.shouldContain("Unexpected JSON token at offset 2; found `[`, expected one of `,`, `]`")
    }

    @Test
    fun itFailsOnTrailingComma() {
        assertFailsWith<DeserializationException> {
            """["",]""".allTokens()
        }.message.shouldContain("Unexpected JSON token at offset 4; found `]`, expected one of `{`, `[`")

        assertFailsWith<DeserializationException> {
            """{"foo":"bar",}""".allTokens()
        }.message.shouldContain("Unexpected JSON token at offset 13; found `}`, expected `\"`")
    }

    @Test
    fun itDeserializesSingleScalarStrings() {
        // language=JSON
        val actual = "\"hello\"".allTokens()
        actual.shouldContainExactly(
            JsonToken.String("hello"),
            JsonToken.EndDocument
        )
    }

    @Test
    fun itDeserializesSingleScalarNumbers() {
        // language=JSON
        val actual = "1.2".allTokens()
        actual.shouldContainExactly(
            JsonToken.Number("1.2"),
            JsonToken.EndDocument
        )
    }

    @Test
    fun itCanHandleAllDataTypes() {
        // language=JSON
        val actual = """[ "hello", true, false, 1.0, 1, -34.234e3, null ]""".allTokens()

        actual.shouldContainExactly(
            JsonToken.BeginArray,
            JsonToken.String("hello"),
            JsonToken.Bool(true),
            JsonToken.Bool(false),
            JsonToken.Number("1.0"),
            JsonToken.Number("1"),
            JsonToken.Number("-34.234e3"),
            JsonToken.Null,
            JsonToken.EndArray,
            JsonToken.EndDocument
        )
    }

    @Test
    fun canHandleNesting() {
        // language=JSON
        val actual = """
        [
          "hello",
          {
            "foo": [
              20,
              true,
              null
            ],
            "bar": "value"
          }
        ]""".allTokens()

        actual.shouldContainExactly(
            JsonToken.BeginArray,
            JsonToken.String("hello"),
            JsonToken.BeginObject,
            JsonToken.Name("foo"),
            JsonToken.BeginArray,
            JsonToken.Number("20"),
            JsonToken.Bool(true),
            JsonToken.Null,
            JsonToken.EndArray,
            JsonToken.Name("bar"),
            JsonToken.String("value"),
            JsonToken.EndObject,
            JsonToken.EndArray,
            JsonToken.EndDocument
        )
    }

    @Test
    fun itSkipsValuesRecursively() {
        val payload = """
        {
            "x": 1,
            "unknown": {
                "a": "a",
                "b": "b",
                "c": ["d", "e", "f"],
                "g": {
                    "h": "h",
                    "i": "i"
                }
             },
            "y": 2
        }
        """.trimIndent()
        val reader = newReader(payload)
        // skip x
        reader.apply {
            nextToken() // begin obj
            nextToken() // x
            nextToken() // value
        }

        val name = reader.nextToken() as JsonToken.Name
        assertEquals("unknown", name.value)
        reader.skipNext()

        val y = reader.nextToken() as JsonToken.Name
        assertEquals("y", y.value)
    }

    @Test
    fun itSkipsValuesRecursivelyAfterPeek() {
        val payload = """
        {
            "x": 1,
            "nested": {
                "a": "a",
                "unknown": {
                    "b": "b",
                    "c": ["d", "e", "f"],
                    "g": {
                        "h": "h",
                        "i": "i"
                    }
                },
                "k": "k"
             },
            "y": 2
        }
        """.trimIndent()
        val reader = newReader(payload)
        // skip x
        // BeginObj, x, value
        repeat(3) { reader.nextToken() }

        val nested = reader.nextToken() as JsonToken.Name
        assertEquals("nested", nested.value)
        // BeginObj, a, value
        repeat(3) { reader.nextToken() }

        val unknown = reader.nextToken() as JsonToken.Name
        assertEquals("unknown", unknown.value)
        // skip the entire unknown subtree
        reader.skipNext()
        reader.peek()

        val remaining = mutableListOf<JsonToken>()
        for (i in 0..6) {
            remaining.add(reader.nextToken())
        }

        remaining.shouldContainExactly(
            JsonToken.Name("k"),
            JsonToken.String("k"),
            JsonToken.EndObject,
            JsonToken.Name("y"),
            JsonToken.Number("2"),
            JsonToken.EndObject,
            JsonToken.EndDocument
        )
    }

    @Test
    fun testPeek() {
        val reader = newReader(KitchenSink.payload)
        KitchenSink.tokens.forEachIndexed { idx, expectedToken ->
            repeat(2) {
                assertEquals(expectedToken, reader.peek(), "[idx=$idx] unexpected peeked token")
            }
            assertEquals(expectedToken, reader.nextToken(), "[idx=$idx] unexpected next token")
        }
    }

    @Test
    fun itSkipsSimpleValues() {
        val payload = """
        {
            "x": 1,
            "z": "unknown",
            "y": 2
        }
        """.trimIndent()
        val reader = newReader(payload)
        // skip x
        reader.apply {
            nextToken() // begin obj
            nextToken() // x
        }
        reader.skipNext()

        val name = reader.nextToken() as JsonToken.Name
        assertEquals("z", name.value)
        reader.skipNext()

        val y = reader.nextToken() as JsonToken.Name
        assertEquals("y", y.value)
    }

    @Test
    fun kitchenSink() {
        val actual = KitchenSink.payload.allTokens()
        actual.shouldContainExactly(KitchenSink.tokens)
    }

    @Test
    fun itHandlesEscapes() {
        val tests = listOf(
            """\"quote""" to "\"quote",
            """\/forward-slash""" to "/forward-slash",
            """\\back-slash""" to "\\back-slash",
            """\bbackspace""" to "\bbackspace",
            """\fformfeed""" to "\u000cformfeed",
            """\nlinefeed""" to "\nlinefeed",
            """\rcarriage-return""" to "\rcarriage-return",
            """\ttab""" to "\ttab",
            // Unicode
        )

        tests.forEach {
            val actual = """
                {
                    "foo": "${it.first}"
                }
            """.trimIndent().allTokens()

            actual.shouldContainExactly(
                JsonToken.BeginObject,
                JsonToken.Name("foo"),
                JsonToken.String(it.second),
                JsonToken.EndObject,
                JsonToken.EndDocument
            )
        }
    }

    @Test
    fun testUnescapeControls() {
        assertEquals("\"test\"", """"\"test\""""".decodeJsonStringToken())
        assertEquals("foo\rbar", """"foo\rbar"""".decodeJsonStringToken())
        assertEquals("foo\r\n", """"foo\r\n"""".decodeJsonStringToken())
        assertEquals("\r\nbar", """"\r\nbar"""".decodeJsonStringToken())
        assertEquals("\bf\u000Co\to\r\n", """"\bf\fo\to\r\n"""".decodeJsonStringToken())
    }

    @Test
    fun testUnicodeUnescape() {
        assertFailsWith<DeserializationException> {
            """"\uD801\nasdf"""".allTokens()
        }.message.shouldContain("Expected surrogate pair")

        assertFailsWith<DeserializationException> {
            """"\uD801\u00"""".allTokens()
        }.message.shouldContain("Unexpected EOF")

        assertFailsWith<DeserializationException> {
            """"\uD801\u+04D"""".allTokens()
        }.message.shouldContain("Invalid unicode escape: `\\u+04D`")

        assertFailsWith<DeserializationException> {
            """"\u00"""".allTokens()
        }.message.shouldContain("Unexpected EOF")

        assertFailsWith<DeserializationException> {
            """"\uD801\uC501"""".allTokens()
        }.message.shouldContain("Invalid surrogate pair: (${0xD801}, ${0xC501})")

        assertFailsWith<DeserializationException> {
            """"\zD801\uC501"""".allTokens()
        }.message.shouldContain("Invalid escape character: `z`")

        assertEquals("\uD801\uDC37", """"\uD801\udc37"""".decodeJsonStringToken(), "surrogate pair")
        assertEquals("\u0000", """"\u0000"""".decodeJsonStringToken())
        assertEquals("\u001f", """"\u001f"""".decodeJsonStringToken())
    }

    @Test
    fun testUnescapedControlChars() {
        assertFailsWith<DeserializationException> {
            """["new
line"]""".allTokens()
        }.message.shouldContain("Unexpected control character")

        assertFailsWith<DeserializationException> {
            val tokens = """["foo	tab"]""".trimIndent().allTokens()
            println(tokens)
        }.message.shouldContain("Unexpected control character")

        // whitespace should be fine
        assertEquals("foo  space", """"foo  space"""".decodeJsonStringToken())
        // delete should be fine
        assertEquals("\u007F", """""""".decodeJsonStringToken())
    }

    @Test
    fun testUnicodeTokens() {

        val languages = listOf(
            "こんにちは世界",
            "مرحبا بالعالم",
            "Привет, мир",
            "Γειά σου Κόσμε",
            "नमस्ते दुनिया",
            "you have summoned ZA̡͊͠͝LGΌ"
        )

        languages.forEach { lang ->
            val actual = """
                {
                    "foo": "$lang",
                    "$lang": "bar"
                }
            """.trimIndent().allTokens()

            actual.shouldContainExactly(
                JsonToken.BeginObject,
                JsonToken.Name("foo"),
                JsonToken.String(lang),
                JsonToken.Name(lang),
                JsonToken.String("bar"),
                JsonToken.EndObject,
                JsonToken.EndDocument
            )
        }
    }
}

private fun String.decodeJsonStringToken(): String {
    val reader = newReader(this)
    val tokens = mutableListOf<JsonToken>()
    while (true) {
        val token = reader.nextToken()
        tokens.add(token)
        if (token is JsonToken.EndDocument) {
            break
        }
    }

    // element + end doc
    assertEquals(2, tokens.size)
    val token = tokens.first()
    assertTrue(token is JsonToken.String)
    return token.value
}

private fun String.allTokens(): List<JsonToken> {
    val reader = newReader(this)
    val tokens = mutableListOf<JsonToken>()
    while (true) {
        val token = reader.nextToken()
        tokens.add(token)
        if (token is JsonToken.EndDocument) {
            return tokens
        }
    }
}

private fun newReader(contents: String): JsonStreamReader = JsonLexer(contents.encodeToByteArray())
