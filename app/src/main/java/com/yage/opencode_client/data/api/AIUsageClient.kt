package com.yage.opencode_client.data.api

import com.yage.opencode_client.data.model.AIUsageQuota
import com.yage.opencode_client.data.model.AIUsageQuotaKey
import com.yage.opencode_client.data.model.AIUsageQuotaSnapshot
import com.yage.opencode_client.data.model.QuotaFetchResult
import com.yage.opencode_client.data.model.dedupeQuotaKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
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

    suspend fun fetchQuotas(rawUrl: String): Result<QuotaFetchResult> = withContext(Dispatchers.IO) {
        try {
            val endpoint = quotasEndpoint(rawUrl)
            val request = Request.Builder().url(endpoint).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "AI Usage Dashboard returned HTTP ${response.code}" }
                Result.success(decodeQuotaFetch(response.body?.string().orEmpty(), System.currentTimeMillis()))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    internal fun decodeQuotaFetch(body: String, fetchedAtMs: Long): QuotaFetchResult {
        val root = try {
            json.parseToJsonElement(body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException("AI Usage Dashboard response is not valid JSON", error)
        }
        if (root !is JsonObject) {
            error("AI Usage Dashboard response is not a JSON object")
        }
        val quotasElement = root["quotas"]
        if (quotasElement !is JsonArray) {
            error("AI Usage Dashboard quotas field is not an array")
        }
        val quotas = ArrayList<AIUsageQuota>(quotasElement.size)
        val failedKeys = linkedSetOf<AIUsageQuotaKey>()
        for (element in quotasElement) {
            try {
                quotas += json.decodeFromJsonElement<AIUsageQuota>(element)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                quotaKeyOrNull(element)?.let { failedKeys += it }
            }
        }
        if (quotasElement.isNotEmpty() && quotas.isEmpty() && failedKeys.isEmpty()) {
            error("AI Usage Dashboard quotas could not be decoded")
        }
        val failed = dedupeQuotaKeys(failedKeys)
        return QuotaFetchResult(
            snapshot = AIUsageQuotaSnapshot(
                generatedAt = readGeneratedAt(root),
                fetchedAtMs = fetchedAtMs,
                quotas = quotas,
                failedKeys = failed
            ),
            failedKeys = failed
        )
    }

    suspend fun refreshDashboard(rawUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
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
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
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

    private fun readGeneratedAt(root: JsonObject): String? {
        val element = root["generated_at"] ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull || !primitive.isString) return null
        return primitive.content
    }

    private fun quotaKeyOrNull(element: JsonElement): AIUsageQuotaKey? {
        val obj = element as? JsonObject ?: return null
        val provider = obj.stringOrNull("provider") ?: return null
        val label = obj.stringOrNull("label") ?: return null
        return AIUsageQuotaKey(provider, label)
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull || !primitive.isString) return null
        return primitive.content.trim().takeIf { it.isNotEmpty() }
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "0.0.0.0" || host.endsWith(".local") || host.endsWith(".ts.net")) return true
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) return true
        val parts = host.split('.')
        return parts.size == 4 && parts[0] == "172" && (parts[1].toIntOrNull() in 16..31)
    }
}
