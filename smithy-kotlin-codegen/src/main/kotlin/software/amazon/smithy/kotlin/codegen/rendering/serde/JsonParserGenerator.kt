/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.kotlin.codegen.rendering.serde

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.kotlin.codegen.core.KotlinWriter
import software.amazon.smithy.kotlin.codegen.core.RuntimeTypes
import software.amazon.smithy.kotlin.codegen.core.withBlock
import software.amazon.smithy.kotlin.codegen.model.boxed
import software.amazon.smithy.kotlin.codegen.model.isBoxed
import software.amazon.smithy.kotlin.codegen.model.knowledge.SerdeIndex
import software.amazon.smithy.kotlin.codegen.rendering.protocol.ProtocolGenerator
import software.amazon.smithy.kotlin.codegen.rendering.protocol.toRenderingContext
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.TimestampFormatTrait

open class JsonParserGenerator(
    // FIXME - we shouldn't need this, it's only required by JsonSerdeDescriptorGenerator because of toRenderingContext
    private val protocolGenerator: ProtocolGenerator,
    private val supportsJsonNameTrait: Boolean = true
) : StructuredDataParserGenerator {

    open val defaultTimestampFormat: TimestampFormatTrait.Format = TimestampFormatTrait.Format.EPOCH_SECONDS

    override fun operationDeserializer(ctx: ProtocolGenerator.GenerationContext, op: OperationShape, members: List<MemberShape>): Symbol {
        val outputSymbol = op.output.get().let { ctx.symbolProvider.toSymbol(ctx.model.expectShape(it)) }
        return op.bodyDeserializer(ctx.settings) { writer ->
            addNestedDocumentDeserializers(ctx, op, writer)
            val fnName = op.bodyDeserializerName()
            if (ctx.settings.codegen.dataClasses) {
                writer.withBlock("private fun #L(payload: ByteArray): #T {", "}", fnName, outputSymbol) {
                    val memberNameSymbolIndex = members.associateWith {
                        ctx.symbolProvider.toMemberName(it) to ctx.symbolProvider.toSymbol(it)
                    }
                    val (nullableMembers, nonNullMembers) = members
                        .partition { memberNameSymbolIndex[it]!!.second.isBoxed }

                    fun renderMemberVar(m: MemberShape) {
                        val (name, memberSymbol) = memberNameSymbolIndex[m]!!
                        // FIXME Using #E because #T doesn't properly include nullability for boxed symbols
                        writer.write("var #L: #E", name, memberSymbol.boxed())
                    }

                    fun renderMemberPassing(m: MemberShape, rightSideAssertion: String = "") {
                        val (name, _) = memberNameSymbolIndex[m]!!
                        writer.write("#1L = #1L$rightSideAssertion,", name)
                    }

                    nonNullMembers.forEach(::renderMemberVar)
                    nullableMembers.forEach(::renderMemberVar)

                    write("val deserializer = #T(payload)", RuntimeTypes.Serde.SerdeJson.JsonDeserializer)
                    renderDeserializerBody(ctx, op, members, writer)

                    writer.withBlock("return #T(", ")", outputSymbol) {
                        nonNullMembers.forEach { renderMemberPassing(it, "!!") }
                        nullableMembers.forEach { renderMemberPassing(it) }
                    }
                }
            } else {
                writer.openBlock("private fun #L(builder: #T.Builder, payload: ByteArray) {", fnName, outputSymbol)
                    .call {
                        renderDeserializeOperationBody(ctx, op, members, writer)
                    }
                    .closeBlock("}")
            }
        }
    }

    /**
     * Register nested structure/map shapes reachable from the operation input shape that require a "document" deserializer
     * implementation
     */
    private fun addNestedDocumentDeserializers(ctx: ProtocolGenerator.GenerationContext, shape: Shape, writer: KotlinWriter) {
        val serdeIndex = SerdeIndex.of(ctx.model)
        val shapesRequiringDocumentDeserializer = serdeIndex.requiresDocumentDeserializer(shape)

        // register a dependency on each of the members that require a deserializer impl
        // ensuring they get generated
        shapesRequiringDocumentDeserializer.forEach {
            val nestedStructOrUnionDeserializer = documentDeserializer(ctx, it)
            writer.addImport(nestedStructOrUnionDeserializer)
        }
    }

    private fun renderDeserializeOperationBody(
        ctx: ProtocolGenerator.GenerationContext,
        op: OperationShape,
        documentMembers: List<MemberShape>,
        writer: KotlinWriter
    ) {
        writer.write("val deserializer = #T(payload)", RuntimeTypes.Serde.SerdeJson.JsonDeserializer)
        val shape = ctx.model.expectShape(op.output.get())
        renderDeserializerBody(ctx, shape, documentMembers, writer)
    }

    private fun documentDeserializer(ctx: ProtocolGenerator.GenerationContext, shape: Shape): Symbol {
        val symbol = ctx.symbolProvider.toSymbol(shape)
        return symbol.documentDeserializer(ctx.settings) { writer ->
            val fnName = symbol.documentDeserializerName()
            writer.openBlock("internal fun #L(deserializer: #T): #T {", fnName, RuntimeTypes.Serde.Deserializer, symbol)
                .call {
                    if (shape.isUnionShape) {
                        writer.write("var value: #T? = null", symbol)
                        renderDeserializerBody(ctx, shape, shape.members().toList(), writer)
                        writer.write("return value ?: throw #T(#S)", RuntimeTypes.Serde.DeserializationException, "Deserialized union value unexpectedly null: ${symbol.name}")
                    } else {
                        if (ctx.settings.codegen.dataClasses) {
                            val structureShape = shape as StructureShape
                            val memberShapes = structureShape.allMembers.values
                            val memberNameSymbolIndex = memberShapes.associateWith {
                                ctx.symbolProvider.toMemberName(it) to ctx.symbolProvider.toSymbol(it)
                            }
                            val (nullableMembers, nonNullMembers) = structureShape
                                .allMembers
                                .values
                                .partition { memberNameSymbolIndex[it]!!.second.isBoxed }

                            fun renderMemberVar(m: MemberShape) {
                                val (name, memberSymbol) = memberNameSymbolIndex[m]!!
                                // FIXME Using #E because #T doesn't properly include nullability for boxed symbols
                                writer.rawString()
                                writer.write("var #L: #E", name, memberSymbol.boxed())
                            }

                            fun renderMemberPassing(m: MemberShape, rightSideAssertion: String = "") {
                                val (name, _) = memberNameSymbolIndex[m]!!
                                writer.write("#1L = #1L$rightSideAssertion,", name)
                            }

                            nonNullMembers.forEach(::renderMemberVar)
                            nullableMembers.forEach(::renderMemberVar)

                            renderDeserializerBody(ctx, shape, shape.members().toList(), writer)

                            writer.withBlock("return #T(", ")", symbol) {
                                nonNullMembers.forEach { renderMemberPassing(it, "!!") }
                                nullableMembers.forEach { renderMemberPassing(it) }
                            }
                        } else {
                            writer.write("val builder = #T.Builder()", symbol)
                            renderDeserializerBody(ctx, shape, shape.members().toList(), writer)
                            writer.write("return builder.build()")
                        }
                    }
                }
                .closeBlock("}")
        }
    }

    override fun errorDeserializer(ctx: ProtocolGenerator.GenerationContext, errorShape: StructureShape, members: List<MemberShape>): Symbol {
        val symbol = ctx.symbolProvider.toSymbol(errorShape)
        return symbol.errorDeserializer(ctx.settings) { writer ->
            addNestedDocumentDeserializers(ctx, errorShape, writer)
            val fnName = symbol.errorDeserializerName()

            if (ctx.settings.codegen.dataClasses) {
                writer.withBlock("private fun #L(payload: ByteArray): #T {", "}", fnName, symbol) {
                    val memberShapes = errorShape.allMembers.values
                    val memberNameSymbolIndex = memberShapes.associateWith {
                        ctx.symbolProvider.toMemberName(it) to ctx.symbolProvider.toSymbol(it)
                    }
                    val (nullableMembers, nonNullMembers) = memberShapes
                        .partition { memberNameSymbolIndex[it]!!.second.isBoxed }

                    fun renderMemberVar(m: MemberShape) {
                        val (name, memberSymbol) = memberNameSymbolIndex[m]!!
                        // FIXME Using #E because #T doesn't properly include nullability for boxed symbols
                        writer.write("var #L: #E", name, memberSymbol.boxed())
                    }

                    fun renderMemberPassing(m: MemberShape, rightSideAssertion: String = "") {
                        val (name, _) = memberNameSymbolIndex[m]!!
                        writer.write("#1L = #1L$rightSideAssertion,", name)
                    }

                    nonNullMembers.forEach(::renderMemberVar)
                    nullableMembers.forEach(::renderMemberVar)

                    write("val deserializer = #T(payload)", RuntimeTypes.Serde.SerdeJson.JsonDeserializer)
                    renderDeserializerBody(ctx, errorShape, members, writer)

                    writer.withBlock("return #T(", ")", symbol) {
                        nonNullMembers.forEach { renderMemberPassing(it, "!!") }
                        nullableMembers.forEach { renderMemberPassing(it) }
                    }
                }
            } else {
                writer.openBlock("private fun #L(builder: #T.Builder, payload: ByteArray) {", fnName, symbol)
                    .call {
                        writer.write("val deserializer = #T(payload)", RuntimeTypes.Serde.SerdeJson.JsonDeserializer)
                        renderDeserializerBody(ctx, errorShape, members, writer)
                    }
                    .closeBlock("}")
            }
        }
    }

    private fun renderDeserializerBody(
        ctx: ProtocolGenerator.GenerationContext,
        shape: Shape,
        members: List<MemberShape>,
        writer: KotlinWriter,
    ) {
        JsonSerdeDescriptorGenerator(ctx.toRenderingContext(protocolGenerator, shape, writer), members, supportsJsonNameTrait).render()
        if (shape.isUnionShape) {
            val name = ctx.symbolProvider.toSymbol(shape).name
            DeserializeUnionGenerator(ctx, name, members, writer, defaultTimestampFormat).render()
        } else {
            DeserializeStructGenerator(ctx, members, writer, defaultTimestampFormat).render()
        }
    }

    override fun payloadDeserializer(ctx: ProtocolGenerator.GenerationContext, member: MemberShape): Symbol {
        // re-use document deserializer (for the target shape!)
        val symbol = ctx.symbolProvider.toSymbol(member)
        val target = ctx.model.expectShape(member.target)
        val deserializeFn = documentDeserializer(ctx, target)
        val fnName = symbol.payloadDeserializerName()
        return symbol.payloadDeserializer(ctx.settings) { writer ->
            addNestedDocumentDeserializers(ctx, target, writer)
            writer.withBlock("internal fun #L(payload: ByteArray): #T {", "}", fnName, symbol) {
                write("val deserializer = #T(payload)", RuntimeTypes.Serde.SerdeJson.JsonDeserializer)
                write("return #T(deserializer)", deserializeFn)
            }
        }
    }
}
