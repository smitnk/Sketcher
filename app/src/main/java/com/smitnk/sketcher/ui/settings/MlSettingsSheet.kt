@file:OptIn(ExperimentalMaterial3Api::class)

package com.smitnk.sketcher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smitnk.sketcher.ui.theme.SketcherTokens

/**
 * "Processing" sheet: choose where the AI line model runs (Auto / CPU / XNNPACK / GPU / NPU) and
 * optionally benchmark each option on this phone.
 *
 * @param accelOptions (id, label) pairs, e.g. "auto" to "Auto (fastest)".
 * @param benchmarkResults id -> milliseconds; null value means unsupported / failed; missing key = not run.
 */
@Composable
fun MlSettingsSheet(
    accelOptions: List<Pair<String, String>>,
    selectedAccel: String,
    onSelectAccel: (String) -> Unit,
    benchmarkResults: Map<String, Long?>,
    benchmarking: Boolean,
    onRunBenchmark: () -> Unit,
    lastInferenceMs: Long?,
    activeAccelLabel: String?,
    fallbackReason: String?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheme = MaterialTheme.colorScheme
    val maxMs = benchmarkResults.values.filterNotNull().maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val bestMs = benchmarkResults.values.filterNotNull().minOrNull()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surfaceContainer,
        tonalElevation = SketcherTokens.elevationSheet,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SketcherTokens.screenMargin),
            verticalArrangement = Arrangement.spacedBy(SketcherTokens.space12),
        ) {
            Text("Processing", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Choose how your phone runs the AI line art.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )

            Column(Modifier.selectableGroup()) {
                for ((id, label) in accelOptions) {
                    val ms = if (benchmarkResults.containsKey(id)) benchmarkResults[id] else null
                    val ran = benchmarkResults.containsKey(id)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = SketcherTokens.minTouchTarget)
                            .selectable(
                                selected = id == selectedAccel,
                                role = Role.RadioButton,
                                onClick = { onSelectAccel(id) },
                            ),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = id == selectedAccel, onClick = null)
                            Spacer(Modifier.width(SketcherTokens.space12))
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (ran) {
                                Text(
                                    if (ms != null) "$ms ms" else "Not supported",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (ms != null && ms == bestMs) scheme.primary else scheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (ran && ms != null) {
                            val frac = (ms.toFloat() / maxMs).coerceIn(0.04f, 1f)
                            Box(
                                Modifier
                                    .padding(start = 56.dp, top = SketcherTokens.space2, bottom = SketcherTokens.space8)
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(scheme.surfaceContainerHighest)
                                    .semantics { contentDescription = "$label took $ms milliseconds" },
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(frac)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (ms == bestMs) scheme.primary else scheme.outline),
                                )
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onRunBenchmark,
                enabled = !benchmarking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (benchmarking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(SketcherTokens.space8))
                    Text("Testing…", style = MaterialTheme.typography.titleSmall)
                } else {
                    Text("Test speed on this phone", style = MaterialTheme.typography.titleSmall)
                }
            }

            if (activeAccelLabel != null || lastInferenceMs != null) {
                Column(verticalArrangement = Arrangement.spacedBy(SketcherTokens.space2)) {
                    if (activeAccelLabel != null) {
                        Text("Running on: $activeAccelLabel", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (lastInferenceMs != null) {
                        Text(
                            "Last drawing: $lastInferenceMs ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (fallbackReason != null) {
                Text(
                    fallbackReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                )
            }
            Text(
                "GPU and NPU may fall back to CPU if your phone doesn't support them. Auto picks the fastest option that works.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(SketcherTokens.space16))
        }
    }
}
