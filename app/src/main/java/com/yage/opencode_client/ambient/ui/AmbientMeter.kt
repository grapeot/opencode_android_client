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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.ambient.protocol.AmbientMeter
import com.yage.opencode_client.ambient.protocol.AmbientPhase

/**
 * Fractions of the 48dp glyph, on the same 28-unit grid as the iOS microphone.
 * The outer head is x 8.8, y 3.7, width 10.4, height 14.4.
 * [LOWER_Y] is the center of the inner-head band a display fill of 0.2 paints.
 * [MID_Y] is still empty at that low level and painted once the display fill reaches 0.5.
 * [UPPER_Y] sits in the top of the inner head, which a display fill of 0.5 leaves empty.
 * These are sample points for rendering checks, not accessibility labels.
 */
object AmbientMicLayout {
    const val LEFT = 8.8f / 28f
    const val TOP = 3.7f / 28f
    const val RIGHT = 19.2f / 28f
    const val BOTTOM = 18.1f / 28f
    const val SAMPLE_X = 0.50f
    const val LOWER_Y = 15.02f / 28f
    const val MID_Y = 12.445f / 28f
    const val UPPER_Y = 7.81f / 28f
}

/**
 * One 28-unit layout for the head, the inset fill, the cradle, the stem, and the base.
 * Cradle and stem are measured from the head, matching CarLiveMicrophoneGlyphLayout.
 */
private object AmbientMicGeometry {
    const val GRID = 28f
    const val STROKE = 1.8f / GRID
    const val SLASH_STROKE = 2.1f / GRID
    const val HEAD_WIDTH = 10.4f / GRID
    const val HEAD_HEIGHT = 14.4f / GRID
    const val HEAD_TOP = 3.7f / GRID
    const val HEAD_LEFT = (GRID - 10.4f) / 2f / GRID
    const val HEAD_RIGHT = HEAD_LEFT + HEAD_WIDTH
    const val HEAD_BOTTOM = HEAD_TOP + HEAD_HEIGHT
    const val INNER_INSET = STROKE / 2f + 1.15f / GRID
    const val CRADLE_GAP = 2.3f / GRID
    const val CRADLE_LEFT = HEAD_LEFT - CRADLE_GAP
    const val CRADLE_RIGHT = HEAD_RIGHT + CRADLE_GAP
    const val ARM_TOP = HEAD_TOP + HEAD_HEIGHT * 0.42f
    const val ARC_RADIUS = (CRADLE_RIGHT - CRADLE_LEFT) / 2f
    const val CRADLE_BOTTOM = HEAD_BOTTOM + 2.8f / GRID
    const val ARC_CENTER_Y = CRADLE_BOTTOM - ARC_RADIUS
    const val STEM_BOTTOM = CRADLE_BOTTOM + 3.5f / GRID
    const val BASE_HALF = 5.6f / GRID
    const val SLASH_START_X = 21.8f / GRID
    const val SLASH_START_Y = 4.6f / GRID
    const val SLASH_END_X = 6.0f / GRID
    const val SLASH_END_Y = 24.8f / GRID
    const val KAPPA = 0.5522847498f
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
        val side = size.minDimension
        val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        val x: (Float) -> Float = { fraction -> origin.x + fraction * side }
        val y: (Float) -> Float = { fraction -> origin.y + fraction * side }
        val stroke = Stroke(
            width = side * AmbientMicGeometry.STROKE,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val head = Rect(
            left = x(AmbientMicGeometry.HEAD_LEFT),
            top = y(AmbientMicGeometry.HEAD_TOP),
            right = x(AmbientMicGeometry.HEAD_RIGHT),
            bottom = y(AmbientMicGeometry.HEAD_BOTTOM),
        )
        val inset = side * AmbientMicGeometry.INNER_INSET
        val inner = Rect(
            left = head.left + inset,
            top = head.top + inset,
            right = head.right - inset,
            bottom = head.bottom - inset,
        )
        val clamped = fill.coerceIn(0f, 1f)
        if (clamped > 0f) {
            val fillTop = inner.bottom - inner.height * clamped
            clipPath(stadium(inner)) {
                drawRect(
                    color = fillColor,
                    topLeft = Offset(inner.left, fillTop),
                    size = Size(inner.width, inner.bottom - fillTop),
                )
            }
        }
        drawPath(stadium(head), outline, style = stroke)
        drawPath(cradle(x, y), outline, style = stroke)
        drawPath(stem(x, y, side), outline, style = stroke)
        if (showSlash) {
            drawLine(
                color = outline,
                start = Offset(
                    x(AmbientMicGeometry.SLASH_START_X),
                    y(AmbientMicGeometry.SLASH_START_Y),
                ),
                end = Offset(
                    x(AmbientMicGeometry.SLASH_END_X),
                    y(AmbientMicGeometry.SLASH_END_Y),
                ),
                strokeWidth = side * AmbientMicGeometry.SLASH_STROKE,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun stadium(rect: Rect): Path = Path().apply {
    addRoundRect(
        RoundRect(
            rect,
            CornerRadius(rect.width / 2f, rect.width / 2f),
        ),
    )
}

private fun cradle(x: (Float) -> Float, y: (Float) -> Float): Path {
    val radius = y(AmbientMicGeometry.ARC_CENTER_Y + AmbientMicGeometry.ARC_RADIUS) -
        y(AmbientMicGeometry.ARC_CENTER_Y)
    val centerY = y(AmbientMicGeometry.ARC_CENTER_Y)
    val left = x(AmbientMicGeometry.CRADLE_LEFT)
    val right = x(AmbientMicGeometry.CRADLE_RIGHT)
    val midX = (left + right) / 2f
    val kappa = AmbientMicGeometry.KAPPA
    return Path().apply {
        moveTo(left, y(AmbientMicGeometry.ARM_TOP))
        lineTo(left, centerY)
        cubicTo(
            left,
            centerY + kappa * radius,
            midX - kappa * radius,
            centerY + radius,
            midX,
            centerY + radius,
        )
        cubicTo(
            midX + kappa * radius,
            centerY + radius,
            right,
            centerY + kappa * radius,
            right,
            centerY,
        )
        lineTo(right, y(AmbientMicGeometry.ARM_TOP))
    }
}

private fun stem(x: (Float) -> Float, y: (Float) -> Float, side: Float): Path {
    val midX = x(0.5f)
    val baseHalf = side * AmbientMicGeometry.BASE_HALF
    val bottom = y(AmbientMicGeometry.STEM_BOTTOM)
    return Path().apply {
        moveTo(midX, y(AmbientMicGeometry.CRADLE_BOTTOM))
        lineTo(midX, bottom)
        moveTo(midX - baseHalf, bottom)
        lineTo(midX + baseHalf, bottom)
    }
}
