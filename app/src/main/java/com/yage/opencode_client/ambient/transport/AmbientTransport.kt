package com.yage.opencode_client.ambient.transport

import com.yage.opencode_client.ambient.protocol.AmbientConfiguration
import com.yage.opencode_client.ambient.protocol.AmbientCreateBody
import com.yage.opencode_client.ambient.protocol.AmbientEndpoint
import com.yage.opencode_client.ambient.protocol.AmbientEventDecoder
import com.yage.opencode_client.ambient.protocol.AmbientProtocol
import com.yage.opencode_client.ambient.protocol.AmbientRejected
import com.yage.opencode_client.ambient.protocol.AmbientServerEvent
import com.yage.opencode_client.ambient.protocol.AmbientTicketParser
import com.yage.opencode_client.ambient.protocol.AmbientTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class AmbientLinkFailure {
    CREATE_STATUS,
    CREATE_BODY,
    TICKET,
    EXPIRY,
    ORIGIN,
    ENCODE,
    STALE,
    REJECTED,
    BUFFER,
    READER,
    SOCKET,
}

class AmbientLinkException(val failure: AmbientLinkFailure) : Exception(failure.name)

/**
 * Dedicated Builder client. No cache, no logging, no authenticator, no redirects.
 * Callers add Bearer only on the session POST. This client is not the repository Basic client.
 */
object AmbientBuilderClients {
    fun create(): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(AmbientProtocol.FOLLOW_REDIRECTS)
            .followSslRedirects(AmbientProtocol.FOLLOW_REDIRECTS)
            .retryOnConnectionFailure(false)
            .cache(null)
            .authenticator(okhttp3.Authenticator.NONE)
            .connectTimeout(AmbientProtocol.CREATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(AmbientProtocol.CREATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .writeTimeout(AmbientProtocol.CREATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .build()
}

class OkHttpAmbientTransport(
    private val client: OkHttpClient = AmbientBuilderClients.create(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : AmbientTransport {
    override suspend fun open(configuration: AmbientConfiguration): com.yage.opencode_client.ambient.protocol.AmbientLink {
        val sessionUrl = AmbientEndpoint.sessionUrl(configuration.portalBaseUrl)
            ?: throw AmbientLinkException(AmbientLinkFailure.ORIGIN)
        val body = try {
            AmbientCreateBody.json(configuration)
        } catch (_: AmbientRejected) {
            throw AmbientLinkException(AmbientLinkFailure.CREATE_BODY)
        }
        val request = Request.Builder()
            .url(sessionUrl)
            .header("Authorization", "Bearer ${configuration.builderToken}")
            .post(body.toRequestBody(JSON))
            .build()
        val response = try {
            execute(client, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AmbientLinkException) {
            throw error
        } catch (_: Exception) {
            throw AmbientLinkException(AmbientLinkFailure.SOCKET)
        }
        val wsUrl = try {
            withContext(Dispatchers.IO) {
                response.use { http ->
                    if (!AmbientEndpoint.acceptsCreateStatus(http.code)) {
                        throw AmbientLinkException(AmbientLinkFailure.CREATE_STATUS)
                    }
                    val text = http.body?.string().orEmpty()
                    val ticket = AmbientTicketParser.parse(text) ?: throw AmbientLinkException(AmbientLinkFailure.TICKET)
                    if (!expiryStillValid(ticket.expiry, nowMillis())) {
                        throw AmbientLinkException(AmbientLinkFailure.EXPIRY)
                    }
                    AmbientEndpoint.resolveWebSocket(configuration.portalBaseUrl, ticket.wsUrl, ticket.ticket)
                        ?: throw AmbientLinkException(AmbientLinkFailure.ORIGIN)
                }
            }
        } finally {
            response.close()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            connect(wsUrl, scope)
        } catch (cancelled: CancellationException) {
            scope.cancel()
            throw cancelled
        } catch (error: AmbientLinkException) {
            scope.cancel()
            throw error
        } catch (_: Exception) {
            scope.cancel()
            throw AmbientLinkException(AmbientLinkFailure.SOCKET)
        }
    }

    private suspend fun connect(wsUrl: String, scope: CoroutineScope): AmbientOrderedLink {
        val handshakeMillis = client.readTimeoutMillis.toLong().let { timeout ->
            if (timeout > 0L) timeout else AmbientProtocol.CREATE_TIMEOUT_MILLIS.toLong()
        }
        try {
            return withTimeout(handshakeMillis) { awaitSocket(wsUrl, scope) }
        } catch (timeout: TimeoutCancellationException) {
            if (!coroutineContext.isActive) throw timeout
            throw AmbientLinkException(AmbientLinkFailure.SOCKET)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitSocket(wsUrl: String, scope: CoroutineScope): AmbientOrderedLink {
        val inbound = AmbientInbound()
        val linkRef = AtomicReference<AmbientOrderedLink?>(null)
        val delivered = AtomicBoolean(false)
        return suspendCancellableCoroutine { cont ->
            val request = Request.Builder().url(wsUrl).build()
            lateinit var webSocket: WebSocket
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val link = AmbientOrderedLink(
                        transmit = { text -> webSocket.send(text) },
                        inbound = inbound,
                        scope = scope,
                        onEmergencyClose = { webSocket.cancel() },
                    )
                    linkRef.set(link)
                    if (!delivered.compareAndSet(false, true)) {
                        link.closeNow()
                        return
                    }
                    cont.resume(link) { link.closeNow() }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    inbound.offer(AmbientEventDecoder.decode(text))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    inbound.offer(AmbientServerEvent.Failed("socket"))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    inbound.close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    inbound.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    response?.close()
                    if (!delivered.compareAndSet(false, true)) {
                        linkRef.get()?.closeNow() ?: webSocket.cancel()
                        return
                    }
                    inbound.fail(AmbientLinkFailure.SOCKET)
                    linkRef.get()?.closeNow()
                    cont.resumeWithException(AmbientLinkException(AmbientLinkFailure.SOCKET))
                }
            }
            webSocket = client.newWebSocket(request, listener)
            cont.invokeOnCancellation {
                webSocket.cancel()
                linkRef.get()?.closeNow()
                scope.cancel()
            }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        internal fun expiryStillValid(expiry: String, nowMillis: Long): Boolean {
            val instant = parseExpiry(expiry) ?: return false
            return instant > nowMillis
        }

        private fun parseExpiry(expiry: String): Long? {
            try {
                return Instant.parse(expiry).toEpochMilli()
            } catch (_: DateTimeParseException) {
                // Fall through to offset and epoch forms.
            }
            try {
                return OffsetDateTime.parse(expiry).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // Fall through to a numeric epoch.
            }
            return expiry.toLongOrNull()
        }

        /**
         * OkHttp callbacks resume through the caller's dispatcher. [Response] ownership
         * stays with that dispatcher: a lost resume closes it, and the body is read on IO.
         * Network failures use [AmbientLinkFailure.SOCKET] and drop the raw throwable.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun execute(client: OkHttpClient, request: Request): Response =
            suspendCancellableCoroutine { cont ->
                val call = client.newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        cont.resumeWithException(AmbientLinkException(AmbientLinkFailure.SOCKET))
                    }

                    override fun onResponse(call: okhttp3.Call, response: Response) {
                        cont.resume(response) { response.close() }
                    }
                })
            }
    }
}
