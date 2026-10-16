package com.yage.opencode_client.ui

import com.yage.opencode_client.ui.chat.ChatFilePreviewRequest
import com.yage.opencode_client.ui.chat.belongsTo

enum class LayoutPreference {
    AUTOMATIC,
    FOCUS;

    companion object {
        fun fromRaw(raw: String?): LayoutPreference =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AUTOMATIC
    }
}

enum class AppWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

enum class EffectiveLayout {
    COMPACT,
    REGULAR,
}

fun resolveEffectiveLayout(preference: LayoutPreference, width: AppWidthClass): EffectiveLayout {
    if (preference == LayoutPreference.FOCUS) return EffectiveLayout.COMPACT
    return if (width == AppWidthClass.EXPANDED) EffectiveLayout.REGULAR else EffectiveLayout.COMPACT
}

enum class FilePreviewKind { CHAT, FILES }

data class OwnedFilePreview(
    val path: String,
    val hostId: String?,
    val sessionId: String?,
    val workspace: String?,
    val kind: FilePreviewKind,
)

data class PreviewMigration(
    val inline: ChatFilePreviewRequest?,
    val column: OwnedFilePreview?,
)

fun layoutMigrationFrom(stored: EffectiveLayout?, current: EffectiveLayout): EffectiveLayout? {
    if (stored == null || stored == current) return null
    return stored
}

fun chatPreviewMatches(preview: OwnedFilePreview, hostId: String?, sessionId: String?, workspace: String?): Boolean =
    preview.kind == FilePreviewKind.CHAT &&
        preview.hostId == hostId &&
        preview.sessionId == sessionId &&
        preview.workspace == workspace

fun filesPreviewMatches(preview: OwnedFilePreview, hostId: String?): Boolean =
    preview.kind == FilePreviewKind.FILES && preview.hostId == hostId

fun retainColumn(column: OwnedFilePreview?, hostId: String?, sessionId: String?, workspace: String?): OwnedFilePreview? {
    column ?: return null
    return when (column.kind) {
        FilePreviewKind.CHAT -> column.takeIf { chatPreviewMatches(it, hostId, sessionId, workspace) }
        FilePreviewKind.FILES -> column.takeIf { filesPreviewMatches(it, hostId) }
    }
}

fun migrateVisiblePreview(
    from: EffectiveLayout,
    to: EffectiveLayout,
    inline: ChatFilePreviewRequest?,
    column: OwnedFilePreview?,
    hostId: String?,
    sessionId: String?,
    workspace: String?,
): PreviewMigration {
    val keptInline = inline?.takeIf { it.belongsTo(hostId, sessionId, workspace) }
    val keptColumn = retainColumn(column, hostId, sessionId, workspace)
    if (from == to) return PreviewMigration(keptInline, keptColumn)
    return when (from) {
        EffectiveLayout.COMPACT -> {
            val active = keptInline ?: return PreviewMigration(null, null)
            PreviewMigration(
                inline = null,
                column = OwnedFilePreview(
                    path = active.path,
                    hostId = active.hostProfileId,
                    sessionId = active.sessionId,
                    workspace = active.workspaceDirectory,
                    kind = FilePreviewKind.CHAT,
                ),
            )
        }
        EffectiveLayout.REGULAR -> when (keptColumn?.kind) {
            FilePreviewKind.CHAT -> PreviewMigration(
                inline = ChatFilePreviewRequest(
                    path = keptColumn.path,
                    hostProfileId = keptColumn.hostId,
                    sessionId = keptColumn.sessionId ?: return PreviewMigration(null, null),
                    workspaceDirectory = keptColumn.workspace,
                ),
                column = null,
            )
            FilePreviewKind.FILES -> PreviewMigration(inline = null, column = keptColumn)
            null -> PreviewMigration(null, null)
        }
    }
}

data class SendRecovery(
    val hostId: String?,
    val sessionId: String,
    val selectionEpoch: Long,
    val text: String,
    val attachments: List<com.yage.opencode_client.data.model.ComposerImageAttachment>,
)

data class DeferredPaste(
    val addition: String,
    val owner: DraftOwner,
    val focused: Boolean,
    val selectionStart: Int,
    val selectionEnd: Int,
    val speechOwnedEnd: Int?,
)

internal data class DeferredSpeech(
    val transcript: String,
    val target: SpeechDraftTarget,
    val attemptId: Long,
)

data class DraftOwner(
    val hostId: String?,
    val sessionId: String?,
) {
    fun matches(hostId: String?, sessionId: String?): Boolean =
        this.hostId == hostId && this.sessionId == sessionId
}

internal const val OWNED_DRAFT_PREFIX = "owned\u001f"

fun ownedDraftStorageKey(hostId: String, sessionId: String): String =
    "$OWNED_DRAFT_PREFIX$hostId\u001f$sessionId"

data class DraftLookup(
    val text: String,
    val migrated: Boolean = false,
    val ambiguousLegacy: Boolean = false,
    val stored: Map<String, String>? = null,
)

