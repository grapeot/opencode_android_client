package com.yage.opencode_client.ambient.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yage.opencode_client.ui.MainViewModel

/** Stable tags for the Ambient destination. Navigation tests should use these names. */
object AmbientNavigationTags {
    const val PHONE_NAV = "ambient-nav"
    const val CONTENT_SWITCH = "ambient-content-switch"
    const val CONTENT_CHAT = "ambient-content-chat"
    const val CONTENT_AMBIENT = "ambient-content-ambient"
    const val ENABLE = "ambient-enable"
    const val ROUTE = "ambient-route"
}

/**
 * Host and runtime captured when the user taps connect.
 * Kept only in this composition. A recreated composition has no permit, so a
 * redelivered permission result cannot start a session.
 */
private class AmbientConnectPermit {
    var hostProfileId: String? = null
    var runtimeGeneration: Long = Long.MIN_VALUE
    var armed: Boolean = false
}

/**
 * Collects the ambient snapshot and wires [AmbientScreen].
 * Composition does not open a microphone, audio device, or network connection.
 * [onConnect] is the only start path, and only after the user taps connect.
 */
@Composable
fun AmbientRoute(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snapshot by viewModel.ambientSnapshot.collectAsStateWithLifecycle()
    val permit = remember { AmbientConnectPermit() }

    fun permitStillValid(): Boolean {
        if (!permit.armed) return false
        val current = viewModel.state.value
        if (!current.ambientEnabled) return false
        if (current.currentHostProfileId != permit.hostProfileId) return false
        if (current.runtimeGeneration != permit.runtimeGeneration) return false
        return lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    fun startIfPermitted() {
        val allowed = permitStillValid() && microphoneGranted(context)
        permit.armed = false
        if (allowed) viewModel.startAmbient()
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Grant and denial are separate from the microphone result.
        // Denial still starts when the microphone permit is valid.
        startIfPermitted()
    }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted || !permitStillValid()) {
            permit.armed = false
            return@rememberLauncherForActivityResult
        }
        if (notificationPermissionOutstanding(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return@rememberLauncherForActivityResult
        }
        startIfPermitted()
    }

    fun onConnect() {
        val current = viewModel.state.value
        if (!current.ambientEnabled) return
        permit.hostProfileId = current.currentHostProfileId
        permit.runtimeGeneration = current.runtimeGeneration
        permit.armed = true
        if (!microphoneGranted(context)) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (notificationPermissionOutstanding(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startIfPermitted()
    }

    AmbientScreen(
        snapshot = snapshot,
        onConnect = ::onConnect,
        onSetMuted = viewModel::setAmbientMuted,
        onInterruptPlayback = viewModel::interruptAmbientPlayback,
        onEnd = viewModel::endAmbient,
        modifier = modifier.testTag(AmbientNavigationTags.ROUTE),
    )
}

private fun microphoneGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

/** API 33+ notification grant. Older releases have no runtime notification permission. */
private fun notificationPermissionOutstanding(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) != PackageManager.PERMISSION_GRANTED
}
