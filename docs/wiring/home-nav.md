# Home + navigation wiring

Files: ui/home/HomeScreen.kt, ui/AppNavigation.kt, MainActivity.kt (SketcherTheme + AppNavigation).

- HomeScreen(onImportPhoto, onImportPose, onTryDemo, onOpenProject: (String)->Unit, recentProjects: List<RecentProjectUi>).
- Navigation is a rememberSaveable enum (HOME/EDITOR). Intent extras autodemo/showlayers/autoexport/autovideo/demo start in (or switch to) Editor.
- Back: Editor currently returns Home via BackHandler in AppNavigation. TODO (EditorScreen/TopBar owner): expose `onBack: () -> Unit` on EditorScreen and SketchScreen, show a back icon in TopBar (left pill), and pass `onBack = { screen = Screen.HOME }` in AppNavigation.
- Recent projects: AppNavigation passes emptyList(). ViewModel should expose a list (id, name, thumbnail ImageBitmap?, updatedLabel) and an `openProject(id)`; then replace emptyList() and the onOpenProject stub.
- Pose hint: AppNavigation keeps `poseHint` (true when picked via "Import pose render"). VM has no pose flag; add e.g. `vm.poseHint = poseHint` before `vm.loadImage(uri)`.
- Import: Home owns the photo picker (PickVisualMedia) and calls vm.loadImage(uri), then goes to Editor. Demo: vm.runDemo().
- Note: opening Home->Editor does not reset the project; a "new project" reset in VM is needed if prior work should be cleared on Try demo.
