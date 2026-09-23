/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.netty

import io.netty.channel.*
import kotlinx.coroutines.sync.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/**
 * Identifies connections that can be shared between requests.
 */
internal data class NettyRoute(val host: String, val port: Int, val secure: Boolean, val proxy: Proxy?)

/**
 * A pooled connection to a [route].
 *
 * A connection is either leased by a single request, idle in the pool, or closed.
 * Each lease holds a permit of the route that is released exactly once when the lease ends.
 */
internal class NettyConnection(
    val channel: Channel,
    val handler: NettyConnectionHandler,
    val route: NettyRoute,
    private val pool: NettyConnectionPool
) {
    private val state = AtomicInteger(LEASED)

    @Volatile
    private var idleTimeout: ScheduledFuture<*>? = null

    /**
     * `true` if this connection was taken from the pool rather than freshly established for the current lease.
     */
    @Volatile
    var reused: Boolean = false
        private set

    init {
        channel.closeFuture().addListener {
            val previous = state.getAndSet(CLOSED)
            pool.onClosed(this, wasLeased = previous == LEASED)
        }
    }

    /**
     * Ends the current lease, returning the connection to the pool if it is [reusable] or closing it otherwise.
     */
    fun release(reusable: Boolean) {
        if (!reusable || !pool.canKeepIdle() || !channel.isActive) {
            channel.close()
            return
        }

        if (!state.compareAndSet(LEASED, IDLE)) return

        idleTimeout = channel.eventLoop().schedule(
            {
                if (state.compareAndSet(IDLE, CLOSED)) channel.close()
            },
            pool.keepAliveTime,
            TimeUnit.MILLISECONDS
        )

        pool.onIdle(this)
        // Keep reading while idle to get notified when the server closes the connection.
        channel.read()
    }

    internal fun tryLease(): Boolean {
        if (!channel.isActive || !state.compareAndSet(IDLE, LEASED)) return false
        idleTimeout?.cancel(false)
        reused = true
        return true
    }

    internal fun closeIfIdle() {
        if (state.compareAndSet(IDLE, CLOSED)) channel.close()
    }

    private companion object {
        const val LEASED = 0
        const val IDLE = 1
        const val CLOSED = 2
    }
}

internal class NettyConnectionPool(
    private val maxConnectionsPerRoute: Int,
    val keepAliveTime: Long
) {
    private val routes = ConcurrentHashMap<NettyRoute, RoutePool>()

    @Volatile
    private var closed = false

    private inner class RoutePool {
        val permits = Semaphore(maxConnectionsPerRoute)
        val idle = ConcurrentLinkedDeque<NettyConnection>()
    }

    init {
        require(maxConnectionsPerRoute > 0) { "maxConnectionsPerRoute should be positive: $maxConnectionsPerRoute" }
    }

    /**
     * Leases an idle connection for the [route] or establishes a new one using [connect].
     * Suspends while the maximum number of connections per route is leased.
     */
    suspend fun acquire(route: NettyRoute, connect: suspend () -> NettyConnection): NettyConnection {
        val routePool = routes.computeIfAbsent(route) { RoutePool() }
        routePool.permits.acquire()

        try {
            while (true) {
                val connection = routePool.idle.pollLast() ?: break
                if (connection.tryLease()) return connection
            }

            return connect()
        } catch (cause: Throwable) {
            routePool.permits.release()
            throw cause
        }
    }

    fun canKeepIdle(): Boolean = !closed && keepAliveTime > 0

    fun onIdle(connection: NettyConnection) {
        val routePool = routes[connection.route] ?: return
        routePool.idle.addLast(connection)
        routePool.permits.release()

        if (closed) connection.closeIfIdle()
    }

    fun onClosed(connection: NettyConnection, wasLeased: Boolean) {
        val routePool = routes[connection.route] ?: return
        routePool.idle.remove(connection)
        if (wasLeased) routePool.permits.release()
    }

    fun close() {
        closed = true
        routes.values.forEach { routePool ->
            routePool.idle.forEach { it.closeIfIdle() }
        }
    }
}
