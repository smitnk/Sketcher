"""Build and judge deployment variants of the four line-art models.

For realistic / coarse / informative / anime:
  fp32          the exported ONNX (reference; informative = the app's bundled asset)
  int8          onnxruntime.quantization.quantize_dynamic, per-channel, QInt8 weights
  fp32.ort/int8.ort   python -m onnxruntime.tools.convert_onnx_models_to_ort (arm target, Fixed style)
Quality: mean |ink_variant - ink_fp32| over the test images (long side 512, anime 512x768 multiples), plus a contact sheet.
Speed: best-of-N CPU-EP time on THIS runner (x86; only a relative hint, the phone is the real judge).
Output: out/variants/*.onnx|ort, out/variants/variants.json (verdict per file), out/variants/sheets/*.png.
Verdict "pass" = mean abs diff <= MAX_MEAN_DIFF and 99.9th-percentile diff <= MAX_P999 on every image.
Usage: python variants.py [images_dir] [models_dir] [out_dir]
"""
import glob, json, os, subprocess, sys, time
import cv2
import numpy as np
import onnxruntime as ort
from onnxruntime.quantization import QuantType, quantize_dynamic

HERE = os.path.dirname(os.path.abspath(__file__))
sys.argv = sys.argv[:1] + sys.argv[1:]  # evaluate.py reads argv at import time with its own defaults
IMG_DIR = sys.argv[1] if len(sys.argv) > 1 else "app/src/debug/assets/testimages"
MODEL_DIR = sys.argv[2] if len(sys.argv) > 2 else "out"
OUT = os.path.join(sys.argv[3] if len(sys.argv) > 3 else "out", "variants")
sys.path.insert(0, HERE)
import evaluate as ev  # noqa: E402  (reuses resize_long / run / label)

APP_MODEL = "app/src/main/assets/models/lineart.onnx"
MAX_MEAN_DIFF = float(os.environ.get("MAX_MEAN_DIFF", "0.02"))
MAX_P999 = float(os.environ.get("MAX_P999", "0.35"))
REPEATS = int(os.environ.get("REPEATS", "3"))
LONG = int(os.environ.get("LONG", "512"))
# name -> (fp32 source, norm, size multiple)
BASES = {
    "informative": (APP_MODEL, "01", 8),
    "realistic": (f"{MODEL_DIR}/lineart_realistic.onnx", "01", 8),
    "coarse": (f"{MODEL_DIR}/lineart_coarse.onnx", "01", 8),
    "anime": (f"{MODEL_DIR}/lineart_anime.onnx", "pm1", 256),
}


def mb(p):
    return os.path.getsize(p) / 1e6


def session(path):
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    so.add_session_config_entry("session.intra_op.allow_spinning", "0")
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])


def build():
    os.makedirs(OUT, exist_ok=True)
    files = {}
    for name, (src, _, _) in BASES.items():
        if not os.path.exists(src):
            print("skip (missing)", name, src)
            continue
        fp32 = f"{OUT}/{name}_fp32.onnx"
        subprocess.run(["cp", src, fp32], check=True)
        files[(name, "fp32")] = fp32
        i8 = f"{OUT}/{name}_int8.onnx"
        try:
            quantize_dynamic(fp32, i8, per_channel=True, weight_type=QuantType.QInt8)
            session(i8)  # must at least load
            files[(name, "int8")] = i8
        except Exception as e:  # unsupported op combos: report, keep going
            print(f"int8 FAILED {name}: {e}", flush=True)
            if os.path.exists(i8):
                os.remove(i8)
    for (name, kind), p in list(files.items()):
        d = f"{OUT}/ort_{name}_{kind}"
        os.makedirs(d, exist_ok=True)
        subprocess.run(["cp", p, d], check=True)
        r = subprocess.run([sys.executable, "-m", "onnxruntime.tools.convert_onnx_models_to_ort", d,
                            "--optimization_style", "Fixed", "--target_platform", "arm"], capture_output=True, text=True)
        o = f"{d}/{os.path.basename(p)[:-5]}.ort"
        if r.returncode == 0 and os.path.exists(o):
            final = f"{OUT}/{name}_{kind}.ort"
            os.replace(o, final)
            files[(name, kind + ".ort")] = final
        else:
            print(f"ort FAILED {name} {kind}: {r.stderr[-300:]}", flush=True)
    return files


def main():
    files = build()
    imgs = sorted(glob.glob(f"{IMG_DIR}/*.jpg") + glob.glob(f"{IMG_DIR}/*.png"))
    os.makedirs(f"{OUT}/sheets", exist_ok=True)
    report = {}
    sess = {}
    for (name, kind), p in files.items():
        try:
            sess[(name, kind)] = session(p)
        except Exception as e:  # e.g. arm-optimised .ort that x86 cannot run: not verifiable here
            report[os.path.basename(p)] = {"model": name, "kind": kind, "mb": round(mb(p), 2), "verdict": "unverified", "note": str(e)[:200]}
    for name, (_, norm, mult) in BASES.items():
        if (name, "fp32") not in sess:
            continue
        stats = {k: {"diffs": [], "p999": [], "ms": []} for (n, k) in sess if n == name}
        tiles = {}
        for path in imgs:
            stem = os.path.splitext(os.path.basename(path))[0]
            rgb = cv2.cvtColor(cv2.imread(path), cv2.COLOR_BGR2RGB)
            src = ev.resize_long(rgb, LONG)
            ref, ms_ref, _ = ev.run(sess[(name, "fp32")], src, norm, mult)
            for kind in stats:
                ink, ms, _ = ev.run(sess[(name, kind)], src, norm, mult) if kind != "fp32" else (ref, ms_ref, None)
                d = np.abs(ink - ref)
                stats[kind]["diffs"].append(float(d.mean()))
                stats[kind]["p999"].append(float(np.percentile(d, 99.9)))
                stats[kind]["ms"].append(ms)
                if stem in ("elf", "beard", "cartoon"):
                    t = cv2.cvtColor(cv2.resize((255 - ink * 255).astype(np.uint8), (256, int(256 * ink.shape[0] / ink.shape[1]))), cv2.COLOR_GRAY2RGB)
                    tiles.setdefault(stem, []).append(ev.label(t, f"{name} {kind} {ms:.0f}ms d={d.mean():.4f}"))
        for kind, s in stats.items():
            p = files[(name, kind)]
            mean, p999 = float(np.mean(s["diffs"])), float(np.max(s["p999"]))
            ok = kind == "fp32" or (mean <= MAX_MEAN_DIFF and p999 <= MAX_P999)
            report[os.path.basename(p)] = {
                "model": name, "kind": kind, "mb": round(mb(p), 2), "mean_abs_diff": round(mean, 5),
                "p999_diff": round(p999, 4), "cpu_ms_runner": round(float(np.mean(s["ms"])), 1),
                "verdict": "pass" if ok else "fail", "publishable_branch": bool(ok and mb(p) < 95),
            }
        for stem, row in tiles.items():
            h = max(t.shape[0] for t in row)
            row = [cv2.copyMakeBorder(t, 0, h - t.shape[0], 0, 0, cv2.BORDER_CONSTANT, value=(255, 255, 255)) for t in row]
            cv2.imwrite(f"{OUT}/sheets/variants_{name}_{stem}.png", cv2.cvtColor(np.hstack(row), cv2.COLOR_RGB2BGR))
    json.dump(report, open(f"{OUT}/variants.json", "w"), indent=1)
    for k, v in sorted(report.items()):
        print(k, v, flush=True)


if __name__ == "__main__":
    main()
