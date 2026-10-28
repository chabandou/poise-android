#!/usr/bin/env python3
"""
Tooling for the hand-written GTCRN in app/src/main/cpp/gtcrn.cpp.

    scripts/native-gtcrn.py export   writes app/src/main/assets/gtcrn.weights from models/gtcrn.onnx
                                     and prints which weight is which
    scripts/native-gtcrn.py check    builds gtcrn.cpp for this machine and runs it next to
                                     ONNX Runtime on 2000 frames of synthetic noisy voice

The weights file: little-endian uint32 count, then count uint32 sizes, then the float32 values.
It holds every weight in the order the graph's nodes first use it, so the C++ reads them in
graph order and checks each size as it goes.

The check runs two copies of gtcrn.cpp beside one ONNX Runtime session. The first is handed
ONNX Runtime's state before every frame, so an error shows up in the frame and the stage that
made it: besides the output and the state it compares every intermediate tensor that
gtcrn_tensor() exposes. The second carries its own state for all 2000 frames, so error that
builds up over time shows. Any tensor off by more than TOLERANCE of its largest value fails.

Needs numpy, onnx, onnxruntime and a host C++ compiler (c++).
"""
import ctypes
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnx import numpy_helper

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / "models" / "gtcrn.onnx"
WEIGHTS = ROOT / "app" / "src" / "main" / "assets" / "gtcrn.weights"
SOURCE = ROOT / "app" / "src" / "main" / "cpp" / "gtcrn.cpp"
#: Largest |difference| allowed, relative to the reference tensor's largest |value| (at least 1).
TOLERANCE = 1e-4
FRAMES = 2000
HOP, FFT = 256, 512
WEIGHT_OPS = {"Conv", "ConvTranspose", "PRelu", "GRU", "MatMul", "LayerNormalization", "Add"}
STATES = {"conv_cache": "conv_cache_out", "tra_cache": "tra_cache_out", "inter_cache": "inter_cache_out"}


def weights(model):
    """[(node index, op, initializer name, array)] in the order nodes first use them."""
    inits = {t.name: numpy_helper.to_array(t) for t in model.graph.initializer}
    out, seen = [], set()
    for i, node in enumerate(model.graph.node):
        if node.op_type not in WEIGHT_OPS:
            continue
        for name in node.input:
            a = inits.get(name)
            # Size-1 Add inputs are constants (an epsilon), not weights
            if a is None or a.dtype != np.float32 or (node.op_type == "Add" and a.size == 1):
                continue
            assert name not in seen, f"{name} is used by two weighted nodes"
            seen.add(name)
            out.append((i, node.op_type, name, a))
    return out


def weights_bytes(entries):
    sizes = np.array([a.size for *_, a in entries], "<u4")
    values = np.concatenate([a.ravel() for *_, a in entries]).astype("<f4")
    return np.array([len(sizes)], "<u4").tobytes() + sizes.tobytes() + values.tobytes()


def export():
    entries = weights(onnx.load(MODEL))
    WEIGHTS.write_bytes(weights_bytes(entries))
    print(f"{'#':>3} {'node':>4} {'op':<18} {'shape':<16} name")
    for n, (i, op, name, a) in enumerate(entries):
        print(f"{n:>3} {i:>4} {op:<18} {str(list(a.shape)):<16} {name}")
    print(f"{sum(a.size for *_, a in entries)} floats in {len(entries)} weights -> {WEIGHTS.relative_to(ROOT)}")


def test_signal(seconds):
    """Voiced harmonics with a gliding pitch and syllable envelope, in noise that changes level,
    with a silent gap and a loud stretch: enough to move every part of the model."""
    rng = np.random.default_rng(42)
    t = np.arange(int(seconds * 16000)) / 16000
    pitch = 140 + 60 * np.sin(2 * np.pi * 0.3 * t) + 20 * np.sin(2 * np.pi * 5 * t)
    phase = 2 * np.pi * np.cumsum(pitch) / 16000
    voice = sum(np.sin(k * phase) / k for k in range(1, 30))
    voice *= np.clip(np.sin(2 * np.pi * 2.5 * t), 0, None) ** 2
    noise = rng.standard_normal(t.size) * (0.02 + 0.1 * (np.sin(2 * np.pi * 0.07 * t) + 1))
    x = 0.2 * voice + noise
    x[(t > 10) & (t < 12)] = 0
    x[(t > 20) & (t < 23)] *= 4
    return x.astype(np.float32)


def spectra(x):
    """[frames, 1, 257, 1, 2] spectra, as the app makes them: 512-point sqrt-Hann, hop 256."""
    window = np.sqrt(np.hanning(FFT + 1)[:FFT]).astype(np.float32)
    padded = np.concatenate([np.zeros(FFT - HOP, np.float32), x])
    n = x.size // HOP
    frames = np.lib.stride_tricks.sliding_window_view(padded, FFT)[::HOP][:n]
    spec = np.fft.rfft(frames * window)
    return np.stack([spec.real, spec.imag], -1).astype(np.float32).reshape(n, 1, 257, 1, 2)


