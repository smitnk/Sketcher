"""Run all ONNX line-art models on test images; write ink renders, contact sheets, timings.csv.

Usage: python evaluate.py [images_dir] [models_dir] [out_dir]
Polarity is auto-detected (the background is the majority, so ink = whichever side the median
pixel is NOT on) and recorded in timings.csv so the app's InkPolarity can be checked.
"""
import csv, glob, os, sys, time
import cv2
import numpy as np
import onnxruntime as ort

IMG_DIR = sys.argv[1] if len(sys.argv) > 1 else "app/src/debug/assets/testimages"
MODEL_DIR = sys.argv[2] if len(sys.argv) > 2 else "out"
OUT = sys.argv[3] if len(sys.argv) > 3 else "out"
APP_MODEL = "app/src/main/assets/models/lineart.onnx"
SIZES = [512, 768, 1024]
REPEATS = int(os.environ.get("REPEATS", "1"))

MODELS = [("informative(app)", APP_MODEL, "01", 8)]
for n, k in [("realistic", "lineart_realistic"), ("coarse", "lineart_coarse")]:
    MODELS.append((n, f"{MODEL_DIR}/{k}.onnx", "01", 8))
    MODELS.append((n + "-fp16", f"{MODEL_DIR}/{k}_fp16.onnx", "01", 8))
MODELS.append(("anime", f"{MODEL_DIR}/lineart_anime.onnx", "pm1", 256))
MODELS.append(("anime-fp16", f"{MODEL_DIR}/lineart_anime_fp16.onnx", "pm1", 256))


def resize_long(img, long_side):
    h, w = img.shape[:2]
    s = long_side / max(h, w)
    return cv2.resize(img, (max(1, round(w * s)), max(1, round(h * s))),
                      interpolation=cv2.INTER_AREA if s < 1 else cv2.INTER_CUBIC)


def subject_crop(img, tol=12, margin=0.06):
    h, w = img.shape[:2]
    corners = np.array([img[0, 0], img[0, -1], img[-1, 0], img[-1, -1]], dtype=np.float32)
    bg = np.median(corners, axis=0)
    mask = np.abs(img.astype(np.float32) - bg).max(axis=2) > tol
    mask = cv2.morphologyEx(mask.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    ys, xs = np.where(mask > 0)
    if len(ys) == 0:
        return img
    y0, y1, x0, x1 = ys.min(), ys.max(), xs.min(), xs.max()
    my, mx = int((y1 - y0) * margin), int((x1 - x0) * margin)
    return img[max(0, y0 - my):min(h, y1 + my + 1), max(0, x0 - mx):min(w, x1 + mx + 1)]


def run(sess, img, norm, mult):
    """Resize to multiple of `mult` (as the app must), infer, resize back. Returns ink 0..1 (1=ink), ms, polarity."""
    h, w = img.shape[:2]
    H, W = int(np.ceil(h / mult) * mult), int(np.ceil(w / mult) * mult)
    x = cv2.resize(img, (W, H), interpolation=cv2.INTER_CUBIC) if (H, W) != (h, w) else img
    x = x.astype(np.float32) / (255.0 if norm == "01" else 127.5)
    if norm == "pm1":
        x -= 1.0
    x = np.ascontiguousarray(x.transpose(2, 0, 1)[None])
    name = sess.get_inputs()[0].name
    ts = []
    for _ in range(REPEATS):
        t = time.perf_counter()
        y = sess.run(None, {name: x})[0][0, 0].astype(np.float32)
        ts.append((time.perf_counter() - t) * 1000)
    if norm == "pm1":
        y = y * 0.5 + 0.5
    y = np.clip(y, 0, 1)
    pol = "ink_high" if np.median(y) < 0.5 else "ink_low"
    ink = y if pol == "ink_high" else 1 - y
    ink = cv2.resize(ink, (w, h), interpolation=cv2.INTER_CUBIC) if (H, W) != (h, w) else ink
    return np.clip(ink, 0, 1), min(ts), pol


def label(tile, text):
    bar = np.full((22, tile.shape[1], 3), 255, np.uint8)
    cv2.putText(bar, text, (3, 15), cv2.FONT_HERSHEY_SIMPLEX, 0.42, (0, 0, 0), 1, cv2.LINE_AA)
    return np.vstack([bar, tile])


def main():
    os.makedirs(f"{OUT}/sheets", exist_ok=True)
    os.makedirs(f"{OUT}/renders", exist_ok=True)
    sessions = {}
    so = ort.SessionOptions()
    for n, p, norm, mult in MODELS:
        if not os.path.exists(p):
            print("skip (missing)", p)
            continue
        try:
            sessions[n] = (ort.InferenceSession(p, so, providers=["CPUExecutionProvider"]), norm, mult)
        except Exception as e:
            print("cannot load", n, e)
    rows = []
    for path in sorted(glob.glob(f"{IMG_DIR}/*.jpg") + glob.glob(f"{IMG_DIR}/*.png")):
        stem = os.path.splitext(os.path.basename(path))[0]
        rgb = cv2.cvtColor(cv2.imread(path), cv2.COLOR_BGR2RGB)
        variants = [("full", rgb)]
        if stem == "elf":
            variants.append(("crop", subject_crop(rgb)))
        for vname, vimg in variants:
            cols = []
            for n, (s, norm, mult) in sessions.items():
                col = []
                for sz in SIZES:
                    src = resize_long(vimg, sz)
                    try:
                        ink, ms, pol = run(s, src, norm, mult)
                    except Exception as e:
                        print("fail", n, stem, sz, e)
                        continue
                    out = (255 - ink * 255).astype(np.uint8)
                    tag = f"{stem}_{vname}_{n}_{sz}"
                    cv2.imwrite(f"{OUT}/renders/{tag}.png", out)
                    rows.append([stem, vname, n, sz, src.shape[1], src.shape[0], f"{ms:.0f}", pol])
                    tile = cv2.cvtColor(cv2.resize(out, (384, int(384 * out.shape[0] / out.shape[1]))), cv2.COLOR_GRAY2RGB)
                    col.append(label(tile, f"{n} {sz}px {ms:.0f}ms"))
                    print(tag, f"{ms:.0f}ms", pol, flush=True)
                if col:
                    cols.append(col)
            if not cols:
                continue
            # sheet: one row per size, one column per model; also a source column
            nrows = max(len(c) for c in cols)
            th = max(t.shape[0] for c in cols for t in c)
            src_t = cv2.resize(vimg, (384, int(384 * vimg.shape[0] / vimg.shape[1])))
            grid = []
            for r in range(nrows):
                tiles = [label(src_t, "source")] + [c[r] if r < len(c) else np.full((th, 384, 3), 255, np.uint8) for c in cols]
                tiles = [cv2.copyMakeBorder(t, 0, th - t.shape[0], 0, 0, cv2.BORDER_CONSTANT, value=(255, 255, 255)) for t in tiles]
                grid.append(np.hstack(tiles))
            cv2.imwrite(f"{OUT}/sheets/{stem}_{vname}.png", cv2.cvtColor(np.vstack(grid), cv2.COLOR_RGB2BGR))
    with open(f"{OUT}/timings.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["image", "variant", "model", "long_side", "w", "h", "cpu_ms", "polarity"])
        w.writerows(rows)


if __name__ == "__main__":
    main()
