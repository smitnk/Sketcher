# UI structure

Root package `com.smitnk.sketcher.ui`. `SketchViewModel` (and `Tool`) stay in `ui/`.
Components are stateless: explicit state + callbacks. Only `EditorScreen` and `SketchCanvas` touch the ViewModel.

| File | Composables |
|------|-------------|
| `ui/SketchScreen.kt` | `SketchScreen(vm = viewModel())` - thin delegate to `EditorScreen` (used by MainActivity) |
| `ui/editor/EditorScreen.kt` | `EditorScreen(vm: SketchViewModel = viewModel())` - composition root, pickers, permissions, animation clock, dialogs |
| `ui/editor/TopBar.kt` | `TopBar(generating: Boolean, playing: Boolean, onImport, onDemo, onGenerate, onSettings, onTogglePlay, onExport: () -> Unit)` |
| `ui/editor/ToolDock.kt` | `ToolDock(tool: Tool, onToolChange: (Tool)->Unit, brushType: BrushType, onSelectBrush: (BrushType)->Unit, brushColor: Int, onColorSelected: (Int)->Unit, brushSize: Float, onBrushSizeChange: (Float)->Unit, brushAlpha: Float, onBrushAlphaChange: (Float)->Unit, canUndo: Boolean, canRedo: Boolean, onUndo, onRedo, onLayers, onAddShadingLayer: () -> Unit, selectedLayer: Layer?)` - emits rows; call inside a Column. Private: ToolRow, SwatchRow, BrushRow, SizeRow |
| `ui/editor/PlaybackBar.kt` | `PlaybackBar(durationSec: Float, onDurationChange: (Float)->Unit, onRestart: () -> Unit, onShowFinished: () -> Unit)` |
| `ui/editor/EmptyState.kt` | `EmptyState(onImport: () -> Unit, onDemo: () -> Unit, modifier: Modifier = Modifier)`; `GeneratingIndicator(modifier: Modifier = Modifier)` |
| `ui/layers/LayersPanel.kt` | `LayersPanel(project: Project, onAddLayer: () -> Unit, onSelectLayer: (Layer)->Unit, onMoveLayer: (Layer, Int)->Unit, onDeleteLayer: (Layer)->Unit, onDismiss: () -> Unit)` (modal bottom sheet; edits layer visibility/lock/opacity/name directly on the `Layer` state objects) |
| `ui/generate/StyleSettingsDialog.kt` | `StyleSettingsDialog(current: GeneratorSettings, onDismiss: () -> Unit, onApply: (GeneratorSettings) -> Unit)` (was `SettingsDialog`) |
| `ui/export/ExportSheet.kt` | `ExportDialog(selectedLayerName: String, onDismiss: () -> Unit, onExport: (scale: Int, format: ExportFormat, transparent: Boolean, layerOnly: Boolean)->Unit, onExportAnimation: (AnimExportKind)->Unit)`; `ExportProgressDialog(label: String, progress: Float, onCancel: () -> Unit)` |
| `ui/canvas/SketchCanvas.kt` | `SketchCanvas(vm: SketchViewModel, modifier: Modifier = Modifier)` (still takes the VM: gesture, live stroke and render wiring) |
| `ui/home/HomeScreen.kt` | `HomeScreen()` - TODO placeholder, not wired |
