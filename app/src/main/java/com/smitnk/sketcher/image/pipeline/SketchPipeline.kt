package com.smitnk.sketcher.image.pipeline

import com.smitnk.sketcher.image.ColorStrokeGenerator
import com.smitnk.sketcher.image.GeneratedDrawing
import com.smitnk.sketcher.image.GeneratorSettings
import com.smitnk.sketcher.image.Stroke
import com.smitnk.sketcher.image.StrokeGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Orchestrates line source selection, vectorising, shading and colour fill. */
object SketchPipeline {

    /** Line source actually used by the most recent [run] (after AUTO resolution and fallbacks). */
    @Volatile var lastLineSource: String = ""

    /**
     * Wall-clock ms per stage of the most recent [run]: preFilter, model, vectorize, coverage, shading, total
     * (stages that did not run are absent). For the processing sheet.
     */
    @Volatile var lastTimings: Map<String, Long> = emptyMap()

    /** Accumulates stage times; inline so call sites stay allocation-free. */
    internal class Timer {
        val ms = LinkedHashMap<String, Long>()
        inline fun <T> time(name: String, f: () -> T): T {
            val t0 = System.nanoTime()
            try { return f() } finally { ms[name] = (ms[name] ?: 0L) + (System.nanoTime() - t0) / 1_000_000 }
        }
    }

    /** Last model/extractor failure that caused a fallback (null if the last run had none). */
    @Volatile var lastError: Throwable? = null

