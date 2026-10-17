package com.yage.opencode_client

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.yage.opencode_client.ambient.service.AmbientVoiceBridge
import com.yage.opencode_client.ambient.service.AmbientVoiceHost
import com.yage.opencode_client.ambient.service.AmbientVoiceService
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 33)
class AmbientServiceInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun grantRuntimePermissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(appContext.packageName, Manifest.permission.RECORD_AUDIO)
        automation.grantRuntimePermission(appContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun resumedRunKeepsOngoingNotificationUntilRequestStop() {
        val runId = UUID.randomUUID().toString()
        val staleId = UUID.randomUUID().toString()
        val eligibility = CopyOnWriteArrayList<String>()
        val acks = CopyOnWriteArrayList<String>()
        val stops = CopyOnWriteArrayList<String>()
        val sawForegroundAtAck = AtomicBoolean(false)
        val sawOngoingAtStop = AtomicBoolean(false)
        val notifications = notifications()
        val host = object : AmbientVoiceHost {
            override fun canStartService(id: String): Boolean {
                eligibility.add(id)
                return id == runId
            }

            override fun onServiceForeground(id: String): Boolean {
                sawForegroundAtAck.set(ambientVoiceServiceForeground())
                acks.add(id)
                return true
            }

            override fun onServiceStop(id: String) {
                sawOngoingAtStop.set(ongoingAmbient(notifications))
                stops.add(id)
            }

            override fun onServiceDestroyed(id: String) = Unit
        }
        withHost(runId, host) {
            composeRule.waitForIdle()
            assertEquals(Lifecycle.State.RESUMED, composeRule.activity.lifecycle.currentState)
            appContext.startService(serviceIntent(AmbientVoiceService.ACTION_START, runId))
            composeRule.waitUntil(TIMEOUT_MS) {
                acks.size == 1 && ongoingAmbient(notifications)
            }
            composeRule.waitForIdle()
            assertEquals(listOf(runId), eligibility.toList())
            assertEquals(listOf(runId), acks.toList())
            assertTrue(sawForegroundAtAck.get())

            appContext.startService(serviceIntent(AmbientVoiceService.ACTION_STOP, staleId))
            appContext.startService(serviceIntent(AmbientVoiceService.ACTION_STOP, runId))
            composeRule.waitUntil(TIMEOUT_MS) { stops.size == 1 }
            composeRule.waitForIdle()
            assertEquals(listOf(runId), stops.toList())
            assertTrue(sawOngoingAtStop.get())
            assertTrue(ongoingAmbient(notifications))

            AmbientVoiceBridge.requestStop(runId)
            composeRule.waitUntil(TIMEOUT_MS) {
                !ongoingAmbient(notifications) && !ambientVoiceServiceRunning()
            }
            assertEquals(listOf(runId), acks.toList())
            assertEquals(listOf(runId), stops.toList())
        }
    }

    @Test
    fun rejectedStartDoesNotAckOrPostOngoingNotification() {
        val runId = UUID.randomUUID().toString()
        val eligibility = CopyOnWriteArrayList<String>()
        val acks = CopyOnWriteArrayList<String>()
        val notifications = notifications()
        val host = object : AmbientVoiceHost {
            override fun canStartService(id: String): Boolean {
                eligibility.add(id)
                return false
            }

            override fun onServiceForeground(id: String): Boolean {
                acks.add(id)
                return true
            }

            override fun onServiceStop(id: String) = Unit

            override fun onServiceDestroyed(id: String) = Unit
        }
        withHost(runId, host) {
            composeRule.waitForIdle()
            assertEquals(Lifecycle.State.RESUMED, composeRule.activity.lifecycle.currentState)
            appContext.startService(serviceIntent(AmbientVoiceService.ACTION_START, runId))
            composeRule.waitUntil(TIMEOUT_MS) { eligibility.size == 1 }
            composeRule.waitForIdle()
            assertEquals(listOf(runId), eligibility.toList())
            assertTrue(acks.isEmpty())
            assertTrue(!ongoingAmbient(notifications))
            composeRule.waitUntil(TIMEOUT_MS) { !ambientVoiceServiceRunning() }
            AmbientVoiceBridge.requestStop(runId)
        }
    }

    private fun withHost(runId: String, host: AmbientVoiceHost, body: () -> Unit) {
        val previous = AmbientVoiceBridge.host
        val notifications = notifications()
        AmbientVoiceBridge.attach(host)
        try {
            body()
        } finally {
            try {
                AmbientVoiceBridge.requestStop(runId)
                composeRule.waitUntil(TIMEOUT_MS) {
                    !ongoingAmbient(notifications) && !ambientVoiceServiceRunning()
                }
            } finally {
                if (previous != null) AmbientVoiceBridge.attach(previous)
            }
        }
    }

    private fun notifications(): NotificationManager =
        checkNotNull(appContext.getSystemService(NotificationManager::class.java))

    private fun serviceIntent(action: String, runId: String): Intent =
        Intent(appContext, AmbientVoiceService::class.java)
            .setAction(action)
            .putExtra(AmbientVoiceService.EXTRA_RUN_ID, runId)

    @Suppress("DEPRECATION")
    private fun runningAmbientVoiceService(): ActivityManager.RunningServiceInfo? {
        val manager = checkNotNull(appContext.getSystemService(ActivityManager::class.java))
        return manager.getRunningServices(Int.MAX_VALUE).firstOrNull { running ->
            running.service.className == AmbientVoiceService::class.java.name
        }
    }

    private fun ambientVoiceServiceRunning(): Boolean = runningAmbientVoiceService() != null

    private fun ambientVoiceServiceForeground(): Boolean =
        runningAmbientVoiceService()?.foreground == true

    private fun ongoingAmbient(notifications: NotificationManager): Boolean =
        notifications.activeNotifications.any { posted ->
            posted.notification.channelId == AMBIENT_CHANNEL &&
                posted.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        }

    private companion object {
        const val AMBIENT_CHANNEL = "ambient_mode"
        const val TIMEOUT_MS = 5_000L
    }
}
