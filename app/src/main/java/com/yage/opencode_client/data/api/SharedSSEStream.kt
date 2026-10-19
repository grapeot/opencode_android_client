package com.yage.opencode_client.data.api

import com.yage.opencode_client.data.model.SSEEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import java.io.IOException

/** A single configured host, independently retained by Chat and background voice collectors. */
internal class SharedSSEStream(
    private val scope: CoroutineScope,
    private val predecessor: Job? = null,
    private val source: (() -> Unit, () -> Unit) -> Flow<Result<SSEEvent>>,
) {
    private sealed interface Frame {
        data object Connected : Frame
        data object Disconnected : Frame
        data class Event(val value: Result<SSEEvent>) : Frame
    }

    private val lock = Any()
    private val listeners = mutableSetOf<Channel<Frame>>()
    private var upstream: Job? = null
    private var retiring: Job? = null
    private var retirement: Job? = null
    private var serial = 0L
    private var connected = false
    private var closed = false

    fun connect(onConnected: (() -> Unit)?, onDisconnected: (() -> Unit)?): Flow<Result<SSEEvent>> = flow {
        val channel = Channel<Frame>(256)
        val start = synchronized(lock) {
            if (closed) {
                channel.close()
                null
            } else {
                listeners.add(channel)
                if (connected) channel.trySend(Frame.Connected)
                if (upstream != null) null else {
                    val generation = ++serial
                    val previous = retiring
                    scope.launch(start = CoroutineStart.LAZY) {
                        predecessor?.join()
                        previous?.join()
                        try {
                            source(
                                { publish(generation, Frame.Connected) },
                                { publish(generation, Frame.Disconnected) },
                            ).collect { publish(generation, Frame.Event(it)) }
                        } finally {
                            publish(generation, Frame.Disconnected)
                        }
                    }.also { upstream = it }
                }
            }
        }
        start?.start()
        try {
            for (frame in channel) {
                if (synchronized(lock) { closed }) break
                when (frame) {
                    Frame.Connected -> onConnected?.invoke()
                    Frame.Disconnected -> onDisconnected?.invoke()
                    is Frame.Event -> emit(frame.value)
                }
            }
        } finally {
            synchronized(lock) {
                listeners.remove(channel)
                channel.cancel()
                if (listeners.isEmpty()) stopLocked()
            }
        }
    }.retryWhen { error, _ ->
        // A slow consumer must bootstrap again, never silently continue after missing frames.
        if (error !is SlowSSEConsumer) false else {
            onDisconnected?.invoke()
            delay(100)
            synchronized(lock) { !closed }
        }
    }

    fun close(): Job? = synchronized(lock) {
        retirement?.let { return@synchronized it }
        closed = true
        listeners.forEach { it.close() }
        listeners.clear()
        stopLocked()
        val own = retiring
        // A cancelled source waiting for its predecessor can finish before that
        // predecessor. Pass a separate barrier through every configuration,
        // including intermediate configurations which never had a collector.
        scope.launch {
            predecessor?.join()
            own?.join()
        }.also { retirement = it }
    }

    private fun stopLocked() {
        serial += 1
        connected = false
        upstream?.let { own ->
            own.cancel()
            val previous = retiring
            // A source cancelled while waiting for another source is not that
            // source's retirement barrier. Preserve the entire detach chain.
            retiring = scope.launch {
                predecessor?.join()
                previous?.join()
                own.join()
            }
        }
        upstream = null
    }

    private fun publish(generation: Long, frame: Frame) = synchronized(lock) {
        if (closed || generation != serial) return@synchronized
        when (frame) {
            Frame.Connected -> connected = true
            Frame.Disconnected -> connected = false
            is Frame.Event -> Unit
        }
        val iterator = listeners.iterator()
        while (iterator.hasNext()) {
            val target = iterator.next()
            if (target.trySend(frame).isFailure) {
                iterator.remove()
                target.close(SlowSSEConsumer())
            }
        }
        if (listeners.isEmpty()) stopLocked()
    }

    private class SlowSSEConsumer : IOException("SSE consumer needs reconciliation")
}
