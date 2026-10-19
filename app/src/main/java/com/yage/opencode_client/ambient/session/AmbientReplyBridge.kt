package com.yage.opencode_client.ambient.session

import com.yage.opencode_client.ambient.protocol.*
import com.yage.opencode_client.data.model.SSEEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import java.util.UUID

/** Run-local reply tracking. No dependency on Chat selection, children, or a polling timer. */
internal class AmbientReplyBridge(
    private val scope: CoroutineScope,
    private val allows: () -> Boolean,
    private val page: suspend (String, String, String?) -> AmbientReplyPage,
    private val idle: suspend (String, String) -> Boolean?,
    private val submit: suspend (String, String) -> AmbientReplySend,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : AmbientReplyObserver {
    private data class Pending(val reservation: AmbientReplyReservation, val baseline: String?, val known: Boolean, var sawIdle: Boolean = false)
    private class Subscription(val reservation: AmbientReplyReservation, var baseline: String?, var known: Boolean) {
        var idleOpen = false
        var dirty = false
        var checkStatus = false
        var fetching = false
        var job: Job? = null
        val attempts = mutableMapOf<String, Int>()
    }
    private enum class DeliveryState { SENDING, SUBMITTED, UNKNOWN, ACCEPTED, REJECTED }
    private data class Delivery(val sessionId: String, val messageId: String, var state: DeliveryState)
    private val lock = Any()
    private var closed = false
    private val pending = mutableMapOf<String, Pending>()
    private val subscriptions = mutableMapOf<String, Subscription>()
    private val deliveries = mutableMapOf<String, Delivery>()
    private val revisions = mutableMapOf<String, Long>()

    override fun reserve(reservation: AmbientReplyReservation, history: JsonArray?): String? = synchronized(lock) {
        if (!live() || reservation.sessionId.isBlank() || reservation.directory.isBlank()) return null
        val decoded = history?.let { AmbientReplyParser.rows(it, reservation.sessionId) }
        val known = reservation.isNew || (decoded != null && !decoded.gaps)
        val token = UUID.randomUUID().toString()
        pending[token] = Pending(reservation, decoded?.rows?.lastOrNull()?.id, known)
        token
    }

    override fun accept(token: String) {
        val accepted = synchronized(lock) {
            val entry = pending.remove(token) ?: return
            if (!live()) return
            val id = entry.reservation.sessionId
            val sub = subscriptions.getOrPut(id) { Subscription(entry.reservation, entry.baseline, entry.known) }
            // Repeated continue preserves unread boundary and existing delivery records.
            if (entry.sawIdle) sub.idleOpen = true
            id to !entry.sawIdle
        }
        schedule(accepted.first, accepted.second)
    }

    override fun rollback(token: String) = synchronized(lock) { pending.remove(token); Unit }

    fun onEvent(event: SSEEvent) {
        when (event.payload.type) {
            "session.status" -> {
                val id = event.payload.getString("sessionID") ?: return
                val type = event.payload.getJsonObject("status")?.let { jsonString(it["type"]) } ?: return
                val wake = synchronized(lock) {
                    if (!live() || (id !in subscriptions && pending.values.none { it.reservation.sessionId == id })) return
                    revisions[id] = (revisions[id] ?: 0) + 1
                    if (type == "idle") pending.values.filter { it.reservation.sessionId == id }.forEach { it.sawIdle = true }
                    val sub = subscriptions[id] ?: return@synchronized false
                    when (type) {
                        "idle" -> { sub.idleOpen = true; true }
                        "busy", "retry" -> { sub.idleOpen = false; false }
                        else -> false
                    }
                }
                if (wake) schedule(id, false)
            }
            "message.updated" -> {
                val id = AmbientReplyParser.terminalUpdate(event) ?: return
                val wake = synchronized(lock) { live() && subscriptions[id]?.idleOpen == true }
                if (wake) schedule(id, false)
            }
        }
    }

    fun onConnected() {
        val ids = synchronized(lock) { if (live()) subscriptions.keys.toList() else emptyList() }
        ids.forEach { schedule(it, true) }
    }

    fun onAppended(eventId: String) = synchronized(lock) {
        if (live()) deliveries[eventId]?.state = DeliveryState.ACCEPTED
    }

    fun close() {
        val jobs = synchronized(lock) {
            closed = true
            val jobs = subscriptions.values.mapNotNull { it.job }
            pending.clear()
            subscriptions.clear()
            deliveries.clear()
            revisions.clear()
            jobs
        }
        jobs.forEach { it.cancel() }
    }

    private fun live(): Boolean = !closed && allows()

    private fun schedule(id: String, checkStatus: Boolean) {
        val job = synchronized(lock) {
            val sub = subscriptions[id] ?: return
            if (!live()) return
            sub.dirty = true
            sub.checkStatus = sub.checkStatus || checkStatus
            if (sub.fetching) return
            sub.fetching = true
            scope.launch(start = CoroutineStart.LAZY) { drain(id, sub) }.also { sub.job = it }
        }
        job.start()
    }

    private suspend fun drain(id: String, sub: Subscription) {
        try {
            while (true) {
                val read = synchronized(lock) {
                    if (!current(id, sub)) return
                    val check = sub.checkStatus && !sub.idleOpen
                    sub.dirty = false
                    sub.checkStatus = false
                    check to (revisions[id] ?: 0)
                }
                var canRead = !read.first
                if (read.first) {
                    val status = try { idle(id, sub.reservation.directory) } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { null }
                    canRead = synchronized(lock) {
                        if (!current(id, sub)) return
                        if ((revisions[id] ?: 0) != read.second) false else {
                            if (status == true) sub.idleOpen = true
                            status == true
                        }
                    }
                }
                if (canRead) readAndSend(id, sub)
                val again = synchronized(lock) {
                    if (!current(id, sub)) return
                    if (sub.dirty) true else {
                        sub.fetching = false
                        sub.job = null
                        false
                    }
                }
                if (!again) return
                // True event-driven work is not discarded after an arbitrary round limit.
                yield()
            }
        } finally {
            synchronized(lock) {
                // Normal drain already released ownership atomically. On cancellation only
                // retire this exact run's job; never wipe a successor scheduled meanwhile.
                if (subscriptions[id] === sub && sub.job == kotlin.coroutines.coroutineContext[Job]) {
                    sub.fetching = false
                    sub.job = null
                }
            }
        }
    }

    private fun current(id: String, sub: Subscription): Boolean = live() && subscriptions[id] === sub

    private suspend fun readAndSend(id: String, sub: Subscription) {
        for (retry in 0..2) {
            val rows = try { collect(id, sub) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { return }
            if (rows == null) return
            val start = synchronized(lock) {
                if (!current(id, sub)) return
                scanStart(sub, rows) ?: return
            }
            val suffix = rows.drop(start)
            var waiting = false
            var sent = false
            for (row in suffix) {
                val candidate = synchronized(lock) {
                    if (!current(id, sub)) return
                    if (handled(id, row.id, sub) || row.role != "assistant" || row.toolCall) false
                    else if (!row.complete || row.text.isBlank()) { waiting = true; false }
                    else true
                }
                if (candidate) {
                    send(id, sub, row)
                    sent = true
                }
            }
            val needsBody = synchronized(lock) {
                if (!current(id, sub)) return
                // User-only / empty accepted history may precede the persisted assistant body.
                val unhandled = suffix.any { !handled(id, it.id, sub) }
                val awaiting = waiting || (!sent && (unhandled || (suffix.isEmpty() && sub.idleOpen)))
                if (sent) sub.idleOpen = waiting else if (awaiting) sub.idleOpen = true else sub.idleOpen = false
                advance(id, sub, rows, start)
                awaiting && !sent
            }
            if (!needsBody || retry == 2) return
            pause(if (retry == 0) 250 else 750)
            synchronized(lock) { if (!current(id, sub)) return }
        }
    }

    private suspend fun collect(id: String, sub: Subscription): List<AmbientReplyRow>? {
        var rows = emptyList<AmbientReplyRow>()
        val seen = mutableSetOf<String>()
        val cursors = mutableSetOf<String>()
        var before: String? = null
        repeat(5) {
            synchronized(lock) { if (!current(id, sub)) return null }
            val next = page(id, sub.reservation.directory, before)
            synchronized(lock) { if (!current(id, sub)) return null }
            if (next.gaps) return null
            rows = next.rows.filter { seen.add(it.id) } + rows
            if (synchronized(lock) { scanStart(sub, rows) != null }) return rows
            val cursor = next.before ?: return null
            if (!cursors.add(cursor)) return null
            before = cursor
        }
        return null
    }

    private fun scanStart(sub: Subscription, rows: List<AmbientReplyRow>): Int? {
        val boundary = if (sub.known) sub.baseline else sub.reservation.userMessageId
        if (sub.known && boundary == null) return 0
        val index = rows.indexOfFirst { it.id == boundary }
        return if (index < 0) null else index + 1
    }

    private fun handled(id: String, messageId: String, sub: Subscription): Boolean =
        (sub.attempts[messageId] ?: 0) >= 2 || deliveries.values.any {
            it.sessionId == id && it.messageId == messageId && it.state != DeliveryState.REJECTED
        }

    private fun advance(id: String, sub: Subscription, rows: List<AmbientReplyRow>, start: Int) {
        for (row in rows.drop(start)) {
            if (row.role == "assistant" && !row.toolCall && !handled(id, row.id, sub)) break
            sub.baseline = row.id
            sub.known = true
        }
    }

    private suspend fun send(id: String, sub: Subscription, row: AmbientReplyRow) {
        val eventId = "reply-" + UUID.randomUUID().toString()
        val text = synchronized(lock) {
            if (!current(id, sub) || handled(id, row.id, sub)) return
            deliveries[eventId] = Delivery(id, row.id, DeliveryState.SENDING)
            AmbientReplyCopy.render(sub.reservation.title, id, row.agent, row.text)
        }
        val result = try { submit(text, eventId) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { AmbientReplySend.UNKNOWN }
        synchronized(lock) {
            if (!current(id, sub)) return
            val delivery = deliveries[eventId] ?: return
            if (delivery.state == DeliveryState.ACCEPTED) return
            delivery.state = when (result) {
                AmbientReplySend.SUBMITTED -> DeliveryState.SUBMITTED
                AmbientReplySend.UNKNOWN -> DeliveryState.UNKNOWN
                AmbientReplySend.REJECTED -> {
                    sub.attempts[row.id] = (sub.attempts[row.id] ?: 0) + 1
                    DeliveryState.REJECTED
                }
            }
        }
    }
}
