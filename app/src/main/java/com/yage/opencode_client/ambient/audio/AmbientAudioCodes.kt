package com.yage.opencode_client.ambient.audio

/**
 * Fixed failure codes. These strings are safe to surface: they contain no PCM,
 * device names, hosts, or credentials.
 */
object AmbientAudioCodes {
    const val RECORD_PERMISSION_MISSING = "record_permission_missing"
    const val CAPTURE_ALREADY_RUNNING = "capture_already_running"
    const val CAPTURE_RATE_UNSUPPORTED = "capture_rate_unsupported"
    const val CAPTURE_START_FAILED = "capture_start_failed"
    const val PLAYBACK_START_FAILED = "playback_start_failed"
    const val FOCUS_DENIED = "focus_denied"
    const val FOCUS_LOSS = "focus_loss"
    const val HEADSET_REMOVED = "headset_removed"
    const val BLUETOOTH_ROUTE_UNREADABLE = "bluetooth_route_unreadable"
    const val CAPTURE_READ_FAILED = "capture_read_failed"
    const val CAPTURE_QUEUE_OVERFLOW = "capture_queue_overflow"
    const val PLAYBACK_WRITE_FAILED = "playback_write_failed"
    const val PLAYBACK_QUEUE_OVERFLOW = "playback_queue_overflow"
    const val GENERATION_OVERFLOW = "generation_overflow"
}

class AmbientAudioStartException(val code: String) : IllegalStateException(code)
