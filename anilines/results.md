# AniLines lab

CPU (GitHub runner), AniLines long side 768px (pad to x16), informative/A2S 512x512. Sheet: anilines_sheet.jpg

## ONNX (fp16, dynamic H/W, 768x512 ok)

| file | bytes | sha256 | 512x768 (HxW) out |
|---|---|---|---|
| anilines_basic.onnx | 34570036 | 55e0514fb3a9f63e28c939513ec56300e09a680e39c121269747cf9882fb9c98 | [1, 1, 512, 768] |
| anilines_detail.onnx | 34568884 | 726777f3335175e25679c4d8e6e3e53c5abf96afd54cad2e8e9f01f8a085077b | [1, 1, 512, 768] |

informative-drawings lineart.onnx bytes: 17193338

## Per-image ms

| image | informative | a2s | AL basic | AL detail |
|---|---|---|---|---|
| astronaut.jpg | 996 | 347 | 3416 | 3492 |
| beard.jpg | 1012 | 319 | 1735 | 1786 |
| cartoon.jpg | 989 | 331 | 2487 | 2469 |
| chelsea.jpg | 994 | 365 | 2249 | 2245 |
| coffee.jpg | 991 | 317 | 2246 | 2257 |
| elf.jpg | 994 | 329 | 2475 | 2479 |
| person1.jpg | 974 | 357 | 2813 | 2806 |
| person2.jpg | 958 | 349 | 2249 | 2254 |
| rocket.jpg | 1003 | 321 | 2273 | 2237 |
| toy_grass.jpg | 992 | 336 | 3329 | 3288 |
| tpose.jpg | 986 | 324 | 3188 | 3103 |
