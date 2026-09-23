/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.netty

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import io.netty.buffer.*
import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.util.*
import io.netty.util.concurrent.ScheduledFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*
import kotlin.coroutines.*
import io.ktor.http.HttpHeaders as KtorHttpHeaders
import io.ktor.http.HttpMethod as KtorHttpMethod
import io.netty.handler.codec.http.DefaultHttpRequest as NettyDefaultHttpRequest
import io.netty.handler.codec.http.HttpMethod as NettyHttpMethod
import io.netty.handler.codec.http.HttpRequest as NettyHttpRequest

private const val BODY_CHUNK_SIZE = 8192
private const val MAX_BYTE_ARRAY_SLICE_SIZE = 64 * 1024
private const val MAX_PENDING_BYTES = 64 * 1024L
private const val CONTINUE_TIMEOUT_MILLIS = 1000L

/**
 * A single HTTP request-response exchange over a leased [connection].
 *
 * The request is written by the calling coroutine (or a child coroutine for streaming bodies),
 * while the response is decoded on the channel event loop and delivered through [response].
 * The response body is transferred from the event loop to a [ByteChannel] by a consumer coroutine.
 * Reading from the socket is paused while too many body bytes are waiting for the consumer.
 *
 * All the `on*` methods and the mutable state they use are confined to the event loop of the connection.
 */
