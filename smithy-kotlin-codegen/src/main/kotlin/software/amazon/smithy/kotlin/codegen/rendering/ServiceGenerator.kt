/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.amazon.smithy.kotlin.codegen.rendering

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.kotlin.codegen.core.*
import software.amazon.smithy.kotlin.codegen.integration.SectionId
import software.amazon.smithy.kotlin.codegen.model.hasStreamingMember
import software.amazon.smithy.kotlin.codegen.model.isBoxed
import software.amazon.smithy.kotlin.codegen.model.operationSignature
import software.amazon.smithy.model.knowledge.OperationIndex
import software.amazon.smithy.model.knowledge.TopDownIndex
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape
import java.lang.reflect.Member

// FIXME - rename file and class to ServiceClientGenerator

/**
 * Renders just the service client interfaces. The actual implementation is handled by protocol generators, see
 * [software.amazon.smithy.kotlin.codegen.rendering.protocol.HttpBindingProtocolGenerator].
 */
class ServiceGenerator(private val ctx: RenderingContext<ServiceShape>) {
    /**
     * SectionId used when rendering the service interface companion object
     */
    object SectionServiceCompanionObject : SectionId {
        /**
         * Context key for the service symbol
         */
        const val ServiceSymbol = "ServiceSymbol"
    }

    /**
     * SectionId used when rendering the service configuration object
     */
    object SectionServiceConfig : SectionId {
        /**
         * The current rendering context for the service generator
         */
        const val RenderingContext = "RenderingContext"
    }

    init {
        require(ctx.shape is ServiceShape) { "ServiceShape is required for generating a service interface; was: ${ctx.shape}" }
    }

    private val service: ServiceShape =
        requireNotNull(ctx.shape) { "ServiceShape is required to render a service client" }
    private val serviceSymbol = ctx.symbolProvider.toSymbol(service)
    private val writer = ctx.writer

    fun render() {

        importExternalSymbols()

        val topDownIndex = TopDownIndex.of(ctx.model)
        val operations = topDownIndex.getContainedOperations(service).sortedBy { it.defaultName() }
        val operationsIndex = OperationIndex.of(ctx.model)

        writer.renderDocumentation(service)
        writer.renderAnnotations(service)
        writer.openBlock("interface ${serviceSymbol.name} : SdkClient {")
            .call { overrideServiceName() }
            .call {
                // allow access to client's Config
                writer.dokka("${serviceSymbol.name}'s configuration")
                writer.write("val config: Config")
            }
            .call {
                // allow integrations to add additional fields to companion object or configuration
                writer.write("")
                writer.declareSection(
                    SectionServiceCompanionObject,
                    context = mapOf(SectionServiceCompanionObject.ServiceSymbol to serviceSymbol)
                ) {
                    renderCompanionObject()
                }
                writer.write("")
                renderServiceConfig()
            }
            .call {
                operations.forEach { op ->
                    renderOperation(operationsIndex, op)
                }
            }
            .closeBlock("}")
            .write("")
    }

    private fun renderServiceConfig() {
        writer.declareSection(
            SectionServiceConfig,
            context = mapOf(SectionServiceConfig.RenderingContext to ctx)
        ) {
            ClientConfigGenerator(ctx).render()
        }
    }

    /**
     * Render the service interface companion object which is the main entry point for most consumers
     *
     * e.g.
     * ```
     * companion object {
     *     operator fun invoke(block: Config.Builder.() -> Unit = {}): LambdaClient {
     *         val config = Config.Builder().apply(block).build()
     *         return DefaultLambdaClient(config)
     *     }
     *
     *     operator fun invoke(config: Config): LambdaClient = DefaultLambdaClient(config)
     * }
     * ```
     */
    private fun renderCompanionObject() {
        writer.withBlock("companion object {", "}") {
            val hasProtocolGenerator = ctx.protocolGenerator != null
            // If there is no ProtocolGenerator, do not codegen references to the non-existent default client.
            callIf(hasProtocolGenerator) {
                withBlock("operator fun invoke(block: Config.Builder.() -> Unit = {}): ${serviceSymbol.name} {", "}") {
                    write("val config = Config.Builder().apply(block).build()")
                    write("return Default${serviceSymbol.name}(config)")
                }
                write("")
                write("operator fun invoke(config: Config): ${serviceSymbol.name} = Default${serviceSymbol.name}(config)")
            }
        }
    }

    private fun importExternalSymbols() {
        // base client interface
        val sdkInterfaceSymbol = Symbol.builder()
            .name("SdkClient")
            .namespace(RUNTIME_ROOT_NS, ".")
            .addDependency(KotlinDependency.CORE)
            .build()

        writer.addImport(sdkInterfaceSymbol)

        // import all the models generated for use in input/output shapes
        writer.addImport("${ctx.settings.pkg.name}.model", "*")
    }

    private fun overrideServiceName() {
        writer.write("")
            .write("override val serviceName: String")
            .indent()
            .write("get() = #S", ctx.settings.sdkId)
            .dedent()
    }

    private fun renderOperation(opIndex: OperationIndex, op: OperationShape) {
        writer.write("")
        writer.renderDocumentation(op)
        writer.renderAnnotations(op)
        writer.write(opIndex.operationSignature(ctx.model, ctx.symbolProvider, op))

        // Add DSL overload (if appropriate)
        opIndex.getInput(op).ifPresent { inputShape ->
            val outputShape = opIndex.getOutput(op)
            val hasOutputStream = outputShape.map { it.hasStreamingMember(ctx.model) }.orElse(false)

            if (!hasOutputStream) {
                val input = ctx.symbolProvider.toSymbol(inputShape).name
                val operationName = op.defaultName()

                writer.write("")
                writer.renderDocumentation(op)
                writer.renderAnnotations(op)

                if (ctx.settings.codegen.dataClasses) {
                    val memberShapes = inputShape.allMembers.values
                    val memberNameSymbolIndex = memberShapes.associateWith {
                        ctx.symbolProvider.toMemberName(it) to ctx.symbolProvider.toSymbol(it)
                    }
                    val (nullableMembers, nonNullMembers) = inputShape
                        .allMembers
                        .values
                        .partition { memberNameSymbolIndex[it]!!.second.isBoxed }

                    fun renderMember(m: MemberShape, defaultValueString: String = "") {
                        val (name, symbol) = memberNameSymbolIndex[m]!!
                        // FIXME Using #F because #T doesn't properly include nullability for boxed symbols
                        writer.write("#L: #F$defaultValueString,", name, symbol)
                    }

                    fun renderMemberPassing(m: MemberShape) {
                        val (name, _) = memberNameSymbolIndex[m]!!
                        writer.write("#1L = #1L,", name)
                    }

                    writer.openBlock("suspend fun #L(", operationName)
                    nonNullMembers.forEach { renderMember(it) }
                    nullableMembers.forEach { renderMember(it, " = null") }
                    writer.closeAndOpenBlock(") = #L(#L(", operationName, input)
                    nonNullMembers.forEach(::renderMemberPassing)
                    nullableMembers.forEach(::renderMemberPassing)
                    writer.closeBlock("))")
                } else {
                    val signature = "suspend fun $operationName(block: $input.Builder.() -> Unit)"
                    val impl = "$operationName($input.Builder().apply(block).build())"
                    writer.write("$signature = $impl")
                }
            }
        }
    }
}
