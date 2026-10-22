/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(ExperimentalForeignApi::class)

package io.ktor.server.engine

import io.ktor.server.application.*
import io.ktor.server.cio.*
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*
import kotlin.test.Test

class NativeShutdownHookTestChild {
    // Re-executed in a child process so a deadlocked shutdown cannot hang the test runner.
    @Test
    fun child() {
        val mode = getenv("KTOR_SHUTDOWN_CHILD")?.toKString() ?: return
        val mainThread = pthread_self()
        val stopping = atomic(0)
        val stopped = CompletableDeferred<Unit>()
        val throwingHookCalled = CompletableDeferred<Unit>()
        lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
        server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            if (mode == "callback") {
                server.platformAddShutdownHook { error("The previous hook should have been replaced") }
                server.platformAddShutdownHook {
                    check(pthread_equal(mainThread, pthread_self()) == 0) { "Hook ran on the interrupted thread" }
                    server.stop()
                    runBlocking { delay(200) }
                    println("CALLBACK_COMPLETED")
                    fflush(stdout)
                }
            } else if (mode == "throw") {
                server.platformAddShutdownHook {
                    throwingHookCalled.complete(Unit)
                    error("Expected shutdown hook failure")
                }
            } else if (mode == "reinstall") {
                signal(SIGTERM, SIG_IGN)
                server.platformAddShutdownHook { server.stop() }
            }
        }
        server.monitor.subscribe(ServerReady) {
            println("SHUTDOWN_READY:${getpid()}")
            fflush(stdout)
        }
        server.monitor.subscribe(ApplicationStopping) {
            check(stopping.incrementAndGet() == 1)
            if (mode == "repeat") {
                // Keep shutdown in progress until the parent has delivered the entire signal burst.
                println("SHUTDOWN_STOPPING")
                fflush(stdout)
                memScoped {
                    val acknowledgement = alloc<ByteVar>()
                    while (true) {
                        val count = read(STDIN_FILENO, acknowledgement.ptr, 1.convert())
                        if (count > 0) break
                        check(count >= 0 || errno == EINTR) { "Cannot read parent acknowledgement: errno $errno" }
                        usleep(10_000u)
                    }
                    check(acknowledgement.value == 1.toByte())
                }
            }
        }
        server.monitor.subscribe(ApplicationStopped) { stopped.complete(Unit) }
        if (mode == "manual") {
            server.start(wait = false)
            server.stop()
        } else if (mode == "no-wait" || mode == "throw") {
            server.start(wait = false)
            if (mode == "throw") {
                runBlocking { throwingHookCalled.await() }
                server.shutdownHookCompletion.value?.awaitCompletion()
                server.platformAddShutdownHook { server.stop() }
                println("RETRY_READY")
                fflush(stdout)
            }
            runBlocking { stopped.await() }
            // ApplicationStopped precedes engine.stop; the hook completes only after engine.stop returns.
            server.shutdownHookCompletion.value?.awaitCompletion()
        } else {
            server.start(wait = true)
        }
        check(stopping.value == 1)
        println("SHUTDOWN_RETURNED")
        fflush(stdout)
    }
}
