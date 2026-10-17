package com.yage.opencode_client.ambient.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

/**
 * Immutable host and Basic credentials captured for one Ambient run.
 * Dispatch reads this snapshot, not a mutable interceptor field.
 */
data class AmbientCapturedConfig(
    val hostProfileId: String,
    val runtimeGeneration: Long,
    val directory: String?,
    val epoch: Long,
    val ownerToken: String,
    val baseUrl: String,
    val username: String,
    val password: String,
) {
    override fun toString(): String =
        "AmbientCapturedConfig(hostProfileId=$hostProfileId, runtimeGeneration=$runtimeGeneration, epoch=$epoch)"
}

data class AmbientGatewayCall(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val jsonBody: String? = null,
)

data class AmbientHttpResult(
    val code: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun header(name: String): String? {
        for ((key, values) in headers) {
            if (key.equals(name, ignoreCase = true) && values.isNotEmpty()) return values.first()
        }
        return null
    }

    fun jsonOrNull(): JsonElement? = try {
        ambientJson.parseToJsonElement(body)
    } catch (_: Exception) {
        null
    }
}

sealed interface AmbientGatewayExchange {
    data class Completed(val http: AmbientHttpResult) : AmbientGatewayExchange
    data class TransportFailed(val reason: String) : AmbientGatewayExchange
    data object StaleLease : AmbientGatewayExchange
}

/**
 * Send-time authority. [configure] replaces the active token before it returns.
 * A later [configure], even with the same host, does not revive an old token.
 *
 * [retire] drops the active token only when [token] is that token, so a delayed
 * retire cannot end a newer run. [invalidate] drops whatever is active. Host
 * reconfigure calls [invalidate] before the replacement [configure].
 *
 * Issued ids start at 1 and increase by 1. [nextDispatchId] does not wrap.
 * When the next id would pass [Long.MAX_VALUE], or the counter is already negative,
 * [configure] clears the active token and returns 0. Zero never authorizes, and a
 * wrapped id is never stored, so an old token cannot become active again.
 * The stored config is [AmbientCapturedConfig.copy]. Dispatch reads that snapshot.
 */
class AmbientDispatchGate internal constructor(initialIssued: Long) {
    constructor() : this(0L)

    private val lock = Any()
    private var issued = initialIssued
    private var active = 0L
    private var config: AmbientCapturedConfig? = null

    fun configure(snapshot: AmbientCapturedConfig): Long = synchronized(lock) {
        val next = nextDispatchId(issued)
        if (next == null) {
            active = 0L
            config = null
            return 0L
        }
        issued = next
        active = next
        config = snapshot.copy()
        next
    }

    /** True only when [token] was the active run and is now retired. */
    fun retire(token: Long): Boolean = synchronized(lock) {
        if (token == 0L || token != active || config == null) return false
        active = 0L
        config = null
        true
    }

    fun invalidate() = synchronized(lock) {
        active = 0L
        config = null
    }

    fun authorize(token: Long): AmbientCapturedConfig? = synchronized(lock) {
        val current = config
        if (token == 0L || token != active || current == null) null else current
    }
}

/**
 * [transport] receives the dispatch token so the HTTP adapter can call
 * [AmbientDispatchGate.authorize] again at network send. The authorize before
 * [transport] is not proof that bytes were sent. After [transport] returns,
 * [exchange] authorizes [token] again and returns [AmbientGatewayExchange.StaleLease]
 * instead of [AmbientGatewayExchange.Completed] when the run was retired or replaced.
 * [CancellationException] is rethrown. Any other exception from [transport] becomes
 * [AmbientGatewayExchange.TransportFailed] with reason `"transport"`.
 */
class AmbientAuthorizingGateway(
    private val gate: AmbientDispatchGate,
    private val transport: suspend (AmbientCapturedConfig, AmbientGatewayCall, Long) -> AmbientHttpResult,
) {
    suspend fun exchange(token: Long, call: AmbientGatewayCall): AmbientGatewayExchange {
        val config = gate.authorize(token) ?: return AmbientGatewayExchange.StaleLease
        val http = try {
            transport(config, call, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AmbientGatewayExchange.TransportFailed("transport")
        }
        if (gate.authorize(token) == null) return AmbientGatewayExchange.StaleLease
        return AmbientGatewayExchange.Completed(http)
    }
}

/** Next id, or null when issuing one would wrap or revive a non-positive id. */
internal fun nextDispatchId(issued: Long): Long? {
    if (issued < 0L || issued == Long.MAX_VALUE) return null
    return issued + 1L
}
