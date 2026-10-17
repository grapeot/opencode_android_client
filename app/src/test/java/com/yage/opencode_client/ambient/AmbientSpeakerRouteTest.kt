package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.audio.AmbientAudioCodes
import com.yage.opencode_client.ambient.audio.AmbientAudioRoute
import com.yage.opencode_client.ambient.audio.AmbientRouteDevice
import com.yage.opencode_client.ambient.audio.AmbientSpeakerAdapter
import com.yage.opencode_client.ambient.audio.AmbientSpeakerKind
import com.yage.opencode_client.ambient.audio.AmbientSpeakerOutcome
import com.yage.opencode_client.ambient.audio.AmbientSpeakerOwnership
import com.yage.opencode_client.ambient.audio.AmbientSpeakerRoute
import com.yage.opencode_client.ambient.audio.AmbientSpeakerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientSpeakerRouteTest {
    @Test
    fun `builtin earpiece selects the speaker communication device`() {
        val adapter = builtin()
        val ready = AmbientSpeakerRoute.applyDefault(adapter, 4L) as AmbientSpeakerOutcome.Ready
        assertEquals(listOf("set:${speaker.id}"), adapter.calls)
        assertEquals(AmbientSpeakerKind.COMMUNICATION, ready.hold.kind)
        assertEquals(speaker.id, ready.hold.appliedId)
        assertEquals(earpiece, ready.hold.previousCommunication)
        assertTrue(AmbientSpeakerRoute.watchIds(adapter.snapshot()).isEmpty())
    }

    @Test
    fun `speaker already selected does not change the route`() {
        val adapter = builtin(current = speaker)
        val ready = AmbientSpeakerRoute.applyDefault(adapter, 1L) as AmbientSpeakerOutcome.Ready
        assertTrue(adapter.calls.isEmpty())
        assertEquals(AmbientSpeakerKind.NONE, ready.hold.kind)
    }

    @Test
    fun `wired usb hfp and ble routes stay put`() {
        assertTrue(kept(wired).calls.isEmpty())
        assertTrue(kept(usb).calls.isEmpty())
        assertTrue(kept(usbHeadset).calls.isEmpty())
        assertTrue(kept(sco).calls.isEmpty())
        assertTrue(kept(ble).calls.isEmpty())
        assertEquals(setOf(wired.id), AmbientSpeakerRoute.watchIds(kept(wired).snapshot()))
        assertEquals(setOf(usb.id), AmbientSpeakerRoute.watchIds(kept(usb).snapshot()))
        assertEquals(setOf(sco.id), AmbientSpeakerRoute.watchIds(kept(sco).snapshot()))
        assertEquals(setOf(ble.id), AmbientSpeakerRoute.watchIds(kept(ble).snapshot()))
    }

    @Test
    fun `legacy speakerphone is used only for a builtin route`() {
        val legacy = builtin(api31 = false)
        val ready = AmbientSpeakerRoute.applyDefault(legacy, 3L) as AmbientSpeakerOutcome.Ready
        assertEquals(listOf("speaker:true"), legacy.calls)
        assertEquals(AmbientSpeakerKind.SPEAKERPHONE, ready.hold.kind)
        assertEquals(false, ready.hold.previousSpeakerphone)

        val guarded = builtin(api31 = false, outputs = listOf(earpiece, speaker, wired), wired = true)
        assertTrue(AmbientSpeakerRoute.applyDefault(guarded, 3L) is AmbientSpeakerOutcome.Ready)
        assertTrue(guarded.calls.isEmpty())
    }

    @Test
    fun `missing communication speaker falls back to guarded speakerphone`() {
        val missing = FakeSpeakerAdapter(
            api31 = true,
            outputs = listOf(earpiece, speaker),
            communicationDevices = listOf(earpiece),
            current = earpiece,
        )
        val ready = AmbientSpeakerRoute.applyDefault(missing, 8L) as AmbientSpeakerOutcome.Ready
        assertEquals(listOf("speaker:true"), missing.calls)
        assertEquals(AmbientSpeakerKind.SPEAKERPHONE, ready.hold.kind)

        val failedSet = builtin(setDeviceResult = false)
        val fallback = AmbientSpeakerRoute.applyDefault(failedSet, 8L) as AmbientSpeakerOutcome.Ready
        assertEquals(listOf("set:${speaker.id}", "speaker:true"), failedSet.calls)
        assertEquals(AmbientSpeakerKind.SPEAKERPHONE, fallback.hold.kind)

        val becameWired = builtin(setDeviceResult = false, wireOnFailedSet = true)
        val keptExternal = AmbientSpeakerRoute.applyDefault(becameWired, 8L) as AmbientSpeakerOutcome.Ready
        assertEquals(listOf("set:${speaker.id}"), becameWired.calls)
        assertEquals(AmbientSpeakerKind.NONE, keptExternal.hold.kind)
    }

    @Test
    fun `unreadable bluetooth fails with a fixed code and does not select a device`() {
        val adapter = FakeSpeakerAdapter(
            api31 = true,
            outputs = listOf(earpiece, sco),
            communicationDevices = null,
            current = null,
            communicationDenied = true,
        )
        val failed = AmbientSpeakerRoute.applyDefault(adapter, 1L) as AmbientSpeakerOutcome.Failed
        assertEquals(AmbientAudioCodes.BLUETOOTH_ROUTE_UNREADABLE, failed.code)
        assertFalse(failed.code.any { it.isDigit() })
        assertTrue(adapter.calls.isEmpty())
    }

    @Test
    fun `old ownership cannot restore over a newer run`() {
        val firstAdapter = builtin()
        val first = AmbientSpeakerRoute.applyDefault(firstAdapter, 1L) as AmbientSpeakerOutcome.Ready
        val secondAdapter = builtin()
        val second = AmbientSpeakerRoute.applyDefault(secondAdapter, 2L) as AmbientSpeakerOutcome.Ready
        val ownership = AmbientSpeakerOwnership()
        ownership.retain(1L, first.hold)
        ownership.retain(2L, second.hold)
        assertNull(ownership.take(1L))
        AmbientSpeakerRoute.undo(secondAdapter, first.hold, activeGeneration = 2L) { true }
        assertEquals(listOf("set:${speaker.id}"), secondAdapter.calls)
        assertEquals(2L, ownership.currentOwner())

        val taken = ownership.take(1L)
        assertNull(taken)
        val live = ownership.take(2L)
        assertEquals(2L, live?.generation)
        assertNull(ownership.take(2L))
    }

    @Test
    fun `restore returns the previous route only while this run still owns it`() {
        val adapter = builtin(current = null)
        val ready = AmbientSpeakerRoute.applyDefault(adapter, 6L) as AmbientSpeakerOutcome.Ready
        adapter.calls.clear()
        AmbientSpeakerRoute.undo(adapter, ready.hold, 6L) { true }
        assertEquals(listOf("clear"), adapter.calls)

        val earpieceAdapter = builtin()
        val earpieceHold = (AmbientSpeakerRoute.applyDefault(earpieceAdapter, 6L) as AmbientSpeakerOutcome.Ready).hold
        earpieceAdapter.calls.clear()
        AmbientSpeakerRoute.undo(earpieceAdapter, earpieceHold, 6L) { true }
        assertEquals(listOf("set:${earpiece.id}"), earpieceAdapter.calls)

        val superseded = builtin()
        val supersededHold = (AmbientSpeakerRoute.applyDefault(superseded, 6L) as AmbientSpeakerOutcome.Ready).hold
        superseded.outputs = listOf(earpiece, speaker, wired)
        superseded.current = wired
        superseded.calls.clear()
        AmbientSpeakerRoute.undo(superseded, supersededHold, 6L) { true }
        assertTrue(superseded.calls.isEmpty())

        val legacy = builtin(api31 = false)
        val legacyHold = (AmbientSpeakerRoute.applyDefault(legacy, 6L) as AmbientSpeakerOutcome.Ready).hold
        legacy.calls.clear()
        AmbientSpeakerRoute.undo(legacy, legacyHold, 6L) { true }
        assertEquals(listOf("speaker:false"), legacy.calls)

        val replaced = AmbientSpeakerOwnership()
        replaced.retain(6L, earpieceHold)
        val stale = replaced.take(6L)
        replaced.retain(7L, earpieceHold.copy(generation = 7L))
        earpieceAdapter.calls.clear()
        AmbientSpeakerRoute.undo(earpieceAdapter, stale!!, 6L) { replaced.currentOwner() == 0L }
        assertTrue(earpieceAdapter.calls.isEmpty())
        assertEquals(7L, replaced.currentOwner())
    }

    private fun kept(external: AmbientRouteDevice): FakeSpeakerAdapter {
        val adapter = builtin(
            outputs = listOf(earpiece, speaker, external),
            communicationDevices = listOf(earpiece, speaker, external),
            current = external,
        )
        val ready = AmbientSpeakerRoute.applyDefault(adapter, 2L) as AmbientSpeakerOutcome.Ready
        assertEquals(AmbientSpeakerKind.NONE, ready.hold.kind)
        return adapter
    }

    private fun builtin(
        api31: Boolean = true,
        current: AmbientRouteDevice? = earpiece,
        outputs: List<AmbientRouteDevice> = listOf(earpiece, speaker),
        communicationDevices: List<AmbientRouteDevice>? = if (api31) listOf(earpiece, speaker) else null,
        wired: Boolean = false,
        setDeviceResult: Boolean = true,
        wireOnFailedSet: Boolean = false,
    ): FakeSpeakerAdapter {
        return FakeSpeakerAdapter(
            api31 = api31,
            outputs = outputs,
            communicationDevices = communicationDevices,
            current = if (api31) current else null,
            speakerphoneOn = false,
            wiredHeadsetOn = wired,
            setDeviceResult = setDeviceResult,
            wireOnFailedSet = wireOnFailedSet,
        )
    }

    private companion object {
        val earpiece = AmbientRouteDevice(11, AmbientAudioRoute.TYPE_BUILTIN_EARPIECE)
        val speaker = AmbientRouteDevice(22, AmbientAudioRoute.TYPE_BUILTIN_SPEAKER)
        val wired = AmbientRouteDevice(33, AmbientAudioRoute.TYPE_WIRED_HEADSET)
        val usb = AmbientRouteDevice(44, AmbientAudioRoute.TYPE_USB_DEVICE)
        val usbHeadset = AmbientRouteDevice(45, AmbientAudioRoute.TYPE_USB_HEADSET)
        val sco = AmbientRouteDevice(55, AmbientAudioRoute.TYPE_BLUETOOTH_SCO)
        val ble = AmbientRouteDevice(66, AmbientAudioRoute.TYPE_BLE_HEADSET)
    }
}

