package com.yage.opencode_client.ambient.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Monotonic time source. [nowNanos] must not go backwards. Sleep must honor cancellation.
 *
 * [SystemAmbientClock] is only a JVM fallback. Production Android injects a clock whose
 * [nowNanos] is `android.os.SystemClock.elapsedRealtimeNanos`, which keeps advancing
 * through deep sleep. This package does not reference Android APIs.
 *
 * [sleepUntil] must re-read [nowNanos] within [SystemAmbientClock.SLEEP_SLICE_NANOS].
 * A deadline that expired while the process was suspended is then noticed on the first
 * check after resume, rather than by finishing one delay scheduled before suspend.
 */
interface AmbientMonotonicClock {
    fun nowNanos(): Long
    suspend fun sleepUntil(deadlineNanos: Long)
}

/**
 * JVM fallback. [nowNanos] is [System.nanoTime] and does not advance during deep sleep,
 * so this object must not be the production Android clock. [sleepUntil] still polls on
 * [SLEEP_SLICE_NANOS] so a clock that does jump across sleep can use the same loop.
 */
object SystemAmbientClock : AmbientMonotonicClock {
    const val SLEEP_SLICE_NANOS = 20_000_000L

    override fun nowNanos(): Long = System.nanoTime()

    override suspend fun sleepUntil(deadlineNanos: Long) {
        while (true) {
            coroutineContext.ensureActive()
            val remaining = deadlineNanos - nowNanos()
            if (remaining <= 0L) return
            val slice = minOf(remaining, SLEEP_SLICE_NANOS)
            val millis = ((slice + 999_999L) / 1_000_000L).coerceAtLeast(1L)
            delay(millis)
        }
    }
}

/**
 * Runs [operation] until it finishes or the monotonic deadline passes.
 * On deadline the operation is cancelled and this function returns without joining it,
 * including when the operation is inside a non-cancellable context.
 * [CancellationException] raised by [operation], or a cancelled operation job, settles
 * false immediately and does not wait for the deadline.
 */
class AmbientCloseBudget(private val clock: AmbientMonotonicClock) {
    suspend fun run(budgetNanos: Long = AmbientProtocol.CLOSE_GRACE_NANOS, operation: suspend () -> Unit): Boolean {
        if (budgetNanos <= 0L) return false
        val start = clock.nowNanos()
        if (start > Long.MAX_VALUE - budgetNanos) return false
        val deadline = start + budgetNanos
        if (start >= deadline) return false
        return suspendCancellableCoroutine { continuation ->
            val supervisor = SupervisorJob()
            val scope = CoroutineScope(supervisor + Dispatchers.Unconfined)
            val settled = AtomicBoolean(false)
            val operationJob = AtomicReference<Job?>(null)
            val timerJob = AtomicReference<Job?>(null)
            fun settle(value: Boolean) {
                if (!settled.compareAndSet(false, true)) return
                operationJob.get()?.cancel()
                timerJob.get()?.cancel()
                supervisor.cancel()
                if (continuation.isActive) continuation.resume(value)
            }
            continuation.invokeOnCancellation { settle(false) }
            operationJob.set(scope.launch {
                try {
                    operation()
                    if (isActive) settle(true) else settle(false)
                } catch (_: CancellationException) {
                    settle(false)
                } catch (_: Exception) {
                    settle(false)
                }
            })
            timerJob.set(scope.launch {
                try {
                    clock.sleepUntil(deadline)
                    settle(false)
                } catch (_: CancellationException) {
                }
            })
            if (settled.get()) {
                operationJob.get()?.cancel()
                timerJob.get()?.cancel()
                supervisor.cancel()
            }
        }
    }

    suspend fun awaitSignal(
        deadlineNanos: Long,
        intervalNanos: Long = AmbientProtocol.CLOSE_POLL_NANOS,
        signal: () -> Boolean,
    ): Boolean {
        while (true) {
            coroutineContext.ensureActive()
            if (signal()) return true
            val now = clock.nowNanos()
            if (now >= deadlineNanos) return false
            val step = intervalNanos.coerceAtLeast(0L)
            val next = if (now > Long.MAX_VALUE - step) deadlineNanos else minOf(deadlineNanos, now + step)
            clock.sleepUntil(next)
        }
    }
}

/** Test clock. [advanceTo] wakes [sleepUntil]. This does not model deep sleep. */
class ManualAmbientClock(startNanos: Long = 0L) : AmbientMonotonicClock {
    private val lock = Any()
    private var now = startNanos
    private val waiters = ArrayList<Waiter>()

    override fun nowNanos(): Long = synchronized(lock) { now }

    override suspend fun sleepUntil(deadlineNanos: Long) {
        suspendCancellableCoroutine { continuation ->
            val waiter = Waiter(deadlineNanos, continuation)
            synchronized(lock) {
                if (now >= deadlineNanos) {
                    continuation.resume(Unit)
                } else {
                    waiters.add(waiter)
                    continuation.invokeOnCancellation {
                        synchronized(lock) { waiters.remove(waiter) }
                    }
                }
            }
        }
    }

    fun advanceTo(nanos: Long) {
        val ready = ArrayList<kotlin.coroutines.Continuation<Unit>>()
        synchronized(lock) {
            if (nanos < now) return
            now = nanos
            val due = waiters.filter { it.deadlineNanos <= now }
            waiters.removeAll(due.toSet())
            ready.addAll(due.map { it.continuation })
        }
        for (continuation in ready) {
            if (continuation is kotlinx.coroutines.CancellableContinuation && !continuation.isActive) continue
            continuation.resume(Unit)
        }
    }

    private class Waiter(
        val deadlineNanos: Long,
        val continuation: kotlin.coroutines.Continuation<Unit>,
    )
}
