package com.smitnk.sketcher.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smitnk.sketcher.ui.home.HomeScreen
import com.smitnk.sketcher.ui.home.RecentProjectUi

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
    // 'pose' hint: set when the picker was opened from "Pose render"; applied via vm.setPoseMode before loading.
    var poseHint by rememberSaveable { mutableStateOf(false) }

    var deleteTarget by remember { mutableStateOf<RecentProjectUi?>(null) }
    var renameTarget by remember { mutableStateOf<RecentProjectUi?>(null) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(screen) {
        if (screen == Screen.HOME) vm.refreshRecent()
    }

    LaunchedEffect(editorRequest) {
        if (editorRequest > 0) screen = Screen.EDITOR
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            vm.setPoseMode(poseHint)
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
                vm.setPoseMode(false)
                vm.runDemo() // starts a fresh project (resets layers, then generates)
                screen = Screen.EDITOR
            },
            onOpenProject = { id ->
                vm.openProject(id)
                screen = Screen.EDITOR
            },
            recentProjects = vm.recentProjects,
            onDeleteProject = { id -> deleteTarget = vm.recentProjects.firstOrNull { it.id == id } },
            onRenameProject = { id ->
                renameTarget = vm.recentProjects.firstOrNull { it.id == id }
                renameText = renameTarget?.name ?: ""
            },
        )
        Screen.EDITOR -> {
            val leave = {
                vm.saveAndRefresh()
                screen = Screen.HOME
            }
            BackHandler { leave() }
            SketchScreen(vm, onBack = leave)
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename project") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it.take(80) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameProjectById(target.id, renameText)
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete project?") },
            text = { Text("\"${target.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(target.id)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}
