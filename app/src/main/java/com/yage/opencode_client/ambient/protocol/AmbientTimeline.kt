package com.yage.opencode_client.ambient.protocol

enum class AmbientCaptionWrite {
    IGNORED,
    APPENDED,
    OPENED,
}

class AmbientCaptionBook {
    private val captionsMut = ArrayList<AmbientCaption>()
    private val openIndex = HashMap<String, Int>()
    var clock: Int = 0
        private set

    val captions: List<AmbientCaption> get() = captionsMut

    fun reset(history: List<AmbientHistoryTurn>) {
        clock = 0
        openIndex.clear()
        captionsMut.clear()
        for (turn in history) {
            if (turn.text.trim().isEmpty()) continue
            clock += 1
            val role = turn.role.trim().ifEmpty { "user" }
            captionsMut.add(
                AmbientCaption(
                    id = "history-$clock",
                    role = role,
                    text = turn.text,
                    sequence = clock,
                ),
            )
        }
    }

    fun append(role: String, text: String, startMs: Int? = null, endMs: Int? = null): AmbientCaptionWrite {
        if (text.isEmpty()) return AmbientCaptionWrite.IGNORED
        val index = openIndex[role]
        if (index != null && index in captionsMut.indices &&
            !splitsForSpeechGap(captionsMut[index].endMs, startMs)
        ) {
            val current = captionsMut[index]
            captionsMut[index] = current.copy(
                text = current.text + text,
                startMs = mergedStart(current.startMs, startMs),
                endMs = mergedEnd(current.endMs, endMs),
            )
            return AmbientCaptionWrite.APPENDED
        }
        clock += 1
        captionsMut.add(
            AmbientCaption(
                id = "caption-$clock",
                role = role,
                text = text,
                sequence = clock,
                startMs = startMs,
                endMs = endMs,
            ),
        )
        openIndex[role] = captionsMut.lastIndex
        trim()
        return AmbientCaptionWrite.OPENED
    }

    fun closeAll() {
        openIndex.clear()
    }

    fun advance(): Int {
        clock += 1
        return clock
    }

    fun openId(role: String): String? {
        val index = openIndex[role] ?: return null
        if (index !in captionsMut.indices) return null
        return captionsMut[index].id
    }

    private fun splitsForSpeechGap(previousEnd: Int?, nextStart: Int?): Boolean {
        if (previousEnd == null || nextStart == null) return false
        return nextStart - previousEnd > AmbientProtocol.SPEECH_GAP_MS
    }

    private fun mergedStart(current: Int?, next: Int?): Int? = when {
        current != null && next != null -> minOf(current, next)
        current != null -> current
        else -> next
    }

    private fun mergedEnd(current: Int?, next: Int?): Int? = when {
        current != null && next != null -> maxOf(current, next)
        current != null -> current
        else -> next
    }

    private fun trim() {
        if (captionsMut.size <= AmbientProtocol.CAPTION_LIMIT) return
        val drop = captionsMut.size - AmbientProtocol.CAPTION_LIMIT
        repeat(drop) { captionsMut.removeAt(0) }
        val nextOpen = HashMap<String, Int>()
        for ((role, index) in openIndex) {
            val moved = index - drop
            if (moved >= 0) nextOpen[role] = moved
        }
        openIndex.clear()
        openIndex.putAll(nextOpen)
    }
}

enum class AmbientClaim {
    ACCEPTED,
    DUPLICATE,
    REJECTED,
}

class AmbientCallBook(generation: Long) {
    var generation: Long = generation
        private set
    var accepting: Boolean = true
        private set
    private val pendingMut = ArrayList<Pair<String, String>>()
    private val seen = HashSet<String>()

    val pending: List<Pair<String, String>> get() = pendingMut
    val keptCalls: Int get() = pendingMut.size

    fun bind(next: Long) {
        generation = next
        accepting = true
        pendingMut.clear()
        seen.clear()
    }

    fun collect(callId: String, name: String) {
        if (callId.isEmpty()) return
        if (pendingMut.any { it.first == callId } || seen.contains(callId)) return
        pendingMut.add(callId to name)
    }

    fun claim(callId: String, generationNow: Long): AmbientClaim {
        if (!accepting || generationNow != generation || callId.isEmpty()) return AmbientClaim.REJECTED
        if (seen.contains(callId)) return AmbientClaim.DUPLICATE
        seen.add(callId)
        pendingMut.removeAll { it.first == callId }
        return AmbientClaim.ACCEPTED
    }

    /** Empty or nonempty lifecycle output does not erase calls already collected. */
    fun noteLifecycle(outputCount: Int) {
        // outputCount is intentionally unused.
        if (outputCount < 0) return
    }
}

class AmbientFlightBook {
    var inFlight: Int = 0
        private set
    var blocked: Boolean = false
        private set
    var continuations: Int = 0
        private set

    fun begin() {
        inFlight += 1
    }

    fun finish(outputSent: Boolean, accepting: Boolean): Boolean {
        if (!outputSent) blocked = true
        inFlight = maxOf(0, inFlight - 1)
        if (!outputSent || blocked || inFlight != 0 || !accepting) return false
        continuations += 1
        return true
    }
}

/**
 * Shared caption and tool sequence. A new tool row closes open captions.
 * Updating an existing tool row does not.
 */
class AmbientTimeline {
    val captionBook = AmbientCaptionBook()
    private val activitiesMut = ArrayList<AmbientActivity>()

    val captions: List<AmbientCaption> get() = captionBook.captions
    val activities: List<AmbientActivity> get() = activitiesMut

    fun reset(history: List<AmbientHistoryTurn> = emptyList()) {
        captionBook.reset(history)
        activitiesMut.clear()
    }

    fun appendCaption(role: String, fragment: AmbientTranscriptFragment): AmbientCaptionWrite =
        captionBook.append(role, fragment.text, fragment.startMs, fragment.endMs)

    fun upsertActivity(id: String, name: String, arguments: String, status: String, result: String) {
        val index = activitiesMut.indexOfFirst { it.id == id }
        if (index >= 0) {
            val current = activitiesMut[index]
            activitiesMut[index] = current.copy(
                status = status,
                result = if (result.isNotEmpty()) result else current.result,
            )
            return
        }
        captionBook.closeAll()
        val sequence = captionBook.advance()
        activitiesMut.add(
            AmbientActivity(
                id = id,
                name = name,
                arguments = arguments,
                status = status,
                result = result,
                sequence = sequence,
            ),
        )
        if (activitiesMut.size > AmbientProtocol.ACTIVITY_LIMIT) {
            val drop = activitiesMut.size - AmbientProtocol.ACTIVITY_LIMIT
            repeat(drop) { activitiesMut.removeAt(0) }
        }
    }
}