@OptIn(InternalAPI::class)
internal class NettyHttpExchange(
    private val connection: NettyConnection,
    private val request: HttpRequestData,
    private val url: Url,
    private val callContext: CoroutineContext,
    private val engineScope: CoroutineScope,
    private val overProxy: Boolean,
    socketTimeout: Long
) {
    private val channel = connection.channel
    private val requestTime = GMTDate()
    private val requestBody = request.body.unwrap()
    private val response = CompletableDeferred<HttpResponseData>()
    private val continueReceived = CompletableDeferred<Unit>()

    private val socketTimeoutNanos: Long =
        if (socketTimeout == HttpTimeoutConfig.INFINITE_TIMEOUT_MS || socketTimeout <= 0) {
            0
        } else {
            TimeUnit.MILLISECONDS.toNanos(socketTimeout)
        }

    // Event loop confined state
    private var state = State.AwaitingResponse
    private var keepAlive = true
    private var requestCompleted = false
    private var bodyQueue: Channel<ByteArray>? = null
    private var writesInFlight = 0
    private var lastActivity = System.nanoTime()
    private var timeoutCheck: ScheduledFuture<*>? = null
    private var callCompletionHandle: DisposableHandle? = null

    private val pendingBytes = AtomicLong()
    private val readPaused = AtomicBoolean()

    @Volatile
    private var requestBodyWriter: Job? = null

    /**
     * `true` if response headers were received for this exchange.
     */
    @Volatile
    private var responseStarted = false

    private enum class State { AwaitingResponse, ReadingBody, Upgraded, Done }

    suspend fun execute(): HttpResponseData {
        try {
            val nettyRequest = createNettyRequest()
            keepAlive = HttpUtil.isKeepAlive(nettyRequest)
            val expectContinue = nettyRequest.headers().contains(HttpHeaderNames.EXPECT)

            channel.runOnEventLoop { attach() }
            channel.write(nettyRequest).addListener(::onWriteCompleted)
            writeBody(expectContinue)

            return response.await()
        } catch (cause: Throwable) {
            // The connection state is unknown, so it can't be reused
            channel.runOnEventLoop { fail(cause) }
            throw cause
        }
    }

    /**
     * Returns `true` if the request can be safely retried on another connection after the [cause] failure.
     */
    fun canRetry(cause: Throwable): Boolean {
        if (responseStarted || cause !is IOException || cause is java.net.SocketTimeoutException) return false
        return requestBody is OutgoingContent.NoContent || requestBody is OutgoingContent.ByteArrayContent
    }

    private fun attach() {
        connection.handler.exchange = this
        callCompletionHandle = callContext.job.invokeOnCompletion { cause ->
            channel.runOnEventLoop { onCallCompleted(cause) }
        }

        if (!channel.isActive) {
            fail(ConnectionClosedException("Connection was closed before the request was sent"))
            return
        }

        lastActivity = System.nanoTime()
        scheduleTimeoutCheck(socketTimeoutNanos)
        channel.read()
    }

    private fun createNettyRequest(): NettyHttpRequest {
        val body = request.body
        val headers = DefaultHttpHeaders(false)

        val contentLength = request.headers[KtorHttpHeaders.ContentLength] ?: body.contentLength?.toString()
        val transferEncoding = request.headers[KtorHttpHeaders.TransferEncoding]
        val bodyTransferEncoding = body.headers[KtorHttpHeaders.TransferEncoding]
        val chunked = contentLength == null || transferEncoding == "chunked" || bodyTransferEncoding == "chunked"
        val expect = request.headers[KtorHttpHeaders.Expect]
        val noContent = requestBody is OutgoingContent.NoContent

        if (requestBody is OutgoingContent.ProtocolUpgrade) {
            throw UnsupportedContentTypeException(requestBody)
        }

        if (!request.headers.contains(KtorHttpHeaders.Host)) {
            val host = if (url.protocol.defaultPort == url.port) url.host else url.hostWithPort
            headers.add(KtorHttpHeaders.Host, host)
        }

        if (contentLength != null) {
            if ((request.method != KtorHttpMethod.Get && request.method != KtorHttpMethod.Head) || !noContent) {
                headers.add(KtorHttpHeaders.ContentLength, contentLength)
            }
        }

        mergeHeaders(request.headers, body) { key, value ->
            if (key == KtorHttpHeaders.ContentLength || key == KtorHttpHeaders.Expect) return@mergeHeaders
            headers.add(key, value)
        }

        if (chunked && transferEncoding == null && bodyTransferEncoding == null && !noContent) {
            headers.add(KtorHttpHeaders.TransferEncoding, "chunked")
        }

        if (expect != null && !noContent) {
            headers.add(KtorHttpHeaders.Expect, expect)
        }

        return NettyDefaultHttpRequest(
            HttpVersion.HTTP_1_1,
            NettyHttpMethod.valueOf(request.method.value),
            requestUri(),
            headers
        )
    }

    private fun requestUri(): String {
        if (!overProxy && url.rawSegments.isNotEmpty()) return url.encodedPathAndQuery

        val normalizedUrl = URLBuilder(url).apply {
            if (url.rawSegments.isEmpty()) encodedPath = "/"
            // WebSocket requests are sent to a proxy using the corresponding HTTP scheme
            if (overProxy) protocol = if (protocol.isSecure()) URLProtocol.HTTPS else URLProtocol.HTTP
        }.build()

        return if (overProxy) normalizedUrl.toString() else normalizedUrl.encodedPathAndQuery
    }

    private suspend fun writeBody(expectContinue: Boolean) {
        val body = requestBody
        when {
            body is OutgoingContent.NoContent -> {
                channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(::onRequestWritten)
            }

            body is OutgoingContent.ByteArrayContent && !expectContinue -> writeByteArrayBody(body.bytes())

            else -> {
                channel.flush()
                requestBodyWriter = CoroutineScope(callContext).launch(CoroutineName("netty-request-body")) {
                    try {
                        if (expectContinue && !awaitContinue()) return@launch
                        writeBodyContent(body)
                    } catch (cause: Throwable) {
                        channel.runOnEventLoop { onRequestBodyFailed(cause) }
                    }
                }
            }
        }
    }

    /**
     * Waits for the `100 Continue` response.
     * Returns `false` if the final response was received instead, so the request body shouldn't be sent.
     */
    private suspend fun awaitContinue(): Boolean {
        withTimeoutOrNull(CONTINUE_TIMEOUT_MILLIS) { continueReceived.await() }
        if (!response.isCompleted) return true

        channel.runOnEventLoop {
            keepAlive = false
            onRequestCompleted()
        }
        return false
    }

    private suspend fun writeBodyContent(body: OutgoingContent) {
        when (body) {
            is OutgoingContent.ByteArrayContent -> writeByteArrayBody(body.bytes())

            is OutgoingContent.ReadChannelContent -> writeChannelBody(body.readFrom())

            is OutgoingContent.WriteChannelContent -> {
                val source = CoroutineScope(callContext).writer(CoroutineName("netty-request-body-writer")) {
                    body.writeTo(this.channel)
                }.channel
                writeChannelBody(source)
            }

            else -> error("Unsupported content type: $body")
        }
    }

    private fun writeByteArrayBody(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(MAX_BYTE_ARRAY_SLICE_SIZE, bytes.size - offset)
            channel.write(DefaultHttpContent(Unpooled.wrappedBuffer(bytes, offset, length)))
                .addListener(::onWriteCompleted)
            offset += length
        }

        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(::onRequestWritten)
    }

    private suspend fun writeChannelBody(source: ByteReadChannel) {
        try {
            val buffer = ByteArray(BODY_CHUNK_SIZE)
            while (true) {
                val count = source.readAvailable(buffer)
                if (count == -1) break
                if (count == 0) continue

                val content = channel.alloc().buffer(count).writeBytes(buffer, 0, count)
                val future = channel.writeAndFlush(DefaultHttpContent(content))
                future.addListener(::onWriteCompleted)
                if (!channel.isWritable) future.awaitSuspend()
            }
        } catch (cause: Throwable) {
            source.cancel(cause)
            throw cause
        }

        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(::onRequestWritten)
    }

    private fun onWriteCompleted(future: io.netty.util.concurrent.Future<*>) {
        if (!future.isSuccess) channel.runOnEventLoop { onError(future.cause()) }
    }

    private fun onRequestWritten(future: io.netty.util.concurrent.Future<*>) {
        channel.runOnEventLoop {
            if (future.isSuccess) onRequestCompleted() else onError(future.cause())
        }
    }

    private fun onRequestCompleted() {
        if (requestCompleted) return
        requestCompleted = true
        lastActivity = System.nanoTime()
    }

    private fun onRequestBodyFailed(cause: Throwable) {
        fail(cause)
    }

    fun onMessage(msg: Any) {
        try {
            lastActivity = System.nanoTime()
            when (state) {
                State.AwaitingResponse -> {
                    if (msg is HttpResponse) onResponse(msg)
                    // Content of informational responses is skipped
                    if (msg is HttpContent && state == State.ReadingBody) onContent(msg)
                }

                State.ReadingBody -> if (msg is HttpContent) onContent(msg)

                State.Upgraded -> if (msg is ByteBuf) enqueue(msg)

                State.Done -> {}
            }
        } finally {
            ReferenceCountUtil.release(msg)
        }
    }

    private fun onResponse(msg: HttpResponse) {
        val decoderResult = msg.decoderResult()
        if (decoderResult.isFailure) {
            fail(decoderResult.cause().unwrapDecoderException().toIOException("Failed to parse HTTP response"))
            return
        }

        val statusCode = msg.status().code()
        val isUpgrade = statusCode == HttpStatusCode.SwitchingProtocols.value && request.isUpgradeRequest()
        if (statusCode / 100 == 1 && !isUpgrade) {
            if (statusCode == HttpStatusCode.Continue.value) continueReceived.complete(Unit)
            return
        }

        val status = HttpStatusCode(statusCode, msg.status().reasonPhrase())
        val headers = msg.headers().toKtorHeaders()
        val version = msg.protocolVersion().toKtorVersion()
        keepAlive = keepAlive && HttpUtil.isKeepAlive(msg)
        responseStarted = true

        if (isUpgrade) {
            onUpgrade(status, headers, version)
        } else {
            state = State.ReadingBody
            val bodyChannel = ByteChannel()
            startBodyConsumer(bodyChannel)
            val responseBody = ResponseBodyChannel(bodyChannel) { cause ->
                channel.runOnEventLoop { if (state == State.ReadingBody) fail(cause.toCancellation()) }
            }

            val body = request.attributes.getOrNull(ResponseAdapterAttributeKey)
                ?.adapt(request, status, headers, responseBody, request.body, callContext)
                ?: responseBody

            response.complete(HttpResponseData(status, requestTime, headers, version, body, callContext))
        }

        continueReceived.complete(Unit)
    }

    private fun onContent(msg: HttpContent) {
        val decoderResult = msg.decoderResult()
        if (decoderResult.isFailure) {
            fail(decoderResult.cause().unwrapDecoderException().toIOException("Failed to parse HTTP response body"))
            return
        }

        val content = msg.content()
        if (content.isReadable) enqueue(content)

        if (msg is LastHttpContent) onResponseCompleted()
    }

    private fun onResponseCompleted() {
        if (requestCompleted) {
            finish(reusable = keepAlive)
        } else {
            // The server responded before the whole request body was sent, so the connection can't be reused.
            requestBodyWriter?.cancel()
            finish(reusable = false)
        }

        // Release the connection before the reader sees the end of the body,
        // so that a subsequent request can reuse it.
        bodyQueue?.close()
    }

    private fun onUpgrade(status: HttpStatusCode, headers: Headers, version: HttpProtocolVersion) {
        state = State.Upgraded
        cancelTimeoutCheck()

        val input = ByteChannel()
        startBodyConsumer(input)

        // Pass the raw bytes through after the upgrade.
        // Bytes that were already received after the response headers are delivered during the codec removal.
        channel.pipeline().get(HttpClientCodec::class.java)?.let { channel.pipeline().remove(it) }

        val output = ByteChannel()
        val outputWriter = engineScope.launch(CoroutineName("netty-websocket-output")) {
            try {
                val buffer = ByteArray(BODY_CHUNK_SIZE)
                while (true) {
                    val count = output.readAvailable(buffer)
                    if (count == -1) break
                    if (count == 0) continue

                    val future = channel.writeAndFlush(channel.alloc().buffer(count).writeBytes(buffer, 0, count))
                    if (!channel.isWritable) future.awaitSuspend()
                }
            } catch (cause: Throwable) {
                output.cancel(cause)
            } finally {
                channel.close()
            }
        }
        channel.closeFuture().addListener {
            outputWriter.cancel()
            output.cancel(IOException("Connection was closed"))
        }

        val session = RawWebSocket(
            input,
            output,
            masking = true,
            coroutineContext = callContext,
            channelsConfig = request.attributes.getOrNull(WEBSOCKETS_KEY)?.channelsConfig
                ?: WebSocketChannelsConfig.UNLIMITED
        )
        response.complete(HttpResponseData(status, requestTime, headers, version, session, callContext))
    }

    private fun enqueue(content: ByteBuf) {
        val bytes = ByteBufUtil.getBytes(content)
        pendingBytes.addAndGet(bytes.size.toLong())
        bodyQueue?.trySend(bytes)
    }

    private fun startBodyConsumer(target: ByteChannel) {
        val queue = Channel<ByteArray>(Channel.UNLIMITED)
        bodyQueue = queue

        // The consumer is a child of the call, so the call isn't completed while the body is being received
        val consumer = CoroutineScope(callContext).launch(CoroutineName("netty-response-body")) {
            try {
                for (bytes in queue) {
                    // Writing to a channel with free space doesn't suspend, so check for cancellation explicitly
                    ensureActive()
                    target.writeFully(bytes)
                    target.flush()
                    onConsumed(bytes.size)
                }
                target.flushAndClose()
            } catch (cause: Throwable) {
                queue.cancel()
                target.cancel(cause)
                channel.runOnEventLoop { onConsumerFailed() }
            }
        }
        // The consumer body doesn't run at all if the call is cancelled before it starts
        consumer.invokeOnCompletion { cause ->
            if (cause == null) return@invokeOnCompletion
            queue.cancel()
            target.cancel(cause)
            channel.runOnEventLoop { onConsumerFailed() }
        }
    }

    private fun onConsumed(count: Int) {
        if (pendingBytes.addAndGet(-count.toLong()) >= MAX_PENDING_BYTES) return
        if (!readPaused.compareAndSet(true, false)) return

        channel.runOnEventLoop {
            if (state == State.ReadingBody || state == State.Upgraded) {
                lastActivity = System.nanoTime()
                channel.read()
            }
        }
    }

    private fun onConsumerFailed() {
        if (state == State.Done) return
        finish(reusable = false)
    }

    fun onReadComplete() {
        when (state) {
            State.AwaitingResponse -> channel.read()
            State.ReadingBody, State.Upgraded -> readIfNotPaused()
            State.Done -> {}
        }
    }

    private fun readIfNotPaused() {
        if (pendingBytes.get() < MAX_PENDING_BYTES) {
            channel.read()
            return
        }

        readPaused.set(true)
        // The consumer could drain the queue before the flag was set
        if (pendingBytes.get() < MAX_PENDING_BYTES && readPaused.compareAndSet(true, false)) {
            channel.read()
        }
    }

    fun onWrite(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (socketTimeoutNanos == 0L || state == State.Done || state == State.Upgraded) {
            ctx.write(msg, promise)
            return
        }

        if (writesInFlight++ == 0) lastActivity = System.nanoTime()
        val writePromise = promise.unvoid()
        writePromise.addListener {
            writesInFlight--
            lastActivity = System.nanoTime()
        }
        ctx.write(msg, writePromise)
    }

    fun onInactive() {
        when (state) {
            State.AwaitingResponse -> {
                fail(ConnectionClosedException("Connection was closed before a response was received"))
            }

            State.ReadingBody -> {
                fail(EOFException("Connection was closed before the response body was fully received"))
            }

            State.Upgraded -> {
                bodyQueue?.close()
                finish(reusable = false)
            }

            State.Done -> {}
        }
    }

    fun onError(cause: Throwable) {
        fail(cause.unwrapDecoderException())
    }

    private fun onCallCompleted(cause: Throwable?) {
        if (state == State.Done) return
        fail(cause.toCancellation())
    }

    private fun fail(cause: Throwable) {
        when (state) {
            State.AwaitingResponse -> {
                response.completeExceptionally(cause)
                continueReceived.complete(Unit)
            }

            State.ReadingBody, State.Upgraded -> bodyQueue?.close(cause)

            State.Done -> return
        }

        requestBodyWriter?.cancel()
        finish(reusable = false)
    }

    private fun finish(reusable: Boolean) {
        if (state == State.Done) return
        state = State.Done

        cancelTimeoutCheck()
        callCompletionHandle?.dispose()
        if (connection.handler.exchange === this) connection.handler.exchange = null
        connection.release(reusable)
    }

    private fun scheduleTimeoutCheck(delayNanos: Long) {
        if (socketTimeoutNanos == 0L) return
        timeoutCheck = channel.eventLoop().schedule(::checkTimeout, delayNanos, TimeUnit.NANOSECONDS)
    }

    private fun cancelTimeoutCheck() {
        timeoutCheck?.cancel(false)
        timeoutCheck = null
    }

    private fun checkTimeout() {
        timeoutCheck = null
        if (state == State.Done || state == State.Upgraded) return

        val now = System.nanoTime()
        val waiting = writesInFlight > 0 || isWaitingForData()
        if (!waiting) {
            scheduleTimeoutCheck(socketTimeoutNanos)
            return
        }

        val idleTime = now - lastActivity
        if (idleTime >= socketTimeoutNanos) {
            fail(SocketTimeoutException(request, cause = null))
            return
        }

        scheduleTimeoutCheck(socketTimeoutNanos - idleTime)
    }

    private fun isWaitingForData(): Boolean = when (state) {
        State.AwaitingResponse -> requestCompleted
        State.ReadingBody -> !readPaused.get()
        else -> false
    }
}

private fun OutgoingContent.unwrap(): OutgoingContent = when (this) {
    is OutgoingContent.ContentWrapper -> delegate().unwrap()
    else -> this
}

private fun Throwable?.toCancellation(): CancellationException = when (this) {
    is CancellationException -> this
    null -> CancellationException("The call was completed before the response was fully received", null)
    else -> CancellationException(message ?: "The call was cancelled", this)
}

/**
 * A response body channel that notifies the exchange when a reader cancels it,
 * so that the connection is released even if no more data is received from the server.
 */
private class ResponseBodyChannel(
    private val delegate: ByteChannel,
    private val onCancel: (Throwable?) -> Unit
) : ByteReadChannel by delegate {
    override fun cancel(cause: Throwable?) {
        delegate.cancel(cause)
        onCancel(cause)
    }
}
