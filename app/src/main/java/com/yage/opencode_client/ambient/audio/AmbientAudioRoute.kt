package com.yage.opencode_client.ambient.audio

import android.media.AudioManager

internal data class AmbientRouteDevice(val id: Int, val type: Int)

/**
 * Platform [android.media.AudioDeviceInfo] type codes. Compared against
 * [android.media.AudioDeviceInfo.getType]; product names are never read.
 */
internal object AmbientAudioRoute {
    const val VOLUME_STREAM: Int = AudioManager.STREAM_VOICE_CALL

    const val TYPE_BUILTIN_EARPIECE = 1
    const val TYPE_BUILTIN_SPEAKER = 2
    const val TYPE_WIRED_HEADSET = 3
    const val TYPE_WIRED_HEADPHONES = 4
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_BLUETOOTH_A2DP = 8
    const val TYPE_USB_DEVICE = 11
    const val TYPE_USB_HEADSET = 22
    const val TYPE_BLE_HEADSET = 26

    fun isWiredOrUsb(type: Int): Boolean = type == TYPE_WIRED_HEADSET ||
        type == TYPE_WIRED_HEADPHONES ||
        type == TYPE_USB_HEADSET ||
        type == TYPE_USB_DEVICE

    fun isScoFamily(type: Int): Boolean = type == TYPE_BLUETOOTH_SCO || type == TYPE_BLE_HEADSET

    fun isExternalDisconnect(type: Int): Boolean = isWiredOrUsb(type) || isScoFamily(type) || type == TYPE_BLUETOOTH_A2DP

    fun watchActive(id: Int, type: Int): Set<Int> = if (isExternalDisconnect(type)) setOf(id) else emptySet()

    /** The routed device only. An enumerated device list is not a watch set. */
    fun watchRouted(routedId: Int, routedType: Int): Set<Int> = watchActive(routedId, routedType)

    fun watchLegacy(
        outputs: List<AmbientRouteDevice>,
        wiredHeadsetOn: Boolean,
        bluetoothScoOn: Boolean,
    ): Set<Int> {
        val ids = LinkedHashSet<Int>()
        if (wiredHeadsetOn) {
            for (device in outputs) {
                if (isWiredOrUsb(device.type)) ids.add(device.id)
            }
        }
        if (bluetoothScoOn) {
            for (device in outputs) {
                if (isScoFamily(device.type)) ids.add(device.id)
            }
        }
        return ids
    }

    fun blocksBecauseBluetoothUnreadable(readable: Boolean, outputs: List<AmbientRouteDevice>): Boolean {
        if (readable) return false
        return outputs.any { isScoFamily(it.type) }
    }

    fun removedEndsRun(watchedIds: Set<Int>, removedId: Int, removedType: Int): Boolean {
        return removedId in watchedIds && isExternalDisconnect(removedType)
    }
}

internal object AmbientAudioSignals {
    fun endsOnFocusChange(change: Int): Boolean = when (change) {
        AudioManager.AUDIOFOCUS_LOSS,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        -> true
        else -> false
    }

    fun reopensAfterFocusGain(): Boolean = false

    fun ducks(): Boolean = false
}
