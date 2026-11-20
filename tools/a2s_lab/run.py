"""Anime2Sketch (MIT, Mukosame) lab: export ONNX (default + improved), compare vs bundled informative-drawings."""
import sys, os, time, json
sys.path.insert(0, "/tmp/A2S")
import numpy as np, torch, onnxruntime as ort
from PIL import Image, ImageDraw
os.chdir("/tmp/A2S"); import model as M; os.chdir(os.environ["GITHUB_WORKSPACE"])
IMGS = "app/src/debug/assets/testimages"; OUT = "out_a2s"; os.makedirs(OUT, exist_ok=True)
S = 512
res = {}
def export(kind):
    cwd = os.getcwd(); os.chdir("/tmp/A2S"); net = M.create_model(kind).eval(); os.chdir(cwd)
    p = f"{OUT}/anime2sketch_{kind}.onnx"
    torch.onnx.export(net, torch.zeros(1, 3, S, S), p, input_names=["input"], output_names=["output"], opset_version=17)
    from onnxconverter_common import float16
    import onnx
    m16 = float16.convert_float_to_float16(onnx.load(p), keep_io_types=True)
    onnx.save(m16, p.replace(".onnx", "_fp16.onnx"))
    return p
def a2s(sess, img):
    x = np.asarray(img.convert("RGB").resize((S, S), Image.BICUBIC), np.float32) / 127.5 - 1
    t = time.time(); y = sess.run(None, {"input": x.transpose(2, 0, 1)[None]})[0][0, 0]; dt = time.time() - t
    return Image.fromarray(((y + 1) * 127.5).clip(0, 255).astype(np.uint8)), dt
def info(sess, img):
    x = np.asarray(img.convert("RGB").resize((S, S), Image.BICUBIC), np.float32) / 255
    n = sess.get_inputs()[0].name
    t = time.time(); y = sess.run(None, {n: x.transpose(2, 0, 1)[None]})[0][0, 0]; dt = time.time() - t
    return Image.fromarray((y * 255).clip(0, 255).astype(np.uint8)), dt
sessions = {}
for k in ["default", "improved"]:
    try:
        p = export(k)
        sessions[k] = ort.InferenceSession(p.replace(".onnx", "_fp16.onnx"))
        res[k] = {"mb_fp16": os.path.getsize(p.replace(".onnx", "_fp16.onnx")) / 1e6}
    except Exception as e:
        res[k] = {"error": repr(e)}
inf = ort.InferenceSession("app/src/main/assets/models/lineart.onnx")
names = [f for f in sorted(os.listdir(IMGS)) if f.endswith(".jpg")]
cols = ["photo", "informative"] + list(sessions)
W = 300; sheet = Image.new("RGB", (W * len(cols), (W + 20) * len(names) + 24), "white")
d = ImageDraw.Draw(sheet)
for c, n in enumerate(cols): d.text((c * W + 6, 4), n, fill="black")
for r, f in enumerate(names):
    im = Image.open(f"{IMGS}/{f}")
    outs = [im.convert("RGB"), info(inf, im)[0]]
    for k, s in sessions.items():
        o, dt = a2s(s, im); outs.append(o); res[k].setdefault("ms", []).append(round(dt * 1000))
    for c, o in enumerate(outs):
        o = o.convert("RGB").resize((W, W)); sheet.paste(o, (c * W, 24 + r * (W + 20)))
    d.text((6, 24 + r * (W + 20) + W + 2), f, fill="black")
sheet.save(f"{OUT}/a2s_sheet.jpg", quality=88)
json.dump(res, open(f"{OUT}/a2s.json", "w"), indent=1); print(json.dumps(res))