fun lookupDraft(
    stored: Map<String, String>,
    hostId: String?,
    sessionId: String,
    knownHostIds: Set<String>,
): DraftLookup {
    if (hostId != null) {
        val ownedKey = ownedDraftStorageKey(hostId, sessionId)
        stored[ownedKey]?.let { return DraftLookup(text = it) }
    }
    val legacy = stored[sessionId]
    if (legacy == null) return DraftLookup(text = "")
    if (hostId == null) return DraftLookup(text = legacy)
    if (knownHostIds.size == 1 && knownHostIds.single() == hostId) {
        val next = stored.toMutableMap()
        next.remove(sessionId)
        next[ownedDraftStorageKey(hostId, sessionId)] = legacy
        return DraftLookup(text = legacy, migrated = true, stored = next)
    }
    if (knownHostIds.size > 1) {
        return DraftLookup(text = "", ambiguousLegacy = true)
    }
    return DraftLookup(text = legacy)
}

fun joinClipboardText(parts: List<String>): String =
    parts.filter { it.isNotEmpty() }.joinToString("\n")

enum class PastePlacement {
    APPEND,
    INSERT,
    DEFER,
}

fun pastePlacement(
    focused: Boolean,
    selectionStart: Int,
    selectionEnd: Int,
    composing: Boolean,
    speechOwnedEnd: Int?,
): PastePlacement {
    if (composing) return PastePlacement.DEFER
    if (!focused) return PastePlacement.APPEND
    if (speechOwnedEnd != null && selectionStart < speechOwnedEnd) return PastePlacement.APPEND
    if (selectionStart < 0 || selectionEnd < selectionStart) return PastePlacement.APPEND
    return PastePlacement.INSERT
}

fun appendPaste(draft: String, addition: String): String {
    if (addition.isEmpty()) return draft
    if (draft.isEmpty()) return addition
    return draft + "\n" + addition
}

fun insertPaste(draft: String, addition: String, start: Int, end: Int): Pair<String, Int> {
    if (addition.isEmpty()) return draft to start.coerceIn(0, draft.length)
    val safeStart = start.coerceIn(0, draft.length)
    val safeEnd = end.coerceIn(safeStart, draft.length)
    val next = draft.replaceRange(safeStart, safeEnd, addition)
    return next to (safeStart + addition.length)
}

data class SpeechRender(
    val text: String,
    val anchor: String,
    val tail: String,
)

fun renderAnchoredSpeech(
    prefix: String,
    transcript: String,
    anchor: String,
    latest: String,
    captureActive: Boolean = false,
): SpeechRender {
    val tail = speechTailAfter(anchor, latest, captureActive)
    if (transcript.trim().isEmpty()) {
        val text = if (anchor.isNotEmpty()) latest else prefix + tail
        return SpeechRender(text = text, anchor = anchor, tail = tail)
    }
    val speech = mergedSpeechInput(prefix, transcript)
    val kept = if (anchor.isEmpty() && speech.isNotEmpty() && tail.startsWith(speech)) {
        tail.removePrefix(speech)
    } else {
        tail
    }
    return SpeechRender(text = speech + kept, anchor = speech, tail = kept)
}

private fun speechTailAfter(anchor: String, latest: String, captureActive: Boolean): String = when {
    anchor.isNotEmpty() && latest.startsWith(anchor) -> latest.removePrefix(anchor)
    anchor.isEmpty() && captureActive -> latest
    else -> ""
}

fun mergingSpeech(prefix: String, transcript: String, latestDraft: String): String {
    val speech = mergedSpeechInput(prefix, transcript)
    val tail = userAppendedTail(latestDraft, prefix)
    if (tail.isEmpty()) {
        if (latestDraft.isEmpty() || prefix.isEmpty() || latestDraft.startsWith(prefix)) return speech
        return latestDraft
    }
    if (speech == tail || speech.endsWith("\n$tail")) return speech
    return appendPaste(speech, tail)
}

fun userAppendedTail(draft: String, prefix: String): String {
    if (draft.isEmpty()) return ""
    val rest = when {
        prefix.isEmpty() -> draft
        draft.startsWith(prefix) -> draft.removePrefix(prefix)
        else -> return ""
    }
    val newline = rest.indexOf('\n')
    if (newline < 0) return ""
    return rest.substring(newline + 1)
}

fun mergeSendFailure(sent: String, latest: String): String {
    if (latest.isEmpty()) return sent
    if (latest == sent) return sent
    if (sent.isNotEmpty() && latest.startsWith(sent + "\n")) return latest
    return appendPaste(sent, latest)
}

fun unionAttachmentsById(sentIds: List<String>, latestIds: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    sentIds.forEach { if (it.isNotEmpty()) seen.add(it) }
    latestIds.forEach { if (it.isNotEmpty()) seen.add(it) }
    return seen.toList()
}

fun shouldPushTextToEditor(
    boundText: String?,
    incoming: String,
    composing: Boolean,
    ownerChanged: Boolean,
): Boolean {
    if (ownerChanged) return true
    if (composing) return false
    return boundText != incoming
}
