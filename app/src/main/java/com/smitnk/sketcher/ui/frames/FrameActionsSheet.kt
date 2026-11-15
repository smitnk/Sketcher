@file:OptIn(ExperimentalMaterial3Api::class)

package com.smitnk.sketcher.ui.frames

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.ui.theme.SketcherTokens

/** Long-press actions of one frame: duplicate, move left/right, delete (the delete asks for confirmation). */
@Composable
fun FrameActionsSheet(
    index: Int,
    count: Int,
    onDuplicate: () -> Unit,
    onMove: (delta: Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        tonalElevation = SketcherTokens.elevationSheet,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = SketcherTokens.screenMargin)
                .padding(bottom = SketcherTokens.space16),
        ) {
            Text(
                "Frame ${index + 1} of $count",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }.padding(bottom = SketcherTokens.space8),
            )
            ActionRow(Icons.Default.ContentCopy, "Duplicate", true, onDuplicate)
            ActionRow(Icons.AutoMirrored.Filled.ArrowBack, "Move left", index > 0, onClick = { onMove(-1) })
            ActionRow(Icons.AutoMirrored.Filled.ArrowForward, "Move right", index < count - 1, onClick = { onMove(1) })
            ActionRow(Icons.Default.Delete, "Delete…", count > 1, onDelete, danger = true)
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, danger: Boolean = false) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = SketcherTokens.minTouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = SketcherTokens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            modifier = Modifier.padding(start = SketcherTokens.space16),
        )
    }
}
