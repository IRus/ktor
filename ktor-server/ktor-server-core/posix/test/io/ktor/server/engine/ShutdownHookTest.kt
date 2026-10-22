/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.engine

import kotlinx.coroutines.*
import kotlin.test.*

class ShutdownHookTest {
    @Test
    fun runsAtMostOnce() = runBlocking {
        var calls = 0
        val hook = ShutdownHook { calls++ }
        List(20) { launch(Dispatchers.Default) { hook.run() } }.joinAll()
        hook.awaitCompletion()
        assertEquals(1, calls)
    }

    @Test
    fun doesNotWaitForAnUnstartedHook() {
        ShutdownHook { error("Should not run") }.awaitCompletion()
    }

    @Test
    fun completesEvenWhenCallbackThrows() {
        val hook = ShutdownHook { error("Failure") }
        assertFailsWith<IllegalStateException> { hook.run() }
        hook.awaitCompletion()
        hook.run()
    }

    @Test
    fun waitsForTheWholeCallback() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var finished = false
        val hook = ShutdownHook {
            started.complete(Unit)
            runBlocking { delay(200) }
            finished = true
        }
        val runner = launch(Dispatchers.Default) { hook.run() }
        started.await()
        hook.awaitCompletion()
        assertTrue(finished)
        runner.join()
    }
}
