"""AniLines (MIT, zhenglinpan) lab: export basic/detail to ONNX fp16 (dynamic H/W), compare vs informative-drawings and Anime2Sketch.
Input size: fully-conv U-Net with 4 poolings -> H/W must be multiples of 16 (reflect-pad, crop after). Model I/O:
  basic : 3ch RGB 0..1 (PIL sharpness x6 first) -> 1ch;  detail: 2ch [gray, 255-normalized sobel] 0..1 -> 1ch. Output ~0..1, white=paper."""
import sys, os, time, json, hashlib
sys.path.insert(0, "/tmp/AL"); sys.path.insert(1, "/tmp/A2S")
import numpy as np, cv2, torch, onnx, onnxruntime as ort
from PIL import Image, ImageDraw, ImageEnhance
from onnxconverter_common import float16
from network.line_extractor import LineExtractor
ROOT = os.getcwd()
os.chdir("/tmp/A2S"); import model as M; a2s_net = M.create_model("default").eval(); os.chdir(ROOT)
IMGS = "app/src/debug/assets/testimages"; OUT = "out_anilines"; os.makedirs(OUT, exist_ok=True)
LONG = 768  # analysis long side; AniLines advises ~1080p, lowered for CPU CI time
res = {}

def export(net, name, cin):
    p = f"{OUT}/{name}_fp32.onnx"
    torch.onnx.export(net, torch.zeros(1, cin, 256, 256), p, input_names=["input"], output_names=["output"], opset_version=17, dynamo=False,
                      dynamic_axes={"input": {2: "h", 3: "w"}, "output": {2: "h", 3: "w"}})
    m = float16.convert_float_to_float16(onnx.load(p), keep_io_types=True)
    q = f"{OUT}/{name}.onnx"; onnx.save(m, q); os.remove(p)
    return q

sess = {}
for mode, cin in [("basic", 3), ("detail", 2)]:
    net = LineExtractor(cin, 1, True)
    net.load_state_dict(torch.load(f"/tmp/AL/weights/{mode}.pth", map_location="cpu", weights_only=True)); net.eval()
    final = export(net, f"anilines_{mode}", cin)
    s = ort.InferenceSession(final); sess[mode] = s
    y = s.run(None, {"input": np.zeros((1, cin, 512, 768), np.float32)})[0]  # 768 wide x 512 high dynamic check
    b = open(final, "rb").read()
    res[mode] = {"bytes": len(b), "sha256": hashlib.sha256(b).hexdigest(), "dyn_512x768_out": list(y.shape), "ms": []}
a2s_p = f"{OUT}/_a2s.onnx"
torch.onnx.export(a2s_net, torch.zeros(1, 3, 512, 512), a2s_p, input_names=["input"], output_names=["output"], opset_version=17, dynamo=False,
                  dynamic_axes={"input": {2: "h", 3: "w"}, "output": {2: "h", 3: "w"}})
a2s_s = ort.InferenceSession(a2s_p)
inf = ort.InferenceSession("app/src/main/assets/models/lineart.onnx")
res["a2s"] = {"ms": []}; res["informative"] = {"ms": [], "bytes": os.path.getsize("app/src/main/assets/models/lineart.onnx")}

def pad16(x):
    H, W = x.shape[2:]
    return np.pad(x, ((0, 0), (0, 0), (0, (-H) % 16), (0, (-W) % 16)), mode="reflect"), H, W

def run_al(mode, im):
    w, h = im.size; k = LONG / max(w, h)
    im = im.convert("RGB").resize((max(16, round(w * k)), max(16, round(h * k))), Image.LANCZOS)
    if mode == "basic":
        x = np.asarray(ImageEnhance.Sharpness(im).enhance(6.0), np.float32).transpose(2, 0, 1)[None] / 255.
    else:
        g = cv2.cvtColor(np.asarray(im), cv2.COLOR_RGB2GRAY)
        sb = cv2.magnitude(cv2.Sobel(g, cv2.CV_64F, 1, 0, ksize=3), cv2.Sobel(g, cv2.CV_64F, 0, 1, ksize=3))
        sb = 255 - cv2.normalize(sb, None, 0, 255, cv2.NORM_MINMAX, cv2.CV_8UC1)
        x = np.stack([g, sb])[None].astype(np.float32) / 255.
    xp, H, W = pad16(x)
    t = time.time(); y = sess[mode].run(None, {"input": xp.astype(np.float32)})[0][0, 0, :H, :W]; dt = time.time() - t
    return y, dt

