/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.netty

import io.ktor.client.*
import io.ktor.client.engine.*

/**
 * A JVM client engine that uses [Netty](https://netty.io) for network communication.
 *
 * To create the client with this engine, pass it to the `HttpClient` constructor:
 * ```kotlin
 * val client = HttpClient(Netty)
 * ```
 * To configure the engine, pass settings exposed by [NettyClientEngineConfig] to the `engine` method:
 * ```kotlin
 * val client = HttpClient(Netty) {
 *     engine {
 *         // this: NettyClientEngineConfig
 *     }
 * }
 * ```
 *
 * You can learn more about client engines from [Engines](https://ktor.io/docs/http-client-engines.html).
 */
public data object Netty : HttpClientEngineFactory<NettyClientEngineConfig> {
    override fun create(block: NettyClientEngineConfig.() -> Unit): HttpClientEngine =
        NettyHttpEngine(NettyClientEngineConfig().apply(block))
}

public class NettyEngineContainer : HttpClientEngineContainer {
    override val factory: HttpClientEngineFactory<*> = Netty

    override fun toString(): String = "Netty"
}
