package com.yage.opencode_client.ambient.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yage.opencode_client.R

/**
 * Microphone foreground service for one in-memory Ambient run.
 * It does not open a recorder, a socket, or a credential, and it does not
 * construct a coordinator if the process was restarted under it.
 */
class AmbientVoiceService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentRunId: String? = null
    private var latestStartId: Int = 0
    private var intentional = false
    private var foreground = false
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AmbientVoiceBridge.install(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val action = intent?.action
        val requested = intent?.getStringExtra(EXTRA_RUN_ID)?.takeIf { it.isNotBlank() }
        when (action) {
            ACTION_STOP -> stopRequested(requested)
            ACTION_START -> startRequested(requested)
            else -> {
                if (currentRunId == null) {
                    intentional = true
                    stopSelf(latestStartId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        val id = currentRunId
        val unexpected = !intentional && !id.isNullOrBlank()
        currentRunId = null
        foreground = false
        AmbientVoiceBridge.clear(this)
        if (unexpected && id != null) AmbientVoiceBridge.host?.onServiceDestroyed(id)
        super.onDestroy()
    }

    internal fun postStop(runId: String) {
        mainHandler.post { stopMatching(runId) }
    }

    private fun startRequested(requested: String?) {
        val owner = AmbientVoiceBridge.host
        val cancelled = requested != null && AmbientVoiceBridge.consumeCancelled(requested)
        val eligible = owner != null && requested != null && !cancelled && owner.canStartService(requested)
        if (!eligible || owner == null || requested == null) {
            if (currentRunId == null) {
                intentional = true
                stopSelf(latestStartId)
            }
            return
        }
        if (currentRunId != null && currentRunId != requested) {
            intentional = true
            currentRunId = null
        }
        if (!promoteForeground(requested)) {
            intentional = true
            owner.onServiceDestroyed(requested)
            stopSelf(latestStartId)
            return
        }
        if (AmbientVoiceBridge.consumeCancelled(requested)) {
            quietStop(requested)
            return
        }
        currentRunId = requested
        intentional = false
        val accepted = owner.onServiceForeground(requested)
        if (!accepted || AmbientVoiceBridge.consumeCancelled(requested)) {
            quietStop(requested)
        }
    }

    private fun stopRequested(requested: String?) {
        if (requested == null || currentRunId != requested) return
        val host = AmbientVoiceBridge.host
        if (host != null) {
            host.onServiceStop(requested)
            return
        }
        quietStop(requested)
    }

    private fun stopMatching(runId: String) {
        if (destroyed || currentRunId != runId) return
        AmbientVoiceBridge.consumeCancelled(runId)
        quietStop(runId)
    }

    private fun quietStop(runId: String) {
        if (currentRunId != null && currentRunId != runId) return
        intentional = true
        if (currentRunId == runId) currentRunId = null
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf(latestStartId)
    }

    private fun promoteForeground(runId: String): Boolean {
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(runId),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            foreground = true
            true
        } catch (_: Exception) {
            foreground = false
            false
        }
    }

    private fun notification(runId: String): Notification {
        ensureChannel()
        val stop = PendingIntent.getService(
            this,
            STOP_REQUEST,
            Intent(this, AmbientVoiceService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_RUN_ID, runId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.ambient_notification_channel))
            .setContentText(getString(R.string.ambient_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.ambient_notification_stop),
                stop,
            )
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ambient_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.yage.opencode_client.ambient.action.START"
        const val ACTION_STOP = "com.yage.opencode_client.ambient.action.STOP"
        const val EXTRA_RUN_ID = "com.yage.opencode_client.ambient.extra.RUN_ID"
        private const val CHANNEL_ID = "ambient_mode"
        private const val NOTIFICATION_ID = 41041
        private const val STOP_REQUEST = 41042
    }
}

interface AmbientVoiceHost {
    fun canStartService(runId: String): Boolean
    fun onServiceForeground(runId: String): Boolean
    fun onServiceStop(runId: String)
    fun onServiceDestroyed(runId: String)
}

object AmbientVoiceBridge {
    @Volatile
    var host: AmbientVoiceHost? = null
        private set

    private val gate = Any()
    private val cancelled = HashSet<String>()
    private var instance: AmbientVoiceService? = null

    fun attach(next: AmbientVoiceHost) {
        host = next
    }

    fun install(service: AmbientVoiceService) {
        synchronized(gate) { instance = service }
    }

    fun clear(service: AmbientVoiceService) {
        synchronized(gate) {
            if (instance === service) instance = null
        }
    }

    fun requestStop(runId: String) {
        if (runId.isBlank()) return
        val service = synchronized(gate) {
            cancelled.add(runId)
            instance
        } ?: return
        service.postStop(runId)
    }

    fun consumeCancelled(runId: String): Boolean = synchronized(gate) { cancelled.remove(runId) }
}
