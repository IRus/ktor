/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.netty

import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.netty.bootstrap.*
import io.netty.channel.*
import io.netty.channel.nio.*
import io.netty.channel.socket.nio.*
import io.netty.handler.codec.http.*
import io.netty.handler.proxy.*
import io.netty.handler.ssl.*
import io.netty.resolver.*
import io.netty.util.concurrent.*
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.TimeUnit
import io.ktor.http.HttpHeaders as KtorHttpHeaders

private const val MAX_RETRIES = 3
private const val SHUTDOWN_TIMEOUT_MILLIS = 1000L

@OptIn(InternalAPI::class)
internal class NettyHttpEngine(override val config: NettyClientEngineConfig) : HttpClientEngineBase("ktor-netty") {

    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> =
        setOf(HttpTimeoutCapability, WebSocketCapability, WebSocketExtensionsCapability, SSECapability)

    private val eventLoopGroupDelegate = lazy {
        config.eventLoopGroup
            ?: MultiThreadIoEventLoopGroup(
                0,
                DefaultThreadFactory("ktor-netty-client", true),
                NioIoHandler.newFactory()
            )
    }
    private val eventLoopGroup: EventLoopGroup by eventLoopGroupDelegate

    private val sslContext: SslContext by lazy {
        config.sslContext ?: SslContextBuilder.forClient().apply(config.sslContextBuilder).build()
    }

    private val pool = NettyConnectionPool(config.maxConnectionsPerRoute, config.keepAliveTime)