    /**
     * @param alphaMask optional known background (w*h, true = transparent/background). When null it is derived from
     * [argbIn]'s alpha channel (transparent pixels). Either way the image is flattened onto white, the mask is used
     * directly as the subject boundary (no background guessing, no letterbox detection), and nothing but the
     * flattened pixels reaches the line extractors / model.
     */
    suspend fun run(
        argbIn: IntArray,
        w: Int,
        h: Int,
        settings: PipelineSettings,
        model: LineArtModel?,
        progress: (stage: String, fraction: Float) -> Unit = { _, _ -> },
        alphaMask: BooleanArray? = null,
        /** TEED/MTEED raw edge model for [LineSource.FAST_EDGES] (separate from the user's [model]); null = Classic fallback. */
        fastModel: LineArtModel? = null,
        /** Optional Anime2Sketch-style model for [LineSource.CARTOON]; null or any failure = built-in cartoon extractor. */
        cartoonModel: LineArtModel? = null,
    ): GeneratedDrawing = withContext(Dispatchers.Default) {
        lastError = null
        val fastSel = settings.lineSource == LineSource.FAST_EDGES
        @Suppress("NAME_SHADOWING")
        val model = if (fastSel && fastModel != null) FastEdgesModel(fastModel) { settings.detail } else model
        val (argb, alphaFull) = flattenAlpha(argbIn, w, h, alphaMask)
        val tm = Timer()
        val tTotal = System.nanoTime()
        val ctx = currentCoroutineContext()
        fun check() = ctx.ensureActive()

        progress("Preparing image", 0.02f)
        val (px, pw, ph) = tm.time("prep") { downscale(argb, w, h, settings.workingSize) }
        check()

        // subject rectangle (flat backdrop / letterbox bars excluded); full frame for ordinary photos
        val alphaBg = alphaFull?.let { downscaleMask(it, w, h, pw, ph) }
        val sub = tm.time("crop") { SubjectCrop.detect(px, pw, ph, alphaBg = alphaBg) }
        // choose line source
        var source = if (fastSel) (if (fastModel != null) LineSource.AI else LineSource.CLASSIC) else settings.lineSource
        if (source == LineSource.AUTO) {
            progress("Analysing image", 0.06f)
            source = when {
                tm.time("analyse") { CartoonLineExtractor.cartoonScore(px, pw, ph, sub) } >= AUTO_CARTOON_CUT -> LineSource.CARTOON
                model != null -> LineSource.AI
                else -> LineSource.CLASSIC
            }
        } else if (source == LineSource.AI && model == null) {
            source = LineSource.CLASSIC
        }
        check()

        val lineWidth = settings.lineWidth // LineArtVectorizer scales width by image size itself
        var ink: FloatArray? = null
        var outline: List<Stroke>? = null
        val bgMask = if (settings.ignoreBackground) sub.bgMask else null
        var aiStrokes: List<Stroke>? = null
        var fusedInk = false
        fun runAi() {
            progress("Running AI line-art model", 0.10f)
            try {
                val r = runModelOnSubject(argb, w, h, pw, ph, sub, bgMask, settings, model!!, tm)
                ink = r.first
                aiStrokes = r.second
                source = LineSource.AI
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                source = LineSource.CLASSIC
                ink = null
                aiStrokes = null
            }
            check()
        }
        if (source == LineSource.AI) runAi()
        var cartoonModelDone = false
        if (source == LineSource.CARTOON && cartoonModel != null) {
            progress("Running cartoon line model", 0.10f)
            try {
                // long side capped at 768 (memory); the ink goes through the same normalise + vectorise as AI line art
                val r = runModelOnSubject(argb, w, h, pw, ph, sub, bgMask, settings, cartoonModel, tm, side = CARTOON_MODEL_SIDE)
                ink = r.first
                aiStrokes = r.second
                cartoonModelDone = true
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                runCatching { android.util.Log.w("SketchPipeline", "cartoon model failed, using built-in cartoon lines", t) }
                ink = null
                aiStrokes = null
            }
            check()
        }
        if (source == LineSource.CARTOON && !cartoonModelDone) {
            progress("Extracting cartoon lines", 0.10f)
            // forced CARTOON on a textured photo (no clean backdrop): smooth fur/grass first or every blade becomes a speck
            val cartoonSrc = if (settings.lineSource == LineSource.CARTOON && !sub.hasBackground &&
                tm.time("analyse") { CartoonLineExtractor.cartoonScore(px, pw, ph, sub) } < AUTO_CARTOON_CUT)
                tm.time("preFilter") { PreFilter.smoothTextures(px, pw, ph, 1f) } else px
            val cm = tm.time("cartoonInk") { CartoonLineExtractor.inkMap(cartoonSrc, pw, ph, settings.detail) }
            if (bgMask != null) for (i in cm.indices) if (bgMask[i]) cm[i] = 0f
            check()
            // AUTO guard: a render/photo that scored as cartoon but yields almost no strong ink is not inked art.
            if (settings.lineSource == LineSource.AUTO && model != null && !hasStrongInk(cm, pw, ph, sub, bgMask)) {
                runAi()
            } else {
                // Fusion: AI ink supplies interior detail, the cartoon extractor bold outlines / pupils / region
                // edges, plus a bold outer silhouette. Without a model (or if it fails) cartoon-only.
                var ai: FloatArray? = null
                if (model != null) {
                    progress("Running AI line-art model", 0.20f)
                    try {
                        ai = runModelOnSubject(argb, w, h, pw, ph, sub, bgMask, settings, model, tm).first
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        lastError = t
                    }
                    check()
                }
                ink = fuseCartoonInk(ai, cm, pw, ph, sub, px, alphaBg)
                fusedInk = ai != null
            }
        }
        lastLineSource = if (fastSel && source == LineSource.AI) "FAST_EDGES" else source.name

        // FLOW_LINES (zero-model): Coherent Line Drawing on the subject crop, background suppressed like the AI path,
        // then the same percentile normalisation. Reuses the classicInk/classicNormalised flags so the vectoriser,
        // pencil texture and colour-fill rules treat it as clean normalised ink.
        var classicInk = false
        var classicNormalised = false
        if (source == LineSource.FLOW_LINES) {
            progress("Flow lines", 0.20f)
            val cx0 = sub.x0.coerceIn(0, pw - 8); val cy0 = sub.y0.coerceIn(0, ph - 8)
            val cx1 = sub.x1.coerceIn(cx0 + 8, pw); val cy1 = sub.y1.coerceIn(cy0 + 8, ph)
            val cw = cx1 - cx0; val chh = cy1 - cy0
            val crop = IntArray(cw * chh)
            for (y in 0 until chh) System.arraycopy(px, (cy0 + y) * pw + cx0, crop, y * cw, cw)
            val ci = tm.time("flowInk") { FlowLines.inkMap(crop, cw, chh, FlowLines.paramsForDetail(settings.detail)) }
            val e = FloatArray(pw * ph)
            for (y in 0 until chh) System.arraycopy(ci, y * cw, e, (cy0 + y) * pw + cx0, cw)
            if (bgMask != null) for (i in e.indices) if (bgMask[i]) e[i] = 0f
            val valid = if (bgMask != null) BooleanArray(pw * ph) { !bgMask[it] } else null
            normaliseInk(e, valid, pw, ph)
            ink = e; classicInk = true; classicNormalised = true
            check()
        }

        // CLASSIC (no model): the cartoon ink extractor + backdrop mask beats raw Canny tracing whenever it finds real ink.
        if (source == LineSource.CLASSIC) {
            progress("Extracting lines", 0.30f)
            val inked = tm.time("analyse") { CartoonLineExtractor.cartoonScore(px, pw, ph, sub) >= AUTO_CARTOON_CUT }
            val cm = if (inked) tm.time("cartoonInk") { CartoonLineExtractor.inkMap(px, pw, ph, settings.detail) } else FloatArray(0)
            if (bgMask != null) for (i in cm.indices) if (bgMask[i]) cm[i] = 0f
            check()
            if (inked && hasStrongInk(cm, pw, ph, sub, bgMask)) { ink = cm; classicInk = true }
            else {
                // renders / photos: normalised multi-channel edge map (same normalisation as model ink)
                val strength = if (sub.hasBackground) 0f else 1f
                val src = if (strength <= 0f) px else tm.time("preFilter") { PreFilter.smoothTextures(px, pw, ph, strength) }
                val e = tm.time("edgeInk") { edgeInk(src, pw, ph) }
                val valid = if (bgMask != null) BooleanArray(pw * ph) { !bgMask[it] } else null
                if (bgMask != null) for (i in e.indices) if (bgMask[i]) e[i] = 0f
                normaliseInk(e, valid, pw, ph)
                if (hasStrongInk(e, pw, ph, sub, bgMask)) { ink = e; classicInk = true; classicNormalised = true }
            }
        }
        if (source == LineSource.CLASSIC && !classicInk) {
            progress("Tracing outline", 0.35f)
            val gs = GeneratorSettings(
                detail = settings.detail,
                includeShading = false,
                maxSide = max(pw, ph),
            )
            outline = tm.time("vectorize") { StrokeGenerator.generateBlocking(px, pw, ph, gs).outline }
        } else if (aiStrokes != null) {
            outline = aiStrokes!!
        } else {
            progress("Vectorising lines", 0.40f)
            outline = tm.time("vectorize") { LineArtVectorizer.vectorize(ink!!, pw, ph, cleanDetail(settings, settings.detail), lineWidth, settings.maxStrokes) }
        }
        // never keep junk in the backdrop or a frame drawn around the image edge
        outline = dropBackgroundStrokes(outline, pw, ph, bgMask)
        check()

        var shading: List<Stroke> = emptyList()
        if (settings.shading != ShadingLevel.NONE) {
            progress("Shading", 0.60f)
            shading = tm.time("shading") { ShadingGenerator.generate(px, pw, ph, settings, ink, cartoon = source == LineSource.CARTOON || classicInk) }
            check()
        }

        var fill: List<Stroke> = emptyList()
        if (settings.colourFill) {
            progress("Colour fill", 0.80f)
            fill = ColorStrokeGenerator.generateFill(
                px, pw, ph, pw, ph, settings.fillColors.coerceIn(6, 16), settings.fillSpacing,
            )
            check()
            // Cartoon ink stays black; photos get a recoloured outline, darkened by >= 45%.
            if (source != LineSource.CARTOON && !classicInk) {
                outline = ColorStrokeGenerator.colorizeOutline(outline, px, pw, ph, pw, ph, darken = 0.55f)
            }
            check()
        }

        // Pencil reveal: keep the exact ink raster; the strokes become reveal paths (+ coverage strokes for dropped ink).
        var inkArgb: IntArray? = null
        var revealWidth = 0f
        val usesInk = (source == LineSource.AI || source == LineSource.CARTOON || classicInk) && ink != null
        if (settings.outlineStyle == OutlineStyle.PENCIL_REVEAL && usesInk && !(settings.colourFill && source != LineSource.CARTOON && !classicInk)) {
            progress("Pencil texture", 0.95f)
            val vis = inkVisibility(ink!!, pw, ph, sub, bgMask, normalised = source == LineSource.AI || fusedInk || classicNormalised)
            val r = revealRadius(settings.lineWidth, pw, ph)
            val base = outline
            val cover = tm.time("coverage") { InkCoverage.coverageStrokes(vis, pw, ph, base, r, COVER_THRESHOLD, settings.lineWidth) }
            // Interleave the hairs with their nearest main stroke so the animation never ends on a pile of tiny marks.
            outline = com.smitnk.sketcher.anim.StrokeInterleave.interleave(base, cover) { it.points }
            inkArgb = visibilityToArgb(vis)
            revealWidth = 2f * r
            check()
        }

        // Clean vector: the vectoriser drops dense detail (eyes, mouth, glasses, beard). Add thin strokes for strong
        // ink it left uncovered so faces survive; the same normalised ink drives both styles.
        if (settings.outlineStyle == OutlineStyle.CLEAN_VECTOR && usesInk) {
            progress("Face detail", 0.95f)
            val vis = inkVisibility(ink!!, pw, ph, sub, bgMask, normalised = source == LineSource.AI || fusedInk || classicNormalised)
            val base = outline
            val r = CLEAN_COVER_RADIUS * settings.lineWidth * (max(pw, ph) / 1024f).coerceIn(0.6f, 2.2f)
            val cover = tm.time("coverage") { InkCoverage.coverageStrokes(vis, pw, ph, base, r, CLEAN_COVER_THRESHOLD, settings.lineWidth * 0.6f) }
            outline = com.smitnk.sketcher.anim.StrokeInterleave.interleave(base, cover) { it.points }
            check()
        }

        progress("Done", 1f)
        tm.ms["total"] = (System.nanoTime() - tTotal) / 1_000_000
        lastTimings = LinkedHashMap(tm.ms)
        GeneratedDrawing(pw, ph, outline, shading, fill, inkArgb, if (inkArgb != null) pw else 0, if (inkArgb != null) ph else 0, revealWidth)
    }

