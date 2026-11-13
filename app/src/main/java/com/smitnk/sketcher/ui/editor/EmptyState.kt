package com.smitnk.sketcher.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown when no project is loaded. Caller positions it (e.g. Modifier.align(Center)). */
@Composable
fun EmptyState(onImport: () -> Unit, onDemo: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Import a photo or try the demo", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onImport) { Text("Import photo") }
        OutlinedButton(onClick = onDemo) { Text("Try demo image") }
    }
}

/** Progress card shown while strokes are being generated. */
@Composable
fun GeneratingIndicator(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Generating strokes...", modifier = Modifier.padding(top = 12.dp))
        }
    }
}
