package com.yage.opencode_client.ambient.audio

/**
 * Builtin-only voice routes prefer the handset speaker. Wired, USB, and active
 * Bluetooth routes are left in place. Product names and device ids are never logged.
 * The engine calls this after communication mode and focus, before capture starts.
 */
internal object AmbientSpeakerRoute {
    fun applyDefault(adapter: AmbientSpeakerAdapter, generation: Long): AmbientSpeakerOutcome {
        val choice = try {
            choose(adapter.snapshot())
        } catch (_: AmbientAudioStartException) {
            return AmbientSpeakerOutcome.Failed(AmbientAudioCodes.BLUETOOTH_ROUTE_UNREADABLE)
        }
        return when (choice) {
            is AmbientSpeakerChoice.Failed -> AmbientSpeakerOutcome.Failed(choice.code)
            AmbientSpeakerChoice.Keep -> hold(generation, AmbientSpeakerKind.NONE, null, null, null)
            is AmbientSpeakerChoice.CommunicationDevice -> applyCommunication(adapter, generation, choice)
            is AmbientSpeakerChoice.Speakerphone -> applySpeakerphone(adapter, generation, choice.previousOn)
        }
    }

    fun choose(snapshot: AmbientSpeakerSnapshot): AmbientSpeakerChoice {
        if (unreadableBluetooth(snapshot)) {
            return AmbientSpeakerChoice.Failed(AmbientAudioCodes.BLUETOOTH_ROUTE_UNREADABLE)
        }
        if (blocksExternal(snapshot)) return AmbientSpeakerChoice.Keep
        if (snapshot.current?.type == AmbientAudioRoute.TYPE_BUILTIN_SPEAKER) return AmbientSpeakerChoice.Keep
        if (!snapshot.api31 && snapshot.speakerphoneOn == true) return AmbientSpeakerChoice.Keep
        if (snapshot.api31 && !snapshot.communicationDenied) {
            val speaker = snapshot.communicationDevices.orEmpty().firstOrNull { device ->
                device.type == AmbientAudioRoute.TYPE_BUILTIN_SPEAKER
            }
            if (speaker != null) {
                if (snapshot.current?.id == speaker.id) return AmbientSpeakerChoice.Keep
                return AmbientSpeakerChoice.CommunicationDevice(speaker.id, snapshot.current)
            }
        }
        if (snapshot.speakerphoneOn == true) return AmbientSpeakerChoice.Keep
        val previous = snapshot.speakerphoneOn ?: return AmbientSpeakerChoice.Failed(
            AmbientAudioCodes.CAPTURE_START_FAILED,
        )
        return AmbientSpeakerChoice.Speakerphone(previous)
    }

    fun watchIds(snapshot: AmbientSpeakerSnapshot): Set<Int> {
        val ids = LinkedHashSet<Int>()
        val current = snapshot.current
        if (current != null && AmbientAudioRoute.isExternalDisconnect(current.type)) ids.add(current.id)
        for (device in blockingDevices(snapshot)) ids.add(device.id)
        if (snapshot.wiredHeadsetOn) {
            for (device in snapshot.outputs) {
                if (device.type == AmbientAudioRoute.TYPE_WIRED_HEADSET ||
                    device.type == AmbientAudioRoute.TYPE_WIRED_HEADPHONES
                ) {
                    ids.add(device.id)
                }
            }
        }
        if (snapshot.bluetoothScoOn == true) {
            for (device in snapshot.outputs) {
                if (AmbientAudioRoute.isScoFamily(device.type)) ids.add(device.id)
            }
        }
        return ids
    }

    fun undo(
        adapter: AmbientSpeakerAdapter,
        hold: AmbientSpeakerHold,
        activeGeneration: Long,
        stillOwner: () -> Boolean,
    ) {
        if (hold.generation != activeGeneration || !stillOwner()) return
        if (hold.kind == AmbientSpeakerKind.NONE) return
        val snapshot = try {
            adapter.snapshot()
        } catch (_: AmbientAudioStartException) {
            return
        }
        if (!stillOwner() || !ownedRouteIntact(hold, snapshot)) return
        when (hold.kind) {
            AmbientSpeakerKind.COMMUNICATION -> {
                val previous = hold.previousCommunication
                if (previous == null) adapter.clearCommunicationDevice()
                else adapter.setCommunicationDevice(previous.id)
            }
            AmbientSpeakerKind.SPEAKERPHONE -> {
                val previous = hold.previousSpeakerphone ?: return
                adapter.setSpeakerphoneOn(previous)
            }
            AmbientSpeakerKind.NONE -> Unit
        }
    }

    private fun applyCommunication(
        adapter: AmbientSpeakerAdapter,
        generation: Long,
        choice: AmbientSpeakerChoice.CommunicationDevice,
    ): AmbientSpeakerOutcome {
        if (adapter.setCommunicationDevice(choice.id)) {
            return hold(
                generation,
                AmbientSpeakerKind.COMMUNICATION,
                choice.id,
                choice.previous,
                null,
            )
        }
        val snapshot = try {
            adapter.snapshot()
        } catch (_: AmbientAudioStartException) {
            return AmbientSpeakerOutcome.Failed(AmbientAudioCodes.BLUETOOTH_ROUTE_UNREADABLE)
        }
        if (blocksExternal(snapshot) || snapshot.current?.type == AmbientAudioRoute.TYPE_BUILTIN_SPEAKER) {
            return hold(generation, AmbientSpeakerKind.NONE, null, null, null)
        }
        val previous = snapshot.speakerphoneOn ?: return AmbientSpeakerOutcome.Failed(
            AmbientAudioCodes.CAPTURE_START_FAILED,
        )
        if (previous) return hold(generation, AmbientSpeakerKind.NONE, null, null, null)
        return applySpeakerphone(adapter, generation, previous)
    }

