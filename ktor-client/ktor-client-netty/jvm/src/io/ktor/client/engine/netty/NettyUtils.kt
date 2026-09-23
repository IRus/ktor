/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.netty

import io.ktor.client.request.*
import io.ktor.http.*
import io.netty.channel.*
import io.netty.handler.codec.DecoderException
import io.netty.util.concurrent.*
import kotlinx.coroutines.*
import java.io.*
import java.util.*
import java.util.concurrent.RejectedExecutionException
import io.netty.handler.codec.http.HttpHeaders as NettyHttpHeaders
import io.netty.handler.codec.http.HttpVersion as NettyHttpVersion

/**
 * Suspends until this Netty [Future] is completed, throwing its failure cause if any.
 * Cancellation of the calling coroutine doesn't cancel the future.
 */
internal suspend fun Future<*>.awaitSuspend() {
    if (isDone) {
        throwIfFailed()
        return
    }

    suspendCancellableCoroutine { continuation ->
        addListener { future ->
            continuation.resumeWith(runCatching { future.throwIfFailed() })
        }
    }
}

private fun Future<*>.throwIfFailed() {
    if (isSuccess) return
    throw cause() ?: IOException("Operation failed")
}

/**
 * Runs the [block] on the event loop of this channel, immediately if the current thread is the event loop.
 */
internal inline fun Channel.runOnEventLoop(crossinline block: () -> Unit) {
    val eventLoop = eventLoop()
    if (eventLoop.inEventLoop()) {
        block()
        return
    }

    try {
        eventLoop.execute { block() }
    } catch (_: RejectedExecutionException) {
        // The event loop is shutting down, so the channel is closed anyway.
    }
}

internal fun NettyHttpHeaders.toKtorHeaders(): Headers {
    if (isEmpty) return Headers.Empty

    val values = TreeMap<String, MutableList<String>>(String.CASE_INSENSITIVE_ORDER)
    val iterator = iteratorAsString()
    while (iterator.hasNext()) {
        val (name, value) = iterator.next()
        values.getOrPut(name) { mutableListOf() }.add(value)
    }

    return HeadersImpl(values)
}

/**
 * Reparses the URL if its host contains URL parts, which happens when `DefaultRequest.host` is set to a URL.
 */
internal fun Url.rebuildIfNeeded(): Url =
    if (host.contains('/') || host.contains('?') || host.contains('#')) Url(toString()) else this

internal fun NettyHttpVersion.toKtorVersion(): HttpProtocolVersion =
    HttpProtocolVersion.fromValue(protocolName(), majorVersion(), minorVersion())

internal fun Throwable.mapConnectException(request: HttpRequestData): Throwable = when (this) {
    is io.netty.channel.ConnectTimeoutException -> io.ktor.client.plugins.ConnectTimeoutException(request, this)
    else -> this
}

internal fun Throwable.unwrapDecoderException(): Throwable {
    val cause = cause
    return if (this is DecoderException && cause != null) cause else this
}

internal fun Throwable.toIOException(message: String): IOException = when (this) {
    is IOException -> this
    else -> IOException("$message: ${this.message}", this)
}

/**
 * Indicates that the connection was closed before a response was received.
 */
internal class ConnectionClosedException(message: String) : IOException(message)
