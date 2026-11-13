package com.smitnk.sketcher.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.ui.theme.SketcherTokens

/** One entry in the "Recent projects" grid. */
data class RecentProjectUi(
    val id: String,
    val name: String,
    val thumbnail: ImageBitmap?,
    val updatedLabel: String,
)

@Composable
fun HomeScreen(
    onImportPhoto: () -> Unit,
    onImportPose: () -> Unit,
    onTryDemo: () -> Unit,
    onOpenProject: (String) -> Unit = {},
    onDeleteProject: (String) -> Unit = {},
    recentProjects: List<RecentProjectUi> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.widthIn(max = SketcherTokens.maxContentWidth).fillMaxSize(),
            contentPadding = PaddingValues(
                start = SketcherTokens.screenMargin,
                end = SketcherTokens.screenMargin,
                top = safe.calculateTopPadding() + SketcherTokens.space24,
                bottom = safe.calculateBottomPadding() + SketcherTokens.space32,
            ),
            horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space16),
            verticalArrangement = Arrangement.spacedBy(SketcherTokens.space16),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Wordmark() }
            item(span = { GridItemSpan(maxLineSpan) }) {
                ImportPhotoCard(onClick = onImportPhoto)
            }
            item(span = { GridItemSpan(1) }) {
                ActionCard(
                    icon = Icons.Outlined.Accessibility,
                    title = "Pose render",
                    onClick = onImportPose,
                )
            }
            item(span = { GridItemSpan(1) }) {
                ActionCard(
                    icon = Icons.Outlined.PlayArrow,
                    title = "Try demo",
                    onClick = onTryDemo,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) { HowItWorks() }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle("Recent projects", Modifier.padding(top = SketcherTokens.space8))
            }
            if (recentProjects.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyRecent() }
            } else {
                items(recentProjects, key = { it.id }) { p ->
                    RecentCard(p, onClick = { onOpenProject(p.id) }, onLongClick = { onDeleteProject(p.id) })
                }
            }
        }
    }
}

@Composable
private fun Wordmark() {
    val accent = MaterialTheme.colorScheme.primary
    Column {
        Text(
            text = "Sketcher",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        // Hand-drawn pencil underline.
        Canvas(Modifier.padding(top = SketcherTokens.space4).size(width = 132.dp, height = 12.dp)) {
            val p = Path().apply {
                moveTo(2f, size.height * 0.6f)
                cubicTo(size.width * 0.25f, size.height * 0.1f, size.width * 0.45f, size.height * 1.0f, size.width * 0.7f, size.height * 0.45f)
                cubicTo(size.width * 0.82f, size.height * 0.2f, size.width * 0.92f, size.height * 0.6f, size.width - 2f, size.height * 0.35f)
            }
            drawPath(p, accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Text(
            text = "Turn any photo into a drawing",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = SketcherTokens.space8),
        )
    }
}

@Composable
private fun ImportPhotoCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) {
        Row(
            Modifier.padding(horizontal = SketcherTokens.space20, vertical = SketcherTokens.space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space16),
        ) {
            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(32.dp))
            Column {
                Text("Import photo", style = MaterialTheme.typography.titleMedium)
                Text("From your gallery", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        Row(
            Modifier.padding(horizontal = SketcherTokens.space16, vertical = SketcherTokens.space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space12),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun HowItWorks() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(SketcherTokens.space12),
            horizontalArrangement = Arrangement.spacedBy(SketcherTokens.space8),
        ) {
            Step(Icons.Outlined.Image, "1", "Pick a photo", Modifier.weight(1f))
            Step(Icons.Outlined.Brush, "2", "Choose a style", Modifier.weight(1f))
            Step(Icons.Outlined.Movie, "3", "Watch it draw", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Step(icon: ImageVector, number: String, label: String, modifier: Modifier) {
    Column(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Step $number: $label" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SketcherTokens.space4),
    ) {
        Box(
            Modifier.size(40.dp).clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(
            "$number. $label",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentCard(p: RecentProjectUi, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).combinedClickable(
            onClickLabel = "Open ${p.name}",
            onLongClickLabel = "Delete ${p.name}",
            onLongClick = onLongClick,
            onClick = onClick,
        ),
    ) {
        Column(Modifier.padding(SketcherTokens.space8)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 5f)
                    .clip(MaterialTheme.shapes.small)
                    .background(SketcherTokens.colors.paper)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                if (p.thumbnail != null) {
                    Image(
                        bitmap = p.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Outlined.Brush,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Text(
                p.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = SketcherTokens.space8),
            )
            Text(
                p.updatedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Empty state drawn only with Canvas strokes: dashed artboard, a sun and hills in pencil. */
@Composable
private fun EmptyRecent() {
    val line = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().padding(vertical = SketcherTokens.space8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SketcherTokens.space12),
    ) {
        Canvas(
            Modifier.size(width = 160.dp, height = 128.dp).clearAndSetSemantics { },
        ) {
            val w = size.width
            val h = size.height
            val sw = 2.dp.toPx()
            drawRoundRect(
                color = line,
                topLeft = Offset(sw, sw),
                size = Size(w - 2 * sw, h - 2 * sw),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                style = Stroke(width = sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
            )
            drawCircle(accent, radius = h * 0.11f, center = Offset(w * 0.72f, h * 0.30f), style = Stroke(sw * 1.4f, cap = StrokeCap.Round))
            val hills = Path().apply {
                moveTo(w * 0.12f, h * 0.78f)
                cubicTo(w * 0.25f, h * 0.45f, w * 0.38f, h * 0.45f, w * 0.52f, h * 0.70f)
                cubicTo(w * 0.60f, h * 0.58f, w * 0.72f, h * 0.55f, w * 0.88f, h * 0.78f)
            }
            drawPath(hills, line, style = Stroke(sw * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val sq = Path().apply {
                moveTo(w * 0.20f, h * 0.88f)
                cubicTo(w * 0.35f, h * 0.82f, w * 0.45f, h * 0.94f, w * 0.80f, h * 0.86f)
            }
            drawPath(sq, accent, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text("No sketches yet", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Start with a photo and it will show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
