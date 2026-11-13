"""Download Annotators weights, export ONNX (fp32 + fp16), verify vs torch."""
import os, sys, urllib.request
import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from models import build_realistic, build_anime

BASE = "https://huggingface.co/lllyasviel/Annotators/resolve/main/"
OUT = os.environ.get("OUT", "out")
RANDOM = os.environ.get("RANDOM_WEIGHTS") == "1"  # dry-run without downloading


def fetch(name):
    os.makedirs("weights", exist_ok=True)
    p = os.path.join("weights", name)
    if not os.path.exists(p):
        print("downloading", name, flush=True)
        req = urllib.request.Request(BASE + name, headers={"User-Agent": "model-lab"})
        with urllib.request.urlopen(req) as r, open(p, "wb") as f:
            while True:
                b = r.read(1 << 20)
                if not b:
                    break
                f.write(b)
    return p


def load(builder, fname, anime=False):
    m = builder()
    if not RANDOM:
        ck = torch.load(fetch(fname), map_location="cpu")
        for k in list(ck.keys()):
            if "module." in k:
                ck[k.replace("module.", "")] = ck.pop(k)
        m.load_state_dict(ck)
    return m.eval()


def export(m, name, hw, anime):
    path = os.path.join(OUT, name + ".onnx")
    x = torch.rand(1, 3, *hw)
    if anime:
        x = x * 2 - 1
    # anime net: spatial size must be a multiple of 256 (8 stride-2 downs); we still mark
    # axes dynamic, caller must feed multiples of 256 (see evaluate.py).
    torch.onnx.export(m, x, path, opset_version=17, input_names=["input"], output_names=["output"],
                      dynamic_axes={"input": {2: "h", 3: "w"}, "output": {2: "h", 3: "w"}},
                      dynamo=False)
    import onnxruntime as ort
    s = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    for shape in [hw, (hw[0] + 256, hw[1] + 512)] if anime else [hw, (hw[0] + 40, hw[1] + 24)]:
        xi = torch.rand(1, 3, *shape)
        if anime:
            xi = xi * 2 - 1
        with torch.no_grad():
            ref = m(xi).numpy()
        got = s.run(None, {"input": xi.numpy()})[0]
        err = float(np.abs(ref - got).max())
        print(f"{name} {shape} max|torch-onnx|={err:.2e}", flush=True)
        assert err < 1e-3, f"mismatch {name}"
    # fp16
    import onnx
    from onnxconverter_common import float16
    mp = onnx.load(path)
    m16 = float16.convert_float_to_float16(mp, keep_io_types=True)
    p16 = os.path.join(OUT, name + "_fp16.onnx")
    onnx.save(m16, p16)
    try:
        s16 = ort.InferenceSession(p16, providers=["CPUExecutionProvider"])
        got16 = s16.run(None, {"input": xi.numpy()})[0]
        print(f"{name} fp16 max err={float(np.abs(ref - got16).max()):.2e}", flush=True)
    except Exception as e:  # fp16 may fail on some ops; report, keep going
        print(f"{name} fp16 FAILED to run: {e}", flush=True)
    return path, p16


def main():
    os.makedirs(OUT, exist_ok=True)
    jobs = [("lineart_realistic", build_realistic, "sk_model.pth", False, (256, 256)),
            ("lineart_coarse", build_realistic, "sk_model2.pth", False, (256, 256)),
            ("lineart_anime", build_anime, "netG.pth", True, (256, 256))]
    if os.environ.get("ONLY"):
        jobs = [j for j in jobs if j[0] in os.environ["ONLY"].split(",")]
    lines = []
    for name, b, f, anime, hw in jobs:
        export(load(b, f, anime), name, hw, anime)
    for fn in sorted(os.listdir(OUT)):
        if fn.endswith(".onnx"):
            lines.append(f"{fn}\t{os.path.getsize(os.path.join(OUT, fn)) / 1e6:.2f} MB")
    open(os.path.join(OUT, "sizes.txt"), "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
