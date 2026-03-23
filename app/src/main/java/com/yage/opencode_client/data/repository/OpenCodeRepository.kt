package com.yage.opencode_client.data.repository

import com.yage.opencode_client.data.api.*
import com.yage.opencode_client.data.model.*
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

    private var okHttpClient: OkHttpClient = buildOkHttpClient()
    private var retrofit: Retrofit = buildRetrofit()
    private var api: OpenCodeApi = retrofit.create(OpenCodeApi::class.java)
    private var sseClient: SSEClient = SSEClient(okHttpClient)

    private fun buildOkHttpClient(): OkHttpClient {
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
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun buildRetrofit(): Retrofit {
        val url = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        return Retrofit.Builder()
            .baseUrl(url.trimEnd('/') + "/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Synchronized
    private fun rebuildClients() {
        okHttpClient = buildOkHttpClient()
        retrofit = buildRetrofit()
        api = retrofit.create(OpenCodeApi::class.java)
        sseClient = SSEClient(okHttpClient)
    }

    @Synchronized
    fun configure(baseUrl: String, username: String? = null, password: String? = null) {
        this.baseUrl = baseUrl
        this.username = username
        this.password = password
        rebuildClients()
    }

    suspend fun checkHealth(): Result<HealthResponse> = runCatching { api.getHealth() }

    suspend fun getSessions(limit: Int? = null): Result<List<Session>> = runCatching { api.getSessions(limit) }

    suspend fun createSession(title: String? = null): Result<Session> = runCatching {
        api.createSession(CreateSessionRequest(title = title))
    }

    suspend fun updateSession(sessionId: String, title: String): Result<Session> = runCatching {
        api.updateSession(sessionId, UpdateSessionRequest(title))
    }

    suspend fun deleteSession(sessionId: String): Result<Unit> = runCatching {
        api.deleteSession(sessionId)
    }

    suspend fun getSessionStatus(): Result<Map<String, SessionStatus>> = runCatching {
        api.getSessionStatus()
    }

    suspend fun getMessages(sessionId: String, limit: Int? = null): Result<List<MessageWithParts>> =
        runCatching { api.getMessages(sessionId, limit) }

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        agent: String = "build",
        model: Message.ModelInfo? = null
    ): Result<Unit> = runCatching {
        val request = PromptRequest(
            parts = listOf(PromptRequest.PartInput(text = text)),
            agent = agent,
            model = model?.let { PromptRequest.ModelInput(it.providerId, it.modelId) }
        )
        val response = api.promptAsync(sessionId, request)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Send failed ${response.code()}: $errorBody")
        }
    }

    suspend fun abortSession(sessionId: String): Result<Unit> = runCatching {
        api.abortSession(sessionId)
    }

    suspend fun forkSession(sessionId: String, messageId: String? = null): Result<Session> = runCatching {
        api.forkSession(sessionId, ForkSessionRequest(messageId))
    }

    suspend fun getPendingPermissions(): Result<List<PermissionRequest>> = runCatching {
        api.getPendingPermissions()
    }

    suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse
    ): Result<Unit> = runCatching {
        api.respondPermission(sessionId, permissionId, PermissionResponseRequest(response.value))
    }

    suspend fun getPendingQuestions(): Result<List<QuestionRequest>> = runCatching {
        api.getPendingQuestions()
    }

    suspend fun replyQuestion(requestId: String, answers: List<List<String>>): Result<Unit> = runCatching {
        val response = api.replyQuestion(requestId, QuestionReplyRequest(answers))
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reply failed ${response.code()}: $errorBody")
        }
    }

    suspend fun rejectQuestion(requestId: String): Result<Unit> = runCatching {
        val response = api.rejectQuestion(requestId)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reject failed ${response.code()}: $errorBody")
        }
    }

    suspend fun getProviders(): Result<ProvidersResponse> = runCatching { api.getProviders() }

    suspend fun getAgents(): Result<List<AgentInfo>> = runCatching { api.getAgents() }

    suspend fun getSessionDiff(sessionId: String): Result<List<FileDiff>> = runCatching {
        api.getSessionDiff(sessionId)
    }

    suspend fun getSessionTodos(sessionId: String): Result<List<TodoItem>> = runCatching {
        api.getSessionTodos(sessionId)
    }

    suspend fun getFileTree(path: String? = null): Result<List<FileNode>> = runCatching {
        api.getFileTree(path ?: "")
    }

    suspend fun getFileContent(path: String): Result<FileContent> = runCatching {
        api.getFileContent(path)
    }

    suspend fun getFileStatus(): Result<List<FileStatusEntry>> = runCatching {
        api.getFileStatus()
    }

    suspend fun findFile(query: String, limit: Int = 50): Result<List<String>> = runCatching {
        api.findFile(query, limit)
    }

    fun connectSSE(): Flow<Result<SSEEvent>> = sseClient.connect(baseUrl, username, password)

    companion object {
        const val DEFAULT_SERVER = "http://localhost:4096"
    }
}
