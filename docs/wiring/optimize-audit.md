# Optimize settings audit

| Setting | Code | UI | Default |
|---|---|---|---|
| Accelerator (auto/cpu/xnnpack/NNAPI) | SketchViewModel.kt `selectedAccel` ~577, `selectAccel` ~631; OnnxLineArtModel.kt `Accel` | Settings sheet "Processing" (MlSettingsSheet) radio list | xnnpack |
| NNAPI opt-in warning | SketchViewModel.kt `KEY_NNAPI_ACK` 164, `nnapiWarningPending` ~626 | dialog when NNAPI radio picked | not acknowledged |
| NNAPI size cap | OnnxLineArtModel.kt `NNAPI_MAX_PIXELS` 621 (512x512) | none | fixed |
| Speed test | SketchViewModel.kt `runBenchmark` ~670 | "Test speed on this phone" button | manual |
| GPU reveal | look/ArrivalReveal.kt `enabled` (not persisted) | Settings > Speed & quality switch (API 33+) | on |
| Preview quality | ui/canvas/PostPreview.kt:79 `MAX_SIDE` | none | 720 px |
| Model input size | SketchPipeline.kt:650 `modelSide(detail)` | indirect via Detail slider | 768 (1024 if detail > 0.7) |
| Working size | PipelineSettings.kt:27 `workingSize` | none (now preset) | 1024 |
| Max strokes | PipelineSettings.kt:26 `maxStrokes`; used SketchPipeline.kt:221,698, ShadingGenerator.kt:98,379 | none (now "Simplify strokes") | 4000 |
| Stroke simplification | LineArtVectorizer.kt:68,111 hard-coded RDP epsilon 0.65 / 0.5; StrokeGenerator.kt:79 `rdpEpsilon` 1.2; VectorTrace.kt:22 `EPSILON` 1.0 | none | fixed |
| Export bitrate | export/VideoExporter.kt:159 and :289, `w*h*5` clamped 1.5-12 Mbps | none | factor 5 |
| Export image max side | export/Renderer.kt:387 `MAX_SIDE` 4096 | none | fixed |
| Cache clearing | none before; now `clearCacheAndModels` in SketchViewModel (speed & quality region) | Settings > "Clear model & cache files" | n/a |
| Downloaded models dir | ModelManager.kt:21 `filesDir/models` | model pickers | n/a |

## New (this change)
- Preset (Fast / Balanced / Best) + "Simplify strokes" slider; prefs `speed_preset`, `simplify_level` in the ML prefs file (`OnnxLineArtModel.PREFS`).
- Table: ui/settings/SpeedQuality.kt. Balanced = 1024 / 720 / 4000 / bitrate x5 (unit-tested).
- Wired now: workingSize and maxStrokes (via `updatePipelineSettings`).
- Not wired yet (need one line in files owned by others): `PostPreview.MAX_SIDE` -> `SpeedQuality.previewMaxSide`; VideoExporter lines 159/289 `* 5` -> `* SpeedQuality.bitrateFactor`.
- Slider maps to maxStrokes (40-100% of the preset), as the pipeline has no tolerance parameter.
