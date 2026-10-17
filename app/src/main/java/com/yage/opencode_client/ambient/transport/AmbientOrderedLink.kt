package com.yage.opencode_client.ambient.transport

import com.yage.opencode_client.ambient.protocol.AmbientClientEvent
import com.yage.opencode_client.ambient.protocol.AmbientLink
import com.yage.opencode_client.ambient.protocol.AmbientServerEvent
import com.yage.opencode_client.ambient.protocol.AmbientWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Hot inbound queue. Events stay in server order, including an early
 * [AmbientServerEvent.Started]. One reader drains [AmbientLink.incoming].
 * A full buffer fails the run with [AmbientServerEvent.Failed]; audio is not
 * dropped to manufacture a shorter successful response.
 */
internal class AmbientInbound(
    private val capacity: Int = INBOUND_CAPACITY,
) {
    private val lock = Any()
    private val queue = ArrayDeque<AmbientServerEvent>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private var terminal: AmbientServerEvent? = null

    fun offer(event: AmbientServerEvent) {
        synchronized(lock) {
            if (closed) return
            if (queue.size >= capacity) {
                terminal = AmbientServerEvent.Failed("buffer")
                closed = true
            } else {
                queue.addLast(event)
            }
        }
        wake.trySend(Unit)
    }

    fun close() {
        synchronized(lock) { closed = true }
        wake.trySend(Unit)
    }

    fun fail(failure: AmbientLinkFailure) {
        synchronized(lock) {
            if (closed) return
            terminal = AmbientServerEvent.Failed(failure.name.lowercase())
            closed = true
        }
        wake.trySend(Unit)
    }

    suspend fun take(): AmbientServerEvent? {
        while (true) {
            val next = synchronized(lock) { poll() }
            if (next !== PENDING) {
                return next as AmbientServerEvent?
            }
            wake.receive()
        }
    }

    private fun poll(): Any? {
        if (queue.isNotEmpty()) return queue.removeFirst()
        terminal?.let {
            terminal = null
            return it
        }
        if (closed) return null
        return PENDING
    }

    private object PENDING

    companion object {
        const val INBOUND_CAPACITY = 64
    }
}

/**
 * Ordered sender. Control frames stay ahead of queued audio and keep their
 * own order, so a function output stays ahead of the following response.create.
 * [closeNow] seals the generation and completes the active and queued senders
 * without joining [transmit] or holding a lock across it.
 */