    internal const val CLEAN_COVER_RADIUS = 1.5f
    internal const val CLEAN_COVER_THRESHOLD = 0.45f

    /** Ink visibility (alpha) at/above which a pixel must be revealed by some reveal stroke. */
    internal const val COVER_THRESHOLD = 0.15f

    /** Reveal-stroke radius in working pixels: 2.5 x the (size-scaled) line width. */
    internal fun revealRadius(lineWidth: Float, pw: Int, ph: Int): Float =
        2.5f * lineWidth * (max(pw, ph) / 1024f).coerceIn(0.6f, 2.2f)

    /**
     * Full-image ink (0..1) -> visible ink alpha: paper noise removed, background zeroed, and a thin margin along
     * the subject crop's edge cleared (the model draws a frame line there; the vectoriser drops it as well).
     */
    internal fun inkVisibility(
        ink: FloatArray, pw: Int, ph: Int, sub: SubjectCrop.Result, bgMask: BooleanArray?, normalised: Boolean = false,
    ): FloatArray {
        val out = FloatArray(pw * ph)
        val m = max(3, (0.004f * max(sub.x1 - sub.x0, sub.y1 - sub.y0)).roundToInt())
        val x0 = sub.x0 + m; val x1 = min(pw, sub.x1) - m
        val y0 = sub.y0 + m; val y1 = min(ph, sub.y1) - m
        for (y in max(0, y0) until y1) {
            for (x in max(0, x0) until x1) {
                val i = y * pw + x
                if (bgMask != null && bgMask[i]) continue
                // AI ink is already percentile-normalised + gamma'd (see normaliseInk); cartoon ink is 0/0.5..1 as is
                out[i] = if (normalised) ink[i].coerceIn(0f, 1f) else ((ink[i] - INK_LO) / (INK_HI - INK_LO)).coerceIn(0f, 1f)
            }
        }
        return out
    }

