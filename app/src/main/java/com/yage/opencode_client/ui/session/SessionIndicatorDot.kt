package com.yage.opencode_client.ui.session

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import com.yage.opencode_client.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yage.opencode_client.ui.theme.*

internal fun runningAnimationEnabled(visible: Boolean, foreground: Boolean, scale: Float) =
    visible && foreground && scale > 0f

@Composable
fun SessionIndicatorDot(sessionId: String, indicator: SessionIndicator?) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var visible by remember { mutableStateOf(false) }
    val resolver = view.context.contentResolver
    fun durationScale() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    var scale by remember { mutableFloatStateOf(durationScale()) }
    DisposableEffect(lifecycle, resolver) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale = durationScale() }
        }
        lifecycle.addObserver(observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, settingsObserver)
        onDispose { lifecycle.removeObserver(observer); resolver.unregisterContentObserver(settingsObserver) }
    }
    val animate = indicator == SessionIndicator.RUNNING && runningAnimationEnabled(visible, foreground, scale)
    val alpha = if (animate) rememberInfiniteTransition(label = "running").animateFloat(
        1f, SessionIndicatorTuning.runningMinOpacity, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "opacity"
    ) else rememberUpdatedState(1f)
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    Box(Modifier.width(SessionIndicatorTuning.columnWidth), contentAlignment = Alignment.CenterStart) {
        if (indicator != null) {
            val emphasis = indicator == SessionIndicator.NEEDS_YOU || indicator == SessionIndicator.FAILED
            val base = if (emphasis) SessionIndicatorTuning.emphasisSize else SessionIndicatorTuning.unreadSize
            val max = if (emphasis) SessionIndicatorTuning.emphasisMaxSize else SessionIndicatorTuning.unreadMaxSize
            val size = (base * LocalDensity.current.fontScale).coerceIn(base, max)
            val color = when (indicator) {
                SessionIndicator.NEEDS_YOU -> BrandGold
                SessionIndicator.FAILED -> StopRed
                SessionIndicator.UNREAD -> BrandPrimary
                SessionIndicator.RUNNING -> if (dark) SessionRunningDark else SessionRunningLight
            }
            val description = stringResource(when (indicator) {
                SessionIndicator.NEEDS_YOU -> R.string.sessions_indicator_needs_you
                SessionIndicator.FAILED -> R.string.sessions_indicator_failed
                SessionIndicator.RUNNING -> R.string.sessions_status_running
                SessionIndicator.UNREAD -> R.string.sessions_indicator_unread
            })
            Box(Modifier.size(size).onGloballyPositioned {
                val b = it.boundsInWindow()
                visible = !b.isEmpty && b.bottom > 0 && b.top < view.height && b.right > 0 && b.left < view.width
            }.drawBehind {
                drawCircle(color, alpha = if (animate) alpha.value else 1f, center = Offset(this.size.width / 2, this.size.height / 2))
            }.semantics { contentDescription = description }.testTag("session-indicator-$sessionId"))
        }
    }
}
