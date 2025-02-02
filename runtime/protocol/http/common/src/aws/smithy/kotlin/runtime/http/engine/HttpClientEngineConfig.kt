/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package aws.smithy.kotlin.runtime.http.engine

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// See https://github.com/aws/aws-sdk-java-v2/blob/master/http-client-spi/src/main/java/software/amazon/awssdk/http/SdkHttpConfigurationOption.java
// for all the options the Java v2 SDK supports

/**
 * Common configuration options to be interpreted by an underlying engine
 *
 * NOTE: Not all engines will support every option! Engines *SHOULD* log a warning when given a configuration
 * option they don't understand/support
 */
open class HttpClientEngineConfig constructor(builder: Builder) {
    constructor() : this(Builder())

    companion object {
        operator fun invoke(block: Builder.() -> Unit): HttpClientEngineConfig = HttpClientEngineConfig(Builder().apply(block))

        /**
         * Default client engine config
         */
        val Default: HttpClientEngineConfig = HttpClientEngineConfig(Builder())
    }

    /**
     * Timeout for each read to an underlying socket
     */
    val socketReadTimeout: Duration = builder.socketReadTimeout

    /**
     * Timeout for each write to an underlying socket
     */
    val socketWriteTimeout: Duration = builder.socketWriteTimeout

    /**
     * Maximum number of open connections
     */
    val maxConnections: UInt = builder.maxConnections

    /**
     * The amount of time to wait for a connection to be established
     */
    val connectTimeout: Duration = builder.connectTimeout

    /**
     * The amount of time to wait for an already-established connection from a connection pool
     */
    val connectionAcquireTimeout: Duration = builder.connectionAcquireTimeout

    /**
     * The amount of time before an idle connection should be reaped from a connection pool. Zero indicates that
     * idle connections should never be reaped.
     */
    val connectionIdleTimeout: Duration = builder.connectionIdleTimeout

    /**
     * Set the ALPN protocol list when a TLS connection starts
     */
    val alpn: List<AlpnId> = builder.alpn

    open class Builder {
        /**
         * Timeout for each read to an underlying socket
         */
        var socketReadTimeout: Duration = 30.seconds

        /**
         * Timeout for each write to an underlying socket
         */
        var socketWriteTimeout: Duration = 30.seconds

        /**
         * Maximum number of open connections
         */
        var maxConnections: UInt = 16u

        /**
         * The amount of time to wait for a connection to be established
         */
        var connectTimeout: Duration = 2.seconds

        /**
         * The amount of time to wait for an already-established connection from a connection pool
         */
        var connectionAcquireTimeout: Duration = 10.seconds

        /**
         * The amount of time before an idle connection should be reaped from a connection pool. Zero indicates that
         * idle connections should never be reaped.
         */
        var connectionIdleTimeout: Duration = 60.seconds

        /**
         * Set the ALPN protocol list when a TLS connection starts
         */
        var alpn: List<AlpnId> = emptyList()
    }
}

/**
 * Common ALPN identifiers
 * See the [IANA registry](https://www.iana.org/assignments/tls-extensiontype-values/tls-extensiontype-values.xhtml#alpn-protocol-ids)
 */
enum class AlpnId(val protocolId: String) {
    /**
     * HTTP 1.1
     */
    HTTP1_1("http/1.1"),

    /**
     * HTTP 2 over TLS
     */
    HTTP2("h2"),

    /**
     * HTTP 3
     */
    HTTP3("h3")
}
