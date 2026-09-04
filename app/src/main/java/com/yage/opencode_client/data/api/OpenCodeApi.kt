package com.yage.opencode_client.data.api

import com.yage.opencode_client.data.model.*
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.*

interface OpenCodeApi {
    @GET("global/health")
    suspend fun getHealth(): HealthResponse

    @GET("session")
    suspend fun getSessions(@Query("limit") limit: Int? = null): List<Session>

    @POST("session")
    suspend fun createSession(@Body body: CreateSessionRequest = CreateSessionRequest()): Session

    @GET("session/{id}")
    suspend fun getSession(@Path("id") sessionId: String): Session

    @PATCH("session/{id}")
    suspend fun updateSession(@Path("id") sessionId: String, @Body body: UpdateSessionRequest): Session

    @DELETE("session/{id}")
    suspend fun deleteSession(@Path("id") sessionId: String): Response<Unit>

    @GET("session/status")
    suspend fun getSessionStatus(): Map<String, SessionStatus>

    @GET("session/{id}/message")
    suspend fun getMessages(
        @Path("id") sessionId: String,
        @Query("limit") limit: Int? = null
    ): List<MessageWithParts>

    @POST("session/{id}/prompt_async")
    suspend fun promptAsync(
        @Path("id") sessionId: String,
        @Body body: PromptRequest
    ): Response<Unit>

    @POST("session/{id}/abort")
    suspend fun abortSession(@Path("id") sessionId: String): Response<Unit>

    @POST("session/{id}/fork")
    suspend fun forkSession(
        @Path("id") sessionId: String,
        @Body body: ForkSessionRequest
    ): Session

    @POST("session/{id}/revert")
    suspend fun revertSession(
        @Path("id") sessionId: String,
        @Body body: RevertSessionRequest
    ): Session

    @POST("session/{id}/permissions/{permissionId}")
    suspend fun respondPermission(
        @Path("id") sessionId: String,
        @Path("permissionId") permissionId: String,
        @Body body: PermissionResponseRequest
    ): Response<Unit>

    @GET("permission")
    suspend fun getPendingPermissions(): List<PermissionRequest>

    @GET("question")
    suspend fun getPendingQuestions(): List<QuestionRequest>

    @POST("question/{requestId}/reply")
    suspend fun replyQuestion(
        @Path("requestId") requestId: String,
        @Body body: QuestionReplyRequest
    ): Response<Unit>

    @POST("question/{requestId}/reject")
    suspend fun rejectQuestion(@Path("requestId") requestId: String): Response<Unit>

    @GET("config/providers")
    suspend fun getProviders(): ProvidersResponse

    @GET("provider")
    suspend fun getProviderRegistry(): ProviderRegistryResponse

    @GET("agent")
    suspend fun getAgents(): List<AgentInfo>

    @GET("session/{id}/diff")
    suspend fun getSessionDiff(@Path("id") sessionId: String): List<FileDiff>

    @GET("session/{id}/todo")
    suspend fun getSessionTodos(@Path("id") sessionId: String): List<TodoItem>

    @GET("file")
    suspend fun getFileTree(@Query("path") path: String? = ""): List<FileNode>

    @GET("file/content")
    suspend fun getFileContent(@Query("path") path: String): FileContent

    @GET("file/status")
    suspend fun getFileStatus(): List<FileStatusEntry>

    @GET("find/file")
    suspend fun findFile(
        @Query("query") query: String,
        @Query("limit") limit: Int = 50
    ): List<String>
}

@kotlinx.serialization.Serializable
data class CreateSessionRequest(
    val title: String? = null,
    @kotlinx.serialization.SerialName("parentID") val parentId: String? = null
)

@kotlinx.serialization.Serializable
data class UpdateSessionRequest(
    val title: String? = null,
    val time: UpdateSessionTimeRequest? = null
)

@kotlinx.serialization.Serializable
data class UpdateSessionTimeRequest(
    val archived: Long? = null
)

@kotlinx.serialization.Serializable
data class PromptRequest(
    @kotlinx.serialization.SerialName("messageID") val messageId: String? = null,
    val parts: List<PartInput>,
    val agent: String = "build",
    val model: ModelInput? = null
) {
    @kotlinx.serialization.Serializable
    data class PartInput(
        val type: String = "text",
        val text: String? = null,
        val mime: String? = null,
        val filename: String? = null,
        val url: String? = null
    )

    @kotlinx.serialization.Serializable
    data class ModelInput(
        @kotlinx.serialization.SerialName("providerID") val providerId: String,
        @kotlinx.serialization.SerialName("modelID") val modelId: String
    )
}

@kotlinx.serialization.Serializable
data class PermissionResponseRequest(
    val response: String
)

@kotlinx.serialization.Serializable
data class QuestionReplyRequest(
    val answers: List<List<String>>
)

@kotlinx.serialization.Serializable
data class ForkSessionRequest(
    @kotlinx.serialization.SerialName("messageID") val messageId: String? = null
)

@kotlinx.serialization.Serializable
data class RevertSessionRequest(
    @kotlinx.serialization.SerialName("messageID") val messageId: String,
    @kotlinx.serialization.SerialName("partID") val partId: String? = null
)
