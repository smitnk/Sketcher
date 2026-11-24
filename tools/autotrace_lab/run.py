"""Compare our LineArtVectorizer (Kotlin, run unmodified on a JVM) with `autotrace -centerline`.
usage: run.py <ci-results debug dir> <kotlin harness jar> <out dir>"""
import sys, os, glob, hashlib, struct, subprocess, time, json, re
import numpy as np, cv2
from scipy import ndimage
from skimage.morphology import skeletonize

SRC, JAR, OUT = sys.argv[1:4]
MAXN, THR = 8, 0.3
for d in ("k_in", "k_out", "sheets"): os.makedirs(f"{OUT}/{d}", exist_ok=True)

# ---- inputs: AI ink maps (0 paper .. 1 ink) dumped by the app on the emulator; dedupe identical maps
seen, names, inks = set(), [], {}
cands = sorted(glob.glob(SRC + "/*/ink_raw.png"), key=lambda p: ("informative" not in p, p))
for p in cands:
    raw = cv2.imread(p, cv2.IMREAD_UNCHANGED)
    if raw is None: continue
    ink = (1 - raw[..., 0].astype(np.float32) / 255).astype(np.float32)
    hh = hashlib.md5(ink.tobytes()).hexdigest()
    if hh in seen: continue
    seen.add(hh)
    n = os.path.basename(os.path.dirname(p)); names.append(n); inks[n] = ink
    if len(names) == MAXN: break
print("inputs:", names)
for n, ink in inks.items():
    h, w = ink.shape
    open(f"{OUT}/k_in/{n}.bin", "wb").write(struct.pack("<ii", w, h) + ink.tobytes())
subprocess.run(["java", "-jar", JAR, f"{OUT}/k_in", f"{OUT}/k_out"], check=True)

def parse_svg(path):
    txt = open(path).read(); strokes = []
    for d in re.findall(r'\sd="([^"]+)"', txt):
        toks = re.findall(r"[MmLlCcZz]|-?\d*\.?\d+(?:[eE][-+]?\d+)?", d); i = 0; cmd = None; cur = None; start = None; pts = []
        def flush():
            nonlocal pts
            if len(pts) >= 2: strokes.append(np.array(pts, np.float32))
            pts = []
        while i < len(toks):
            t = toks[i]
            if t.isalpha():
                cmd = t; i += 1
                if cmd in "Zz":
                    if start is not None and pts: pts.append(start)
                    flush()
                continue
            if cmd in "Mm":
                flush(); cur = np.array([float(toks[i]), float(toks[i + 1])]); i += 2; start = cur.copy(); pts = [cur.copy()]; cmd = "L" if cmd == "M" else "l"
            elif cmd in "Ll":
                p = np.array([float(toks[i]), float(toks[i + 1])]); i += 2
                cur = p if cmd == "L" else cur + p; pts.append(cur.copy())
            elif cmd in "Cc":
                q = np.array([float(x) for x in toks[i:i + 6]]).reshape(3, 2); i += 6
                if cmd == "c": q = q + cur
                for u in np.linspace(0, 1, 9)[1:]:
                    pts.append(((1 - u) ** 3) * cur + 3 * ((1 - u) ** 2) * u * q[0] + 3 * (1 - u) * u * u * q[1] + u ** 3 * q[2])
                cur = q[2]
            else: i += 1
        flush()
    return strokes

def poly_len(s): return float(np.linalg.norm(np.diff(s, axis=0), axis=1).sum())

def metrics(strokes, ink, off):
    """strokes in image-pixel coords; `off` converts them to integer pixel-centre coords"""
    H, W = ink.shape
    ints = [np.round(s - off).astype(np.int32).reshape(-1, 1, 2) for s in strokes]
    cl = np.zeros((H, W), np.uint8); acc = np.zeros((H, W), np.int16)
    for p in ints:
        cv2.polylines(cl, [p], False, 255, 1)
        x0, y0 = max(0, p[:, 0, 0].min() - 3), max(0, p[:, 0, 1].min() - 3)
        x1, y1 = min(W, p[:, 0, 0].max() + 4), min(H, p[:, 0, 1].max() + 4)
        if x1 <= x0 or y1 <= y0: continue
        tmp = np.zeros((y1 - y0, x1 - x0), np.uint8)
        cv2.polylines(tmp, [p - np.array([x0, y0], np.int32)], False, 1, 5)  # ~2px radius
        acc[y0:y1, x0:x1] += tmp
    skel = skeletonize(ink >= THR)
    lab, nl = ndimage.label(skel, np.ones((3, 3)))
    sizes = ndimage.sum(skel, lab, range(1, nl + 1)) if nl else []
    big = np.isin(lab, [i + 1 for i, s in enumerate(sizes) if s >= 15])  # ignore ink specks < 15px
    d_stroke = cv2.distanceTransform((cl == 0).astype(np.uint8), cv2.DIST_L2, 3)
    gap = big & (d_stroke > 1.5)
    d_ink = cv2.distanceTransform((~skel).astype(np.uint8), cv2.DIST_L2, 3)
    stray = (cl > 0) & (d_ink > 2.5)
    dbl = (cl > 0) & (acc >= 2)
    nc, _, st, _ = cv2.connectedComponentsWithStats(dbl.astype(np.uint8), connectivity=8)
    dbl_big = sum(int(st[i, cv2.CC_STAT_AREA]) for i in range(1, nc) if st[i, cv2.CC_STAT_AREA] >= 10)  # drop crossings/joins
    npx = max(1, int((cl > 0).sum()))
    travel = sum(float(np.linalg.norm(strokes[i + 1][0] - strokes[i][-1])) for i in range(len(strokes) - 1))
    return dict(strokes=len(strokes), path_len=round(sum(poly_len(s) for s in strokes)),
                gap_pct=round(100 * gap.sum() / max(1, big.sum()), 2), stray_pct=round(100 * stray.sum() / npx, 2),
                doubled_pct=round(100 * dbl_big / npx, 2), pen_travel=round(travel),
                short_strokes_lt10px=sum(poly_len(s) < 10 for s in strokes))

