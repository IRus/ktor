/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.netty

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.network.tls.certificates.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.net.*
import java.security.*
import javax.net.ssl.*
import kotlin.test.*
import io.ktor.server.netty.Netty as NettyServer

class NettyTlsTest {

    private val keyStore: KeyStore = buildKeyStore {
        certificate("test") {
            password = "changeit"
            domains = listOf("localhost")
            ipAddresses = listOf(InetAddress.getByName("127.0.0.1"))
        }
    }

    private val port = ServerSocket(0).use { it.localPort }

    private val server = embeddedServer(
        NettyServer,
        configure = {
            sslConnector(keyStore, "test", { "changeit".toCharArray() }, { "changeit".toCharArray() }) {
                port = this@NettyTlsTest.port
            }
        }
    ) {
        routing {
            get("/") { call.respondText("Hello, TLS!") }
        }
    }

    private val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(keyStore) }

    @BeforeTest
    fun startServer() {
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.stop(0, 0)
    }

    @Test
    fun testTrustedCertificate(): Unit = runBlocking {
        createClient().use { client ->
            assertEquals("Hello, TLS!", client.get("https://localhost:$port/").bodyAsText())
            assertEquals("Hello, TLS!", client.get("https://127.0.0.1:$port/").bodyAsText())
        }
    }

    @Test
    fun testHostnameIsVerified(): Unit = runBlocking {
        createClient().use { client ->
            // 127.0.0.2 is a loopback address, but it isn't listed in the certificate
            assertFailsWith<SSLHandshakeException> {
                client.get("https://127.0.0.2:$port/")
            }
        }
    }

    @Test
    fun testUntrustedCertificate(): Unit = runBlocking {
        HttpClient(Netty).use { client ->
            assertFailsWith<SSLHandshakeException> {
                client.get("https://localhost:$port/")
            }
        }
    }

    private fun createClient() = HttpClient(Netty) {
        engine {
            sslContext {
                trustManager(trustManagerFactory)
            }
        }
    }
}
