# LayersPanel wiring
No new required parameters; EditorScreen call is unchanged. Duplicate layer omitted (no callback exists). If wanted later, add `onDuplicateLayer: ((Layer) -> Unit)? = null` and a menu item.
Delete now confirms inside the panel before calling onDeleteLayer.
