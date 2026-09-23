/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.netty

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.test.base.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.netty.channel.*
import io.netty.channel.nio.*
import kotlinx.coroutines.*
import kotlinx.io.readByteArray
import java.io.*
import java.net.*
import java.util.concurrent.atomic.*
import kotlin.concurrent.*
import kotlin.test.*

class NettyEngineTests : TestWithKtor() {

    private val concurrentRequests = AtomicInteger()
    private val maxConcurrentRequests = AtomicInteger()

    override val server = embeddedServer(CIO, serverPort) {
        routing {
            get("/port") {
                call.respondText(call.request.local.remotePort.toString())
            }
            get("/concurrent") {
                val current = concurrentRequests.incrementAndGet()
                maxConcurrentRequests.updateAndGet { maxOf(it, current) }
                delay(100)
                concurrentRequests.decrementAndGet()
                call.respondText("OK")
            }
            post("/echo") {
                call.respondText(call.receiveText())
            }
            get("/stream") {
                call.respondBytesWriter {
                    repeat(100) {
                        writeFully(ByteArray(64 * 1024) { it.toByte() })
                        flush()
                    }
                }
            }
        }
    }

    @Test
    fun testConnectionIsReused() = testWithEngine(Netty) {
        test { client ->
            val ports = List(5) { client.get("$testUrl/port").bodyAsText() }
            assertEquals(1, ports.toSet().size, "Expected a single connection to be used, but got ports $ports")
        }
    }

    @Test
    fun testConnectionIsNotReusedWithoutKeepAlive() = testWithEngine(Netty) {
        config {
            engine {
                keepAliveTime = 0
            }
        }

        test { client ->
            val ports = List(3) { client.get("$testUrl/port").bodyAsText() }
            assertEquals(3, ports.toSet().size, "Expected separate connections, but got ports $ports")
        }
    }

    @Test
    fun testConnectionIsNotReusedAfterConnectionClose() = testWithEngine(Netty) {
        test { client ->
            val ports = List(3) {
                client.get("$testUrl/port") {
                    header(HttpHeaders.Connection, "close")
                }.bodyAsText()
            }
            assertEquals(3, ports.toSet().size, "Expected separate connections, but got ports $ports")
        }
    }

    @Test
    fun testConnectionIsReusedAfterStreamingResponse() = testWithEngine(Netty) {
        test { client ->
            val firstPort = client.get("$testUrl/port").bodyAsText()
            val bytes = client.get("$testUrl/stream").bodyAsChannel().readBuffer().readByteArray()
            assertEquals(100 * 64 * 1024, bytes.size)
            val secondPort = client.get("$testUrl/port").bodyAsText()
            assertEquals(firstPort, secondPort)
        }
    }

    @Test
    fun testConnectionIsNotReusedAfterPartiallyReadResponse() = testWithEngine(Netty) {
        test { client ->
            val firstPort = client.get("$testUrl/port").bodyAsText()
            client.prepareGet("$testUrl/stream").execute { response ->
                val channel = response.bodyAsChannel()
                channel.readBuffer(1024)
            }
            val secondPort = client.get("$testUrl/port").bodyAsText()
            assertNotEquals(firstPort, secondPort)
        }
    }

    @Test
    fun testMaxConnectionsPerRoute() = testWithEngine(Netty) {
        config {
            engine {
                maxConnectionsPerRoute = 2
            }
        }

        test { client ->
            coroutineScope {
                List(6) {
                    async { client.get("$testUrl/concurrent").bodyAsText() }
                }.awaitAll().forEach { assertEquals("OK", it) }
            }

            assertEquals(2, maxConcurrentRequests.get())
        }
    }

    @Test
    fun testExpectContinue(): Unit = runBlocking {
        val body = "Hello, world"
        val server = RawHttpServer { socket, request ->
            assertTrue(request.contains("Expect: 100-continue", ignoreCase = true), request)
            socket.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())

            val receivedBody = ByteArray(body.length)
            DataInputStream(socket.getInputStream()).readFully(receivedBody)
            socket.getOutputStream().write(
                "HTTP/1.1 200 OK\r\nContent-Length: ${receivedBody.size}\r\n\r\n".toByteArray() + receivedBody
            )
        }

