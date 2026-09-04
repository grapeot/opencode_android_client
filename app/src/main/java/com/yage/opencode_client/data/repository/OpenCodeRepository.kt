package com.yage.opencode_client.data.repository

import com.yage.opencode_client.data.api.*
import com.yage.opencode_client.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenCodeRepository @Inject constructor() {
    private var baseUrl: String = DEFAULT_SERVER
    private var username: String? = null
    private var password: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false  // Omit null fields - server rejects model: null
        encodeDefaults = true  // Include type in parts - server needs discriminator
    }

    private var restHttpClient: OkHttpClient = buildHttpClient(REST_READ_TIMEOUT_SECONDS)
    private var sseHttpClient: OkHttpClient = buildHttpClient(SSE_READ_TIMEOUT_SECONDS)
    private var retrofit: Retrofit = buildRetrofit(restHttpClient)
    private var api: OpenCodeApi = retrofit.create(OpenCodeApi::class.java)
    private var sseClient: SSEClient = SSEClient(sseHttpClient)

    private fun buildHttpClient(readTimeoutSeconds: Long): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .apply {
                        val u = username
                        val p = password
                        if (u != null && p != null) {
                            val credential = "$u:$p"
                            val encoded = Base64.getEncoder().encodeToString(credential.toByteArray())
                            header("Authorization", "Basic $encoded")
                        }
                    }
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun buildRetrofit(client: OkHttpClient): Retrofit {
        val url = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        return Retrofit.Builder()
            .baseUrl(url.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Synchronized
    private fun rebuildClients() {
        restHttpClient = buildHttpClient(REST_READ_TIMEOUT_SECONDS)
        sseHttpClient = buildHttpClient(SSE_READ_TIMEOUT_SECONDS)
        retrofit = buildRetrofit(restHttpClient)
        api = retrofit.create(OpenCodeApi::class.java)
        sseClient = SSEClient(sseHttpClient)
    }

    @Synchronized
    fun configure(baseUrl: String, username: String? = null, password: String? = null) {
        this.baseUrl = baseUrl
        this.username = username
        this.password = password
        rebuildClients()
    }

    private suspend fun <T> apiCall(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun checkHealth(): Result<HealthResponse> = apiCall { api.getHealth() }

    suspend fun getSessions(limit: Int? = null): Result<List<Session>> = apiCall { api.getSessions(limit) }

    suspend fun getSession(sessionId: String): Result<Session> = apiCall { api.getSession(sessionId) }

    suspend fun createSession(title: String? = null): Result<Session> = apiCall {
        api.createSession(CreateSessionRequest(title = title))
    }

    suspend fun updateSession(sessionId: String, title: String): Result<Session> = apiCall {
        api.updateSession(sessionId, UpdateSessionRequest(title = title))
    }

    suspend fun updateSessionArchived(sessionId: String, archived: Long): Result<Session> = apiCall {
        api.updateSession(sessionId, UpdateSessionRequest(time = UpdateSessionTimeRequest(archived = archived)))
    }

    suspend fun deleteSession(sessionId: String): Result<Unit> = apiCall {
        api.deleteSession(sessionId)
    }

    suspend fun getSessionStatus(): Result<Map<String, SessionStatus>> = apiCall {
        api.getSessionStatus()
    }

    suspend fun getMessages(sessionId: String, limit: Int? = null): Result<List<MessageWithParts>> =
        apiCall { api.getMessages(sessionId, limit) }

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        agent: String = "build",
        model: Message.ModelInfo? = null,
        attachments: List<ComposerImageAttachment> = emptyList(),
        messageId: String? = null
    ): Result<Unit> = apiCall {
        val parts = buildList {
            if (text.isNotBlank()) add(PromptRequest.PartInput(type = "text", text = text))
            attachments.forEach { attachment ->
                add(
                    PromptRequest.PartInput(
                        type = "file",
                        mime = attachment.mime,
                        filename = attachment.filename,
                        url = attachment.dataUrl
                    )
                )
            }
        }
        val request = PromptRequest(
            messageId = messageId,
            parts = parts,
            agent = agent,
            model = model?.let { PromptRequest.ModelInput(it.providerId, it.modelId) }
        )
        val response = api.promptAsync(sessionId, request)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Send failed ${response.code()}: $errorBody")
        }
    }

    suspend fun abortSession(sessionId: String): Result<Unit> = apiCall {
        api.abortSession(sessionId)
    }

    suspend fun forkSession(sessionId: String, messageId: String? = null): Result<Session> = apiCall {
        api.forkSession(sessionId, ForkSessionRequest(messageId))
    }

    suspend fun revertSession(sessionId: String, messageId: String, partId: String? = null): Result<Session> = apiCall {
        api.revertSession(sessionId, RevertSessionRequest(messageId, partId))
    }

    suspend fun getPendingPermissions(): Result<List<PermissionRequest>> = apiCall {
        api.getPendingPermissions()
    }

    suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse
    ): Result<Unit> = apiCall {
        api.respondPermission(sessionId, permissionId, PermissionResponseRequest(response.value))
    }

    suspend fun getPendingQuestions(): Result<List<QuestionRequest>> = apiCall {
        api.getPendingQuestions()
    }

    suspend fun replyQuestion(requestId: String, answers: List<List<String>>): Result<Unit> = apiCall {
        val response = api.replyQuestion(requestId, QuestionReplyRequest(answers))
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reply failed ${response.code()}: $errorBody")
        }
    }

    suspend fun rejectQuestion(requestId: String): Result<Unit> = apiCall {
        val response = api.rejectQuestion(requestId)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reject failed ${response.code()}: $errorBody")
        }
    }

    suspend fun getProviders(): Result<ProvidersResponse> = apiCall { api.getProviders() }

    suspend fun getProviderRegistry(): Result<ProviderRegistryResponse> = apiCall { api.getProviderRegistry() }

    suspend fun getAgents(): Result<List<AgentInfo>> = apiCall { api.getAgents() }

    suspend fun getSessionDiff(sessionId: String): Result<List<FileDiff>> = apiCall {
        api.getSessionDiff(sessionId)
    }

    suspend fun getSessionTodos(sessionId: String): Result<List<TodoItem>> = apiCall {
        api.getSessionTodos(sessionId)
    }

    suspend fun getFileTree(path: String? = null): Result<List<FileNode>> = apiCall {
        api.getFileTree(path ?: "")
    }

    suspend fun getFileContent(path: String): Result<FileContent> = apiCall {
        api.getFileContent(path)
    }

    suspend fun getFileStatus(): Result<List<FileStatusEntry>> = apiCall {
        api.getFileStatus()
    }

    suspend fun findFile(query: String, limit: Int = 50): Result<List<String>> = apiCall {
        api.findFile(query, limit)
    }

    fun connectSSE(): Flow<Result<SSEEvent>> = sseClient.connect(baseUrl, username, password)

    companion object {
        const val DEFAULT_SERVER = "http://localhost:4096"

        // REST calls (prompt_async, getMessages, ...) get a bounded read timeout so a
        // half-open connection fails fast instead of hanging forever. SSE is a
        // long-lived stream that can be silent for minutes while the agent works, so
        // it keeps an infinite read timeout on its own client.
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val WRITE_TIMEOUT_SECONDS = 60L
        private const val REST_READ_TIMEOUT_SECONDS = 60L
        private const val SSE_READ_TIMEOUT_SECONDS = 0L
    }
}
