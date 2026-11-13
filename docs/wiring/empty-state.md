# EmptyState / GeneratingIndicator wiring
- `GeneratingIndicator(modifier, stage: String = "", fraction: Float? = null)`: now a full-size dimming overlay (fillMaxSize + scrim, blocks touches). Place it as the last child of the canvas Box.
- To show real progress, expose `genStage: String` (e.g. GenerationStages.ReadingPhoto, FindingLines, Inking, Shading, Colouring) and `genFraction: Float?` from the ViewModel and pass them in.
- `EmptyState` unchanged signature; tip card dismissal uses rememberSaveable only.
