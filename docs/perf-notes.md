# Performance notes (on-device AI line art)

Sources fetched through Context7 (current official docs). URLs as cited by Context7.

## ONNX Runtime (Java / Android)
- Thread pool: `SessionOptions.setIntraOpNumThreads` = "size of the CPU thread pool used for executing a single graph";
  `setInterOpNumThreads` = pool for running multiple requests concurrently (we run one: keep 1).
  https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html
- Defaults (threading doc): `intra_op_num_threads=0` (all physical cores), `ORT_SEQUENTIAL`, `ORT_ENABLE_ALL`,
  `session.intra_op.allow_spinning=1`. We set SEQUENTIAL explicitly, allow_spinning=0 (battery).
  https://onnxruntime.ai/docs/performance/tune-performance/threading.html
- `session.dynamic_block_base` = positive value (recommended 4) enables the dynamic cost model, "reduces latency variance".
  https://onnxruntime.ai/docs/performance/tune-performance/troubleshooting.html
- `setMemoryPatternOptimization` (preallocate when shapes known) and `setCPUArenaAllocator` kept OFF: our shapes vary per image and
  peak memory matters more than allocator speed on phones.
- XNNPACK EP (recommended configuration): it has its own pool -> disable ORT spinning, XNNPACK `intra_op_num_threads` = physical cores,
  ORT intra-op = 1 "if the compute-heavy nodes are XNNPACK-supported"; if some compute-heavy ops fall to CPU EP, raise ORT intra-op.
  https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html
- ORT format: `python -m onnxruntime.tools.convert_onnx_models_to_ort <model|dir>`; `--optimization_style Fixed` bakes platform-specific
  optimisations (recommended for non-NNAPI/CoreML), `--target_platform arm`. https://onnxruntime.ai/docs/performance/model-optimizations/ort-format-models.html
- `RunOptions.setTerminate(boolean)` is public: incomplete `run` calls using that RunOptions terminate ASAP.
  https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.RunOptions.html
- fp16: "Avoid float16 models if possible, as float16 is not natively supported by CPU and it is going to be slow"
  (https://onnxruntime.ai/docs/tutorials/web/performance-diagnosis.html). Quantization doc: int8 gains need dot-product ARM cores
  (A715/A510 have them) but quantize/dequantize overhead can make it slower; measure.
  https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html

## Decisions
1. Session: SEQUENTIAL, inter=1, spinning off, dynamic_block_base=4. CPU EP: intra = min(4, cores). XNNPACK: ORT intra = 2 (the
   models contain InstanceNorm etc. that run on CPU EP, so not 1), XNNPACK pool = min(4, cores). Constant `MAX_THREADS`
   in OnnxLineArtModel; A/B 2 vs 4 on the Dimensity 7200 with the in-app benchmark (needs a device; not measurable here).
2. fp16 models are the worst fit for the phone CPU: plan is fp32 (or dynamic int8 if the CI quality gate passes) instead.
   `ModelSpec.int8` added (CPU EP only). Download URLs are NOT switched yet: variants are produced and judged by the model-lab
   workflow (`tools/model_lab/variants.py` -> `variants.json`, verdict pass/fail/unverified); switch a URL only for a `pass` file.
   Caveat: dynamic quantisation mostly helps MatMul/Gemm; Conv nets often need static QDQ, so expect int8 to fail the gate for some.
3. `.ort` (arm, Fixed) is produced and verified on the runner only if x86 can load it ("unverified" otherwise): validate on device.
4. terminate: reflection replaced by the documented public `setTerminate`.

## Kotlin / Android
- Dispatchers.Default is the shared CPU-bound pool (async(Dispatchers.Default) + awaitAll for chunked work).
  https://github.com/kotlin/kotlinx.coroutines/blob/master/docs/topics/coroutines-and-channels.md
  Not applied to the pure-Kotlin hot loops: dev box has 2 loaded cores so a gain could not be verified.
- Hot loops: primitive arrays only; the box-blur / morphology column pass now runs row-major with a running sum per column.

## Measured (JVM harness, warmed, best-of-N, beard.png / elf.png, model cached)
Shading stage (MEDIUM): beard ~280 -> ~176 ms, elf ~250 -> ~186 ms (bit-identical output, verified against the old column-walk).