    init {
        config.proxy?.checkSupported()

        coroutineContext.job.invokeOnCompletion {
            pool.close()
            if (config.eventLoopGroup == null && eventLoopGroupDelegate.isInitialized()) {
                eventLoopGroup.shutdownGracefully(0, SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val url = data.url.rebuildIfNeeded()
        val secure = url.protocol.isSecure()
        val route = NettyRoute(url.host, url.port, secure, selectProxy(url.host, url.port, secure))
        val timeouts = data.getCapabilityOrNull(HttpTimeoutCapability)
        val connectTimeout = timeouts?.connectTimeoutMillis ?: config.connectTimeout
        val socketTimeout = timeouts?.socketTimeoutMillis ?: config.socketTimeout
        val overProxy = route.proxy?.type() == Proxy.Type.HTTP && !route.secure

        var retries = 0
        while (true) {
            val connection = pool.acquire(route) { connect(route, data, connectTimeout) }
            val exchange = NettyHttpExchange(connection, data, url, callContext, this, overProxy, socketTimeout)

            try {
                return exchange.execute()
            } catch (cause: Throwable) {
                val retry = connection.reused &&
                    retries++ < MAX_RETRIES &&
                    callContext.isActive &&
                    exchange.canRetry(cause)

                if (!retry) throw cause
            }
        }
    }

    /**
     * Returns the proxy configured for the engine or selected by the default [ProxySelector] if not configured.
     */
    private fun selectProxy(host: String, port: Int, secure: Boolean): Proxy? {
        val proxy = config.proxy ?: defaultProxy(host, port, secure)
        return proxy?.takeIf { it.type() != Proxy.Type.DIRECT }
    }

    private fun defaultProxy(host: String, port: Int, secure: Boolean): Proxy? {
        val selector = ProxySelector.getDefault() ?: return null
        return try {
            val uri = URI(if (secure) "https" else "http", null, host, port, null, null, null)
            selector.select(uri).firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun connect(
        route: NettyRoute,
        request: HttpRequestData,
        connectTimeout: Long
    ): NettyConnection {
        val proxy = route.proxy
        val proxyHandler = createProxyHandler(route, request, connectTimeout)
        val remoteAddress = when {
            proxy == null -> resolve(route.host, route.port)
            proxyHandler == null -> resolve(proxy.address() as InetSocketAddress)
            else -> InetSocketAddress.createUnresolved(route.host, route.port)
        }

        val handler = NettyConnectionHandler()
        val bootstrap = Bootstrap()
            .group(eventLoopGroup)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.AUTO_READ, false)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, convertLongTimeoutToIntWithInfiniteAsZero(connectTimeout))
            .apply(config.bootstrapConfig)
            .handler(
                object : ChannelInitializer<Channel>() {
                    override fun initChannel(channel: Channel) {
                        val pipeline = channel.pipeline()
                        if (proxyHandler != null) pipeline.addLast("proxy", proxyHandler)
                        if (route.secure) pipeline.addLast("ssl", createSslHandler(channel, route, connectTimeout))
                        pipeline.addLast("codec", createCodec())
                        pipeline.addLast("handler", handler)
                    }
                }
            )

        if (proxyHandler != null) {
            // The target address is resolved by the proxy
            bootstrap.resolver(NoopAddressResolverGroup.INSTANCE)
        }

        val connectFuture = bootstrap.connect(remoteAddress)
        val channel = connectFuture.channel()
        try {
            connectFuture.awaitSuspend()
            proxyHandler?.connectFuture()?.awaitSuspend()
            channel.pipeline().get(SslHandler::class.java)?.handshakeFuture()?.awaitSuspend()
        } catch (cause: Throwable) {
            connectFuture.cancel(false)
            channel.close()
            throw cause.mapConnectException(request)
        }

        return NettyConnection(channel, handler, route, pool)
    }

    private suspend fun resolve(address: InetSocketAddress): InetSocketAddress {
        if (!address.isUnresolved) return address
        return resolve(address.hostString, address.port)
    }

    private suspend fun resolve(host: String, port: Int): InetSocketAddress {
        val address = withContext(dispatcher) { InetSocketAddress(host, port) }
        if (address.isUnresolved) throw UnknownHostException(host)
        return address
    }

    private suspend fun createProxyHandler(
        route: NettyRoute,
        request: HttpRequestData,
        connectTimeout: Long
    ): ProxyHandler? {
        val proxy = route.proxy ?: return null
        val address = resolve(proxy.address() as InetSocketAddress)

        val handler = when (proxy.type()) {
            Proxy.Type.HTTP -> {
                // Plain HTTP requests are sent to the proxy directly
                if (!route.secure) return null

                val headers = DefaultHttpHeaders()
                for (name in TUNNEL_HEADERS) {
                    request.headers.getAll(name)?.forEach { headers.add(name, it) }
                }
                HttpProxyHandler(address, headers)
            }

            Proxy.Type.SOCKS -> Socks5ProxyHandler(address)

            else -> error("Netty engine does not currently support ${proxy.type()} proxies.")
        }

        handler.setConnectTimeoutMillis(convertLongTimeoutToLongWithInfiniteAsZero(connectTimeout))
        return handler
    }

    private fun createSslHandler(channel: Channel, route: NettyRoute, connectTimeout: Long): SslHandler {
        val handler = sslContext.newHandler(channel.alloc(), route.host, route.port)
        val engine = handler.engine()
        engine.sslParameters = engine.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
        }
        handler.handshakeTimeoutMillis = convertLongTimeoutToLongWithInfiniteAsZero(connectTimeout)
        return handler
    }

    private fun createCodec(): HttpClientCodec {
        val decoderConfig = HttpDecoderConfig()
            .setMaxInitialLineLength(config.maxInitialLineLength)
            .setMaxHeaderSize(config.maxHeaderSize)
            .setMaxChunkSize(config.maxChunkSize)
            .setValidateHeaders(false)

        return HttpClientCodec(decoderConfig, false, false)
    }

    private fun Proxy.checkSupported() {
        val type = type()
        check(type == Proxy.Type.DIRECT || type == Proxy.Type.HTTP || type == Proxy.Type.SOCKS) {
            "Netty engine does not currently support $type proxies."
        }
    }

    private companion object {
        val TUNNEL_HEADERS = listOf(
            KtorHttpHeaders.UserAgent,
            KtorHttpHeaders.ProxyAuthorization,
        )
    }
}