def gray(y): return Image.fromarray((np.clip(y, 0, 1) * 255 + .5).astype(np.uint8))
def run_fixed(s, im, a2s):
    x = np.asarray(im.convert("RGB").resize((512, 512), Image.BICUBIC), np.float32)
    x = x / 127.5 - 1 if a2s else x / 255
    t = time.time(); y = s.run(None, {s.get_inputs()[0].name: x.transpose(2, 0, 1)[None]})[0][0, 0]; dt = time.time() - t
    y = (y + 1) / 2 if a2s else y
    return gray(y), dt

names = [f for f in sorted(os.listdir(IMGS)) if f.lower().endswith((".jpg", ".png"))]
cols = ["input", "informative", "Anime2Sketch", "AL basic", "AL detail", "basic bin.5", "detail bin.5"]
W = 256; sheet = Image.new("RGB", (W * len(cols), (W + 18) * len(names) + 22), "white"); d = ImageDraw.Draw(sheet)
for c, n in enumerate(cols): d.text((c * W + 6, 4), n, fill="black")
per = {}
for r, f in enumerate(names):
    im = Image.open(f"{IMGS}/{f}"); outs = [im.convert("RGB")]; row = {}
    o, dt = run_fixed(inf, im, False); outs.append(o); res["informative"]["ms"].append(round(dt * 1000)); row["informative"] = round(dt * 1000)
    o, dt = run_fixed(a2s_s, im, True); outs.append(o); res["a2s"]["ms"].append(round(dt * 1000)); row["a2s"] = round(dt * 1000)
    ys = {}
    for m in ["basic", "detail"]:
        y, dt = run_al(m, im); ys[m] = y; outs.append(gray(y)); res[m]["ms"].append(round(dt * 1000)); row[m] = round(dt * 1000)
    for m in ["basic", "detail"]: outs.append(gray((ys[m] > 0.5).astype(np.float32)))
    per[f] = row
    for c, o in enumerate(outs):
        o = o.convert("RGB"); o.thumbnail((W, W)); sheet.paste(o, (c * W, 22 + r * (W + 18)))
    d.text((6, 22 + r * (W + 18) + W + 2), f, fill="black")
sheet.save(f"{OUT}/anilines_sheet.jpg", quality=88)
os.remove(a2s_p)
with open(f"{OUT}/results.md", "w") as fh:
    fh.write(f"# AniLines lab\n\nCPU (GitHub runner), AniLines long side {LONG}px (pad to x16), informative/A2S 512x512. Sheet: anilines_sheet.jpg\n\n")
    fh.write("## ONNX (fp16, dynamic H/W, 768x512 ok)\n\n| file | bytes | sha256 | 512x768 (HxW) out |\n|---|---|---|---|\n")
    for m in ["basic", "detail"]:
        fh.write(f"| anilines_{m}.onnx | {res[m]['bytes']} | {res[m]['sha256']} | {res[m]['dyn_512x768_out']} |\n")
    fh.write(f"\ninformative-drawings lineart.onnx bytes: {res['informative']['bytes']}\n\n## Per-image ms\n\n| image | informative | a2s | AL basic | AL detail |\n|---|---|---|---|---|\n")
    for f, r in per.items(): fh.write(f"| {f} | {r['informative']} | {r['a2s']} | {r['basic']} | {r['detail']} |\n")
json.dump(res, open(f"{OUT}/results.json", "w"), indent=1); print(open(f"{OUT}/results.md").read())
