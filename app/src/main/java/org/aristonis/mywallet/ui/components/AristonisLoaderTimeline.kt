package org.aristonis.mywallet.ui.components

import androidx.compose.animation.core.CubicBezierEasing

internal const val LOADER_DELAY_MILLIS = 300L
private const val FADE_MILLIS = 200.0
private const val CYCLE_MILLIS = 3200.0
private const val MESSAGE_COUNT = 8
private val traceEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val fillEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val revealEasing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val traceWindows = listOf(0f to 28f, 14f to 38f, 32f to 46f, 40f to 54f)

internal data class LoaderFrame(
    val revealAlpha: Float,
    val traceFractions: List<Float>,
    val outlineAlpha: Float,
    val fillAlpha: Float,
    val shineProgress: Float?,
    val messageIndex: Int,
)

/** Elapsed time belongs to the loading composition, so completion never depends on this timeline. */
internal fun loaderFrame(
    elapsedMillis: Long,
    durationScale: Float = 1f,
    reducedMotion: Boolean = false,
): LoaderFrame {
    val elapsed = elapsedMillis.coerceAtLeast(0)
    val isAnimationEnabled = durationScale.isFinite() && durationScale > 0f
    val reveal = when {
        elapsed < LOADER_DELAY_MILLIS -> 0f
        !isAnimationEnabled -> 1f
        else -> revealEasing.transform(
            ((elapsed - LOADER_DELAY_MILLIS) / (FADE_MILLIS * durationScale)).toFloat().coerceIn(0f, 1f),
        )
    }
    if (!isAnimationEnabled || reducedMotion) {
        return LoaderFrame(reveal, listOf(0f, 0f, 0f, 0f), 0f, 1f, null, 0)
    }
    // Reduce before narrowing to an integer; uptime and small scales cannot overflow a cycle counter.
    val position = (elapsed.toDouble() / durationScale) % (CYCLE_MILLIS * MESSAGE_COUNT)
    val phase = ((position % CYCLE_MILLIS) / CYCLE_MILLIS * 100).toFloat()
    val clear = progress(phase, 88f, 96f)
    return LoaderFrame(
        revealAlpha = reveal,
        traceFractions = traceWindows.map { (start, end) -> traceEasing.transform(progress(phase, start, end)) },
        outlineAlpha = 1f - traceEasing.transform(clear),
        fillAlpha = fillEasing.transform(progress(phase, 54f, 64f)) * (1f - fillEasing.transform(clear)),
        shineProgress = if (phase in 66f..84f) traceEasing.transform(progress(phase, 66f, 84f)) else null,
        messageIndex = (position / CYCLE_MILLIS).toInt(),
    )
}

private fun progress(value: Float, start: Float, end: Float): Float =
    ((value - start) / (end - start)).coerceIn(0f, 1f)
