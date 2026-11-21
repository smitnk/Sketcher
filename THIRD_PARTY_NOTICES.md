# Third-party notices

Sketcher uses the components below. The same text ships inside the app as
`app/src/main/assets/licenses.txt` and is shown on the About screen.

## 1. informative-drawings line-art model

- File: `app/src/main/assets/models/lineart.onnx`
- Paper: Chan, Durand, Isola. "Learning to generate line drawings that convey geometry and semantics". CVPR 2022.
- Code: https://github.com/carolineec/informative-drawings
- Copyright (c) 2022 Caroline Chan
- Licence: MIT (text below)
- ONNX export: josephrocca, https://huggingface.co/rocca/informative-drawings-line-art-onnx

## 2. ONNX Runtime (`onnxruntime-android` 1.20.0)

- Copyright (c) Microsoft Corporation
- Licence: MIT (text below)
- https://github.com/microsoft/onnxruntime

## 3. AndroidX / Jetpack libraries

core-ktx, activity-compose, lifecycle-viewmodel, exifinterface, test libraries.

- Copyright The Android Open Source Project
- Licence: Apache License 2.0, https://www.apache.org/licenses/LICENSE-2.0

## 4. Jetpack Compose

ui, foundation, material3, runtime and the Compose BOM.

- Copyright The Android Open Source Project
- Licence: Apache License 2.0, https://www.apache.org/licenses/LICENSE-2.0

## 5. Kotlin and kotlinx.coroutines

- Copyright JetBrains s.r.o. and contributors
- Licence: Apache License 2.0, https://www.apache.org/licenses/LICENSE-2.0
- https://github.com/Kotlin/kotlinx.coroutines

## 6. Material Icons (`material-icons-extended`)

- Copyright Google LLC
- Licence: Apache License 2.0, https://www.apache.org/licenses/LICENSE-2.0

## 7. Test images (debug builds only, `app/src/debug/assets/testimages/`)

Not included in release builds. Sources are from scikit-image sample data.

- astronaut.jpg: NASA Great Images (https://flic.kr/p/r9qvLn), Eileen Collins. "No known copyright restrictions, released into the public domain."
- coffee.jpg: photographer Rachel Michetti. "No copyright restrictions. CC0 by the photographer (Rachel Michetti)."
- chelsea.jpg: photographer Stefan van der Walt. "No copyright restrictions. CC0 by the photographer (Stefan van der Walt)."
- rocket.jpg: SpaceX Photos (Flickr). "captured by SpaceX and released in the public domain"
- cartoon.jpg: generated synthetic work, no restrictions.
- toy_grass.jpg: chelsea.jpg (CC0) with a procedural noisy grass texture added at the bottom.

## 8. Lemo-Opuscar (Look presets)

The Atkinson / Bayer / halftone post filters, paper texture and colour-fill misregistration are Kotlin ports of
techniques from the MIT-licensed JavaScript demos of Lemo-Opuscar. Its CC BY-NC style documents are not used.

- Copyright (c) 2026 Lemomo (LemoLab)
- Licence: MIT (full text at the end of this file)
- https://github.com/lemomo-ai/lemo-opuscar

## 9. Coherent-Line-Drawing (Flow lines engine)

`FlowLines.kt` is a Kotlin port of the C++ implementation of Kang, Lee, Chui, "Coherent Line Drawing" (NPAR 2007).
Ported from SSARCandy/Coherent-Line-Drawing (MIT, (c) 2019 HSU,SHU-HSUAN).

- Copyright (c) 2019 HSU,SHU-HSUAN
- Licence: MIT (same terms as the MIT text in this file)
- https://github.com/SSARCandy/Coherent-Line-Drawing

## 10. TEED, Tiny and Efficient Edge Detector (`models/teed.onnx`, "Fast edges" engine)

- Paper: Soria, Sappa, Vintimilla, Velesaca. "Tiny and Efficient Model for the Edge Detection Generalization". ICCV 2023 Workshops.
- Code and BIPED weights (epoch 5): https://github.com/xavysp/TEED
- Copyright (c) 2022 Xavier Soria Poma
- Licence: MIT (same terms as the MIT text below)
- ONNX export with baked-in preprocessing: `tools/model_lab/export_teed.py`

## 11. MTEED (optional download "Fast edges+", not bundled)

- TEED architecture with the Anyline weights `MTEED.pth` by TheMistoAI: https://huggingface.co/TheMistoAI/MistoLine (`Anyline/MTEED.pth`)
- Loader and pre/post-processing reference: ComfyUI-Anyline (MIT)
- Licence of the weights: **CreativeML OpenRAIL++, not MIT.** Use is subject to the OpenRAIL++ use-based restrictions (Attachment A of the licence, https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/blob/main/LICENSE.md). In summary you may not use the model to: violate any law; exploit, harm or endanger minors; generate or spread verifiably false information to harm others; generate or spread personal identifiable information that could harm an individual; defame, disparage or harass; make fully automated decisions that affect a person's legal rights; discriminate against or harm individuals or groups based on social behaviour or personal characteristics; exploit the vulnerabilities of a specific group; or for any use intended or likely to cause harm. These restrictions pass on to anyone who receives the model from you. The download is optional.

## 12. Anime2Sketch (optional download "Bold cartoon lines", not bundled)

- Anime2Sketch by Xiaoyu Xiang (Mukosame): https://github.com/Mukosame/Anime2Sketch ("improved" artifact-free weights, converted to ONNX fp16)
- Copyright (c) 2021 Xiaoyu Xiang
- Licence: MIT (same terms as the MIT text below). Used only by the Cartoon line engine; the download is optional.

## MIT License

The text below applies to items 1, 2 and 12. The copyright lines for each are given in their sections above.

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## MIT License (item 8: Lemo-Opuscar)

```
MIT License

Copyright (c) 2026 Lemomo (LemoLab)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the
following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
USE OR OTHER DEALINGS IN THE SOFTWARE.
```