def build():
    lib = Path(tempfile.mkdtemp()) / "libgtcrn.so"
    subprocess.run(["c++", "-std=c++17", "-O2", "-shared", "-fPIC", "-o", lib, SOURCE], check=True)
    so = ctypes.CDLL(str(lib))
    so.gtcrn_create.restype = ctypes.c_void_p
    so.gtcrn_create.argtypes = [ctypes.c_char_p, ctypes.c_size_t]
    so.gtcrn_run.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
    so.gtcrn_tensor.restype = ctypes.POINTER(ctypes.c_float)
    so.gtcrn_tensor.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
    so.gtcrn_destroy.argtypes = [ctypes.c_void_p]
    return so


def view(so, model, name, size):
    """gtcrn.cpp's buffer for an ONNX tensor as a numpy array over the same memory, or None."""
    p = so.gtcrn_tensor(model, name.encode())
    return np.ctypeslib.as_array(p, (size,)) if p else None


def error(actual, reference):
    return float(np.abs(actual - reference).max() / max(1.0, np.abs(reference).max()))


def check():
    proto = onnx.load(MODEL)
    blob = weights_bytes(weights(proto))
    if not WEIGHTS.exists() or WEIGHTS.read_bytes() != blob:
        sys.exit(f"{WEIGHTS.relative_to(ROOT)} doesn't match {MODEL.relative_to(ROOT)}: run export")

    # Every intermediate tensor becomes an output, so any of them can be compared
    graph_outputs = {o.name for o in proto.graph.output}
    order = [o for node in proto.graph.node for o in node.output if o]
    for name in order:
        if name not in graph_outputs:
            proto.graph.output.append(onnx.helper.make_tensor_value_info(name, onnx.TensorProto.FLOAT, None))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    session = ort.InferenceSession(proto.SerializeToString(), options, providers=["CPUExecutionProvider"])
    names = [o.name for o in session.get_outputs()]
    state = {i.name: np.zeros([d for d in i.shape], np.float32) for i in session.get_inputs() if i.name != "mix"}

    so = build()
    forced, free = (so.gtcrn_create(blob, len(blob)) for _ in range(2))
    if not forced:
        sys.exit("gtcrn_create rejected the weights file")
    enh = np.zeros(257 * 2, np.float32)
    free_enh = np.zeros_like(enh)
    worst = {}   # tensor -> (error, frame), forced copy
    drift = []   # per frame, free-running copy: worst error over the output and the state

    try:
        for f, mix in enumerate(spectra(test_signal(FRAMES * HOP / 16000))):
            for name, value in state.items():
                view(so, forced, name, value.size)[:] = value.ravel()
            ref = dict(zip(names, session.run(names, {"mix": mix, **state})))
            so.gtcrn_run(forced, mix.ctypes.data, enh.ctypes.data)
            so.gtcrn_run(free, mix.ctypes.data, free_enh.ctypes.data)

            compared = {"enh": enh}
            for name in [*STATES, *order]:
                if name not in compared and name != "enh":
                    target = STATES.get(name, name)
                    v = view(so, forced, name, ref[target].size)
                    if v is not None:
                        compared[target] = v
            for name, v in compared.items():
                e = error(v, ref[name].ravel())
                if e > worst.get(name, (-1, 0))[0]:
                    worst[name] = (e, f)
            drift.append(max([error(free_enh, ref["enh"].ravel())] +
                             [error(view(so, free, s, ref[o].size), ref[o].ravel()) for s, o in STATES.items()]))
            state = {s: ref[o] for s, o in STATES.items()}
    finally:
        so.gtcrn_destroy(forced)
        so.gtcrn_destroy(free)

    position = {name: i for i, name in enumerate(order)}
    print(f"{'tensor':<28} {'shape':<18} {'worst error':>11} {'at frame':>8}")
    for name, (e, f) in sorted(worst.items(), key=lambda kv: position.get(kv[0], -1)):
        mark = "  FAIL" if e > TOLERANCE else ""
        print(f"{name:<28} {str(list(ref[name].shape)):<18} {e:>11.2e} {f:>8}{mark}")
    quarter = FRAMES // 4
    print(f"own state for {FRAMES} frames: worst error {max(drift):.2e} "
          f"(first quarter {max(drift[:quarter]):.2e}, last {max(drift[-quarter:]):.2e})")
    failed = max(e for e, _ in worst.values()) > TOLERANCE or max(drift) > TOLERANCE
    print("FAIL" if failed else "OK")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    {"export": export, "check": check}.get(sys.argv[1] if len(sys.argv) > 1 else "", lambda: sys.exit(__doc__))()
