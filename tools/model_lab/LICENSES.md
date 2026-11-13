# Licences

- Model code (`models.py`): copied from controlnet_aux (https://github.com/patrickvonplaten/controlnet_aux), Apache-2.0.
  Original architectures: Generator = Informative Drawings / Photo-Sketching style (Chan et al.), UnetGenerator = pix2pix (Isola et al.).
- Weights: https://huggingface.co/lllyasviel/Annotators (sk_model.pth, sk_model2.pth, netG.pth). The HF repo is tagged
  `openrail` (CreativeML OpenRAIL-M: use-based restrictions, redistribution allowed with the same restrictions). Check the
  model card before shipping; netG.pth (anime line art) originates from github.com/Mukosame/Anime2Sketch (check its licence too).
- Converted ONNX files are derivatives of those weights and inherit their licence terms.
