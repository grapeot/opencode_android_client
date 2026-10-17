package com.yage.opencode_client.ambient.session

import com.yage.opencode_client.ambient.protocol.AmbientMonotonicClock
import com.yage.opencode_client.ambient.protocol.SystemAmbientClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Runtime clock for Ambient shutdown and elapsed session time.
 * [nowNanos] reads [android.os.SystemClock.elapsedRealtimeNanos] so deep sleep
 * still moves the deadline. Each slice re-reads that clock after [delay].
 */
class AndroidAmbientClock : AmbientMonotonicClock {
    override fun nowNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()

    override suspend fun sleepUntil(deadlineNanos: Long) {
        while (true) {
            coroutineContext.ensureActive()
            val remaining = deadlineNanos - nowNanos()
            if (remaining <= 0L) return
            val slice = minOf(remaining, SystemAmbientClock.SLEEP_SLICE_NANOS)
            val millis = ((slice + 999_999L) / 1_000_000L).coerceAtLeast(1L)
            delay(millis)
        }
    }
}
