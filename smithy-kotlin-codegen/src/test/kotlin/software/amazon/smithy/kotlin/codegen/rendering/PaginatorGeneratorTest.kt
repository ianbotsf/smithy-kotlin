/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.amazon.smithy.kotlin.codegen.rendering

import software.amazon.smithy.build.MockManifest
import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.kotlin.codegen.KotlinSettings
import software.amazon.smithy.kotlin.codegen.core.CodegenContext
import software.amazon.smithy.kotlin.codegen.integration.KotlinIntegration
import software.amazon.smithy.kotlin.codegen.rendering.protocol.ProtocolGenerator
import software.amazon.smithy.kotlin.codegen.test.newTestContext
import software.amazon.smithy.kotlin.codegen.test.shouldContainOnlyOnceWithDiff
import software.amazon.smithy.kotlin.codegen.test.toSmithyModel
import software.amazon.smithy.model.Model
import kotlin.test.Test

class PaginatorGeneratorTest {
    private val testModelWithItems = """
        namespace com.test
        
        use aws.protocols#restJson1
        
        service Lambda {
            operations: [ListFunctions]
        }
        
        @paginated(
            inputToken: "Marker",
            outputToken: "NextMarker",
            pageSize: "MaxItems",
            items: "Functions"
        )
        
        @readonly
        @http(method: "GET", uri: "/functions", code: 200)
        operation ListFunctions {
            input: ListFunctionsRequest,
            output: ListFunctionsResponse
        }
        
        structure ListFunctionsRequest {
            @httpQuery("FunctionVersion")
            FunctionVersion: String,
            @httpQuery("Marker")
            Marker: String,
            @httpQuery("MasterRegion")
            MasterRegion: String,
            @httpQuery("MaxItems")
            MaxItems: Integer
        }
        
        structure ListFunctionsResponse {
            Functions: FunctionConfigurationList,
            NextMarker: String
        }
        
        list FunctionConfigurationList {
            member: FunctionConfiguration
        }
        
        structure FunctionConfiguration {
            FunctionName: String
        }
        """.toSmithyModel()
    private val testContextWithItems = testModelWithItems.newTestContext("Lambda", "com.test")

    private val codegenContextWithItems = object : CodegenContext {
        override val model: Model = testContextWithItems.generationCtx.model
        override val symbolProvider: SymbolProvider = testContextWithItems.generationCtx.symbolProvider
        override val settings: KotlinSettings = testContextWithItems.generationCtx.settings
        override val protocolGenerator: ProtocolGenerator? = testContextWithItems.generator
        override val integrations: List<KotlinIntegration> = testContextWithItems.generationCtx.integrations
    }

    private val testModelNoItem = """
        namespace com.test
        
        use aws.protocols#restJson1
        
        service Lambda {
            operations: [ListFunctions]
        }
        
        @paginated(
            inputToken: "Marker",
            outputToken: "NextMarker",
            pageSize: "MaxItems"
        )
        
        @readonly
        @http(method: "GET", uri: "/functions", code: 200)
        operation ListFunctions {
            input: ListFunctionsRequest,
            output: ListFunctionsResponse
        }
        
        structure ListFunctionsRequest {
            @httpQuery("FunctionVersion")
            FunctionVersion: String,
            @httpQuery("Marker")
            Marker: String,
            @httpQuery("MasterRegion")
            MasterRegion: String,
            @httpQuery("MaxItems")
            MaxItems: Integer
        }
        
        structure ListFunctionsResponse {
            Functions: FunctionConfigurationList,
            NextMarker: String
        }
        
        list FunctionConfigurationList {
            member: FunctionConfiguration
        }
        
        structure FunctionConfiguration {
            FunctionName: String
        }
        """.toSmithyModel()
    private val testContextNoItem = testModelNoItem.newTestContext("Lambda", "com.test")

    private val codegenContextNoItem = object : CodegenContext {
        override val model: Model = testContextNoItem.generationCtx.model
        override val symbolProvider: SymbolProvider = testContextNoItem.generationCtx.symbolProvider
        override val settings: KotlinSettings = testContextNoItem.generationCtx.settings
        override val protocolGenerator: ProtocolGenerator? = testContextNoItem.generator
        override val integrations: List<KotlinIntegration> = testContextNoItem.generationCtx.integrations
    }

