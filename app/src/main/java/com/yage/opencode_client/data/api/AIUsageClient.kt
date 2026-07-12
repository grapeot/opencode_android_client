package com.yage.opencode_client.data.api

import com.yage.opencode_client.data.model.AIUsageQuotaSnapshot
import com.yage.opencode_client.data.model.AIUsageQuotasResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AIUsageClient internal constructor(
    private val client: OkHttpClient
) {
    @Inject
    constructor() : this(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .build()
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchQuotas(rawUrl: String): Result<AIUsageQuotaSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = quotasEndpoint(rawUrl)
            val request = Request.Builder().url(endpoint).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "AI Usage Dashboard returned HTTP ${response.code}" }
                val body = response.body?.string().orEmpty()
                val decoded = json.decodeFromString<AIUsageQuotasResponse>(body)
                AIUsageQuotaSnapshot(
                    generatedAt = decoded.generatedAt,
                    fetchedAtMs = System.currentTimeMillis(),
                    quotas = decoded.quotas
                )
            }
        }
    }

    suspend fun refreshDashboard(rawUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = quotasEndpoint(rawUrl).removeSuffix("/api/v1/quotas") + "/api/v1/display/update"
            val body = """{"reason":"force_button","view":"7d","device_id":"opencode-android"}"""
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(endpoint)
                .post(body)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "AI Usage Dashboard returned HTTP ${response.code}" }
            }
        }
    }

    internal fun quotasEndpoint(rawUrl: String): String {
        var value = rawUrl.trim().trimEnd('/')
        require(value.isNotEmpty()) { "AI Usage Dashboard URL is empty" }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://$value"
        }
        val uri = URI(value)
        val host = uri.host ?: error("Invalid AI Usage Dashboard URL")
        if (uri.scheme == "http" && !isPrivateHost(host)) {
            error("Public AI Usage Dashboard URLs must use HTTPS")
        }
        return if (uri.path.trimEnd('/') == "/api/v1/quotas") value else "$value/api/v1/quotas"
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "0.0.0.0" || host.endsWith(".local") || host.endsWith(".ts.net")) return true
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) return true
        val parts = host.split('.')
        return parts.size == 4 && parts[0] == "172" && (parts[1].toIntOrNull() in 16..31)
    }
}
