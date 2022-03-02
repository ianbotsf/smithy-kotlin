/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.kotlin.codegen.rendering.waiters

import software.amazon.smithy.kotlin.codegen.core.*
import software.amazon.smithy.kotlin.codegen.lang.KotlinTypes
import software.amazon.smithy.kotlin.codegen.model.isBoxed
import software.amazon.smithy.model.shapes.MemberShape
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

/**
 * Renders the top-level retry strategy for a waiter.
 */
private fun KotlinWriter.renderRetryStrategy(wi: WaiterInfo, asValName: String) {
    addImport(
        RuntimeTypes.Core.Retries.Delay.ExponentialBackoffWithJitterOptions,
        RuntimeTypes.Core.Retries.Delay.ExponentialBackoffWithJitter,
        RuntimeTypes.Core.Retries.StandardRetryStrategyOptions,
        RuntimeTypes.Core.Retries.StandardRetryStrategy,
        RuntimeTypes.Core.Retries.Delay.InfiniteTokenBucket,
    )

    withBlock("val #L = run {", "}", asValName) {
        withBlock("val delayOptions = ExponentialBackoffWithJitterOptions(", ")") {
            write("initialDelay = #L.#T,", wi.waiter.minDelay.msFormat, KotlinTypes.Time.milliseconds)
            write("scaleFactor = 1.5,")
            write("jitter = 1.0,")
            write("maxBackoff = #L.#T,", wi.waiter.maxDelay.msFormat, KotlinTypes.Time.milliseconds)
        }
        write("val delay = ExponentialBackoffWithJitter(delayOptions)")
        write("")
        write("val waiterOptions = StandardRetryStrategyOptions(maxTime = 300.#T, maxAttempts = 20)", KotlinTypes.Time.seconds)
        write("StandardRetryStrategy(waiterOptions, InfiniteTokenBucket, delay)")
    }
}

/**
 * Renders the client extension methods for a waiter.
 */
internal fun KotlinWriter.renderWaiter(wi: WaiterInfo) {
    addImport(
        wi.serviceSymbol,
        wi.inputSymbol,
        wi.outputSymbol,
        RuntimeTypes.Core.Retries.Outcome,
        RuntimeTypes.Core.Retries.Delay.ExponentialBackoffWithJitterOptions,
        RuntimeTypes.Core.Retries.Delay.ExponentialBackoffWithJitter,
        RuntimeTypes.Core.Retries.StandardRetryStrategyOptions,
        RuntimeTypes.Core.Retries.StandardRetryStrategy,
        RuntimeTypes.Core.Retries.Policy.RetryDirective,
        RuntimeTypes.Core.Retries.Policy.AcceptorRetryPolicy,
    )

    write("")
    wi.waiter.documentation.ifPresent(::dokka)
    withBlock(
        "suspend fun #T.#L(request: #T): Outcome<#T> {",
        "}",
        wi.serviceSymbol,
        wi.methodName,
        wi.inputSymbol,
        wi.outputSymbol,
    ) {
        renderRetryStrategy(wi, "strategy")
        write("")
        renderAcceptorList(wi, "acceptors")
        write("")
        write("val policy = AcceptorRetryPolicy(request, acceptors)")
        write("return strategy.retry(policy) { #L(request) }", wi.opMethodName)
    }

    write("")
    wi.waiter.documentation.ifPresent(this::dokka)
    if (wi.ctx.settings.codegen.dataClasses) {
        // FIXME This is necessary because #F doesn't actually fully-qualify type parameters e.g.,
        // Map<String, Condition> vs kotlin.collections.Map<kotlin.String, aws.sdk.kotlin.services.dynamodb.model.Condition>
        addImport(wi.inputSymbol.namespace, "*")

        val inputShape = wi.input
        val memberShapes = inputShape.allMembers.values
        val memberNameSymbolIndex = memberShapes.associateWith {
            wi.ctx.symbolProvider.toMemberName(it) to wi.ctx.symbolProvider.toSymbol(it)
        }
        val (nullableMembers, nonNullMembers) = inputShape
            .allMembers
            .values
            .partition { memberNameSymbolIndex[it]!!.second.isBoxed }

        fun renderMember(m: MemberShape, defaultValueString: String = "") {
            val (name, symbol) = memberNameSymbolIndex[m]!!
            // FIXME Using #F because #T doesn't properly include nullability for boxed symbols
            write("#L: #F$defaultValueString,", name, symbol)
        }

        fun renderMemberPassing(m: MemberShape) {
            val (name, _) = memberNameSymbolIndex[m]!!
            write("#1L = #1L,", name)
        }

        openBlock("suspend fun #T.#L(", wi.serviceSymbol, wi.methodName)
        nonNullMembers.forEach { renderMember(it) }
        nullableMembers.forEach { renderMember(it, " = null") }
        closeAndOpenBlock(") = #L(#T(", wi.methodName, wi.inputSymbol)
        nonNullMembers.forEach(::renderMemberPassing)
        nullableMembers.forEach(::renderMemberPassing)
        closeBlock("))")
    } else {
        write(
            "suspend fun #T.#L(block: #T.Builder.() -> Unit): Outcome<#T> =",
            wi.serviceSymbol,
            wi.methodName,
            wi.inputSymbol,
            wi.outputSymbol,
        )
        indent()
        write("#L(#T.Builder().apply(block).build())", wi.methodName, wi.inputSymbol)
        dedent()
    }
}

private val thousandsFormatter = DecimalFormat(",###", DecimalFormatSymbols().apply { groupingSeparator = '_' })

private val Int.msFormat: String
    get() = thousandsFormatter.format(this * 1000)
