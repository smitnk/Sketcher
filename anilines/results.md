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
| astronaut.jpg | 566 | 172 | 1736 | 1631 |
| beard.jpg | 552 | 160 | 811 | 801 |
| cartoon.jpg | 506 | 151 | 1136 | 1130 |
| chelsea.jpg | 538 | 155 | 1035 | 1026 |
| coffee.jpg | 505 | 152 | 1066 | 1024 |
| elf.jpg | 552 | 168 | 1213 | 1144 |
| person1.jpg | 535 | 163 | 1331 | 1294 |
| person2.jpg | 512 | 163 | 1069 | 1020 |
| rocket.jpg | 514 | 153 | 1029 | 1027 |
| toy_grass.jpg | 510 | 164 | 1557 | 1521 |
| tpose.jpg | 512 | 148 | 1514 | 1509 |
