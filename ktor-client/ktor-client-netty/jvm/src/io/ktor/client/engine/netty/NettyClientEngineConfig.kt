/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.netty

import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.netty.bootstrap.*
import io.netty.channel.*
import io.netty.handler.ssl.*

/**
 * A configuration for the [Netty] client engine.
 */
public class NettyClientEngineConfig : HttpClientEngineConfig() {

    /**
     * Specifies a time period (in milliseconds) in which a client should establish a connection with a server.
     *
     * This value is used when the [HttpTimeout] plugin doesn't specify a connect timeout for the request.
     * Use [HttpTimeoutConfig.INFINITE_TIMEOUT_MS] to disable the timeout.
     */
    public var connectTimeout: Long = 10_000

    /**
     * Specifies a maximum time (in milliseconds) of inactivity between two data packets when exchanging data
     * with a server.
     *
     * This value is used when the [HttpTimeout] plugin doesn't specify a socket timeout for the request.
     * Use [HttpTimeoutConfig.INFINITE_TIMEOUT_MS] to disable the timeout.
     */
    public var socketTimeout: Long = HttpTimeoutConfig.INFINITE_TIMEOUT_MS

    /**
     * Specifies the maximum number of simultaneously leased connections for each host.
     * When the limit is reached, new requests to the same host wait until a connection is released.
     */
    public var maxConnectionsPerRoute: Int = 100

    /**
     * Specifies a time period (in milliseconds) during which an idle connection is kept in the pool
     * to be reused by subsequent requests. Use `0` to disable connection reuse.
     */
    public var keepAliveTime: Long = 5_000

    /**
     * Specifies the maximum length of the HTTP response status line.
     */
    public var maxInitialLineLength: Int = 4096

    /**
     * Specifies the maximum size of all HTTP response headers.
     */
    public var maxHeaderSize: Int = 8192

    /**
     * Specifies the maximum size of a single content chunk produced by the HTTP response decoder.
     */
    public var maxChunkSize: Int = 8192

    /**
     * The [EventLoopGroup] used for network communication.
     *
     * If not set, the engine creates its own event loop group and shuts it down when the engine is closed.
     * An event loop group passed here is not shut down by the engine.
     * When using a non-NIO event loop group, specify a matching channel type using [bootstrap].
     */
    public var eventLoopGroup: EventLoopGroup? = null

    /**
     * An SSL context used for HTTPS and WSS connections.
     *
     * If not set, the engine builds one using [SslContextBuilder.forClient] and the [sslContext] configuration block.
     *
     * The engine enables the `HTTPS` endpoint identification algorithm, so server hostnames are verified by
     * the trust manager. Note that Netty wraps plain [javax.net.ssl.X509TrustManager] instances in a way
     * that skips hostname verification, so prefer passing a [javax.net.ssl.TrustManagerFactory]
     * or an [javax.net.ssl.X509ExtendedTrustManager] to [SslContextBuilder.trustManager].
     */
    public var sslContext: SslContext? = null

    internal var sslContextBuilder: SslContextBuilder.() -> Unit = {}

    internal var bootstrapConfig: Bootstrap.() -> Unit = {}

    /**
     * Configures the default [SslContext] using [SslContextBuilder].
     * This configuration is ignored if the [sslContext] property is set explicitly.
     *
     * ```kotlin
     * engine {
     *     sslContext {
     *         trustManager(myTrustManager)
     *     }
     * }
     * ```
     */
    public fun sslContext(block: SslContextBuilder.() -> Unit) {
        val oldConfig = sslContextBuilder
        sslContextBuilder = {
            oldConfig()
            block()
        }
    }

    /**
     * Configures a Netty [Bootstrap] used to create connections, for example, to set channel options
     * or a channel type matching a custom [eventLoopGroup].
     */
    public fun bootstrap(block: Bootstrap.() -> Unit) {
        val oldConfig = bootstrapConfig
        bootstrapConfig = {
            oldConfig()
            block()
        }
    }
}
