package com.yage.opencode_client.ambient.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.yage.opencode_client.ambient.protocol.AmbientAudioCause
import com.yage.opencode_client.ambient.protocol.AmbientAudioEngine
import com.yage.opencode_client.ambient.protocol.AmbientAudioEvent
import com.yage.opencode_client.ambient.protocol.AmbientCaptureHandle
import com.yage.opencode_client.ambient.protocol.AmbientPcm
import com.yage.opencode_client.ambient.protocol.AmbientProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

/**
 * Full-duplex voice engine. The constructor keeps [Context.getApplicationContext]
 * and does not open the microphone, request focus, or change the route.
 * [startCapture] is the explicit start, after the coordinator has checked
 * permission and foreground-service eligibility. After communication mode and
 * focus, a builtin-only route prefers the handset speaker. Wired, USB, and
 * active Bluetooth routes are not overridden.
 */
class AmbientNativeAudioEngine(context: Context) : AmbientAudioEngine {
    private val appContext: Context = context.applicationContext

    @Volatile
    var failureCode: String? = null
        private set

    private val stateLock = Any()
    private val lifecycleLock = Any()
    /** True while a prior run or a failed start still owns record, track, or focus. */
    private var teardownActive = false
    private var teardownGeneration = 0L
    private val ledger = AmbientPlaybackLedger()
    private val cursor = AmbientPlaybackCursor()
    private val playbackQueue = ArrayBlockingQueue<QueuedPcm>(PLAYBACK_QUEUE_CAPACITY)
    private val flushGeneration = AtomicInteger(0)
    private val eventPipe = AmbientEventPipe()

    @Volatile private var muted = false
    private var modeOwner = 0L
    private var modeChanged = false
    private var savedMode = AudioManager.MODE_NORMAL
    private val speakerOwnership = AmbientSpeakerOwnership()
    private var watchedIds: Set<Int> = emptySet()
    private var captureReadBytes = AMBIENT_UPLINK_FRAME_BYTES
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var callbackThread: HandlerThread? = null
    private var audioHandler: Handler? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var frames: Channel<ByteArray>? = null
    private var captureResampler: AmbientResampler? = null
    private var playbackResampler: AmbientResampler? = null
    private var frameAssembler: AmbientFrameAssembler? = null
    private var levelTap: AmbientLevelTap? = null

    override val events: Flow<AmbientAudioEvent> = eventPipe.events

    override suspend fun startCapture(): AmbientCaptureHandle {
        val delivered = AtomicReference<Long?>(null)
        try {
            return withContext(Dispatchers.Default) {
                val handle = startOnWorker()
                delivered.set(handle.generation)
                coroutineContext.ensureActive()
                handle
            }
        } catch (error: CancellationException) {
            val generation = delivered.get()
            if (generation != null) {
                withContext(NonCancellable + Dispatchers.Default) {
                    endRun(generation, cause = null, code = null)
                }
            }
            throw error
        }
    }