internal class AmbientOrderedLink(
    private val transmit: suspend (String) -> Boolean,
    private val inbound: AmbientInbound,
    private val scope: CoroutineScope,
    private val onEmergencyClose: () -> Unit,
) : AmbientLink {
    private val sendLock = Any()
    private val generation = AtomicLong(1L)
    private val closed = AtomicBoolean(false)
    private val reader = AtomicBoolean(false)
    private val control = Channel<Pending>(CONTROL_CAPACITY)
    private val audioQueue = ArrayDeque<Pending>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val inFlight = AtomicReference<Pending?>(null)
    private val terminalFailure = AtomicReference<AmbientLinkFailure?>(null)

    private val pumpJob = scope.launch {
        try {
            pump()
        } catch (_: CancellationException) {
            settle(AmbientLinkFailure.STALE)
        } finally {
            settle(terminalFailure.get() ?: AmbientLinkFailure.STALE)
        }
    }

    override val incoming: Flow<AmbientServerEvent> = flow {
        if (!reader.compareAndSet(false, true)) {
            throw AmbientLinkException(AmbientLinkFailure.READER)
        }
        while (true) {
            val event = inbound.take() ?: break
            emit(event)
        }
    }

    override suspend fun send(event: AmbientClientEvent) {
        val gen = generation.get()
        if (gen == 0L || closed.get()) throw AmbientLinkException(AmbientLinkFailure.STALE)
        val text = AmbientWire.encode(event) ?: throw AmbientLinkException(AmbientLinkFailure.ENCODE)
        val pending = Pending(gen, text, CompletableDeferred())
        val failure = if (event is AmbientClientEvent.Audio) {
            enqueueAudio(pending)
        } else {
            enqueueControl(pending)
        }
        if (failure != null) throw AmbientLinkException(failure)
        try {
            val result = pending.result.await()
            if (result != null) throw AmbientLinkException(result)
        } catch (cancelled: CancellationException) {
            pending.abandon()
            throw cancelled
        }
    }

    override suspend fun close() {
        closeNow()
    }

    fun closeNow() {
        closed.set(true)
        try {
            onEmergencyClose()
        } catch (_: Exception) {
            // The socket is already unusable. Close still returns.
        }
        inbound.close()
        settle(AmbientLinkFailure.STALE)
        scope.cancel()
        pumpJob.cancel()
    }

    private suspend fun pump() {
        while (currentCoroutineContext().isActive && terminalFailure.get() == null) {
            val item = next() ?: return
            if (!markInFlight(item)) {
                item.result.complete(terminalFailure.get() ?: AmbientLinkFailure.STALE)
                continue
            }
            try {
                val blocked = synchronized(sendLock) {
                    generation.get() == 0L || terminalFailure.get() != null || !item.tryDispatch()
                }
                if (blocked) {
                    item.result.complete(terminalFailure.get() ?: AmbientLinkFailure.STALE)
                    continue
                }
                val accepted = try {
                    transmit(item.text)
                } catch (cancelled: CancellationException) {
                    settle(AmbientLinkFailure.STALE)
                    throw cancelled
                } catch (_: Exception) {
                    settle(AmbientLinkFailure.SOCKET)
                    return
                }
                val failure = when {
                    terminalFailure.get() != null -> terminalFailure.get()
                    item.generation != generation.get() -> AmbientLinkFailure.STALE
                    !accepted -> AmbientLinkFailure.REJECTED
                    else -> null
                }
                item.result.complete(failure)
                if (failure == AmbientLinkFailure.REJECTED) {
                    settle(AmbientLinkFailure.REJECTED)
                    return
                }
            } finally {
                inFlight.compareAndSet(item, null)
            }
        }
    }

    private suspend fun next(): Pending? {
        while (currentCoroutineContext().isActive) {
            if (generation.get() == 0L || terminalFailure.get() != null) return null
            control.tryReceive().getOrNull()?.let { return it }
            popAudio()?.let { return it }
            if (generation.get() == 0L || terminalFailure.get() != null) return null
            wake.receive()
        }
        return null
    }

    private fun markInFlight(item: Pending): Boolean = synchronized(sendLock) {
        if (generation.get() == 0L || terminalFailure.get() != null) return false
        inFlight.set(item)
        true
    }

    private fun enqueueControl(item: Pending): AmbientLinkFailure? {
        val failure = synchronized(sendLock) {
            if (generation.get() != item.generation || terminalFailure.get() != null) {
                return@synchronized AmbientLinkFailure.STALE
            }
            if (control.trySend(item).isSuccess) null else AmbientLinkFailure.BUFFER
        }
        if (failure == null) wake.trySend(Unit)
        return failure
    }

    private fun enqueueAudio(item: Pending): AmbientLinkFailure? {
        val failure = synchronized(sendLock) {
            if (generation.get() != item.generation || terminalFailure.get() != null) {
                return@synchronized AmbientLinkFailure.STALE
            }
            if (audioQueue.size >= AUDIO_CAPACITY) return@synchronized AmbientLinkFailure.BUFFER
            audioQueue.addLast(item)
            null
        }
        if (failure == null) wake.trySend(Unit)
        return failure
    }

    private fun popAudio(): Pending? = synchronized(sendLock) { audioQueue.removeFirstOrNull() }

    private fun settle(failure: AmbientLinkFailure) {
        terminalFailure.compareAndSet(null, failure)
        val sealed = terminalFailure.get() ?: failure
        synchronized(sendLock) {
            generation.set(0L)
            closed.set(true)
            inFlight.getAndSet(null)?.result?.complete(sealed)
            while (true) {
                val item = control.tryReceive().getOrNull() ?: break
                item.result.complete(sealed)
            }
            while (true) {
                val item = audioQueue.removeFirstOrNull() ?: break
                item.result.complete(sealed)
            }
        }
        wake.trySend(Unit)
    }

    private class Pending(
        val generation: Long,
        val text: String,
        val result: CompletableDeferred<AmbientLinkFailure?>,
    ) {
        private val state = AtomicInteger(QUEUED)

        fun tryDispatch(): Boolean = state.compareAndSet(QUEUED, DISPATCHED)

        fun abandon() {
            if (state.compareAndSet(QUEUED, ABANDONED)) {
                result.complete(AmbientLinkFailure.STALE)
            }
        }

        companion object {
            const val QUEUED = 0
            const val DISPATCHED = 1
            const val ABANDONED = 2
        }
    }

    companion object {
        const val CONTROL_CAPACITY = 32
        const val AUDIO_CAPACITY = 8
    }
}