    private fun applySpeakerphone(
        adapter: AmbientSpeakerAdapter,
        generation: Long,
        previousOn: Boolean,
    ): AmbientSpeakerOutcome {
        if (!adapter.setSpeakerphoneOn(true)) {
            return AmbientSpeakerOutcome.Failed(AmbientAudioCodes.CAPTURE_START_FAILED)
        }
        return hold(generation, AmbientSpeakerKind.SPEAKERPHONE, null, null, previousOn)
    }

    private fun unreadableBluetooth(snapshot: AmbientSpeakerSnapshot): Boolean {
        val outputs = snapshot.outputs
        if (snapshot.communicationDenied &&
            AmbientAudioRoute.blocksBecauseBluetoothUnreadable(false, outputs)
        ) {
            return true
        }
        return !snapshot.api31 &&
            snapshot.bluetoothScoOn == null &&
            AmbientAudioRoute.blocksBecauseBluetoothUnreadable(false, outputs)
    }

    private fun blocksExternal(snapshot: AmbientSpeakerSnapshot): Boolean {
        if (snapshot.wiredHeadsetOn || snapshot.bluetoothScoOn == true) return true
        return blockingDevices(snapshot).isNotEmpty()
    }

    private fun blockingDevices(snapshot: AmbientSpeakerSnapshot): List<AmbientRouteDevice> {
        val found = LinkedHashMap<Int, AmbientRouteDevice>()
        fun add(device: AmbientRouteDevice) {
            if (!AmbientAudioRoute.isExternalDisconnect(device.type)) return
            found.putIfAbsent(device.id, device)
        }
        for (device in snapshot.outputs) add(device)
        snapshot.current?.let { add(it) }
        for (device in snapshot.communicationDevices.orEmpty()) add(device)
        return found.values.toList()
    }

    private fun ownedRouteIntact(hold: AmbientSpeakerHold, snapshot: AmbientSpeakerSnapshot): Boolean {
        if (blocksExternal(snapshot)) return false
        return when (hold.kind) {
            AmbientSpeakerKind.COMMUNICATION ->
                snapshot.current?.id == hold.appliedId &&
                    snapshot.current?.type == AmbientAudioRoute.TYPE_BUILTIN_SPEAKER
            AmbientSpeakerKind.SPEAKERPHONE -> snapshot.speakerphoneOn == true
            AmbientSpeakerKind.NONE -> false
        }
    }

    private fun hold(
        generation: Long,
        kind: AmbientSpeakerKind,
        appliedId: Int?,
        previousCommunication: AmbientRouteDevice?,
        previousSpeakerphone: Boolean?,
    ): AmbientSpeakerOutcome.Ready {
        return AmbientSpeakerOutcome.Ready(
            AmbientSpeakerHold(
                generation = generation,
                kind = kind,
                appliedId = appliedId,
                previousCommunication = previousCommunication,
                previousSpeakerphone = previousSpeakerphone,
            ),
        )
    }
}

internal interface AmbientSpeakerAdapter {
    fun snapshot(): AmbientSpeakerSnapshot

    fun setCommunicationDevice(id: Int): Boolean

    fun clearCommunicationDevice()

    fun setSpeakerphoneOn(on: Boolean): Boolean
}

internal data class AmbientSpeakerSnapshot(
    val api31: Boolean,
    val outputs: List<AmbientRouteDevice>,
    val communicationDevices: List<AmbientRouteDevice>?,
    val current: AmbientRouteDevice?,
    val communicationDenied: Boolean,
    val speakerphoneOn: Boolean?,
    val wiredHeadsetOn: Boolean,
    val bluetoothScoOn: Boolean?,
)

internal sealed class AmbientSpeakerChoice {
    data object Keep : AmbientSpeakerChoice()

    data class CommunicationDevice(val id: Int, val previous: AmbientRouteDevice?) : AmbientSpeakerChoice()

    data class Speakerphone(val previousOn: Boolean) : AmbientSpeakerChoice()

    data class Failed(val code: String) : AmbientSpeakerChoice()
}

internal enum class AmbientSpeakerKind {
    NONE,
    COMMUNICATION,
    SPEAKERPHONE,
}

internal data class AmbientSpeakerHold(
    val generation: Long,
    val kind: AmbientSpeakerKind,
    val appliedId: Int?,
    val previousCommunication: AmbientRouteDevice?,
    val previousSpeakerphone: Boolean?,
)

internal sealed class AmbientSpeakerOutcome {
    data class Ready(val hold: AmbientSpeakerHold) : AmbientSpeakerOutcome()

    data class Failed(val code: String) : AmbientSpeakerOutcome()
}

/**
 * Per-run speaker override. A later [retain] replaces the owner so an older
 * generation's [take] cannot clear the route the new run selected.
 */
internal class AmbientSpeakerOwnership {
    private var owner = 0L
    private var hold: AmbientSpeakerHold? = null

    fun retain(generation: Long, hold: AmbientSpeakerHold) {
        owner = generation
        this.hold = hold
    }

    fun currentOwner(): Long = owner

    fun take(generation: Long): AmbientSpeakerHold? {
        if (owner != generation) return null
        val taken = hold
        owner = 0L
        hold = null
        return taken
    }
}