    private const val INK_LO = 0.06f
    private const val INK_HI = 0.95f

    /** AUTO: cartoon score at/above which the cartoon extractor is used (3D renders and photos score below). */
    internal const val AUTO_CARTOON_CUT = 0.75f

    /** Fraction of subject pixels that must carry strong ink (> 0.5) for the cartoon extractor to be trusted. */
    internal const val MIN_STRONG_INK = 0.004f

    internal fun hasStrongInk(ink: FloatArray, pw: Int, ph: Int, sub: SubjectCrop.Result, bgMask: BooleanArray?): Boolean {
        var strong = 0; var area = 0
        for (y in max(0, sub.y0) until min(ph, sub.y1)) for (x in max(0, sub.x0) until min(pw, sub.x1)) {
            val i = y * pw + x
            if (bgMask != null && bgMask[i]) continue
            area++
            if (ink[i] > 0.5f) strong++
        }
        return area > 0 && strong >= MIN_STRONG_INK * area
    }

    /** Fraction of transparent pixels (alpha < 128) at/above which an image without an explicit mask counts as cut-out. */
    private const val MIN_TRANSPARENT = 0.005f

    /**
     * Composites [argb] onto white (so transparent pixels are white, never black) and returns it with the background
     * mask: [given] when supplied, else alpha < 128 when at least [MIN_TRANSPARENT] of the pixels are that
     * transparent, else null. Fully opaque input is returned as is.
     */
    internal fun flattenAlpha(argb: IntArray, w: Int, h: Int, given: BooleanArray?): Pair<IntArray, BooleanArray?> {
        val n = w * h
        if (argb.size < n) return argb to null
        var hasAlpha = false; var transparent = 0
        for (i in 0 until n) { val a = argb[i] ushr 24; if (a < 255) { hasAlpha = true; if (a < 128) transparent++ } }
        val mask = given?.takeIf { it.size >= n }
            ?: if (transparent >= MIN_TRANSPARENT * n) BooleanArray(n) { (argb[it] ushr 24) < 128 } else null
        if (!hasAlpha && mask == null) return argb to null
        val out = IntArray(n)
        for (i in 0 until n) {
            val p = argb[i]; val a = p ushr 24
            out[i] = if (mask != null && mask[i]) -1
            else if (a >= 255) p
            else {
                val r = (((p shr 16) and 255) * a + 255 * (255 - a)) / 255
                val g = (((p shr 8) and 255) * a + 255 * (255 - a)) / 255
                val b = ((p and 255) * a + 255 * (255 - a)) / 255
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out to mask
    }

    /** Nearest-sample a full-size mask to the working size (same grid as [downscale]'s block centres). */
    internal fun downscaleMask(m: BooleanArray, w: Int, h: Int, pw: Int, ph: Int): BooleanArray {
        if (w == pw && h == ph) return m
        return BooleanArray(pw * ph) {
            val x = ((it % pw + 0.5f) * w / pw).toInt().coerceIn(0, w - 1)
            val y = ((it / pw + 0.5f) * h / ph).toInt().coerceIn(0, h - 1)
            m[y * w + x]
        }
    }

    /**
     * CARTOON ink = max(aiInk, cartoonInk) (aiInk optional, already normalised). When the subject has a backdrop its
     * silhouette is only REINFORCED: computed at full working resolution (from [alphaBg], else per-pixel colour distance
     * to the local backdrop colour in [argb]), smoothed (blur + threshold) and added as bold ink only where the ink
     * already has a line within [SIL_NEAR] px. Never invents contours, so no blocky halos. Returns a new array.
     */
    internal fun fuseCartoonInk(
        ai: FloatArray?, cartoon: FloatArray, pw: Int, ph: Int, sub: SubjectCrop.Result,
        argb: IntArray? = null, alphaBg: BooleanArray? = null,
    ): FloatArray {
        val out = FloatArray(pw * ph) { if (ai != null) max(ai[it], cartoon[it]) else cartoon[it] }
        val coarse = sub.bgMask ?: return out
        val fg0 = silhouetteFg(coarse, argb, alphaBg, pw, ph) ?: return out
        val rs = max(2, (max(pw, ph) / 300f).roundToInt())
        val fg = smoothMask(fg0, pw, ph, rs)
        // ink within SIL_NEAR px of a pixel (computed on the un-reinforced ink)
        val has = BooleanArray(pw * ph) { out[it] > SIL_INK }
        val near = dilateBox(has, pw, ph, SIL_NEAR)
        val r = max(1, (max(pw, ph) / 600f).roundToInt())
        val m = 3
        val x0 = max(sub.x0 + m, 1); val x1 = min(sub.x1 - m, pw - 1)
        val y0 = max(sub.y0 + m, 1); val y1 = min(sub.y1 - m, ph - 1)
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * pw + x
            if (!fg[i] || !(!fg[i - 1] || !fg[i + 1] || !fg[i - pw] || !fg[i + pw])) continue
            if (!near[i]) continue
            for (dy in -r..r) for (dx in -r..r) {
                val xx = x + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= pw || yy >= ph) continue
                val j = yy * pw + xx
                if (fg[j] && out[j] < SIL_VALUE) out[j] = SIL_VALUE
            }
        }
        return out
    }

