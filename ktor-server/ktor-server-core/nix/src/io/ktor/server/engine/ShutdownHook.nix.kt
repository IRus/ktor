/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.engine

import io.ktor.server.engine.interop.*
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.*
import kotlinx.cinterop.*

internal actual val SHUTDOWN_HOOK_ENABLED = true

private val shutdownHook = atomic<(() -> Unit)?>(null)
private val registrationLock = SynchronizedObject()

private var signalBridgeInitialized = false

// Called with registrationLock held, including when a failed initialization is retried.
@OptIn(ExperimentalForeignApi::class)
private fun ensureSignalBridge() {
    if (signalBridgeInitialized) return
    val result = ktor_shutdown_hook_init(staticCFunction<Unit> { shutdownHook.value?.invoke() })
    check(result == 0) { "Failed to initialize the shutdown signal listener: errno $result" }
    signalBridgeInitialized = true
}

/** Registers the last callback on a process-wide thread, outside the POSIX signal handler. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun EmbeddedServer<*, *>.platformAddShutdownHook(stop: () -> Unit) {
    synchronized(registrationLock) {
        ensureSignalBridge()
        val hook = ShutdownHook(stop)
        val previous = shutdownHook.value
        shutdownHook.value = {
            try {
                hook.run()
            } catch (cause: Throwable) {
                environment.log.error("Exception in shutdown hook", cause)
            }
        }
        val result = ktor_shutdown_hook_install()
        if (result != 0) {
            shutdownHook.value = previous
            error("Failed to install shutdown signal handlers: errno $result")
        }
        shutdownHookCompletion.value = hook
    }
}
