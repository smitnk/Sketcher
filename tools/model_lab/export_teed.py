"""Export TEED (MIT, xavysp/TEED) and optionally MTEED (OpenRAIL++, TheMistoAI/MistoLine) to ONNX.

Usage: python export_teed.py --ted-dir <TEED clone> --weights <file.pth> --out <file.onnx> [--mean bgr|none]

The exported graph takes RGB float32 0..1 NCHW (dynamic H/W, multiple of 8) like the app's other models and
bakes in the model's own preprocessing (x255, RGB->BGR, BGR mean subtraction) plus sigmoid on the output.
Output: 1x1xHxW edge probability, HIGH = edge (ink). --mode: fused (last head), avg (mean of sigmoids) or avglogit (sigmoid of mean logits). MTEED: --mean none --mode avglogit.
"""
import argparse, sys, os
import numpy as np
import torch

BIPED_MEAN_BGR = [103.939, 116.779, 123.68]


class Wrapped(torch.nn.Module):
    def __init__(self, ted, mean_bgr, mode):
        super().__init__()
        self.ted = ted
        self.mode = mode
        self.register_buffer("mean", torch.tensor(mean_bgr if mean_bgr else [0.0, 0.0, 0.0]).view(1, 3, 1, 1))

    def forward(self, x):
        x = x[:, [2, 1, 0]] * 255.0 - self.mean
        outs = self.ted(x)
        if self.mode == "avg":
            return torch.stack([torch.sigmoid(o) for o in outs], 0).mean(0)
        if self.mode == "avglogit":  # controlnet_aux TEEDdetector / Anyline: sigmoid(mean of the 4 logit maps)
            return torch.sigmoid(torch.stack(outs, 0).mean(0))
        return torch.sigmoid(outs[-1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ted-dir", required=True)
    ap.add_argument("--weights", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--mean", default="bgr", choices=["bgr", "none"])
    ap.add_argument("--mode", default="fused", choices=["fused", "avg", "avglogit"])
    a = ap.parse_args()
    sys.path.insert(0, a.ted_dir)
    import types  # ted.py imports utils.img_processing which needs kornia/tensorboard only for training helpers
    for n in ("kornia", "kornia.filters", "kornia.geometry"):
        sys.modules.setdefault(n, types.ModuleType(n))
    from ted import TED
    m = TED()
    sd = torch.load(a.weights, map_location="cpu")
    sd = {k.replace("module.", ""): v for k, v in sd.items()}
    m.load_state_dict(sd)
    m.eval()
    w = Wrapped(m, BIPED_MEAN_BGR if a.mean == "bgr" else None, a.mode).eval()
    x = torch.rand(1, 3, 256, 256)
    torch.onnx.export(w, x, a.out, opset_version=17, input_names=["input"], output_names=["output"],
                      dynamic_axes={"input": {2: "h", 3: "w"}, "output": {2: "h", 3: "w"}}, dynamo=False)
    import onnxruntime as ort
    s = ort.InferenceSession(a.out, providers=["CPUExecutionProvider"])
    for shape in [(256, 256), (320, 480), (200, 136)]:
        xi = torch.rand(1, 3, *shape)
        with torch.no_grad():
            ref = w(xi).numpy()
        got = s.run(None, {"input": xi.numpy()})[0]
        err = float(np.abs(ref - got).max())
        print(f"{os.path.basename(a.out)} {shape} out={got.shape} max|torch-onnx|={err:.2e}", flush=True)
        assert err < 1e-4, "mismatch"
    print(f"{a.out}: {os.path.getsize(a.out)} bytes")


if __name__ == "__main__":
    main()