    private const val SIL_NEAR = 3
    private const val SIL_INK = 0.3f
    private const val SIL_VALUE = 0.9f
    private const val SIL_COLOR_TOL = 22

    /** true = subject. From [alphaBg] directly, else colour distance to the local backdrop mean; null if neither is available. */
    private fun silhouetteFg(coarse: BooleanArray, argb: IntArray?, alphaBg: BooleanArray?, pw: Int, ph: Int): BooleanArray? {
        if (alphaBg != null) return BooleanArray(pw * ph) { !alphaBg[it] }
        if (argb == null) return null
        val W = pw + 1
        val cnt = IntArray(W * (ph + 1)); val sr = LongArray(W * (ph + 1)); val sg = LongArray(W * (ph + 1)); val sb = LongArray(W * (ph + 1))
        for (y in 0 until ph) for (x in 0 until pw) {
            val i = y * pw + x; val k = (y + 1) * W + x + 1; val a = y * W + x + 1; val b = (y + 1) * W + x; val c = y * W + x
            val c0 = argb[i]; val f = if (coarse[i]) 1 else 0
            cnt[k] = cnt[a] + cnt[b] - cnt[c] + f
            sr[k] = sr[a] + sr[b] - sr[c] + f * ((c0 shr 16) and 255)
            sg[k] = sg[a] + sg[b] - sg[c] + f * ((c0 shr 8) and 255)
            sb[k] = sb[a] + sb[b] - sb[c] + f * (c0 and 255)
        }
        val R = max(16, max(pw, ph) / 30)
        val out = BooleanArray(pw * ph)
        for (y in 0 until ph) for (x in 0 until pw) {
            val i = y * pw + x
            if (coarse[i]) continue
            val xa = max(0, x - R); val xb = min(pw, x + R + 1); val ya = max(0, y - R); val yb = min(ph, y + R + 1)
            val c = cnt[yb * W + xb] - cnt[ya * W + xb] - cnt[yb * W + xa] + cnt[ya * W + xa]
            if (c == 0) { out[i] = true; continue }
            fun box(t: LongArray) = (t[yb * W + xb] - t[ya * W + xb] - t[yb * W + xa] + t[ya * W + xa]) / c
            val p = argb[i]
            val d = max(kotlin.math.abs(((p shr 16) and 255) - box(sr).toInt()),
                max(kotlin.math.abs(((p shr 8) and 255) - box(sg).toInt()), kotlin.math.abs((p and 255) - box(sb).toInt())))
            out[i] = d > SIL_COLOR_TOL
        }
        return out
    }

