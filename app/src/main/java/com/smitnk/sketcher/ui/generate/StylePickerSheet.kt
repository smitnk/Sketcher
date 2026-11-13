@file:OptIn(ExperimentalMaterial3Api::class)

package com.smitnk.sketcher.ui.generate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.image.pipeline.LineSource
import com.smitnk.sketcher.image.pipeline.PipelineSettings
import com.smitnk.sketcher.image.pipeline.ShadingLevel
import com.smitnk.sketcher.image.pipeline.ShadingStyle
import com.smitnk.sketcher.ui.theme.SketcherTokens
import kotlin.math.roundToInt

private enum class StyleChoice { OUTLINE, SHADED, COLOUR }

private fun PipelineSettings.styleChoice(): StyleChoice = when {
    colourFill -> StyleChoice.COLOUR
    shading != ShadingLevel.NONE -> StyleChoice.SHADED
    else -> StyleChoice.OUTLINE
}

private fun PipelineSettings.withStyle(choice: StyleChoice): PipelineSettings = when (choice) {
    StyleChoice.OUTLINE -> copy(colourFill = false, shading = ShadingLevel.NONE)
    StyleChoice.SHADED -> copy(
        colourFill = false,
        shading = if (shading == ShadingLevel.NONE) ShadingLevel.MEDIUM else shading,
    )
    StyleChoice.COLOUR -> copy(colourFill = true, shading = ShadingLevel.NONE)
}

/**
 * "How should I draw this?" sheet: pick a style card, tweak line engine / detail / width and the
 * style-specific options, then tap "Draw it".
 */
