/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.engine

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*

internal class ShutdownHook(private val block: () -> Unit) {
    private val started = atomic(false)
    private val completed = CompletableDeferred<Unit>()

    fun run() {
        if (!started.compareAndSet(false, true)) return
        try {
            block()
        } finally {
            completed.complete(Unit)
        }
    }

    fun awaitCompletion() {
        if (started.value) {
            runBlocking { completed.await() }
        }
    }
}
