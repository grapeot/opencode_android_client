package com.yage.opencode_client.ambient

import android.media.AudioManager
import com.yage.opencode_client.ambient.audio.AmbientAudioCodes
import com.yage.opencode_client.ambient.audio.AmbientAudioRoute
import com.yage.opencode_client.ambient.audio.AmbientAudioSignals
import com.yage.opencode_client.ambient.audio.AmbientHardwareWriter
import com.yage.opencode_client.ambient.audio.AmbientPlaybackCursor
import com.yage.opencode_client.ambient.audio.AmbientPlaybackLedger
import com.yage.opencode_client.ambient.audio.AmbientPlaybackPump
import com.yage.opencode_client.ambient.audio.AmbientResampler
import com.yage.opencode_client.ambient.audio.AmbientRouteDevice
import com.yage.opencode_client.ambient.audio.AmbientSpeakerChoice
import com.yage.opencode_client.ambient.audio.AmbientSpeakerRoute
import com.yage.opencode_client.ambient.audio.AmbientSpeakerSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientPlaybackGenerationTest {
    @Test
    fun `idle waits until every scheduled slice drains`() {
        val ledger = AmbientPlaybackLedger()
        val capture = ledger.open()
        val playback = ledger.playbackGeneration
        assertTrue(capture > 0L)
        assertFalse(ledger.noteDrained(capture, playback).idle)
        assertNull(ledger.enqueue(capture, 1))
        assertEquals(playback, ledger.enqueue(capture, 960))
        assertEquals(playback, ledger.enqueue(capture, 960))
        assertTrue(ledger.beginSlice(capture, playback, hasHardwareBytes = true))
        val early = ledger.noteDrained(capture, playback)
        assertTrue(early.matches)
        assertFalse(early.idle)
        assertTrue(ledger.beginSlice(capture, playback, hasHardwareBytes = true))
        val done = ledger.noteDrained(capture, playback)
        assertTrue(done.matches)
        assertTrue(done.idle)
    }

    @Test
    fun `clear discards stale work and fresh pcm for the same capture plays`() {
        val ledger = AmbientPlaybackLedger()
        val capture = ledger.open()
        val playback = ledger.playbackGeneration
        assertEquals(playback, ledger.enqueue(capture, 960))
        assertTrue(ledger.beginSlice(capture, playback, hasHardwareBytes = true))
        val effect = ledger.clear(capture)
        assertTrue(effect.accepted)
        assertTrue(effect.flushLivePlayer)
        assertFalse(effect.shouldRestart)
        val stale = ledger.noteDrained(capture, playback)
        assertFalse(stale.matches)
        assertFalse(stale.idle)
        val decision = ledger.decision(capture, playback, hasAudio = true)
        assertFalse(decision.shouldPlayScheduled)
        assertFalse(decision.shouldRestartFromClear)
        val resumed = ledger.enqueue(capture, 960)
        assertEquals(ledger.playbackGeneration, resumed)
        val fresh = ledger.decision(capture, resumed!!, hasAudio = true)
        assertTrue(fresh.shouldPlayScheduled)
        assertFalse(fresh.shouldRestartFromClear)
    }

    @Test
    fun `stop detaches the player and an old callback cannot touch the next run`() {
        val ledger = AmbientPlaybackLedger()
        val first = ledger.open()
        val oldPlayback = ledger.playbackGeneration
        assertTrue(ledger.stop(first))
        assertFalse(ledger.playerAttached)
        val stoppedPlayback = ledger.playbackGeneration
        assertFalse(ledger.clear(first).accepted)
        assertFalse(ledger.clear(first).flushLivePlayer)
        assertEquals(stoppedPlayback, ledger.playbackGeneration)
        assertNull(ledger.enqueue(first, 960))
        assertFalse(ledger.noteDrained(first, oldPlayback).matches)
        val second = ledger.open()
        val livePlayback = ledger.playbackGeneration
        assertNotEquals(first, second)
        assertFalse(ledger.acceptCallback(first))
        assertTrue(ledger.acceptCallback(second))
        assertFalse(ledger.clear(first).accepted)
        assertEquals(livePlayback, ledger.playbackGeneration)
        assertFalse(ledger.stop(first))
        assertTrue(ledger.acceptCallback(second))
        assertFalse(ledger.decision(second, oldPlayback, hasAudio = true).shouldPlayScheduled)
    }

    @Test
    fun `cursor completes only frames the head has reached`() {
        val cursor = AmbientPlaybackCursor()
        cursor.addWritten(100, capture = 2L, playback = 1L)
        cursor.addWritten(80, capture = 2L, playback = 1L)
        assertTrue(cursor.takeCompleted(0).isEmpty())
        assertEquals(1, cursor.takeCompleted(100).size)
        assertTrue(cursor.takeCompleted(100).isEmpty())
        assertEquals(1, cursor.takeCompleted(180).size)
        cursor.reset()
        cursor.addWritten(50, capture = 3L, playback = 2L)
        assertTrue(cursor.takeCompleted(0).isEmpty())
        assertEquals(1, cursor.pendingCount())
    }

    @Test
    fun `volume stream focus and headset route stay fixed`() {
        assertEquals(AudioManager.STREAM_VOICE_CALL, AmbientAudioRoute.VOLUME_STREAM)
        assertEquals(3, AmbientAudioRoute.TYPE_WIRED_HEADSET)
        assertEquals(4, AmbientAudioRoute.TYPE_WIRED_HEADPHONES)
        assertEquals(7, AmbientAudioRoute.TYPE_BLUETOOTH_SCO)
        assertEquals(8, AmbientAudioRoute.TYPE_BLUETOOTH_A2DP)
        assertEquals(11, AmbientAudioRoute.TYPE_USB_DEVICE)
        assertEquals(22, AmbientAudioRoute.TYPE_USB_HEADSET)
        assertEquals(26, AmbientAudioRoute.TYPE_BLE_HEADSET)
        assertTrue(AmbientAudioSignals.endsOnFocusChange(AudioManager.AUDIOFOCUS_LOSS))
        assertTrue(AmbientAudioSignals.endsOnFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertTrue(AmbientAudioSignals.endsOnFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertFalse(AmbientAudioSignals.endsOnFocusChange(AudioManager.AUDIOFOCUS_GAIN))
        assertFalse(AmbientAudioSignals.reopensAfterFocusGain())
        val earpieceOnly = AmbientSpeakerRoute.choose(
            AmbientSpeakerSnapshot(
                api31 = true,
                outputs = listOf(
                    AmbientRouteDevice(5, AmbientAudioRoute.TYPE_BUILTIN_EARPIECE),
                    AmbientRouteDevice(6, AmbientAudioRoute.TYPE_BUILTIN_SPEAKER),
                ),
                communicationDevices = listOf(
                    AmbientRouteDevice(5, AmbientAudioRoute.TYPE_BUILTIN_EARPIECE),
                    AmbientRouteDevice(6, AmbientAudioRoute.TYPE_BUILTIN_SPEAKER),
                ),
                current = AmbientRouteDevice(5, AmbientAudioRoute.TYPE_BUILTIN_EARPIECE),
                communicationDenied = false,
                speakerphoneOn = false,
                wiredHeadsetOn = false,
                bluetoothScoOn = false,
            ),
        ) as AmbientSpeakerChoice.CommunicationDevice
        assertEquals(6, earpieceOnly.id)
        assertFalse(AmbientAudioSignals.ducks())
        assertEquals("focus_loss", AmbientAudioCodes.FOCUS_LOSS)
        assertEquals("headset_removed", AmbientAudioCodes.HEADSET_REMOVED)
        assertEquals("capture_rate_unsupported", AmbientAudioCodes.CAPTURE_RATE_UNSUPPORTED)
        assertEquals("bluetooth_route_unreadable", AmbientAudioCodes.BLUETOOTH_ROUTE_UNREADABLE)

        val speaker = AmbientRouteDevice(1, AmbientAudioRoute.TYPE_BUILTIN_SPEAKER)
        val wired = AmbientRouteDevice(4, AmbientAudioRoute.TYPE_WIRED_HEADSET)
        val sco = AmbientRouteDevice(9, AmbientAudioRoute.TYPE_BLUETOOTH_SCO)
        assertTrue(AmbientAudioRoute.watchActive(speaker.id, speaker.type).isEmpty())
        assertEquals(setOf(4), AmbientAudioRoute.watchActive(wired.id, wired.type))
        assertFalse(AmbientAudioRoute.removedEndsRun(emptySet(), speaker.id, speaker.type))
        assertTrue(AmbientAudioRoute.removedEndsRun(setOf(4), wired.id, wired.type))
        assertFalse(AmbientAudioRoute.removedEndsRun(setOf(4), sco.id, sco.type))
        val legacy = AmbientAudioRoute.watchLegacy(
            outputs = listOf(speaker, wired, sco),
            wiredHeadsetOn = true,
            bluetoothScoOn = false,
        )
        assertEquals(setOf(4), legacy)
        assertTrue(
            AmbientAudioRoute.blocksBecauseBluetoothUnreadable(
                readable = false,
                outputs = listOf(sco),
            ),
        )
        assertFalse(
            AmbientAudioRoute.blocksBecauseBluetoothUnreadable(
                readable = false,
                outputs = listOf(AmbientRouteDevice(3, AmbientAudioRoute.TYPE_BLUETOOTH_A2DP)),
            ),
        )
        assertFalse(AmbientAudioRoute.removedEndsRun(setOf(1), 1, AmbientAudioRoute.TYPE_BUILTIN_SPEAKER))
        val enumerated = listOf(speaker, wired, sco)
        assertEquals(setOf(4), AmbientAudioRoute.watchRouted(wired.id, wired.type))
        assertTrue(AmbientAudioRoute.watchLegacy(enumerated, wiredHeadsetOn = false, bluetoothScoOn = false).isEmpty())
        assertFalse(AmbientAudioRoute.watchRouted(speaker.id, speaker.type).contains(sco.id))
    }

    @Test
    fun `flush discards hardware without play and a later clear is a second flush`() {
        val log = ArrayList<String>()
        val pump = AmbientPlaybackPump(recordingWriter(log), AmbientResampler(24_000, 24_000))
        assertEquals(1, pump.acknowledgeFlush(1) { true })
        assertEquals(listOf("flush"), log)
        assertEquals(1, pump.appliedFlush)
        assertEquals(1, pump.acknowledgeFlush(1) { true })
        assertEquals(listOf("flush"), log)
        assertEquals(2, pump.acknowledgeFlush(2) { true })
        assertEquals(listOf("flush", "flush"), log)
        assertEquals(2, pump.appliedFlush)
    }

    @Test
    fun `stale pcm is not resampled and a retired owner does not touch the device`() {
        val log = ArrayList<String>()
        var owner = true
        val pump = AmbientPlaybackPump(recordingWriter(log) { owner }, AmbientResampler(24_000, 24_000))
        assertNull(pump.resampleIfEligible(byteArrayOf(0x34, 0x12), eligible = false))
        assertTrue(log.isEmpty())
        assertArrayEquals(ByteArray(0), pump.resampleIfEligible(byteArrayOf(0x34), eligible = true))
        assertEquals(1, pump.acknowledgeFlush(1) { owner })
        val fresh = pump.resampleIfEligible(byteArrayOf(0x12, 0x00), eligible = true)
        assertArrayEquals(byteArrayOf(0x12, 0x00), fresh)
        assertTrue(pump.playAndWrite(fresh!!, stillOwner = { owner }))
        assertEquals(listOf("flush", "play", "write:2"), log)
        owner = false
        assertEquals(1, pump.acknowledgeFlush(2) { false })
        assertFalse(pump.playAndWrite(byteArrayOf(0x56, 0x00), stillOwner = { false }))
        assertEquals(listOf("flush", "play", "write:2"), log)
    }

    @Test
    fun `a write stops between chunks when ownership drops`() {
        val chunks = ArrayList<Int>()
        var owner = true
        val writer = object : AmbientHardwareWriter {
            override fun pauseAndFlush(stillOwner: () -> Boolean): Boolean = stillOwner()

            override fun startForPcm(stillOwner: () -> Boolean): Boolean = stillOwner()

            override fun writeChunk(
                pcm: ByteArray,
                offset: Int,
                length: Int,
                stillOwner: () -> Boolean,
            ): Int {
                if (!stillOwner()) return -1
                chunks.add(length)
                owner = false
                return length
            }
        }
        val pump = AmbientPlaybackPump(writer, AmbientResampler(24_000, 24_000))
        val hardware = ByteArray(4_000)
        assertFalse(pump.playAndWrite(hardware) { owner })
        assertEquals(listOf(1920), chunks)
    }

    private fun recordingWriter(
        log: MutableList<String>,
        owner: () -> Boolean = { true },
    ): AmbientHardwareWriter {
        return object : AmbientHardwareWriter {
            override fun pauseAndFlush(stillOwner: () -> Boolean): Boolean {
                if (!stillOwner() || !owner()) return false
                log.add("flush")
                return true
            }

            override fun startForPcm(stillOwner: () -> Boolean): Boolean {
                if (!stillOwner() || !owner()) return false
                log.add("play")
                return true
            }

            override fun writeChunk(
                pcm: ByteArray,
                offset: Int,
                length: Int,
                stillOwner: () -> Boolean,
            ): Int {
                if (!stillOwner() || !owner()) return -1
                log.add("write:$length")
                return length
            }
        }
    }
}