@Composable
fun StylePickerSheet(
    settings: PipelineSettings,
    onSettingsChange: (PipelineSettings) -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
    sourceThumbnail: ImageBitmap?,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val choice = settings.styleChoice()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = SketcherTokens.elevationSheet,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SketcherTokens.screenMargin),
                verticalArrangement = Arrangement.spacedBy(SketcherTokens.space16),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sourceThumbnail != null) {
                        Image(
                            bitmap = sourceThumbnail,
                            contentDescription = "Your photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
                        )
                        Spacer(Modifier.size(SketcherTokens.space12))
                    }
                    Column {
                        Text("How should I draw it?", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Pick a look, then fine-tune.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8)) {
                    StyleCard(
                        label = "Outline only",
                        kind = StyleChoice.OUTLINE,
                        selected = choice == StyleChoice.OUTLINE,
                        onClick = { onSettingsChange(settings.withStyle(StyleChoice.OUTLINE)) },
                        modifier = Modifier.weight(1f),
                    )
                    StyleCard(
                        label = "Outline + Shading",
                        kind = StyleChoice.SHADED,
                        selected = choice == StyleChoice.SHADED,
                        onClick = { onSettingsChange(settings.withStyle(StyleChoice.SHADED)) },
                        modifier = Modifier.weight(1f),
                    )
                    StyleCard(
                        label = "Colour fill",
                        kind = StyleChoice.COLOUR,
                        selected = choice == StyleChoice.COLOUR,
                        onClick = { onSettingsChange(settings.withStyle(StyleChoice.COLOUR)) },
                        modifier = Modifier.weight(1f),
                    )
                }

                SectionLabel("Line engine")
                val engines = listOf(
                    LineSource.AUTO to "Auto",
                    LineSource.AI to "AI line art",
                    LineSource.CARTOON to "Cartoon",
                    LineSource.CLASSIC to "Classic",
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    engines.forEachIndexed { index, (source, name) ->
                        SegmentedButton(
                            selected = settings.lineSource == source,
                            onClick = { onSettingsChange(settings.copy(lineSource = source)) },
                            shape = SegmentedButtonDefaults.itemShape(index, engines.size),
                            icon = {},
                            modifier = Modifier.heightIn(min = SketcherTokens.minTouchTarget),
                        ) {
                            Text(
                                name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                LabeledSlider(
                    title = "Detail",
                    startHint = "Fewer lines",
                    endHint = "More lines",
                    value = settings.detail,
                    range = 0f..1f,
                    onChange = { onSettingsChange(settings.copy(detail = it)) },
                )
                LabeledSlider(
                    title = "Line width",
                    startHint = "Fine",
                    endHint = "Bold",
                    value = settings.lineWidth,
                    range = 1f..5f,
                    onChange = { onSettingsChange(settings.copy(lineWidth = it)) },
                    valueText = "%.1f px".format(settings.lineWidth),
                )

                AnimatedVisibility(
                    visible = choice == StyleChoice.SHADED,
                    enter = fadeIn(tween(SketcherTokens.MotionMedium)) + expandVertically(tween(SketcherTokens.MotionMedium)),
                    exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(SketcherTokens.space12)) {
                        SectionLabel("Shading level")
                        ChipRow(
                            options = listOf(
                                ShadingLevel.LIGHT to "Light",
                                ShadingLevel.MEDIUM to "Medium",
                                ShadingLevel.HEAVY to "Heavy",
                            ),
                            selected = settings.shading,
                            onSelect = { onSettingsChange(settings.copy(shading = it)) },
                        )
                        SectionLabel("Shading style")
                        ChipRow(
                            options = listOf(
                                ShadingStyle.HATCH to "Hatch",
                                ShadingStyle.CROSS_HATCH to "Cross-hatch",
                                ShadingStyle.SCRIBBLE to "Scribble",
                            ),
                            selected = settings.shadingStyle,
                            onSelect = { onSettingsChange(settings.copy(shadingStyle = it)) },
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = SketcherTokens.minTouchTarget)
                                .selectable(
                                    selected = settings.ignoreBackground,
                                    role = Role.Switch,
                                    onClick = {
                                        onSettingsChange(settings.copy(ignoreBackground = !settings.ignoreBackground))
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Ignore background", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Don't shade flat areas behind the subject",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = settings.ignoreBackground, onCheckedChange = null)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = choice == StyleChoice.COLOUR,
                    enter = fadeIn(tween(SketcherTokens.MotionMedium)) + expandVertically(tween(SketcherTokens.MotionMedium)),
                    exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(SketcherTokens.space12)) {
                        LabeledSlider(
                            title = "Fill colours",
                            startHint = "Few",
                            endHint = "Many",
                            value = settings.fillColors.toFloat(),
                            range = 4f..24f,
                            steps = 19,
                            onChange = { onSettingsChange(settings.copy(fillColors = it.roundToInt().coerceIn(4, 24))) },
                            valueText = "${settings.fillColors}",
                        )
                        LabeledSlider(
                            title = "Fill spacing",
                            startHint = "Dense",
                            endHint = "Loose",
                            value = settings.fillSpacing,
                            range = 3f..12f,
                            onChange = { onSettingsChange(settings.copy(fillSpacing = it)) },
                            valueText = "%.1f px".format(settings.fillSpacing),
                        )
                    }
                }
                Spacer(Modifier.height(SketcherTokens.space4))
            }

            Button(
                onClick = onGenerate,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SketcherTokens.screenMargin, vertical = SketcherTokens.space12)
                    .heightIn(min = 56.dp),
            ) {
                Text("Draw it", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun LabeledSlider(
    title: String,
    startHint: String,
    endHint: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    steps: Int = 0,
    valueText: String? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (valueText != null) {
                Text(
                    valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.heightIn(min = SketcherTokens.minTouchTarget).semantics { contentDescription = title },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                startHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                endHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8),
    ) {
        for ((value, name) in options) {
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(name, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

@Composable
private fun StyleCard(
    label: String,
    kind: StyleChoice,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val borderColor by animateColorAsState(
        if (selected) scheme.primary else scheme.outlineVariant,
        tween(160),
        label = "cardBorder",
    )
    val container by animateColorAsState(
        if (selected) scheme.primaryContainer else scheme.surfaceContainerLowest,
        tween(160),
        label = "cardFill",
    )
    Surface(
        modifier = modifier
            .heightIn(min = 140.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = container,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
    ) {
        Column(
            Modifier.padding(SketcherTokens.space8),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SketcherTokens.space8),
        ) {
            StylePreview(kind, Modifier.fillMaxWidth().height(80.dp).clearAndSetSemantics { })
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            )
        }
    }
}

/** Tiny illustrated preview (an apple-ish ball with a leaf) drawn in the three styles. */
@Composable
private fun StylePreview(kind: StyleChoice, modifier: Modifier = Modifier) {
    val tokens = SketcherTokens.colors
    val shadeColor = MaterialTheme.colorScheme.secondary
    val fillColor = MaterialTheme.colorScheme.tertiary
    val paper = tokens.paper
    val ink = tokens.ink
    Canvas(modifier.clip(RoundedCornerShape(10.dp))) {
        drawRect(paper)
        val r = size.minDimension * 0.38f
        val c = Offset(size.width / 2f, size.height * 0.56f)
        val ball = Path().apply { addOval(Rect(c, r)) }
        val lw = 2.dp.toPx()
        if (kind == StyleChoice.COLOUR) {
            clipPath(ball) {
                drawRect(fillColor.copy(alpha = 0.55f))
                // lighter highlight band and darker lower band
                drawCircle(Color.White.copy(alpha = 0.35f), r * 0.45f, Offset(c.x - r * 0.35f, c.y - r * 0.35f))
                drawCircle(fillColor.copy(alpha = 0.55f), r * 0.9f, Offset(c.x + r * 0.35f, c.y + r * 0.55f))
            }
        }
        if (kind == StyleChoice.SHADED) {
            clipPath(ball) { hatch(Offset(c.x + r * 0.2f, c.y + r * 0.2f), r, shadeColor, 1.2.dp.toPx(), 5.dp.toPx()) }
        }
        drawCircle(ink, r, c, style = Stroke(lw, cap = StrokeCap.Round))
        // stem and leaf
        drawLine(ink, Offset(c.x, c.y - r), Offset(c.x + r * 0.1f, c.y - r * 1.3f), lw, StrokeCap.Round)
        val leaf = Path().apply {
            moveTo(c.x + r * 0.1f, c.y - r * 1.2f)
            quadraticBezierTo(c.x + r * 0.7f, c.y - r * 1.5f, c.x + r * 0.8f, c.y - r * 1.05f)
            quadraticBezierTo(c.x + r * 0.4f, c.y - r * 0.95f, c.x + r * 0.1f, c.y - r * 1.2f)
        }
        drawPath(leaf, ink, style = Stroke(lw * 0.8f, cap = StrokeCap.Round))
    }
}

/** Diagonal hatch lines over the lower-right part of the ball. */
private fun DrawScope.hatch(center: Offset, r: Float, color: Color, width: Float, gap: Float) {
    var x = center.x - r * 2f
    while (x < center.x + r * 2f) {
        drawLine(color, Offset(x, center.y + r * 1.2f), Offset(x + r * 1.6f, center.y - r * 1.2f), width)
        x += gap
    }
}
