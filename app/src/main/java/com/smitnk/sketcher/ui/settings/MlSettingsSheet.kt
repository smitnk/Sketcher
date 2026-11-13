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
import com.smitnk.sketcher.image.pipeline.NNAPI_LABEL
import com.smitnk.sketcher.image.pipeline.SketchPipeline
import com.smitnk.sketcher.ui.theme.SketcherTokens

/**
 * "Processing" sheet: choose where the AI line model runs (Auto / CPU / XNNPACK / NNAPI accelerator) and
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
    /** Non-null while the "NNAPI may crash your phone" warning must be shown. */
    nnapiWarningPending: Boolean = false,
    onConfirmNnapi: () -> Unit = {},
    onDismissNnapi: () -> Unit = {},
    /** Per-stage ms of the last drawing (SketchPipeline.lastTimings); "total" is the whole run. */
    stageTimings: Map<String, Long> = SketchPipeline.lastTimings,
) {
    // One honest NNAPI entry (GPU vs NPU cannot be chosen from ORT); CPU and CPU+XNNPACK stay as is.
    val options = collapseNnapi(accelOptions)
    val selected = if (selectedAccel.startsWith("nnapi")) NNAPI_ID else selectedAccel
    val results = collapseNnapiResults(benchmarkResults)
    val activeLabel = activeAccelLabel?.let { if (it.contains("NNAPI")) NNAPI_LABEL else it }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheme = MaterialTheme.colorScheme
    val maxMs = results.values.filterNotNull().maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val bestMs = results.values.filterNotNull().minOrNull()

    if (nnapiWarningPending) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissNnapi,
            title = { Text("Use NNAPI anyway?") },
            text = {
                Text(
                    "NNAPI hands the AI to your phone's GPU/NPU driver. On some phones (for example MediaTek " +
                        "chips) this can freeze or crash the whole system, not just this app, and the large " +
                        "downloaded models are never run on NNAPI. CPU + XNNPACK is the safe default. " +
                        "Only continue if you want to experiment.",
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = onConfirmNnapi) { Text("Use anyway") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = onDismissNnapi) { Text("Keep CPU") } },
        )
    }

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
                for ((id, label) in options) {
                    val ms = if (results.containsKey(id)) results[id] else null
                    val ran = results.containsKey(id)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = SketcherTokens.minTouchTarget)
                            .selectable(
                                selected = id == selected,
                                role = Role.RadioButton,
                                onClick = { onSelectAccel(id) },
                            ),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = id == selected, onClick = null)
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

            val totalMs = stageTimings["total"]
            if (activeLabel != null || lastInferenceMs != null || totalMs != null) {
                Column(verticalArrangement = Arrangement.spacedBy(SketcherTokens.space2)) {
                    if (activeLabel != null) {
                        Text("Running on: $activeLabel", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (lastInferenceMs != null || totalMs != null) {
                        Text(
                            listOfNotNull(
                                lastInferenceMs?.let { "AI model $it ms" },
                                totalMs?.let { "whole drawing $it ms" },
                            ).joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    val rest = stageTimings.filterKeys { it != "total" && it != "model" }.filterValues { it > 0 }
                    if (rest.isNotEmpty()) {
                        Text(
                            rest.entries.joinToString("  ") { (k, v) -> "$k $v ms" },
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
                "NNAPI lets Android choose the GPU, NPU or CPU for you, and may fall back to CPU if your phone doesn't support it, so it can run at the same speed as CPU. Auto picks the faster of CPU and CPU + XNNPACK after you run the speed test. Speed tests use the built-in model only.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(SketcherTokens.space16))
        }
    }
}

private const val NNAPI_ID = "nnapi_gpu"

/** Merges the two NNAPI flag variants into a single "NNAPI accelerator (GPU/NPU)" option. */
internal fun collapseNnapi(options: List<Pair<String, String>>): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    for ((id, label) in options) {
        if (id.startsWith("nnapi")) { if (out.none { it.first == NNAPI_ID }) out.add(NNAPI_ID to NNAPI_LABEL) } else out.add(id to label)
    }
    return out
}

/** NNAPI benchmark = the faster of its two variants (null if neither ran successfully, still "ran" if present). */
internal fun collapseNnapiResults(r: Map<String, Long?>): Map<String, Long?> {
    val out = LinkedHashMap<String, Long?>()
    var nn: Long? = null; var seen = false
    for ((k, v) in r) {
        if (k.startsWith("nnapi")) { seen = true; if (v != null && (nn == null || v < nn)) nn = v } else out[k] = v
    }
    if (seen) out[NNAPI_ID] = nn
    return out
}
