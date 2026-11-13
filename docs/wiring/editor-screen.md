# Editor shell wiring
- `SketchScreen(vm, onBack = {}, projectTitle = "Untitled sketch", onOpenSettings: (() -> Unit)? = null)` and `EditorScreen` take the same params. MainActivity/nav should pass `onBack` (go Home), a real project title, and `onOpenSettings` (menu item is hidden while null).
- `TopBar` signature changed (title, onBack, onStyle, onLayers, onTogglePlay, onExport, onImport, onDemo, onGenerate, onSettings?).
- SketchCanvas (not owned): still paints its own hardcoded `Color(0xFFCFD8DC)` backdrop and white paper. It should draw nothing behind the sheet (EditorScreen already supplies `SketcherTokens.colors.canvasBackdrop`), fill the sheet with `colors.paper`, and add a soft shadow under the sheet.
- Chrome toggle: two-finger tap or single tap within 28dp of the left/right screen edge (non-consuming Initial-pass observer, drawing unaffected). Hidden chrome shows a "Show controls" handle; Back restores it. Tools auto-hide while playing.
- Tool dock is wrapped in a scrollable floating surface (max 300dp); redesigned ToolDock may want to drop that wrapper.
