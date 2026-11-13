package com.smitnk.sketcher.ui.common

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import kotlin.random.Random

/** True when the user disabled animations (ANIMATOR_DURATION_SCALE == 0). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Builds a hand-drawn looking horizontal line from [x0] to [x1] around [y]. */
internal fun wobblyLinePath(x0: Float, x1: Float, y: Float, amp: Float, seed: Int, step: Float = 8f): Path {
    val rnd = Random(seed)
    val path = Path()
    path.moveTo(x0, y)
    var x = x0
    while (x < x1) {
        x = minOf(x + step, x1)
        val yy = y + sin(x * 0.045f + seed) * amp * 0.6f + (rnd.nextFloat() - 0.5f) * amp * 0.8f
        path.lineTo(x, yy)
    }
    return path
}

/** Hand-drawn underline. Decorative (no semantics). */
@Composable
fun SketchyUnderline(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 2.dp,
    seed: Int = 7,
) {
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val sw = strokeWidth.toPx()
        drawPath(
            wobblyLinePath(sw, size.width - sw, size.height / 2f, size.height * 0.3f, seed),
            color = color,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Thin hand-drawn divider in the outline-variant colour. */
@Composable
fun SketchyDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant,
    seed: Int = 3,
) {
    SketchyUnderline(modifier, color = color, strokeWidth = 1.dp, seed = seed)
}

/** Draws a pencil whose tip is at [tip], leaning up and to the right. Length in px. */
fun DrawScope.drawPencil(
    tip: Offset,
    length: Float,
    bodyColor: Color,
    woodColor: Color,
    leadColor: Color,
    eraserColor: Color,
    angleDegrees: Float = -35f,
) {
    val w = length * 0.12f
    val tipLen = length * 0.2f
    withTransform({
        translate(tip.x, tip.y)
        rotate(angleDegrees, Offset.Zero)
    }) {
        // wood cone
        val cone = Path().apply {
            moveTo(0f, 0f)
            lineTo(tipLen, -w / 2f)
            lineTo(tipLen, w / 2f)
            close()
        }
        drawPath(cone, woodColor)
        // lead
        val lead = Path().apply {
            moveTo(0f, 0f)
            lineTo(tipLen * 0.35f, -w * 0.17f)
            lineTo(tipLen * 0.35f, w * 0.17f)
            close()
        }
        drawPath(lead, leadColor)
        // body
        drawRect(bodyColor, topLeft = Offset(tipLen, -w / 2f), size = androidx.compose.ui.geometry.Size(length * 0.62f, w))
        // ferrule + eraser
        val fx = tipLen + length * 0.62f
        drawRect(leadColor.copy(alpha = 0.55f), topLeft = Offset(fx, -w / 2f), size = androidx.compose.ui.geometry.Size(length * 0.07f, w))
        drawRect(eraserColor, topLeft = Offset(fx + length * 0.07f, -w / 2f), size = androidx.compose.ui.geometry.Size(length * 0.11f, w))
    }
}

/**
 * Animated pencil scribbling a line. [fraction] null = indeterminate (pencil sweeps back and forth),
 * otherwise determinate 0..1.
 */
@Composable
fun SketchyProgress(
    fraction: Float?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    val reduced = rememberReducedMotion()
    val pencilBody = MaterialTheme.colorScheme.primary
    val wood = MaterialTheme.colorScheme.tertiaryContainer
    val lead = MaterialTheme.colorScheme.onSurface
    val eraser = MaterialTheme.colorScheme.secondary

    val sweep by rememberInfiniteTransition(label = "sketchyProgress").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "sweep",
    )
    val animated by animateFloatAsState(
        targetValue = (fraction ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(300, easing = LinearEasing),
        label = "fraction",
    )
    val head = when {
        fraction != null -> animated
        reduced -> 0.5f
        else -> sweep
    }
    val tail = if (fraction != null) 0f else (head - 0.4f).coerceAtLeast(0f)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics {
                progressBarRangeInfo = if (fraction != null) {
                    ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                } else {
                    ProgressBarRangeInfo.Indeterminate
                }
            },
    ) {
        val sw = 3.dp.toPx()
        val pad = 12.dp.toPx()
        val x0 = pad
        val x1 = size.width - pad
        val y = size.height * 0.72f
        val amp = 3.dp.toPx()
        // dotted track
        drawPath(
            wobblyLinePath(x0, x1, y, amp * 0.5f, 11),
            trackColor,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val hx = x0 + (x1 - x0) * head
        val tx = x0 + (x1 - x0) * tail
        if (hx - tx > 2f) {
            drawPath(
                wobblyLinePath(tx, hx, y, amp, 5, step = 6f),
                color,
                style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        val bob = if (reduced) 0f else sin(head * 40f) * 2.dp.toPx()
        drawPencil(
            tip = Offset(hx, y + bob),
            length = 46.dp.toPx(),
            bodyColor = pencilBody,
            woodColor = wood,
            leadColor = lead,
            eraserColor = eraser,
        )
    }
}

private fun Modifier.paperGrain(color: Color): Modifier = drawWithCache {
    val count = (size.width * size.height / 700f).toInt().coerceIn(30, 600)
    val rnd = Random(42)
    val pts = List(count) { Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height) }
    onDrawBehind {
        drawPoints(pts, PointMode.Points, color, 1.2.dp.toPx(), StrokeCap.Round)
    }
}

/** Card with a faint procedural paper grain and a pencil-grey outline. */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val grain = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.paperGrain(grain)) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}
