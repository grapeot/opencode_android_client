package com.yage.opencode_client.ambient.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.ambient.protocol.AmbientMeter
import com.yage.opencode_client.ambient.protocol.AmbientPhase

/**
 * Fractions of the 48dp glyph. The capsule is a fixed stadium.
 * [LOWER_Y] sits in the bottom band that a low live level (display fill 0.2) paints.
 * [MID_Y] is still empty at that low level and painted once the display fill reaches 0.5.
 * [UPPER_Y] sits in the top band that only a full live level paints.
 * These are sample points for rendering checks, not accessibility labels.
 */
object AmbientMicLayout {
    const val LEFT = 0.34f
    const val TOP = 0.08f
    const val RIGHT = 0.66f
    const val BOTTOM = 0.54f
    const val SAMPLE_X = 0.50f
    const val LOWER_Y = 0.49f
    const val MID_Y = 0.40f
    const val UPPER_Y = 0.18f
}

private class CapsuleFillClock {
    val animatable = Animatable(0f)
    var presented = 0f
}

/**
 * Local capsule fill. Attack and release retarget from the animation's current value.
 * Mute, idle, connecting, closing, failed, and reduced motion return [AmbientMeter.transition]'s
 * target on this composition. That target is the shared display fill, or zero when the meter
 * is not live. Raw RMS is not modified.
 */
@Composable
internal fun rememberAmbientCapsuleFill(
    inputLevel: Double,
    phase: AmbientPhase,
    muted: Boolean,
): Float {
    val reduceMotion = !ValueAnimator.areAnimatorsEnabled()
    val clock = remember { CapsuleFillClock() }
    if (clock.animatable.isRunning) {
        clock.presented = clock.animatable.value
    }
    val transition = AmbientMeter.transition(
        from = clock.presented.toDouble(),
        inputLevel = inputLevel,
        phase = phase,
        muted = muted,
        reduceMotion = reduceMotion,
    )
    val target = transition.fill.coerceIn(0.0, 1.0).toFloat()
    if (transition.motion == AmbientMeter.Motion.SNAP) {
        clock.presented = target
        LaunchedEffect(target) {
            if (clock.animatable.value != target) clock.animatable.snapTo(target)
        }
        return target
    }
    val durationMillis = (transition.durationNanos / 1_000_000L).toInt().coerceAtLeast(1)
    LaunchedEffect(target, durationMillis) {
        if (clock.animatable.value != clock.presented) {
            clock.animatable.snapTo(clock.presented)
        }
        clock.animatable.animateTo(
            targetValue = target,
            animationSpec = tween(durationMillis = durationMillis, easing = LinearEasing),
        )
        clock.presented = clock.animatable.value
    }
    return if (clock.animatable.isRunning) clock.animatable.value else clock.presented
}

/**
 * 48dp microphone. Outline, cradle, stem, and base stay fixed.
 * The capsule fills from the bottom using [fill]. A slash is drawn only when muted.
 * No content description: the level must not be announced on every frame.
 */
@Composable
internal fun AmbientMicGlyph(
    fill: Float,
    showSlash: Boolean,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.onSurface
    val fillColor = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(AmbientMeter.GLYPH_DP.dp)) {
        val stroke = size.minDimension * 0.045f
        val capsuleRect = Rect(
            left = size.width * AmbientMicLayout.LEFT,
            top = size.height * AmbientMicLayout.TOP,
            right = size.width * AmbientMicLayout.RIGHT,
            bottom = size.height * AmbientMicLayout.BOTTOM,
        )
        val capsule = Path().apply {
            addRoundRect(
                RoundRect(
                    capsuleRect,
                    CornerRadius(capsuleRect.width / 2f, capsuleRect.width / 2f),
                ),
            )
        }
        val clamped = fill.coerceIn(0f, 1f)
        if (clamped > 0f) {
            val fillTop = capsuleRect.bottom - capsuleRect.height * clamped
            clipPath(capsule) {
                drawRect(
                    color = fillColor,
                    topLeft = Offset(capsuleRect.left, fillTop),
                    size = Size(capsuleRect.width, capsuleRect.bottom - fillTop),
                )
            }
        }
        drawPath(path = capsule, color = outline, style = Stroke(width = stroke))
        drawArc(
            color = outline,
            startAngle = 15f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(size.width * 0.18f, size.height * 0.36f),
            size = Size(size.width * 0.64f, size.height * 0.40f),
            style = Stroke(width = stroke),
        )
        val centerX = size.width * 0.5f
        drawLine(
            color = outline,
            start = Offset(centerX, size.height * 0.76f),
            end = Offset(centerX, size.height * 0.86f),
            strokeWidth = stroke,
        )
        drawLine(
            color = outline,
            start = Offset(size.width * 0.34f, size.height * 0.86f),
            end = Offset(size.width * 0.66f, size.height * 0.86f),
            strokeWidth = stroke,
        )
        if (showSlash) {
            drawLine(
                color = outline,
                start = Offset(size.width * 0.22f, size.height * 0.20f),
                end = Offset(size.width * 0.78f, size.height * 0.84f),
                strokeWidth = stroke * 1.6f,
            )
        }
    }
}
