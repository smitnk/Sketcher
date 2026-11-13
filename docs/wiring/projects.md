# Projects persistence wiring

Package `com.smitnk.sketcher.data` (new files only):
- `ProjectCodec` / `ProjectSnapshot` (pure Kotlin, "SKCH" v1 binary).
- `Project.toSnapshot(animationSeconds)` / `Project.applySnapshot(s)` (ProjectMapper.kt).
- `ProjectRepository(context)` : `save`, `load`, `list`, `delete`, `rename` (all suspend, IO).

## ViewModel
```kotlin
private val repo = ProjectRepository(app)   // AndroidViewModel, or pass context
var currentProjectId: String? = null

fun saveProject(name: String) {
    val snap = project.toSnapshot(animationSeconds)   // MAIN thread: reads Compose state
    val bg = project.background
    viewModelScope.launch { currentProjectId = repo.save(currentProjectId, name, snap, bg) }
}
fun openProject(id: String) = viewModelScope.launch {
    val loaded = repo.load(id) ?: return@launch   // null = missing/corrupt
    project.applySnapshot(loaded.snapshot)         // main thread (resumes on Main)
    project.background = loaded.source
    animationSeconds = loaded.snapshot.animationSeconds.takeIf { it > 0f } ?: animationSeconds
    currentProjectId = id
    // clear undo stacks, navigate to editor
}
fun newProject() { currentProjectId = null; /* reset Project */ }
```
Home screen: `val items = repo.list()` (refresh after save/delete/rename); show
`ProjectMeta.thumbPath` (File -> BitmapFactory/Coil), `name`, `updatedAt`.
`repo.delete(id)`, `repo.rename(id, name)` return Boolean.

Notes: applySnapshot assigns fresh layer ids (Project.nextId is private); the
background bitmap is separate from the snapshot. Autosave: call saveProject on
pause / after each stroke debounce. Thumbnail is rendered inside save() via
Renderer.drawLayers (best effort).
