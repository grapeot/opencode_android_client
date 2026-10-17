package com.yage.opencode_client.ambient.gateway

import com.yage.opencode_client.ambient.protocol.AmbientAuthorizingGateway
import com.yage.opencode_client.ambient.protocol.AmbientCapturedConfig
import com.yage.opencode_client.ambient.protocol.AmbientDispatchGate
import com.yage.opencode_client.ambient.protocol.AmbientGatewayCall
import com.yage.opencode_client.ambient.protocol.AmbientGatewayExchange
import com.yage.opencode_client.ambient.protocol.AmbientHttpResult
import com.yage.opencode_client.data.repository.OpenCodeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenCode client for one live lease. No logging, no cache, no authenticator,
 * no redirects, and no Builder bearer. Basic credentials are stamped only after
 * [AmbientDispatchGate.authorize] succeeds inside the call interceptor.
 */
object AmbientOpenCodeClients {
    fun create(): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cache(null)
            .authenticator(okhttp3.Authenticator.NONE)
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
}

/**
 * Static OpenCode paths used by the eight ambient tools.
 * The path and query cannot select a new scheme, host, or credentials.
 */
object AmbientLivePaths {
    private const val ID = "[A-Za-z0-9._:~+-]{1,200}"

    private val queryKeys = setOf("directory", "limit", "cursor", "roots", "archived")

    private val routes = listOf(
        Route("GET", Regex("^config/providers$")),
        Route("GET", Regex("^provider$")),
        Route("GET", Regex("^experimental/session$")),
        Route("GET", Regex("^session/status$")),
        Route("POST", Regex("^session$")),
        Route("GET", Regex("^session/$ID$")),
        Route("PATCH", Regex("^session/(?!status$)$ID$")),
        Route("GET", Regex("^session/$ID/children$")),
        Route("GET", Regex("^session/$ID/message$")),
        Route("POST", Regex("^session/$ID/prompt_async$")),
        Route("POST", Regex("^session/$ID/abort$")),
        Route("GET", Regex("^permission$")),
        Route("POST", Regex("^session/$ID/permissions/$ID$")),
        Route("GET", Regex("^question$")),
        Route("POST", Regex("^question/$ID/reply$")),
    )

    fun accepts(call: AmbientGatewayCall): Boolean {
        val path = normalize(call.path) ?: return false
        val method = call.method.trim().uppercase()
        if (routes.none { it.method == method && it.path.matches(path) }) return false
        return queryOk(call.query)
    }

    fun normalize(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed.length > 512) return null
        if (trimmed.startsWith("//")) return null
        if (trimmed.indexOf('\\') >= 0 || trimmed.indexOf('@') >= 0) return null
        if (trimmed.contains("://") || trimmed.contains('?') || trimmed.contains('#')) return null
        if (trimmed.any { it == '\r' || it == '\n' || it == '\u0000' || it.isWhitespace() }) return null
        val relative = if (trimmed.startsWith("/")) trimmed.substring(1) else trimmed
        if (relative.isEmpty() || relative.contains("//")) return null
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return relative
    }

    fun buildUrl(baseUrl: String, call: AmbientGatewayCall): HttpUrl? {
        if (!accepts(call)) return null
        val path = normalize(call.path) ?: return null
        val parsed = baseUrl.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "http" && parsed.scheme != "https") return null
        if (parsed.username.isNotEmpty()) return null
        val builder = parsed.newBuilder()
        for (segment in path.split('/')) builder.addPathSegment(segment)
        for ((key, value) in call.query) {
            if (key == "directory" && value.isBlank()) continue
            builder.addQueryParameter(key, value)
        }
        val built = builder.build()
        if (!built.scheme.equals(parsed.scheme, ignoreCase = true)) return null
        if (!built.host.equals(parsed.host, ignoreCase = true)) return null
        if (built.port != parsed.port) return null
        return built
    }

    private fun queryOk(query: Map<String, String>): Boolean {
        if (query.size > 8) return false
        for ((key, value) in query) {
            if (key !in queryKeys) return false
            if (value.length > 4096) return false
            if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) return false
        }
        return true
    }

    private data class Route(val method: String, val path: Regex)
}

