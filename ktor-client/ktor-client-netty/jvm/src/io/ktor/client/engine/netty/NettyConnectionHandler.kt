/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.netty

import io.netty.channel.*
import io.netty.util.*

/**
 * The last handler in a connection pipeline. Dispatches connection events to the current [NettyHttpExchange].
 *
 * All the methods are called on the event loop of the channel,
 * so [exchange] should be changed only on the event loop as well.
 */
internal class NettyConnectionHandler : ChannelDuplexHandler() {

    var exchange: NettyHttpExchange? = null

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val exchange = exchange
        if (exchange == null) {
            // Unexpected data received on an idle connection, so it can't be reused anymore.
            ReferenceCountUtil.release(msg)
            ctx.close()
            return
        }

        exchange.onMessage(msg)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        exchange?.onReadComplete()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        exchange?.onInactive()
        ctx.fireChannelInactive()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        exchange?.onError(cause)
        ctx.close()
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        val exchange = exchange
        if (exchange == null) {
            ctx.write(msg, promise)
        } else {
            exchange.onWrite(ctx, msg, promise)
        }
    }
}
