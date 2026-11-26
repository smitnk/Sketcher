# Input photo size and crop

Where a picked photo is shrunk or cropped before it becomes the canvas. Paths are under
`app/src/main/java/com/smitnk/sketcher/`.

## Automatic (InputFrame.auto = true, today's behaviour)

1. **Decode**: `image/ImageLoader.kt:30` `load(uri, maxSide)`. Uses `inSampleSize` until the decode is at most
   2x `maxSide` (`:38`), then scales so the long side is `maxSide` (`:57`). `maxSide` is
   `GeneratorSettings.maxSide = 1024` (`image/StrokeGenerator.kt:81`). Demo/test images: `loadAsset` (`:16`).
2. **Working size**: `SketchPipeline.run` downscales to `PipelineSettings.workingSize = 1024`
   (`image/pipeline/PipelineSettings.kt:33`, `image/pipeline/SketchPipeline.kt:72`). The drawing (and the canvas)
   is this size.
3. **SubjectCrop** (`image/pipeline/SketchPipeline.kt:77`): finds the subject rectangle and a backdrop mask. It only
   limits where lines are looked for; it never shrinks the canvas (the drawing stays `pw x ph`).
4. **AI models** run on the subject crop at their own long side and are mapped back to the working size:
   `SketchPipeline.modelSide` 768 / 1024 at high detail (`:674`), cartoon 768 (`:677`), never more than 2x the crop
   (`:697`). `OnnxLineArtModel.longSide` default 768 (`image/pipeline/OnnxLineArtModel.kt:75`, `:604`), capped by
   `ModelSpec.maxPixels = 768*768` (`image/models/ModelSpec.kt:36`).
5. **FlowLines** runs at most 768 on the long side and upsamples (`image/pipeline/FlowLines.kt:30`, `:50`).

## Chosen size (InputFrame.auto = false)

- `ui/SketchViewModel.kt` `loadImage` (`:1230`) shows the "Adjust photo" step (preview <= 1280) unless
  `skipAdjustStep`.
- `decodeSide` (`:1250`) decodes just enough for the crop and long side, max 2048 (still `inSampleSize`).
- `applyFrame` (`:1257`) crops by the normalised rect and scales to `InputFrame.canvasSize` - this is the canvas.
- `generate` passes `workingSize = canvas long side` (`:1604`), so step 2 does not shrink it again. Models keep
  their own safe long side (step 4); their ink is mapped to the canvas. Above 1440 a `SketcherFrame` warning is logged.
- The choice is saved per project (ProjectCodec v7) and as the remembered default (ML prefs `input_frame`).