class AmbientLiveGateway(
    private val repository: OpenCodeRepository,
    val gate: AmbientDispatchGate = AmbientDispatchGate(),
    private val baseClient: OkHttpClient = AmbientOpenCodeClients.create(),
    private val beforeDispatch: suspend (Long) -> Unit = {},
) {
    val authorizing: AmbientAuthorizingGateway = AmbientAuthorizingGateway(gate, ::dispatch)

    init {
        repository.bindAmbientGate(gate)
    }

    fun arm(
        hostProfileId: String,
        runtimeGeneration: Long,
        directory: String?,
        epoch: Long,
        ownerToken: String,
    ): Long = repository.armAmbient(
        gate,
        hostProfileId,
        runtimeGeneration,
        directory,
        epoch,
        ownerToken,
    )

    fun retire(token: Long): Boolean = gate.retire(token)

    suspend fun exchange(token: Long, call: AmbientGatewayCall): AmbientGatewayExchange {
        if (!AmbientLivePaths.accepts(call)) {
            return AmbientGatewayExchange.TransportFailed("rejected")
        }
        return authorizing.exchange(token, call)
    }

    private suspend fun dispatch(
        config: AmbientCapturedConfig,
        call: AmbientGatewayCall,
        token: Long,
    ): AmbientHttpResult {
        if (!AmbientLivePaths.accepts(call)) throw AmbientLiveTransportError()
        beforeDispatch(token)
        val live = gate.authorize(token) ?: return AmbientHttpResult(0, emptyMap(), "")
        if (!sameAuthority(live, config)) return AmbientHttpResult(0, emptyMap(), "")
        val url = AmbientLivePaths.buildUrl(live.baseUrl, call) ?: throw AmbientLiveTransportError()
        val request = Request.Builder()
            .url(url)
            .method(call.method.trim().uppercase(), requestBody(call))
            .header("Accept", "application/json")
            .build()
        val response = execute(clientFor(token, live), request)
        try {
            return withContext(Dispatchers.IO) {
                response.use { http -> readResult(token, http) }
            }
        } finally {
            response.close()
        }
    }

    private fun readResult(token: Long, http: Response): AmbientHttpResult {
        if (http.header(STALE_HEADER) == "stale" || gate.authorize(token) == null) {
            return AmbientHttpResult(0, emptyMap(), "")
        }
        val headers = LinkedHashMap<String, List<String>>()
        for (name in http.headers.names()) {
            headers[name] = http.headers.values(name)
        }
        return AmbientHttpResult(http.code, headers, http.body?.string().orEmpty())
    }

    private fun clientFor(token: Long, config: AmbientCapturedConfig): OkHttpClient =
        baseClient.newBuilder()
            .addInterceptor { chain ->
                // After OkHttp has taken this call off its queue, and before proceed().
                // Credentials come from the immutable gate snapshot. Once proceed()
                // starts, this check cannot pull the write back; a later result is
                // discarded when authorize fails. No second revoke protocol.
                val current = gate.authorize(token)
                if (current == null || !sameAuthority(current, config)) {
                    return@addInterceptor staleResponse(chain.request())
                }
                val builder = chain.request().newBuilder().removeHeader("Authorization")
                val credential = basic(current.username, current.password)
                if (credential != null) builder.header("Authorization", credential)
                chain.proceed(builder.build())
            }
            .build()

    private fun requestBody(call: AmbientGatewayCall): RequestBody? {
        val method = call.method.trim().uppercase()
        if (method == "GET" || method == "HEAD") return null
        return (call.jsonBody ?: "{}").toRequestBody(JSON)
    }

    private fun staleResponse(request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(503)
            .message("stale")
            .header(STALE_HEADER, "stale")
            .body(ByteArray(0).toResponseBody(null))
            .build()

    companion object {
        private const val STALE_HEADER = "X-Ambient-Dispatch"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        internal fun basic(username: String, password: String): String? {
            if (username.isEmpty() && password.isEmpty()) return null
            val encoded = Base64.getEncoder().encodeToString(
                "$username:$password".toByteArray(Charsets.UTF_8),
            )
            return "Basic $encoded"
        }

        internal fun sameAuthority(left: AmbientCapturedConfig, right: AmbientCapturedConfig): Boolean =
            left.baseUrl == right.baseUrl &&
                left.username == right.username &&
                left.password == right.password &&
                left.hostProfileId == right.hostProfileId &&
                left.runtimeGeneration == right.runtimeGeneration &&
                left.epoch == right.epoch &&
                left.ownerToken == right.ownerToken &&
                left.directory == right.directory

        /**
         * The callback thread only resumes. Body reads happen later on IO.
         * [CancellableContinuation.resume]'s onCancellation closes a response that
         * lost the race with cancellation before the caller could read it.
         * Failures carry [AmbientLiveTransportError] and not the OkHttp throwable.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun execute(client: OkHttpClient, request: Request): Response =
            suspendCancellableCoroutine { cont ->
                val call = client.newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        cont.resumeWithException(AmbientLiveTransportError())
                    }

                    override fun onResponse(call: okhttp3.Call, response: Response) {
                        cont.resume(response) { response.close() }
                    }
                })
            }
    }
}

private class AmbientLiveTransportError : Exception("transport")