    override suspend fun stopCapture(generation: Long) {
        withContext(Dispatchers.Default) {
            endRun(generation, cause = null, code = null)
        }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    override fun enqueuePlayback(pcm: ByteArray, generation: Long) {
        val even = pcm.size - pcm.size % 2
        if (even < 2) return
        val copy = pcm.copyOf(even)
        val overflow = synchronized(stateLock) {
            val playback = ledger.enqueue(generation, even) ?: return
            val item = QueuedPcm(copy, generation, playback)
            if (playbackQueue.offer(item)) {
                false
            } else {
                ledger.undoEnqueue()
                true
            }
        }
        if (overflow) failAsync(generation, AmbientAudioCodes.PLAYBACK_QUEUE_OVERFLOW)
    }

    override fun clearPlayback(generation: Long) {
        val accepted = synchronized(stateLock) {
            val effect = ledger.clear(generation)
            if (!effect.accepted) return@synchronized false
            playbackQueue.clear()
            cursor.reset()
            if (effect.flushLivePlayer) flushGeneration.incrementAndGet()
            true
        }
        if (accepted) emitOutputSilence(generation)
    }

    private fun startOnWorker(): AmbientCaptureHandle {
        val attempt = StartAttempt()
        var bag: Released? = null
        var ownsTeardown = false
        try {
            return synchronized(lifecycleLock) {
                if (teardownActive) {
                    throw AmbientAudioStartException(AmbientAudioCodes.CAPTURE_ALREADY_RUNNING)
                }
                try {
                    startLocked(attempt)
                } catch (error: Exception) {
                    val code = codeOf(error)
                    failureCode = code
                    bag = unlink(attempt.generation, code)
                    if (bag != null) {
                        val manager = audioManager()
                        restoreSpeaker(manager, attempt.generation)
                        restoreMode(manager, attempt.generation)
                    }
                    ownsTeardown = bag != null ||
                        attempt.record != null ||
                        attempt.track != null ||
                        attempt.handlerThread != null
                    if (ownsTeardown) {
                        teardownActive = true
                        teardownGeneration = attempt.generation
                    }
                    throw error
                }
            }
        } catch (error: Exception) {
            try {
                releaseAttempt(attempt)
                bag?.let { releaseReleased(it) }
            } finally {
                if (ownsTeardown) clearTeardown(attempt.generation)
            }
            if (bag != null) {
                eventPipe.emitStatus(AmbientAudioEvent.Status(attempt.generation, AmbientAudioCause.FAILED))
            }
            if (error is CancellationException) throw error
            if (error is AmbientAudioStartException) throw error
            throw AmbientAudioStartException(codeOf(error))
        }
    }

    private fun startLocked(attempt: StartAttempt): AmbientCaptureHandle {
        synchronized(stateLock) {
            if (ledger.running) throw AmbientAudioStartException(AmbientAudioCodes.CAPTURE_ALREADY_RUNNING)
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            failureCode = AmbientAudioCodes.RECORD_PERMISSION_MISSING
            throw AmbientAudioStartException(AmbientAudioCodes.RECORD_PERMISSION_MISSING)
        }
        failureCode = null
        val manager = audioManager() ?: throw AmbientAudioStartException(AmbientAudioCodes.CAPTURE_START_FAILED)
        val watched = snapshotRoute(manager)
        val attributes = voiceAttributes()
        val handlerThread = HandlerThread("AmbientAudioCallback").also { it.start() }
        attempt.handlerThread = handlerThread
        val handler = Handler(handlerThread.looper)
        val openedRecord = openRecord()
        attempt.record = openedRecord.record
        val openedTrack = openTrack(attributes)
        attempt.track = openedTrack.track
        val generation = synchronized(stateLock) {
            val opened = ledger.open()
            record = attempt.record
            track = attempt.track
            attempt.record = null
            attempt.track = null
            callbackThread = handlerThread
            audioHandler = handler
            attempt.handlerThread = null
            captureResampler = AmbientResampler(openedRecord.choice.hertz, AmbientProtocol.SAMPLE_RATE_HZ)
            playbackResampler = AmbientResampler(AmbientProtocol.SAMPLE_RATE_HZ, openedTrack.choice.hertz)
            frameAssembler = AmbientFrameAssembler()
            levelTap = AmbientLevelTap()
            captureReadBytes = openedRecord.choice.bufferBytes
            watchedIds = watched
            flushGeneration.set(0)
            playbackQueue.clear()
            cursor.reset()
            opened
        }
        attempt.generation = generation
        val previousMode = synchronized(stateLock) {
            savedMode = manager.mode
            modeOwner = generation
            modeChanged = savedMode != AudioManager.MODE_IN_COMMUNICATION
            savedMode
        }
        if (previousMode != AudioManager.MODE_IN_COMMUNICATION) {
            try {
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
            } catch (_: SecurityException) {
                synchronized(stateLock) {
                    if (modeOwner == generation) modeChanged = false
                }
                throw AmbientAudioStartException(AmbientAudioCodes.CAPTURE_START_FAILED)
            }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener(generation), handler)
            .build()
        val focus = try {
            manager.requestAudioFocus(request)
        } catch (_: SecurityException) {
            throw AmbientAudioStartException(AmbientAudioCodes.FOCUS_DENIED)
        }
        if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw AmbientAudioStartException(AmbientAudioCodes.FOCUS_DENIED)
        }
        synchronized(stateLock) {
            if (!ledger.acceptCallback(generation)) {
                try {
                    manager.abandonAudioFocusRequest(request)
                } catch (_: RuntimeException) {
                }
                throw AmbientAudioStartException(AmbientAudioCodes.FOCUS_DENIED)
            }
            focusRequest = request
        }
        routeDefaultSpeaker(manager, generation)
        val selectedWatch = currentRouteWatch(manager)
        synchronized(stateLock) {
            if (!ledger.acceptCallback(generation)) {
                throw AmbientAudioStartException(AmbientAudioCodes.FOCUS_DENIED)
            }
            watchedIds = selectedWatch
        }
        val callback = routeCallback(generation)
        synchronized(stateLock) {
            if (!ledger.acceptCallback(generation)) {
                throw AmbientAudioStartException(AmbientAudioCodes.FOCUS_DENIED)
            }
            deviceCallback = callback
        }
        manager.registerAudioDeviceCallback(callback, handler)
        val liveRecord = synchronized(stateLock) { record }
            ?: throw AmbientAudioStartException(AmbientAudioCodes.CAPTURE_START_FAILED)
        if (!armRecord(liveRecord)) {
            throw AmbientAudioStartException(AmbientAudioCodes.CAPTURE_START_FAILED)
        }
        synchronized(stateLock) {
            if (!ledger.acceptCallback(generation)) {
                throw AmbientAudioStartException(AmbientAudioCodes.FOCUS_DENIED)
            }
            track?.setPlaybackPositionUpdateListener(playbackListener(generation), handler)
        }
        val channel = Channel<ByteArray>(CAPTURE_QUEUE_CAPACITY)
        val capture = Thread({ captureLoop(generation, channel) }, "AmbientCapture")
        val playback = Thread({ playbackLoop(generation) }, "AmbientPlayback")
        synchronized(stateLock) {
            if (!ledger.acceptCallback(generation)) {
                channel.close()
                throw AmbientAudioStartException(AmbientAudioCodes.FOCUS_DENIED)
            }
            frames = channel
            captureThread = capture
            playbackThread = playback
            capture.start()
            playback.start()
        }
        return CaptureHandle(generation, channel)
    }

