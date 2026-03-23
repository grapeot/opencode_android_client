package com.yage.opencode_client.data.api

import com.yage.opencode_client.data.model.SSEEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.Base64

class SSEClient(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 30000L
        private const val RETRY_MULTIPLIER = 2.0
    }

    fun connect(
        baseUrl: String,
        username: String? = null,
        password: String? = null
    ): Flow<Result<SSEEvent>> = connectOnce(baseUrl, username, password)
        .retryWhen { _, attempt ->
            val delayMs = (INITIAL_RETRY_DELAY_MS * Math.pow(RETRY_MULTIPLIER, attempt.toDouble()))
                .toLong()
                .coerceAtMost(MAX_RETRY_DELAY_MS)
            delay(delayMs)
            true
        }

    private fun connectOnce(
        baseUrl: String,
        username: String? = null,
        password: String? = null
    ): Flow<Result<SSEEvent>> = callbackFlow {
        val url = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        val request = Request.Builder()
            .url("$url/global/event")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .apply {
                if (username != null && password != null) {
                    val credential = "$username:$password"
                    val encoded = Base64.getEncoder().encodeToString(credential.toByteArray())
                    header("Authorization", "Basic $encoded")
                }
            }
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data.isNotBlank() && data != "[DONE]") {
                    try {
                        val event = kotlinx.serialization.json.Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                            coerceInputValues = true
                        }.decodeFromString<SSEEvent>(data)
                        trySend(Result.success(event))
                    } catch (_: Exception) {
                        // Skip malformed events silently
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close(Exception("SSE connection closed by server"))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                close(t ?: Exception("SSE connection failed"))
            }
        }

        val eventSource = EventSources.createFactory(okHttpClient)
            .newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
    }
}
