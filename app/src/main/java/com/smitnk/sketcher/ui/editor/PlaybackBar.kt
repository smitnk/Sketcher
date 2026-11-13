package com.smitnk.sketcher.ui.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun PlaybackBar(
    durationSec: Float,
    onDurationChange: (Float) -> Unit,
    onRestart: () -> Unit,
    onShowFinished: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onRestart) { Icon(Icons.Default.Replay, contentDescription = "Restart") }
        IconButton(onClick = onShowFinished) {
            Icon(Icons.Default.SkipNext, contentDescription = "Show finished")
        }
        Text("Time ${durationSec.roundToInt()}s", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = durationSec,
            onValueChange = onDurationChange,
            valueRange = 2f..60f,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}