    private fun releaseAttempt(attempt: StartAttempt) {
        releaseRecord(attempt.record)
        releaseTrack(attempt.track)
        attempt.handlerThread?.quitSafely()
        attempt.record = null
        attempt.track = null
        attempt.handlerThread = null
    }

    private fun captureLoop(generation: Long, frames: Channel<ByteArray>) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val record = synchronized(stateLock) { this.record } ?: return
        val resampler = synchronized(stateLock) { captureResampler } ?: return
        val assembler = synchronized(stateLock) { frameAssembler } ?: return
        val tap = synchronized(stateLock) { levelTap } ?: return
        val buffer = ByteArray(captureReadBytes.coerceAtLeast(AMBIENT_UPLINK_FRAME_BYTES))
        try {
            check(Looper.myLooper() != Looper.getMainLooper())
            while (isLive(generation)) {
                if (frames.isClosedForSend) {
                    val discarded = readRecord(record, buffer)
                    if (discarded < 0) break
                    continue
                }
                val read = readRecord(record, buffer)
                if (!isLive(generation)) break
                if (read < 0) {
                    failAsync(generation, AmbientAudioCodes.CAPTURE_READ_FAILED)
                    break
                }
                if (read == 0) continue
                val wire = resampler.process(buffer.copyOf(read))
                if (wire.isEmpty()) continue
                for (raw in assembler.push(wire)) {
                    if (!isLive(generation) || frames.isClosedForSend) break
                    val send = AmbientPcm.uplinkFrame(raw, muted) ?: continue
                    val result = frames.trySend(send)
                    if (result.isClosed) break
                    if (result.isFailure) {
                        failAsync(generation, AmbientAudioCodes.CAPTURE_QUEUE_OVERFLOW)
                        return
                    }
                    val level = tap.noteInput(raw, elapsedNanos(), generation)
                    if (level != null) eventPipe.emitLevel(level)
                }
            }
        } catch (_: Exception) {
            if (isLive(generation)) failAsync(generation, AmbientAudioCodes.CAPTURE_READ_FAILED)
        }
    }

    private fun playbackLoop(generation: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val resampler = synchronized(stateLock) { playbackResampler } ?: return
        synchronized(stateLock) {
            if (track == null || !ledger.acceptCallback(generation)) return
        }
        val pump = AmbientPlaybackPump(TrackWriter(), resampler)
        try {
            check(Looper.myLooper() != Looper.getMainLooper())
            while (isLive(generation)) {
                val pending = flushGeneration.get()
                if (pending != pump.appliedFlush) {
                    acknowledgePlaybackFlush(pump, generation, pending)
                    continue
                }
                val item = try {
                    playbackQueue.poll(20, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
                if (item == null) {
                    drainHead(generation)
                    continue
                }
                if (!isLive(generation)) break
                val pendingAfter = flushGeneration.get()
                if (pendingAfter != pump.appliedFlush) {
                    if (!samePlayback(item)) continue
                    if (!acknowledgePlaybackFlush(pump, generation, pendingAfter)) continue
                }
                if (!samePlayback(item)) continue
                val hardware = pump.resampleIfEligible(item.pcm, eligible = true) ?: continue
                val accepted = synchronized(stateLock) {
                    if (!samePlayback(item) || flushGeneration.get() != pump.appliedFlush) {
                        pump.resetResampler()
                        false
                    } else {
                        ledger.beginSlice(
                            item.captureGeneration,
                            item.playbackGeneration,
                            hardware.size >= 2,
                        )
                    }
                }
                if (!accepted || hardware.size < 2) continue
                val wrote = pump.playAndWrite(hardware) {
                    isLive(generation) &&
                        samePlayback(item) &&
                        flushGeneration.get() == pump.appliedFlush
                }
                if (!wrote) {
                    val current = isLive(generation) &&
                        samePlayback(item) &&
                        flushGeneration.get() == pump.appliedFlush
                    if (current) failAsync(generation, AmbientAudioCodes.PLAYBACK_WRITE_FAILED)
                    if (!isLive(generation)) break
                    continue
                }
                synchronized(stateLock) {
                    val live = track
                    if (live != null &&
                        item.playbackGeneration == ledger.playbackGeneration &&
                        ledger.playerAttached
                    ) {
                        val marker = cursor.addWritten(
                            hardware.size / 2,
                            item.captureGeneration,
                            item.playbackGeneration,
                        )
                        try {
                            live.notificationMarkerPosition = marker
                        } catch (_: IllegalStateException) {
                        }
                    }
                }
                val level = levelTap?.noteOutput(item.pcm, elapsedNanos(), generation)
                if (level != null) eventPipe.emitLevel(level)
                drainHead(generation)
            }
        } catch (_: Exception) {
            if (isLive(generation)) failAsync(generation, AmbientAudioCodes.PLAYBACK_WRITE_FAILED)
        }
    }

    private fun acknowledgePlaybackFlush(pump: AmbientPlaybackPump, generation: Long, requested: Int): Boolean {
        val performed = pump.acknowledgeFlush(requested) { isLive(generation) }
        if (performed != requested) return false
        synchronized(stateLock) {
            if (isLive(generation)) cursor.reset()
        }
        emitOutputSilence(generation)
        return true
    }

    private fun samePlayback(item: QueuedPcm): Boolean {
        return synchronized(stateLock) {
            item.playbackGeneration == ledger.playbackGeneration &&
                item.captureGeneration == ledger.captureGeneration &&
                ledger.running &&
                ledger.playerAttached
        }
    }

    private fun drainHead(generation: Long) {
        val head = synchronized(stateLock) {
            if (!ledger.acceptCallback(generation)) return
            val live = track ?: return
            try {
                if (live.state != AudioTrack.STATE_INITIALIZED) return
                live.playbackHeadPosition
            } catch (_: IllegalStateException) {
                return
            }
        }
        val marks = synchronized(stateLock) { cursor.takeCompleted(head) }
        for (mark in marks) {
            val drain = synchronized(stateLock) {
                ledger.noteDrained(mark.captureGeneration, mark.playbackGeneration)
            }
            if (drain.idle) {
                eventPipe.emitStatus(AmbientAudioEvent.Status(mark.captureGeneration, AmbientAudioCause.PLAYBACK_IDLE))
                emitOutputSilence(mark.captureGeneration)
            }
        }
    }

    private fun endRun(generation: Long, cause: AmbientAudioCause?, code: String?) {
        val bag = synchronized(lifecycleLock) {
            val taken = unlink(generation, code) ?: return
            val manager = audioManager()
            restoreSpeaker(manager, generation)
            restoreMode(manager, generation)
            teardownActive = true
            teardownGeneration = generation
            taken
        }
        try {
            releaseReleased(bag)
        } finally {
            clearTeardown(generation)
        }
        if (cause != null) {
            eventPipe.emitStatus(AmbientAudioEvent.Status(generation, cause))
        }
    }

    private fun clearTeardown(generation: Long) {
        synchronized(lifecycleLock) {
            if (teardownActive && teardownGeneration == generation) {
                teardownActive = false
                teardownGeneration = 0L
            }
        }
    }

    private fun unlink(generation: Long, code: String?): Released? {
        return synchronized(stateLock) {
            if (!ledger.stop(generation)) return null
            if (code != null) failureCode = code
            playbackQueue.clear()
            cursor.reset()
            val taken = Released(
                record = record,
                track = track,
                captureThread = captureThread,
                playbackThread = playbackThread,
                focus = focusRequest,
                callback = deviceCallback,
                handlerThread = callbackThread,
                frames = frames,
                generation = generation,
            )
            record = null
            track = null
            captureThread = null
            playbackThread = null
            focusRequest = null
            deviceCallback = null
            callbackThread = null
            audioHandler = null
            frames = null
            captureResampler = null
            playbackResampler = null
            frameAssembler = null
            levelTap = null
            watchedIds = emptySet()
            taken
        }
    }

    private fun releaseReleased(bag: Released) {
        val manager = audioManager()
        if (manager != null && bag.callback != null) {
            try {
                manager.unregisterAudioDeviceCallback(bag.callback)
            } catch (_: IllegalArgumentException) {
            }
        }
        if (manager != null && bag.focus != null) {
            try {
                manager.abandonAudioFocusRequest(bag.focus)
            } catch (_: RuntimeException) {
            }
        }
        try {
            if (bag.record != null &&
                bag.record.state == AudioRecord.STATE_INITIALIZED &&
                bag.record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            ) {
                bag.record.stop()
            }
        } catch (_: IllegalStateException) {
        }
        bag.captureThread?.interrupt()
        bag.playbackThread?.interrupt()
        bag.captureThread?.let { if (it != Thread.currentThread()) it.join(JOIN_MILLIS) }
        bag.playbackThread?.let { if (it != Thread.currentThread()) it.join(JOIN_MILLIS) }
        releaseRecord(bag.record)
        synchronized(stateLock) {
            releaseTrack(bag.track)
        }
        try {
            bag.frames?.close()
        } catch (_: Exception) {
        }
        bag.handlerThread?.quitSafely()
        if (bag.handlerThread != null && Thread.currentThread() != bag.handlerThread) {
            bag.handlerThread.join(JOIN_MILLIS)
        }
    }

    private fun restoreMode(manager: AudioManager?, generation: Long) {
        if (manager == null) return
        val previous = synchronized(stateLock) {
            if (modeOwner != generation || !modeChanged) return
            modeOwner = 0L
            modeChanged = false
            savedMode
        }
        try {
            manager.mode = previous
        } catch (_: SecurityException) {
        }
    }

    private fun routeDefaultSpeaker(manager: AudioManager, generation: Long) {
        val adapter = EngineSpeakerAdapter(manager)
        when (val outcome = AmbientSpeakerRoute.applyDefault(adapter, generation)) {
            is AmbientSpeakerOutcome.Failed -> throw AmbientAudioStartException(outcome.code)
            is AmbientSpeakerOutcome.Ready -> {
                val kept = synchronized(stateLock) {
                    if (!ledger.acceptCallback(generation)) {
                        false
                    } else {
                        speakerOwnership.retain(generation, outcome.hold)
                        true
                    }
                }
                if (!kept) {
                    AmbientSpeakerRoute.undo(adapter, outcome.hold, generation) { true }
                    throw AmbientAudioStartException(AmbientAudioCodes.FOCUS_DENIED)
                }
            }
        }
    }

    private fun restoreSpeaker(manager: AudioManager?, generation: Long) {
        if (manager == null) return
        val hold = synchronized(stateLock) { speakerOwnership.take(generation) } ?: return
        AmbientSpeakerRoute.undo(EngineSpeakerAdapter(manager), hold, generation) {
            synchronized(stateLock) { speakerOwnership.currentOwner() == 0L }
        }
    }

    private fun currentRouteWatch(manager: AudioManager): Set<Int> {
        val base = snapshotRoute(manager)
        return base + AmbientSpeakerRoute.watchIds(readSpeakerSnapshot(manager))
    }

    private fun readSpeakerSnapshot(manager: AudioManager): AmbientSpeakerSnapshot {
        val outputs = try {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
                AmbientRouteDevice(device.id, device.type)
            }
        } catch (_: SecurityException) {
            throw AmbientAudioStartException(AmbientAudioCodes.BLUETOOTH_ROUTE_UNREADABLE)
        }
        val api31 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val communication = readCommunication(manager)
        var denied = communication is Communication.Denied
        val current = (communication as? Communication.Selected)?.let { selected ->
            AmbientRouteDevice(selected.id, selected.type)
        }
        val available = if (!api31 || denied) {
            null
        } else {
            val listed = readAvailableCommunication(manager)
            if (listed == null) denied = true
            listed
        }
        return AmbientSpeakerSnapshot(
            api31 = api31,
            outputs = outputs,
            communicationDevices = available,
            current = current,
            communicationDenied = denied,
            speakerphoneOn = readSpeakerphone(manager),
            wiredHeadsetOn = readWired(manager),
            bluetoothScoOn = readSco(manager),
        )
    }

    private fun readAvailableCommunication(manager: AudioManager): List<AmbientRouteDevice>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return readAvailableCommunicationS(manager)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun readAvailableCommunicationS(manager: AudioManager): List<AmbientRouteDevice>? {
        return try {
            manager.availableCommunicationDevices.map { device -> AmbientRouteDevice(device.id, device.type) }
        } catch (_: SecurityException) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun readSpeakerphone(manager: AudioManager): Boolean? {
        return try {
            manager.isSpeakerphoneOn
        } catch (_: SecurityException) {
            null
        }
    }

    private fun snapshotRoute(manager: AudioManager): Set<Int> {
        val outputs = try {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
                AmbientRouteDevice(device.id, device.type)
            }
        } catch (_: SecurityException) {
            throw AmbientAudioStartException(AmbientAudioCodes.BLUETOOTH_ROUTE_UNREADABLE)
        }
        return when (val communication = readCommunication(manager)) {
            Communication.Denied -> {
                if (AmbientAudioRoute.blocksBecauseBluetoothUnreadable(false, outputs)) {
                    throw AmbientAudioStartException(AmbientAudioCodes.BLUETOOTH_ROUTE_UNREADABLE)
                }
                AmbientAudioRoute.watchLegacy(outputs, readWired(manager), bluetoothScoOn = false)
            }
            is Communication.Selected -> AmbientAudioRoute.watchRouted(communication.id, communication.type)
            Communication.Absent -> {
                val sco = readSco(manager)
                if (sco == null && AmbientAudioRoute.blocksBecauseBluetoothUnreadable(false, outputs)) {
                    throw AmbientAudioStartException(AmbientAudioCodes.BLUETOOTH_ROUTE_UNREADABLE)
                }
                AmbientAudioRoute.watchLegacy(outputs, readWired(manager), sco == true)
            }
        }
    }

    private fun readCommunication(manager: AudioManager): Communication {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Communication.Absent
        return readCommunicationS(manager)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun readCommunicationS(manager: AudioManager): Communication {
        return try {
            val device = manager.communicationDevice ?: return Communication.Absent
            Communication.Selected(device.id, device.type)
        } catch (_: SecurityException) {
            Communication.Denied
        }
    }

    @Suppress("DEPRECATION")
    private fun readWired(manager: AudioManager): Boolean {
        return try {
            manager.isWiredHeadsetOn
        } catch (_: SecurityException) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun readSco(manager: AudioManager): Boolean? {
        return try {
            manager.isBluetoothScoOn
        } catch (_: SecurityException) {
            null
        }
    }

    private fun openRecord(): OpenedRecord {
        var kept: AudioRecord? = null
        val choice = AmbientCaptureRates.select(
            minBufferBytes = { rate ->
                AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            },
            initialize = initialize@{ probe ->
                val candidate = try {
                    buildRecord(probe)
                } catch (_: IllegalArgumentException) {
                    return@initialize false
                } catch (_: UnsupportedOperationException) {
                    return@initialize false
                }
                val ready = candidate.state == AudioRecord.STATE_INITIALIZED
                if (!ready) {
                    releaseRecord(candidate)
                    return@initialize false
                }
                kept?.let { releaseRecord(it) }
                kept = candidate
                true
            },
        ) ?: throw AmbientAudioStartException(AmbientAudioCodes.CAPTURE_RATE_UNSUPPORTED)
        return OpenedRecord(kept ?: throw AmbientAudioStartException(AmbientAudioCodes.CAPTURE_RATE_UNSUPPORTED), choice)
    }

    private fun openTrack(attributes: AudioAttributes): OpenedTrack {
        var kept: AudioTrack? = null
        val choice = AmbientCaptureRates.select(
            minBufferBytes = { rate ->
                AudioTrack.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            },
            initialize = initialize@{ probe ->
                val candidate = try {
                    buildTrack(probe, attributes)
                } catch (_: IllegalArgumentException) {
                    return@initialize false
                } catch (_: UnsupportedOperationException) {
                    return@initialize false
                }
                if (!armTrack(candidate, probe.hertz)) {
                    releaseTrack(candidate)
                    return@initialize false
                }
                kept?.let { releaseTrack(it) }
                kept = candidate
                true
            },
        ) ?: throw AmbientAudioStartException(AmbientAudioCodes.PLAYBACK_START_FAILED)
        return OpenedTrack(kept ?: throw AmbientAudioStartException(AmbientAudioCodes.PLAYBACK_START_FAILED), choice)
    }

    private fun buildRecord(probe: AmbientCaptureRates.Choice): AudioRecord {
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(probe.hertz)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(probe.bufferBytes)
            .build()
    }

    private fun buildTrack(probe: AmbientCaptureRates.Choice, attributes: AudioAttributes): AudioTrack {
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(probe.hertz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(probe.bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun armRecord(record: AudioRecord): Boolean {
        if (record.state != AudioRecord.STATE_INITIALIZED) return false
        return try {
            record.startRecording()
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (_: SecurityException) {
            throw AmbientAudioStartException(AmbientAudioCodes.RECORD_PERMISSION_MISSING)
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun armTrack(track: AudioTrack, rate: Int): Boolean {
        if (track.state != AudioTrack.STATE_INITIALIZED) return false
        return try {
            track.setPositionNotificationPeriod(maxOf(1, rate / 50))
            track.playState != AudioTrack.PLAYSTATE_PLAYING
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun voiceAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private fun focusListener(generation: Long) = AudioManager.OnAudioFocusChangeListener { change ->
        if (!AmbientAudioSignals.endsOnFocusChange(change)) return@OnAudioFocusChangeListener
        audioHandler?.post { endRun(generation, AmbientAudioCause.FOCUS_LOSS, AmbientAudioCodes.FOCUS_LOSS) }
    }

    private fun routeCallback(generation: Long) = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (added.isEmpty()) return
            refreshRoutedWatch(generation)
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            val ends = synchronized(stateLock) {
                if (!ledger.acceptCallback(generation)) return
                removed.any { device ->
                    AmbientAudioRoute.removedEndsRun(watchedIds, device.id, device.type)
                }
            }
            if (ends) {
                audioHandler?.post {
                    endRun(generation, AmbientAudioCause.HEADSET_REMOVED, AmbientAudioCodes.HEADSET_REMOVED)
                }
            }
        }
    }

    private fun playbackListener(generation: Long) = object : AudioTrack.OnPlaybackPositionUpdateListener {
        override fun onMarkerReached(track: AudioTrack) {
            drainHead(generation)
        }

        override fun onPeriodicNotification(track: AudioTrack) {
            drainHead(generation)
        }
    }

    private fun refreshRoutedWatch(generation: Long) {
        if (!isLive(generation)) return
        val manager = audioManager() ?: return
        val watched = try {
            currentRouteWatch(manager)
        } catch (error: AmbientAudioStartException) {
            if (error.code == AmbientAudioCodes.BLUETOOTH_ROUTE_UNREADABLE) {
                failAsync(generation, error.code)
            }
            return
        }
        synchronized(stateLock) {
            if (!ledger.acceptCallback(generation)) return
            watchedIds = watched
        }
    }

    private fun emitOutputSilence(generation: Long) {
        if (!isLive(generation)) return
        val level = levelTap?.markOutputSilent(elapsedNanos(), generation) ?: return
        eventPipe.emitLevel(level)
    }

    private fun failAsync(generation: Long, code: String) {
        val handler = audioHandler ?: return
        handler.post { endRun(generation, AmbientAudioCause.FAILED, code) }
    }

    private fun isLive(generation: Long): Boolean = synchronized(stateLock) { ledger.acceptCallback(generation) }

    private fun elapsedNanos(): Long = SystemClock.elapsedRealtimeNanos()

    private fun audioManager(): AudioManager? {
        return appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private fun codeOf(error: Exception): String = when (error) {
        is AmbientAudioStartException -> error.code
        is SecurityException -> AmbientAudioCodes.RECORD_PERMISSION_MISSING
        else -> AmbientAudioCodes.CAPTURE_START_FAILED
    }

    private class CaptureHandle(
        override val generation: Long,
        private val frames: Channel<ByteArray>,
    ) : AmbientCaptureHandle {
        override suspend fun receive(): ByteArray? = frames.receiveCatching().getOrNull()

        override fun finish() {
            frames.close()
        }
    }

    private data class QueuedPcm(
        val pcm: ByteArray,
        val captureGeneration: Long,
        val playbackGeneration: Long,
    )

    private class StartAttempt {
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        var handlerThread: HandlerThread? = null
        var generation: Long = 0L
    }

    private data class OpenedRecord(val record: AudioRecord, val choice: AmbientCaptureRates.Choice)
    private data class OpenedTrack(val track: AudioTrack, val choice: AmbientCaptureRates.Choice)

    private data class Released(
        val record: AudioRecord?,
        val track: AudioTrack?,
        val captureThread: Thread?,
        val playbackThread: Thread?,
        val focus: AudioFocusRequest?,
        val callback: AudioDeviceCallback?,
        val handlerThread: HandlerThread?,
        val frames: Channel<ByteArray>?,
        val generation: Long,
    )

    private inner class TrackWriter : AmbientHardwareWriter {
        override fun pauseAndFlush(stillOwner: () -> Boolean): Boolean {
            return synchronized(stateLock) {
                val live = ownedTrack(stillOwner) ?: return false
                try {
                    if (live.state != AudioTrack.STATE_INITIALIZED) return stillOwner()
                    if (live.playState == AudioTrack.PLAYSTATE_PLAYING) live.pause()
                    if (live.playState == AudioTrack.PLAYSTATE_PAUSED) live.flush()
                    stillOwner()
                } catch (_: IllegalStateException) {
                    false
                }
            }
        }

        override fun startForPcm(stillOwner: () -> Boolean): Boolean {
            return synchronized(stateLock) {
                val live = ownedTrack(stillOwner) ?: return false
                try {
                    if (live.state != AudioTrack.STATE_INITIALIZED) return false
                    if (live.playState != AudioTrack.PLAYSTATE_PLAYING) live.play()
                    stillOwner()
                } catch (_: IllegalStateException) {
                    false
                }
            }
        }

        override fun writeChunk(pcm: ByteArray, offset: Int, length: Int, stillOwner: () -> Boolean): Int {
            return synchronized(stateLock) {
                val live = ownedTrack(stillOwner) ?: return -1
                try {
                    live.write(pcm, offset, length, AudioTrack.WRITE_NON_BLOCKING)
                } catch (_: IllegalStateException) {
                    -1
                }
            }
        }

        private fun ownedTrack(stillOwner: () -> Boolean): AudioTrack? {
            if (!stillOwner()) return null
            return track
        }
    }

    private inner class EngineSpeakerAdapter(
        private val manager: AudioManager,
    ) : AmbientSpeakerAdapter {
        override fun snapshot(): AmbientSpeakerSnapshot = readSpeakerSnapshot(manager)

        override fun setCommunicationDevice(id: Int): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            return setCommunicationDeviceS(id)
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun setCommunicationDeviceS(id: Int): Boolean {
            val match = try {
                manager.availableCommunicationDevices.firstOrNull { it.id == id }
            } catch (_: SecurityException) {
                return false
            } ?: return false
            return try {
                manager.setCommunicationDevice(match)
            } catch (_: SecurityException) {
                false
            }
        }

        override fun clearCommunicationDevice() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            clearCommunicationDeviceS()
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun clearCommunicationDeviceS() {
            try {
                manager.clearCommunicationDevice()
            } catch (_: SecurityException) {
            }
        }

        @Suppress("DEPRECATION")
        override fun setSpeakerphoneOn(on: Boolean): Boolean {
            return try {
                manager.setSpeakerphoneOn(on)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }

    private sealed interface Communication {
        data object Absent : Communication
        data object Denied : Communication
        data class Selected(val id: Int, val type: Int) : Communication
    }

    private companion object {
        const val CAPTURE_QUEUE_CAPACITY = 32
        const val PLAYBACK_QUEUE_CAPACITY = 32
        const val JOIN_MILLIS = 1_500L
    }
}

private fun readRecord(record: AudioRecord, buffer: ByteArray): Int {
    return try {
        record.read(buffer, 0, buffer.size)
    } catch (_: IllegalStateException) {
        -1
    }
}

private fun releaseRecord(record: AudioRecord?) {
    if (record == null) return
    try {
        if (record.state == AudioRecord.STATE_INITIALIZED &&
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        ) {
            record.stop()
        }
    } catch (_: IllegalStateException) {
    }
    try {
        record.release()
    } catch (_: RuntimeException) {
    }
}

private fun releaseTrack(track: AudioTrack?) {
    if (track == null) return
    try {
        if (track.state == AudioTrack.STATE_INITIALIZED &&
            track.playState != AudioTrack.PLAYSTATE_STOPPED
        ) {
            track.pause()
            track.flush()
            track.stop()
        }
    } catch (_: IllegalStateException) {
    }
    try {
        track.release()
    } catch (_: RuntimeException) {
    }
}
