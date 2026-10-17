package com.yage.opencode_client.ambient.protocol

enum class AmbientPhase {
    IDLE,
    CONNECTING,
    LISTENING,
    SPEAKING,
    CLOSING,
    FAILED,
}

data class AmbientCaption(
    val id: String,
    val role: String,
    val text: String,
    val sequence: Int,
    val startMs: Int? = null,
    val endMs: Int? = null,
)

data class AmbientActivity(
    val id: String,
    val name: String,
    val arguments: String,
    val status: String,
    val result: String,
    val sequence: Int,
)

data class AmbientHistoryTurn(val role: String, val text: String)

data class AmbientConfiguration(
    val portalBaseUrl: String,
    val builderToken: String,
    val history: List<AmbientHistoryTurn> = emptyList(),
    val instructions: String = AmbientSchema.frontendInstructions,
    val backendInstructions: String = AmbientSchema.backendInstructions,
    val voice: String = AmbientProtocol.DEFAULT_VOICE,
    val toolsJson: String = AmbientSchema.toolsJson(),
    val ownerToken: String = "",
) {
    override fun toString(): String =
        "AmbientConfiguration(portalBaseUrl=$portalBaseUrl, voice=$voice, ownerSet=${ownerToken.isNotEmpty()})"
}

data class AmbientFunctionCall(
    val callId: String,
    val name: String,
    val arguments: String,
    val ownerToken: String,
)

data class AmbientToolResult(
    val output: String,
    val acceptedSessionId: String? = null,
)

data class AmbientToolEffect(
    val output: String,
    val sessionId: String? = null,
    val messageId: String? = null,
    val directory: String? = null,
    val model: String? = null,
    val clearSessionId: String? = null,
    val poll: Boolean = false,
)

data class AmbientBinding(
    val sessionId: String,
    val messageId: String? = null,
    val directory: String,
    val model: String,
)

/**
 * Start-of-run authority. [directory] may be null when no chat session was selected.
 * Chat selection is not an input: [workspaceUnchanged] is the host workspace snapshot,
 * not the row the user tapped in the list.
 */
data class AmbientLease(
    val hostProfileId: String,
    val runtimeGeneration: Long,
    val directory: String?,
    val epoch: Long,
    val ownerToken: String,
    val transportToken: Long = 0,
) {
    /**
     * Early check. [ownerToken] is the run owner captured at start.
     * [workspaceUnchanged] is the host workspace snapshot.
     * This does not compare [directory] or the chat row selected in the list.
     */
    fun allows(
        connected: Boolean,
        hostProfileId: String,
        runtimeGeneration: Long,
        workspaceUnchanged: Boolean,
        epoch: Long,
        ownerToken: String,
    ): Boolean =
        connected &&
            this.hostProfileId == hostProfileId &&
            this.runtimeGeneration == runtimeGeneration &&
            workspaceUnchanged &&
            this.epoch == epoch &&
            this.ownerToken == ownerToken
}

object AmbientRunOwner {
    fun shouldClose(current: AmbientLease?, owned: AmbientLease): Boolean = current == owned
}

object AmbientBindingPolicy {
    /** Only a 404 for the requested id removes that binding. 401, 500, and transport failure keep it. */
    fun afterLookup(current: AmbientBinding?, requestedId: String, httpCode: Int?): AmbientBinding? {
        if (httpCode == 404 && current?.sessionId == requestedId) return null
        return current
    }
}

object AmbientStartPolicy {
    const val REJECTS_MISSING_DIRECTORY = false

    /**
     * Null or blank means the server default directory. Never invent a path.
     * A value that contains a non-whitespace character is kept as given.
     */
    fun createDirectoryArgument(snapshotDirectory: String?): String? =
        snapshotDirectory?.takeIf { it.isNotBlank() }
}

data class AmbientSnapshot(
    val phase: AmbientPhase,
    val captions: List<AmbientCaption>,
    val activities: List<AmbientActivity>,
    val muted: Boolean,
    val inputRms: Double,
    val outputRms: Double,
    val voiceSeconds: Double,
    val voiceHasSnapshot: Boolean,
    val voiceUnconfirmed: Boolean,
    val local: AmbientBackendUsage,
    val portalUsd: Double?,
    val portalSettled: Boolean,
    val usageUnconfirmed: Boolean,
    val statusMessage: String?,
    /** Session clock in whole seconds. Not [voiceSeconds]. Omitted callers stay at 0. */
    val elapsedSeconds: Long = 0L,
) {
    val microphoneFill: Double
        get() = AmbientMeter.targetFill(inputRms, phase, muted)
}

data class AmbientCostSnapshot(
    val localEstimateUsd: Double,
    val localUnpricedCount: Int,
    val portalUsd: Double?,
    val portalSettled: Boolean,
    val estimateNote: String = AmbientProtocol.ESTIMATE_NOTE,
)