private class FakeSpeakerAdapter(
    private val api31: Boolean,
    var outputs: List<AmbientRouteDevice>,
    private val communicationDevices: List<AmbientRouteDevice>?,
    var current: AmbientRouteDevice?,
    private val communicationDenied: Boolean = false,
    private var speakerphoneOn: Boolean? = false,
    private var wiredHeadsetOn: Boolean = false,
    private val bluetoothScoOn: Boolean? = false,
    private val setDeviceResult: Boolean = true,
    private val setSpeakerResult: Boolean = true,
    private val wireOnFailedSet: Boolean = false,
) : AmbientSpeakerAdapter {
    val calls = ArrayList<String>()

    override fun snapshot(): AmbientSpeakerSnapshot {
        return AmbientSpeakerSnapshot(
            api31 = api31,
            outputs = outputs,
            communicationDevices = communicationDevices,
            current = current,
            communicationDenied = communicationDenied,
            speakerphoneOn = speakerphoneOn,
            wiredHeadsetOn = wiredHeadsetOn,
            bluetoothScoOn = bluetoothScoOn,
        )
    }

    override fun setCommunicationDevice(id: Int): Boolean {
        calls.add("set:$id")
        if (!setDeviceResult) {
            if (wireOnFailedSet) {
                outputs = listOf(earpiece(), speaker(), wired())
                current = wired()
                wiredHeadsetOn = true
            }
            return false
        }
        current = (communicationDevices.orEmpty() + outputs).firstOrNull { it.id == id } ?: current
        return true
    }

    override fun clearCommunicationDevice() {
        calls.add("clear")
        current = null
    }

    override fun setSpeakerphoneOn(on: Boolean): Boolean {
        calls.add("speaker:$on")
        if (!setSpeakerResult) return false
        speakerphoneOn = on
        return true
    }

    private fun earpiece() = AmbientRouteDevice(11, AmbientAudioRoute.TYPE_BUILTIN_EARPIECE)

    private fun speaker() = AmbientRouteDevice(22, AmbientAudioRoute.TYPE_BUILTIN_SPEAKER)

    private fun wired() = AmbientRouteDevice(33, AmbientAudioRoute.TYPE_WIRED_HEADSET)
}