def render(strokes, shape, off, order=False):
    H, W = shape; im = np.full((H, W, 3), 255, np.uint8); n = len(strokes)
    if order:
        for i in range(n - 1):
            a, b = strokes[i][-1] - off, strokes[i + 1][0] - off
            cv2.line(im, tuple(int(v) for v in np.round(a)), tuple(int(v) for v in np.round(b)), (200, 200, 200), 1, cv2.LINE_AA)
    for i, s in enumerate(strokes):
        col = (0, 0, 0)
        if order:
            r, g, b = cv2.cvtColor(np.uint8([[[int(170 * i / max(1, n - 1)), 255, 220]]]), cv2.COLOR_HSV2RGB)[0, 0]
            col = (int(b), int(g), int(r))
        cv2.polylines(im, [np.round((s - off) * 16).astype(np.int32).reshape(-1, 1, 2)], False, col, 2 if order else 1, cv2.LINE_AA, 4)
    return im

def label(im, text):
    im = im.copy(); cv2.rectangle(im, (0, 0), (im.shape[1], 18), (255, 255, 255), -1)
    cv2.putText(im, text, (3, 13), cv2.FONT_HERSHEY_SIMPLEX, 0.42, (0, 0, 200), 1, cv2.LINE_AA); return im

res = {}
for n in names:
    ink = inks[n]; H, W = ink.shape
    bw = np.where(ink >= THR, 0, 255).astype(np.uint8)
    cv2.imwrite(f"{OUT}/{n}_bw.png", bw)
    t = time.perf_counter()
    subprocess.run(["autotrace", "-centerline", "-output-format", "svg", "-output-file", f"{OUT}/{n}.svg", f"{OUT}/{n}_bw.png"], check=True)
    at_ms = (time.perf_counter() - t) * 1000
    at = parse_svg(f"{OUT}/{n}.svg")
    ours = [np.array(l.split(), np.float32).reshape(-1, 2) for l in open(f"{OUT}/k_out/{n}.txt").read().split("\n") if l.strip()]
    mo = metrics(ours, ink, 0.5); mo["ms"] = round(float(open(f"{OUT}/k_out/{n}.ms").read()))  # ours: pixel-centre +0.5 coords, warm JVM
    ma = metrics(at, ink, 0.0); ma["ms"] = round(at_ms)  # autotrace: process wall time incl. PNG read + SVG write
    res[n] = dict(ours=mo, autotrace=ma); print(n, json.dumps(res[n]))
    panels = [label(cv2.cvtColor(bw, cv2.COLOR_GRAY2BGR), f"{n} (ink>={THR})"),
              label(render(ours, (H, W), 0.5), f"ours n={mo['strokes']} gap={mo['gap_pct']}% dbl={mo['doubled_pct']}%"),
              label(render(at, (H, W), 0.0), f"autotrace n={ma['strokes']} gap={ma['gap_pct']}% dbl={ma['doubled_pct']}%"),
              label(render(ours, (H, W), 0.5, True), f"ours order travel={mo['pen_travel']}px"),
              label(render(at, (H, W), 0.0, True), f"autotrace order travel={ma['pen_travel']}px")]
    cv2.imwrite(f"{OUT}/sheets/{n}.jpg", np.hstack(panels), [cv2.IMWRITE_JPEG_QUALITY, 85])

fields = ["strokes", "path_len", "gap_pct", "stray_pct", "doubled_pct", "pen_travel", "short_strokes_lt10px", "ms"]
res["_mean"] = {k: {f: round(float(np.mean([res[n][k][f] for n in names])), 2) for f in fields} for k in ("ours", "autotrace")}
json.dump(res, open(OUT + "/autotrace.json", "w"), indent=1)
md = ["| image | who | " + " | ".join(fields) + " |", "|" + "---|" * (len(fields) + 2)]
for n in names + ["_mean"]:
    for k in ("ours", "autotrace"): md.append(f"| {n} | {k} | " + " | ".join(str(res[n][k][f]) for f in fields) + " |")
open(OUT + "/autotrace.md", "w").write("\n".join(md) + "\n"); print("\n".join(md))
