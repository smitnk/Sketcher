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

## MIT License

The text below applies to items 1 and 2. The copyright lines for each are given in their sections above.

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
