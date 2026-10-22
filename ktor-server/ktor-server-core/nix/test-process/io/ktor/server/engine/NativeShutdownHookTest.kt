/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(ExperimentalForeignApi::class)

package io.ktor.server.engine

import io.ktor.server.engine.test.interop.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class NativeShutdownHookTest {
    @Test
    fun sigtermStopsWaitingServer() = checkShutdown("wait", SIGTERM)

    @Test
    fun sigintStopsWaitingServer() = checkShutdown("wait", SIGINT)

    @Test
    fun repeatedSignalsStopOnlyOnce() = checkShutdown("repeat", SIGTERM)

    @Test
    fun signalStopsNonWaitingServer() = checkShutdown("no-wait", SIGTERM)

    @Test
    fun waitsForTheLastRegisteredCallback() = checkShutdown("callback", SIGTERM)

    @Test
    fun manualStopDoesNotWaitForSignal() = checkShutdown("manual", null)

    @Test
    fun listenerSurvivesThrowingCallback() = checkShutdown("throw", SIGTERM)

    @Test
    fun registrationRestoresSignalHandlers() = checkShutdown("reinstall", SIGTERM)

    private fun checkShutdown(mode: String, signal: Int?) {
        val output = checkNotNull(tmpfile()) { "Cannot create child log: errno $errno" }
        try {
            val input = checkNotNull(tmpfile()) { "Cannot create child input: errno $errno" }
            try {
                runChild(mode, signal, fileno(input), fileno(output))
            } finally {
                fclose(input)
            }
        } finally {
            fclose(output)
        }
    }

    private fun runChild(mode: String, signal: Int?, input: Int, output: Int) = memScoped {
        val childPid = alloc<IntVar>()
        val result = ktor_test_spawn(mode, input, output, childPid.ptr)
        check(result == 0) { "Cannot spawn shutdown test child: errno $result" }
        val pid = childPid.value
        val status = alloc<IntVar>()
        var exitStatus: Int? = null

        fun pollExit(): Int? {
            if (exitStatus != null) return exitStatus
            val waited = waitpid(pid, status.ptr, WNOHANG)
            check(waited >= 0 || errno == EINTR) { "Cannot wait for child $pid: errno $errno" }
            if (waited == pid) exitStatus = status.value
            return exitStatus
        }

        fun awaitOutput(marker: String) {
            val started = TimeSource.Monotonic.markNow()
            while (marker !in readOutput(output)) {
                check(pollExit() == null && started.elapsedNow() < 15.seconds) {
                    "Missing $marker from child $pid:\n${readOutput(output)}"
                }
                usleep(10_000u)
            }
        }

        fun awaitExit(timeout: Duration): Int {
            val started = TimeSource.Monotonic.markNow()
            while (true) {
                pollExit()?.let { return it }
                check(started.elapsedNow() < timeout) { "Child $pid hung:\n${readOutput(output)}" }
                usleep(10_000u)
            }
        }

        fun sendSignal(signal: Int) {
            check(kill(pid, signal) == 0) { "Cannot send signal $signal to child $pid: errno $errno" }
        }

        try {
            awaitOutput("SHUTDOWN_READY")
            if (signal != null) {
                sendSignal(signal)
                if (mode == "repeat") {
                    awaitOutput("SHUTDOWN_STOPPING")
                    repeat(20) { sendSignal(if (it % 2 == 0) SIGINT else SIGTERM) }
                    val acknowledgement = alloc<ByteVar>().apply { value = 1 }
                    check(pwrite(input, acknowledgement.ptr, 1.convert(), 0) > 0)
                } else if (mode == "throw") {
                    awaitOutput("RETRY_READY")
                    sendSignal(signal)
                }
            }
            val childStatus = awaitExit(15.seconds)
            val log = readOutput(output)
            assertEquals(0, ktor_test_exit_code(childStatus), "Child $pid status $childStatus:\n$log")
            assertContains(log, "SHUTDOWN_RETURNED")
            if (mode == "throw") {
                assertContains(log, "Exception in shutdown hook")
                assertContains(log, "Expected shutdown hook failure")
            } else {
                assertFalse("Exception in shutdown hook" in log, log)
            }
            if (mode == "callback") {
                assertContains(log, "CALLBACK_COMPLETED")
                assertTrue(log.indexOf("CALLBACK_COMPLETED") < log.indexOf("SHUTDOWN_RETURNED"), log)
            }
        } finally {
            if (exitStatus == null) {
                kill(pid, SIGKILL)
                awaitExit(5.seconds)
            }
        }
    }

    private fun readOutput(fd: Int): String = memScoped {
        val buffer = allocArray<ByteVar>(4096)
        var offset = 0L
        buildString {
            while (true) {
                // pread leaves the shared file offset used by the child's stdout untouched.
                val count = pread(fd, buffer, 4096.convert(), offset)
                if (count < 0 && errno == EINTR) continue
                check(count >= 0) { "Cannot read child output: errno $errno" }
                if (count == 0L) break
                append(buffer.readBytes(count.toInt()).decodeToString())
                offset += count
            }
        }
    }
}