    /** Two box blurs of radius [r] then threshold at 0.5: removes pixel stair-steps and specks. */
    internal fun smoothMask(a: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        var f = FloatArray(w * h) { if (a[it]) 1f else 0f }
        repeat(2) { f = boxBlur(f, w, h, r) }
        return BooleanArray(w * h) { f[it] >= 0.5f }
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        val k = 2 * r + 1f
        for (y in 0 until h) {
            var s = 0f
            for (x in -r..r) s += src[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = s / k
                s += src[y * w + (x + r + 1).coerceIn(0, w - 1)] - src[y * w + (x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var s = 0f
            for (y in -r..r) s += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = s / k
                s += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    /** Square dilation by [r] (true if any true within r, separable). */
    private fun dilateBox(a: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(w * h); val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = false
            for (dx in -r..r) { val xx = x + dx; if (xx in 0 until w && a[y * w + xx]) { v = true; break } }
            tmp[y * w + x] = v
        }
        for (y in 0 until h) for (x in 0 until w) {
            var v = false
            for (dy in -r..r) { val yy = y + dy; if (yy in 0 until h && tmp[yy * w + x]) { v = true; break } }
            out[y * w + x] = v
        }
        return out
    }

    private const val NORM_LO = 0.04f
    private const val NORM_GAMMA = 0.7f

    /** Local contrast stage: target level of a tile's 97th-percentile ink, gain cap, "is ink" floor, min ink pixels. */
    private const val LOCAL_TARGET = 0.9f
    private const val LOCAL_MAX_GAIN = 4f
    private const val LOCAL_FLOOR = 0.10f
    private const val LOCAL_MIN_PIX = 10
    /** Raw-ink hi (99.5th percentile) at/below which a model counts as thin+faint and gets 3x3 thickening; fades out by +0.1. */
    private const val THIN_HI = 0.50f

    /**
     * Normalises AI ink in place so thin / light models (anime, informative) read as clearly as heavy ones.
     * 1) Global: percentile (99.5th) of the subject ([valid] true, or all when null) -> hi, clamped to [0.35, 0.95]; a
     *    noise floor scaling with hi; then gamma.
     * 2) Local (only when [w]x[h] match [ink]): a few very dark spots (glasses, eyes) set the global hi, leaving the
     *    body lines faint. Per tile (~max(w,h)/14 px) the 97th percentile of the ink pixels is lifted to
     *    [LOCAL_TARGET] (gain 1..[LOCAL_MAX_GAIN], never dims), gains bilinear-interpolated between tile centres
     *    weighted by whether a tile has ink at all, so blank paper keeps gain 1 and stays clean. Models whose
     *    raw ink is faint overall (hi <= [THIN_HI], i.e. hairline output like anime) also get 3x3 max-blend
     *    thickening, otherwise 1px lines at 0.4 ink stay hard to see on a phone.
     */
    internal fun normaliseInk(ink: FloatArray, valid: BooleanArray? = null, w: Int = 0, h: Int = 0) {
        val bins = IntArray(1001)
        var n = 0
        for (i in ink.indices) {
            if (valid != null && !valid[i]) continue
            bins[(ink[i].coerceIn(0f, 1f) * 1000f).toInt()]++; n++
        }
        if (n == 0) return
        val target = (n * 0.005f).toInt()
        var acc = 0; var b = 1000
        while (b > 0) { acc += bins[b]; if (acc > target) break; b-- }
        val hi = (b / 1000f).coerceIn(0.35f, 0.95f)
        val lo = max(NORM_LO, 0.10f * hi)
        val inv = 1f / (hi - lo)
        for (i in ink.indices) {
            val v = ((ink[i] - lo) * inv).coerceIn(0f, 1f)
            ink[i] = if (v <= 0f) 0f else Math.pow(v.toDouble(), NORM_GAMMA.toDouble()).toFloat()
        }
        if (w > 0 && h > 0 && w * h == ink.size) localContrast(ink, w, h, valid, ((THIN_HI + 0.1f - hi) / 0.1f).coerceIn(0f, 1f))
    }

    private fun localContrast(ink: FloatArray, w: Int, h: Int, valid: BooleanArray?, thick: Float) {
        val t = max(40, (max(w, h) / 14f).roundToInt())
        val nx = (w + t - 1) / t; val ny = (h + t - 1) / t
        val tg = FloatArray(nx * ny); val tw = FloatArray(nx * ny)
        val hist = IntArray(101)
        for (ty in 0 until ny) for (tx in 0 until nx) {
            java.util.Arrays.fill(hist, 0)
            var cnt = 0
            for (y in ty * t until min(h, (ty + 1) * t)) for (x in tx * t until min(w, (tx + 1) * t)) {
                val i = y * w + x
                if (valid != null && !valid[i]) continue
                val v = ink[i]
                if (v > LOCAL_FLOOR) { hist[(v.coerceAtMost(1f) * 100f).toInt()]++; cnt++ }
            }
            if (cnt < LOCAL_MIN_PIX) continue
            val want = (cnt * 0.03f).toInt()
            var a = 0; var q = 100
            while (q > 0) { a += hist[q]; if (a > want) break; q-- }
            val p97 = max(0.05f, (q + 0.5f) / 100f)
            tg[ty * nx + tx] = (LOCAL_TARGET / p97).coerceIn(1f, LOCAL_MAX_GAIN)
            tw[ty * nx + tx] = 1f
        }
        // gain map: weighted bilinear interpolation between tile centres
        val gain = FloatArray(w * h)
        val xi0 = IntArray(w); val xi1 = IntArray(w); val xf = FloatArray(w)
        for (x in 0 until w) {
            val gx = ((x + 0.5f) / t - 0.5f).coerceIn(0f, (nx - 1).toFloat())
            xi0[x] = min(gx.toInt(), max(nx - 2, 0)); xi1[x] = min(xi0[x] + 1, nx - 1); xf[x] = gx - xi0[x]
        }
        for (y in 0 until h) {
            val gy = ((y + 0.5f) / t - 0.5f).coerceIn(0f, (ny - 1).toFloat())
            val y0 = min(gy.toInt(), max(ny - 2, 0)); val y1 = min(y0 + 1, ny - 1); val fy = gy - y0
            for (x in 0 until w) {
                val fx = xf[x]
                val a = y0 * nx + xi0[x]; val bI = y0 * nx + xi1[x]; val c = y1 * nx + xi0[x]; val d = y1 * nx + xi1[x]
                val w00 = (1 - fy) * (1 - fx) * tw[a]; val w01 = (1 - fy) * fx * tw[bI]
                val w10 = fy * (1 - fx) * tw[c]; val w11 = fy * fx * tw[d]
                val den = w00 + w01 + w10 + w11
                gain[y * w + x] = if (den > 0.05f) {
                    max(1f, (w00 * tg[a] + w01 * tg[bI] + w10 * tg[c] + w11 * tg[d]) / den)
                } else 1f
            }
        }
        val src = ink.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val g = gain[i]
            var v = src[i] * g
            if (thick > 0f) {
                var m = 0f
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val s = src[yy * w + xx]; if (s > m) m = s
                }
                v = max(v, thick * 0.75f * min(1f, m * g))
            }
            ink[i] = v.coerceIn(0f, 1f)
        }
    }

    /**
     * Edge strength 0..1 (not yet normalised): Sobel magnitude on a 3x3-smoothed image, max over R, G, B, so colour
     * edges between equally bright regions (skin / hair / cloth) count as well as luminance edges.
     */
    internal fun edgeInk(argb: IntArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        if (w < 5 || h < 5) return out
        val ch = Array(3) { c -> FloatArray(w * h) { ((argb[it] shr (16 - 8 * c)) and 255) / 255f } }
        for (c in 0 until 3) ch[c] = boxBlur(ch[c], w, h, 1)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var best = 0f
            for (c in 0 until 3) {
                val a = ch[c]; val i = y * w + x
                val gx = (a[i - w + 1] + 2 * a[i + 1] + a[i + w + 1]) - (a[i - w - 1] + 2 * a[i - 1] + a[i + w - 1])
                val gy = (a[i + w - 1] + 2 * a[i + w] + a[i + w + 1]) - (a[i - w - 1] + 2 * a[i - w] + a[i - w + 1])
                val m = kotlin.math.sqrt(gx * gx + gy * gy) * 0.25f
                if (m > best) best = m
            }
            out[y * w + x] = best
        }
        return out
    }

    /** CLEAN_VECTOR strokes are the only line output, so vectorise at full detail (faces, glasses survive). */
    internal fun cleanDetail(s: PipelineSettings, d: Float): Float = if (s.outlineStyle == OutlineStyle.CLEAN_VECTOR) 1f else d

    /** Ink-black ARGB with alpha = visibility. */
    internal fun visibilityToArgb(vis: FloatArray): IntArray =
        IntArray(vis.size) { (((vis[it] * 255f).roundToInt().coerceIn(0, 255)) shl 24) }


    /** Model input long side: 768 normally, 1024 at high detail (OnnxLineArtModel.longSide must be >= this). */
    internal fun modelSide(detail: Float): Int = if (detail > 0.7f) 1024 else 768

    /** Cartoon (Anime2Sketch) input long side: always 768, never 1024 (fp16 UNet memory). */
    internal const val CARTOON_MODEL_SIDE = 768

    /**
     * Runs the model on the subject crop (taken from the original [argb] for full detail, resampled to
     * [modelSide]), vectorises there and maps strokes back to working-image coordinates. Also returns a
     * full-frame ink map (zero outside the crop) for shading.
     */
    private fun runModelOnSubject(
        argb: IntArray, w: Int, h: Int, pw: Int, ph: Int,
        sub: SubjectCrop.Result, bgMask: BooleanArray?, settings: PipelineSettings, model: LineArtModel, tm: Timer,
        side: Int = modelSide(settings.detail),
    ): Pair<FloatArray, List<Stroke>> {
        val fx = w.toFloat() / pw; val fy = h.toFloat() / ph
        val ox0 = (sub.x0 * fx).roundToInt().coerceIn(0, w - 8)
        val oy0 = (sub.y0 * fy).roundToInt().coerceIn(0, h - 8)
        val ox1 = (sub.x1 * fx).roundToInt().coerceIn(ox0 + 8, w)
        val oy1 = (sub.y1 * fy).roundToInt().coerceIn(oy0 + 8, h)
        val cw = ox1 - ox0; val ch = oy1 - oy0
        val crop = IntArray(cw * ch)
        for (y in 0 until ch) System.arraycopy(argb, (oy0 + y) * w + ox0, crop, y * cw, cw)
        val target = min(side, 2 * max(cw, ch))
        val (mpx, mw, mh) = resample(crop, cw, ch, target)
        // fur/grass -> smooth tone so the model draws contours, not every hair (high detail = less smoothing)
        // Renders/characters on a clean studio backdrop have no noisy texture; smoothing would erase
        // glasses, eyes and beard detail, so only pre-filter real photos (no detected backdrop).
        val preStrength = if (sub.hasBackground) 0f else 1f - 0.8f * settings.detail.coerceIn(0f, 1f)
        val modelIn = if (preStrength <= 0f) mpx
            else tm.time("preFilter") { PreFilter.smoothTextures(mpx, mw, mh, preStrength) }
        val ink = tm.time("model") { model.infer(modelIn, mw, mh) }
        // crop (model space) -> working-image coordinates
        val cropPw = (sub.x1 - sub.x0).toFloat(); val cropPh = (sub.y1 - sub.y0).toFloat()
        val sx = cropPw / mw; val sy = cropPh / mh
        var valid: BooleanArray? = null
        if (bgMask != null) {
            valid = BooleanArray(mw * mh) { true }
            for (y in 0 until mh) {
                val py = min(ph - 1, sub.y0 + (y * sy).toInt())
                for (x in 0 until mw) {
                    val pxx = min(pw - 1, sub.x0 + (x * sx).toInt())
                    if (bgMask[py * pw + pxx]) { ink[y * mw + x] = 0f; valid[y * mw + x] = false }
                }
            }
        }
        // percentile-normalise inside the subject (bg already zeroed) before vectorising and for the pencil texture
        normaliseInk(ink, valid, mw, mh)
        var strokes = tm.time("vectorize") { LineArtVectorizer.vectorize(ink, mw, mh, cleanDetail(settings, (settings.detail + 0.2f).coerceAtMost(1f)), settings.lineWidth, settings.maxStrokes) }
        strokes = dropBorderStrokes(strokes, mw, mh)
        val ws = (sx + sy) * 0.5f
        val mapped = strokes.map { st ->
            val p = FloatArray(st.points.size)
            var i = 0
            while (i + 1 < p.size) { p[i] = sub.x0 + st.points[i] * sx; p[i + 1] = sub.y0 + st.points[i + 1] * sy; i += 2 }
            st.copy(points = p, width = max(0.6f, st.width * ws))
        }
        // full-frame ink for shading (bilinear from model space, 0 outside the crop)
        val full = FloatArray(pw * ph)
        val x0 = sub.x0; val y0 = sub.y0
        for (y in y0 until min(ph, sub.y1)) {
            val gy = ((y - y0 + 0.5f) / sy - 0.5f).coerceIn(0f, mh - 1f)
            val iy = min(mh - 2, gy.toInt()).coerceAtLeast(0); val ty = gy - iy
            for (x in x0 until min(pw, sub.x1)) {
                val gx = ((x - x0 + 0.5f) / sx - 0.5f).coerceIn(0f, mw - 1f)
                val ix = min(mw - 2, gx.toInt()).coerceAtLeast(0); val tx = gx - ix
                val i = iy * mw + ix
                val i1 = if (ix + 1 < mw) 1 else 0; val j1 = if (iy + 1 < mh) mw else 0
                val top = ink[i] * (1 - tx) + ink[i + i1] * tx
                val bot = ink[i + j1] * (1 - tx) + ink[i + j1 + i1] * tx
                full[y * pw + x] = top * (1 - ty) + bot * ty
            }
        }
        return full to mapped
    }

    /** Strokes lying along the image frame (a rectangle traced round the edge) are removed. */
    internal fun dropBorderStrokes(strokes: List<Stroke>, w: Int, h: Int): List<Stroke> {
        val m = max(2f, 0.006f * max(w, h))
        return strokes.filter { st ->
            val p = st.points; val n = p.size / 2
            if (n == 0) return@filter false
            var near = 0
            for (i in 0 until n) {
                val x = p[2 * i]; val y = p[2 * i + 1]
                if (x <= m || y <= m || x >= w - 1 - m || y >= h - 1 - m) near++
            }
            near < 0.8f * n
        }
    }

    internal fun dropBackgroundStrokes(strokes: List<Stroke>, w: Int, h: Int, bg: BooleanArray?): List<Stroke> {
        val s = dropBorderStrokes(strokes, w, h)
        if (bg == null) return s
        return s.filter { st ->
            val p = st.points; val n = p.size / 2
            var inBg = 0
            for (i in 0 until n) {
                val x = p[2 * i].toInt().coerceIn(0, w - 1); val y = p[2 * i + 1].toInt().coerceIn(0, h - 1)
                if (bg[y * w + x]) inBg++
            }
            n > 0 && inBg < 0.6f * n
        }
    }

    /** Resize so the long side is [target] (area average when shrinking, bilinear when enlarging). */
    private fun resample(src: IntArray, w: Int, h: Int, target: Int): Img {
        if (max(w, h) >= target) return downscale(src, w, h, target)
        val f = target.toFloat() / max(w, h)
        val tw = max(1, (w * f).roundToInt()); val th = max(1, (h * f).roundToInt())
        val out = IntArray(tw * th)
        for (y in 0 until th) {
            val gy = ((y + 0.5f) / f - 0.5f).coerceIn(0f, h - 1f)
            val y0 = min(h - 2, gy.toInt()).coerceAtLeast(0); val ty = gy - y0; val y1 = min(h - 1, y0 + 1)
            for (x in 0 until tw) {
                val gx = ((x + 0.5f) / f - 0.5f).coerceIn(0f, w - 1f)
                val x0 = min(w - 2, gx.toInt()).coerceAtLeast(0); val tx = gx - x0; val x1 = min(w - 1, x0 + 1)
                var res = 0xFF shl 24
                for (sh in intArrayOf(16, 8, 0)) {
                    val a = (src[y0 * w + x0] shr sh) and 255; val b = (src[y0 * w + x1] shr sh) and 255
                    val c = (src[y1 * w + x0] shr sh) and 255; val d = (src[y1 * w + x1] shr sh) and 255
                    val v = (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
                    res = res or (v.roundToInt().coerceIn(0, 255) shl sh)
                }
                out[y * tw + x] = res
            }
        }
        return Img(out, tw, th)
    }

    private data class Img(val px: IntArray, val w: Int, val h: Int)

    /** Bilinear / area-ish downscale so the long side is at most [maxSide]; returns input if already small. */
    private fun downscale(src: IntArray, w: Int, h: Int, maxSide: Int): Img {
        val long = max(w, h)
        if (long <= maxSide || maxSide <= 0) return Img(src, w, h)
        val f = maxSide.toFloat() / long
        val tw = max(1, (w * f).roundToInt())
        val th = max(1, (h * f).roundToInt())
        val out = IntArray(tw * th)
        val sx = w.toFloat() / tw
        val sy = h.toFloat() / th
        for (y in 0 until th) {
            val y0 = min(h - 1, floor(y * sy).toInt())
            val y1 = max(y0 + 1, min(h, floor((y + 1) * sy).toInt()))
            for (x in 0 until tw) {
                val x0 = min(w - 1, floor(x * sx).toInt())
                val x1 = max(x0 + 1, min(w, floor((x + 1) * sx).toInt()))
                var a = 0; var r = 0; var g = 0; var b = 0
                for (yy in y0 until y1) {
                    val row = yy * w
                    for (xx in x0 until x1) {
                        val p = src[row + xx]
                        a += p ushr 24; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
                    }
                }
                val n = (y1 - y0) * (x1 - x0)
                out[y * tw + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return Img(out, tw, th)
    }
}
