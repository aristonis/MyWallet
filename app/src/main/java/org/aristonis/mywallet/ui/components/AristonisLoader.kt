package org.aristonis.mywallet.ui.components

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.theme.LocalWalletDarkTheme
import kotlin.coroutines.coroutineContext

/** Occupies the caller's bounded loading region. Removing it cancels every pending reveal/frame. */
@Composable
fun AristonisLoader(modifier: Modifier = Modifier) {
    val palette = loaderPalette(LocalWalletDarkTheme.current)
    val frame = rememberLoaderFrame()
    val label = stringResource(R.string.aristonis_loader_loading)
    val messages = stringArrayResource(R.array.aristonis_loader_messages)
    Box(
        modifier.fillMaxSize()
            .background(Color(palette.backdrop).copy(alpha = LoaderGeometry.backdropAlpha))
            .clearAndSetSemantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 256.dp).graphicsLayer { alpha = frame.revealAlpha },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoaderMark(frame, palette)
            Text(
                messages[frame.messageIndex],
                modifier = Modifier.testTag("aristonis_loader_message"),
                color = Color(palette.text).copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun rememberLoaderFrame(): LoaderFrame {
    val systemScale = rememberSystemAnimationScale()
    var frame by remember { mutableStateOf(loaderFrame(0)) }
    val started = remember { SystemClock.elapsedRealtime() }
    LaunchedEffect(systemScale) {
        val motionScale = coroutineContext[MotionDurationScale]
        snapshotFlow { motionScale?.scaleFactor ?: systemScale }.collectLatest { composeScale ->
            val scale = if (!systemScale.isFinite() || systemScale <= 0f) 0f else composeScale
            val elapsed = SystemClock.elapsedRealtime() - started
            frame = loaderFrame(elapsed, scale)
            delay((LOADER_DELAY_MILLIS - elapsed).coerceAtLeast(0))
            val revealElapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(LOADER_DELAY_MILLIS)
            frame = loaderFrame(revealElapsed, scale)
            if (scale.isFinite() && scale > 0f) {
                var firstFrame: Long? = null
                while (isActive) {
                    withInfiniteAnimationFrameNanos { now ->
                        val origin = firstFrame ?: now.also { firstFrame = it }
                        frame = loaderFrame(revealElapsed + (now - origin) / 1_000_000L, scale)
                    }
                }
            }
        }
    }
    return frame
}

@Composable
private fun rememberSystemAnimationScale(): Float {
    val resolver = LocalContext.current.contentResolver
    fun readScale(): Float = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    var scale by remember(resolver) { mutableFloatStateOf(readScale()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale = readScale() }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        // Close the race between the initial read and observer registration.
        scale = readScale()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale
}

@Composable
private fun LoaderMark(frame: LoaderFrame, palette: LoaderPalette) {
    val paths = remember {
        LoaderGeometry.polygons.map { coordinates ->
            Path().apply {
                moveTo(coordinates[0], coordinates[1])
                for (index in 2 until coordinates.size step 2) lineTo(coordinates[index], coordinates[index + 1])
                close()
            }
        }
    }
    val clip = remember(paths) { Path().apply { paths.forEach { addPath(it) } } }
    val measure = remember { PathMeasure() }
    val segment = remember { Path() }
    Canvas(Modifier.size(48.dp).testTag("aristonis_loader_mark")) {
        scale(size.width / LoaderGeometry.viewBox[2], pivot = Offset.Zero) {
            translate(-LoaderGeometry.viewBox[0], -LoaderGeometry.viewBox[1]) {
                paths.forEachIndexed { index, path ->
                    val bounds = path.getBounds()
                    val gold = Brush.linearGradient(
                        *LoaderGeometry.goldStops.zip(palette.gold).map { (stop, color) -> stop to Color(color) }.toTypedArray(),
                        start = bounds.topLeft, end = bounds.bottomRight,
                    )
                    if (frame.fillAlpha > 0f) drawPath(path, gold, alpha = frame.fillAlpha)
                    if (frame.outlineAlpha > 0f && frame.traceFractions[index] > 0f) {
                        measure.setPath(path, forceClosed = true)
                        segment.reset()
                        measure.getSegment(0f, measure.length * frame.traceFractions[index], segment)
                        drawPath(segment, gold, alpha = frame.outlineAlpha, style = Stroke(LoaderGeometry.outlineWidth, join = StrokeJoin.Round))
                    }
                }
                frame.shineProgress?.let { progress ->
                    clipPath(clip) { drawShine(progress) }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShine(progress: Float) {
    val x = -80f + 340f * progress
    val skew = Matrix().apply { this[0, 1] = -0.36397022f } // The brand shine is skewed by -20 degrees.
    withTransform({ transform(skew) }) {
        drawRect(
            Brush.linearGradient(
                0f to Color(0xFFFFF8E6).copy(alpha = 0f),
                0.5f to Color(0xFFFFF8E6).copy(alpha = 0.85f),
                1f to Color(0xFFFFF8E6).copy(alpha = 0f),
                start = Offset(x, 0f), end = Offset(x + 56f, 0f),
            ),
            topLeft = Offset(x, 0f),
            size = Size(56f, 216f),
        )
    }
}
