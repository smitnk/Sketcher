package com.smitnk.sketcher.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smitnk.sketcher.ui.home.HomeScreen

private enum class Screen { HOME, EDITOR }

/**
 * State-based navigation: Home <-> Editor (no navigation library).
 *
 * [startInEditor] is true when launched with automation extras (autodemo etc.).
 * [editorRequest] is bumped by MainActivity for later intents; any change switches to the Editor.
 */
@Composable
fun AppNavigation(
    startInEditor: Boolean,
    editorRequest: Int,
    vm: SketchViewModel = viewModel(),
) {
    var screen by rememberSaveable { mutableStateOf(if (startInEditor) Screen.EDITOR else Screen.HOME) }
    // 'pose' hint: set when the picker was opened from "Import pose render". Not yet consumed by the VM.
    var poseHint by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(editorRequest) {
        if (editorRequest > 0) screen = Screen.EDITOR
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            vm.loadImage(uri)
            screen = Screen.EDITOR
        }
    }
    val launchPicker = { pose: Boolean ->
        poseHint = pose
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            onImportPhoto = { launchPicker(false) },
            onImportPose = { launchPicker(true) },
            onTryDemo = {
                vm.runDemo()
                screen = Screen.EDITOR
            },
            onOpenProject = { screen = Screen.EDITOR },
            recentProjects = emptyList(),
        )
        Screen.EDITOR -> {
            // EditorScreen/TopBar do not expose onBack yet; system back returns Home meanwhile.
            BackHandler { screen = Screen.HOME }
            SketchScreen(vm)
        }
    }
}