    @Test
    fun testRenderPaginatorNoItem() {
        val unit = PaginatorGenerator()
        unit.writeAdditionalFiles(codegenContextNoItem, testContextNoItem.generationCtx.delegator)

        testContextNoItem.generationCtx.delegator.flushWriters()
        val testManifest = testContextNoItem.generationCtx.delegator.fileManifest as MockManifest
        val actual = testManifest.expectFileString("src/main/kotlin/com/test/paginators/Paginators.kt")

        val expected = """
            /**
             * Paginate over [ListFunctionsResponse] results.
             *
             * When this operation is called, a [kotlinx.coroutines.Flow] is created. Flows are lazy (cold) so no service
             * calls are made until the flow is collected. This also means there is no guarantee that the request is valid
             * until then. Once you start collecting the flow, the SDK will lazily load response pages by making service
             * calls until there are no pages left or the flow is cancelled. If there are errors in your request, you will
             * see the failures only after you start collection.
             * @param initialRequest A [ListFunctionsRequest] to start pagination
             * @return A [kotlinx.coroutines.flow.Flow] that can collect [ListFunctionsResponse]
             */
            fun TestClient.listFunctionsPaginated(initialRequest: ListFunctionsRequest): Flow<ListFunctionsResponse> =
                flow {
                    var cursor: kotlin.String? = null
                    var isFirstPage: Boolean = true
            
                    while (isFirstPage || (cursor?.isNotEmpty() == true)) {
                        val req = initialRequest.copy {
                            this.marker = cursor
                        }
                        val result = this@listFunctionsPaginated.listFunctions(req)
                        isFirstPage = false
                        cursor = result.nextMarker
                        emit(result)
                    }
                }
            
            /**
             * Paginate over [ListFunctionsResponse] results.
             *
             * When this operation is called, a [kotlinx.coroutines.Flow] is created. Flows are lazy (cold) so no service
             * calls are made until the flow is collected. This also means there is no guarantee that the request is valid
             * until then. Once you start collecting the flow, the SDK will lazily load response pages by making service
             * calls until there are no pages left or the flow is cancelled. If there are errors in your request, you will
             * see the failures only after you start collection.
             * @param block A builder block used for DSL-style invocation of the operation
             * @return A [kotlinx.coroutines.flow.Flow] that can collect [ListFunctionsResponse]
             */
            fun TestClient.listFunctionsPaginated(block: ListFunctionsRequest.Builder.() -> Unit): Flow<ListFunctionsResponse> =
                listFunctionsPaginated(ListFunctionsRequest.Builder().apply(block).build())
        """.trimIndent()

        actual.shouldContainOnlyOnceWithDiff(expected)
    }

    @Test
    fun testRenderPaginatorWithItem() {
        val unit = PaginatorGenerator()
        unit.writeAdditionalFiles(codegenContextWithItems, testContextWithItems.generationCtx.delegator)

        testContextWithItems.generationCtx.delegator.flushWriters()
        val testManifest = testContextWithItems.generationCtx.delegator.fileManifest as MockManifest
        val actual = testManifest.expectFileString("src/main/kotlin/com/test/paginators/Paginators.kt")

        val expectedCode = """
            /**
             * Paginate over [ListFunctionsResponse] results.
             *
             * When this operation is called, a [kotlinx.coroutines.Flow] is created. Flows are lazy (cold) so no service
             * calls are made until the flow is collected. This also means there is no guarantee that the request is valid
             * until then. Once you start collecting the flow, the SDK will lazily load response pages by making service
             * calls until there are no pages left or the flow is cancelled. If there are errors in your request, you will
             * see the failures only after you start collection.
             * @param initialRequest A [ListFunctionsRequest] to start pagination
             * @return A [kotlinx.coroutines.flow.Flow] that can collect [ListFunctionsResponse]
             */
            fun TestClient.listFunctionsPaginated(initialRequest: ListFunctionsRequest): Flow<ListFunctionsResponse> =
                flow {
                    var cursor: kotlin.String? = null
                    var isFirstPage: Boolean = true
            
                    while (isFirstPage || (cursor?.isNotEmpty() == true)) {
                        val req = initialRequest.copy {
                            this.marker = cursor
                        }
                        val result = this@listFunctionsPaginated.listFunctions(req)
                        isFirstPage = false
                        cursor = result.nextMarker
                        emit(result)
                    }
                }
            
            /**
             * Paginate over [ListFunctionsResponse] results.
             *
             * When this operation is called, a [kotlinx.coroutines.Flow] is created. Flows are lazy (cold) so no service
             * calls are made until the flow is collected. This also means there is no guarantee that the request is valid
             * until then. Once you start collecting the flow, the SDK will lazily load response pages by making service
             * calls until there are no pages left or the flow is cancelled. If there are errors in your request, you will
             * see the failures only after you start collection.
             * @param block A builder block used for DSL-style invocation of the operation
             * @return A [kotlinx.coroutines.flow.Flow] that can collect [ListFunctionsResponse]
             */
            fun TestClient.listFunctionsPaginated(block: ListFunctionsRequest.Builder.() -> Unit): Flow<ListFunctionsResponse> =
                listFunctionsPaginated(ListFunctionsRequest.Builder().apply(block).build())
            
            /**
             * This paginator transforms the flow returned by [listFunctionsPaginated]
             * to access the nested member [FunctionConfiguration]
             * @return A [kotlinx.coroutines.flow.Flow] that can collect [FunctionConfiguration]
             */
            @JvmName("listFunctionsResponseFunctionConfiguration")
            fun Flow<ListFunctionsResponse>.functions(): Flow<FunctionConfiguration> =
                transform() { response ->
                    response.functions?.forEach {
                        emit(it)
                    }
                }
        """.trimIndent()

        actual.shouldContainOnlyOnceWithDiff(expectedCode)

        val expectedImports = """
            import com.test.model.FunctionConfiguration
            import com.test.model.ListFunctionsRequest
            import com.test.model.ListFunctionsResponse
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.transform
        """.trimIndent()

        actual.shouldContainOnlyOnceWithDiff(expectedImports)
    }
}
