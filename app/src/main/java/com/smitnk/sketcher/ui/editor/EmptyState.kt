package com.smitnk.sketcher.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.ui.common.PaperCard
import com.smitnk.sketcher.ui.common.SketchyProgress
import com.smitnk.sketcher.ui.common.SketchyUnderline
import com.smitnk.sketcher.ui.common.drawPencil
import com.smitnk.sketcher.ui.common.rememberReducedMotion
import com.smitnk.sketcher.ui.theme.SketcherTokens
import kotlin.math.sin

/** Stage names the generator may report through [GeneratingIndicator]'s `stage` parameter. */
object GenerationStages {
    const val ReadingPhoto = "Reading photo"
    const val FindingLines = "Finding lines (AI)"
    const val Inking = "Inking strokes"
    const val Shading = "Shading"
    const val Colouring = "Colouring"
}

/** Shown when no project is loaded. Caller positions it (e.g. Modifier.align(Center)). */
@Composable
fun EmptyState(onImport: () -> Unit, onDemo: () -> Unit, modifier: Modifier = Modifier) {
    var tipDismissed by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SketcherTokens.screenMargin, vertical = SketcherTokens.space24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SketcherTokens.space12),
    ) {
        WelcomeIllustration(Modifier.size(width = 240.dp, height = 156.dp))

        Text(
            "Start with a photo",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        SketchyUnderline(Modifier.width(120.dp))
        Text(
            "Turn any photo into a drawing you can watch being sketched.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.size(SketcherTokens.space4))
        Button(
            onClick = onImport,
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).heightIn(min = 56.dp),
        ) {
            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null)
            Spacer(Modifier.width(SketcherTokens.space8))
            Text("Import photo")
        }
        OutlinedButton(
            onClick = onDemo,
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).heightIn(min = 48.dp),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(SketcherTokens.space8))
            Text("Try demo image")
        }

        Spacer(Modifier.size(SketcherTokens.space4))
        PaperCard(Modifier.fillMaxWidth().widthIn(max = 360.dp)) {
            Text("Good photos", style = MaterialTheme.typography.titleSmall)
            Text(
                "Clear subject, soft even light, not too busy a background.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = SketcherTokens.space4),
            )
        }

        AnimatedVisibility(visible = !tipDismissed) {
            PaperCard(Modifier.fillMaxWidth().widthIn(max = 360.dp)) {
                Text(
                    "Tip: auto outline on one layer, shade by hand on another",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { tipDismissed = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Got it") }
                }
            }
        }
    }
}

@Composable
private fun WelcomeIllustration(modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val line = MaterialTheme.colorScheme.onSurface
    val soft = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.surfaceContainerLowest
    val wood = MaterialTheme.colorScheme.tertiaryContainer
    val eraser = MaterialTheme.colorScheme.secondary
    val phase by rememberInfiniteTransition(label = "welcome").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse),
        label = "bob",
    )
    val bob = if (reduced) 0f else phase

    // Decorative only: hidden from accessibility via no semantics.
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()
        val board = Size(w * 0.82f, h * 0.82f)
        val tl = Offset(0f, h * 0.14f)
        drawRoundRect(paper, tl, board, CornerRadius(10.dp.toPx()))
        drawRoundRect(
            soft, tl, board, CornerRadius(10.dp.toPx()),
            style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
        )
        val round = Stroke(sw * 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // hills
        val hill = Path().apply {
            moveTo(w * 0.06f, h * 0.78f)
            cubicTo(w * 0.22f, h * 0.45f, w * 0.38f, h * 0.5f, w * 0.5f, h * 0.7f)
            cubicTo(w * 0.58f, h * 0.58f, w * 0.68f, h * 0.55f, w * 0.78f, h * 0.76f)
        }
        drawPath(hill, line, style = round)
        // sun
        drawCircle(accent, radius = h * 0.09f, center = Offset(w * 0.62f, h * 0.38f), style = round)
        // hatch shading
        for (i in 0 until 5) {
            val x = w * (0.14f + i * 0.045f)
            drawLine(soft, Offset(x, h * 0.74f), Offset(x + w * 0.03f, h * 0.62f - i * 2f), sw, StrokeCap.Round)
        }
        // pencil
        drawPencil(
            tip = Offset(w * 0.8f, h * 0.9f - bob * 6.dp.toPx() + sin(bob * 6f) * 1.dp.toPx()),
            length = h * 0.95f,
            bodyColor = accent,
            woodColor = wood,
            leadColor = line,
            eraserColor = eraser,
            angleDegrees = -55f,
        )
    }
}

/**
 * Progress overlay shown while strokes are generated. Dims the canvas behind it and blocks touches.
 * [stage] e.g. "Reading photo"; [fraction] 0..1 or null for indeterminate.
 */
@Composable
fun GeneratingIndicator(
    modifier: Modifier = Modifier,
    stage: String = "",
    fraction: Float? = null,
) {
    Box(
        modifier
            .fillMaxSize()
            .testTag("generating_overlay")
            .background(SketcherTokens.colors.scrim)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        PaperCard(
            Modifier.padding(SketcherTokens.screenMargin).widthIn(max = 320.dp).fillMaxWidth(),
            contentPadding = SketcherTokens.space20,
        ) {
            Crossfade(
                targetState = stage.ifBlank { "Generating strokes" },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                label = "stage",
            ) { text ->
                Text(text, style = MaterialTheme.typography.titleMedium)
            }
            SketchyProgress(fraction, Modifier.padding(top = SketcherTokens.space8))
            Text(
                text = if (fraction != null) "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%" else "This can take a moment",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