        server.use {
            HttpClient(Netty).use { client ->
                val response = client.post(server.url) {
                    header(HttpHeaders.Expect, "100-continue")
                    setBody(body)
                }
                assertEquals(body, response.bodyAsText())
            }
        }
    }

    @Test
    fun testExpectContinueWithFinalResponse(): Unit = runBlocking {
        val server = RawHttpServer { socket, _ ->
            socket.getOutputStream().write(
                "HTTP/1.1 417 Expectation Failed\r\nContent-Length: 0\r\n\r\n".toByteArray()
            )
        }

        server.use {
            HttpClient(Netty).use { client ->
                val response = client.post(server.url) {
                    header(HttpHeaders.Expect, "100-continue")
                    setBody("Hello, world")
                }
                assertEquals(HttpStatusCode.ExpectationFailed, response.status)
            }
        }
    }

    @Test
    fun testStreamingRequestBody() = testWithEngine(Netty) {
        test { client ->
            val content = "x".repeat(1024 * 1024)
            val response = client.post("$testUrl/echo") {
                setBody(ByteReadChannel(content))
            }
            assertEquals(content, response.bodyAsText())
        }
    }

    @Test
    fun testServerClosesIdleConnection(): Unit = runBlocking {
        val serverSocket = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            repeat(2) {
                serverSocket.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine().isNotEmpty()) {
                        // Skip the request headers
                    }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray())
                        flush()
                    }
                }
            }
        }

        HttpClient(Netty).use { client ->
            repeat(2) {
                assertEquals("OK", client.get("http://127.0.0.1:${serverSocket.localPort}/").bodyAsText())
            }
        }

        serverJob.join()
        serverSocket.close()
    }

    @Test
    fun testExternalEventLoopGroupIsNotShutDown(): Unit = runBlocking {
        val group = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        try {
            HttpClient(Netty) {
                engine {
                    eventLoopGroup = group
                }
            }.use { client ->
                assertEquals("OK", client.get("$testUrl/concurrent").bodyAsText())
            }

            delay(100)
            assertFalse(group.isShuttingDown)
        } finally {
            group.shutdownGracefully()
        }
    }

    @Test
    fun testDefaultProxySelectorIsUsed() = testWithEngine(Netty) {
        test { client ->
            val defaultSelector = ProxySelector.getDefault()
            ProxySelector.setDefault(
                object : ProxySelector() {
                    override fun select(uri: URI): List<Proxy> = when (uri.host) {
                        "google.com" -> listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 8082)))
                        else -> listOf(Proxy.NO_PROXY)
                    }

                    override fun connectFailed(uri: URI, address: SocketAddress, cause: IOException) {}
                }
            )

            try {
                assertEquals("proxy", client.get("http://google.com/").bodyAsText())
                assertEquals("OK", client.get("$testUrl/concurrent").bodyAsText())
            } finally {
                ProxySelector.setDefault(defaultSelector)
            }
        }
    }

    @Test
    fun testConnectionRefused() = testWithEngine(Netty) {
        test { client ->
            val port = ServerSocket(0).use { it.localPort }
            assertFailsWith<ConnectException> {
                client.get("http://127.0.0.1:$port/")
            }
        }
    }

    @Test
    fun testSocketTimeoutConfig() = testWithEngine(Netty) {
        config {
            engine {
                socketTimeout = 500
            }
        }

        test { client ->
            val cause = assertFails {
                client.get("$TEST_SERVER/timeout/with-stream") {
                    parameter("delay", 5000)
                }.bodyAsText()
            }
            assertIs<SocketTimeoutException>(cause.rootCauseOrSelf())
        }
    }

    @Test
    fun testConnectTimeoutFromPlugin() = testWithEngine(Netty) {
        config {
            install(HttpTimeout) {
                connectTimeoutMillis = 1
            }
        }

        test { client ->
            // A non-routable address makes the connection hang until the timeout expires
            val cause = assertFails { client.get("http://10.255.255.1:12345/") }
            assertTrue(
                cause is io.ktor.client.network.sockets.ConnectTimeoutException || cause is ConnectException,
                "Unexpected exception: $cause"
            )
        }
    }

    private fun Throwable.rootCauseOrSelf(): Throwable {
        var current = this
        while (current !is SocketTimeoutException) {
            current = current.cause ?: return this
        }
        return current
    }
}

/**
 * A single-threaded HTTP server that passes the raw request head to the [handler] for each accepted connection.
 */
private class RawHttpServer(
    private val handler: (socket: Socket, request: String) -> Unit
) : Closeable {
    private val serverSocket = ServerSocket(0)

    @Volatile
    private var failure: Throwable? = null
    private val thread = thread(isDaemon = true) {
        while (!serverSocket.isClosed) {
            try {
                serverSocket.accept().use { socket ->
                    val request = readRequestHead(socket.getInputStream())
                    handler(socket, request)
                    socket.getOutputStream().flush()
                }
            } catch (cause: Throwable) {
                if (!serverSocket.isClosed) failure = cause
            }
        }
    }

    val url: String = "http://127.0.0.1:${serverSocket.localPort}/"

    private fun readRequestHead(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte == -1) break
            head.append(byte.toChar())
        }
        return head.toString()
    }

    override fun close() {
        serverSocket.close()
        thread.join()
        failure?.let { throw it }
    }
}
