package com.smitnk.sketcher.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smitnk.sketcher.ui.editor.EditorScreen

/** Kept for MainActivity; delegates to [EditorScreen]. */
@Composable
fun SketchScreen(
    vm: SketchViewModel = viewModel(),
    onBack: () -> Unit = {},
    projectTitle: String = "Untitled sketch",
    onOpenSettings: (() -> Unit)? = null,
) = EditorScreen(vm, onBack, projectTitle, onOpenSettings)
