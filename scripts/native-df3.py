#!/usr/bin/env python3
"""
Tooling for the hand-written DeepFilterNet3 in app/src/main/cpp/df3.cpp.

    scripts/native-df3.py export   writes app/src/main/assets/df3.weights from models/df3.onnx
                                   and prints which weight is which
    scripts/native-df3.py check    builds df3.cpp for this machine and runs it next to ONNX
                                   Runtime on 2000 frames of synthetic noisy voice

models/df3.onnx is the desktop app's DF3 (poise-voice-isolator
stream_denoiser/models/denoiser_model_df3.onnx, as rewritten by its tools/optimize_df3.py).

The weights file has the same format as gtcrn.weights (see scripts/native-gtcrn.py): every weight
in the order the graph's nodes first use them, so the C++ reads them in graph order and checks
each size as it goes.

The check works like native-gtcrn.py's: one copy of df3.cpp is handed ONNX Runtime's state before
every frame and compared on every tensor df3_tensor() exposes, the other carries its own state
for all 2000 frames. Only what df3.cpp exposes is compared, so it can be checked as it is
written, stage by stage; the output counts once df3.cpp exposes "enhanced_audio_frame".

Needs numpy, onnx, onnxruntime and a host C++ compiler (c++).
"""
import ctypes
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnx import numpy_helper

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / "models" / "df3.onnx"
WEIGHTS = ROOT / "app" / "src" / "main" / "assets" / "df3.weights"
SOURCE = ROOT / "app" / "src" / "main" / "cpp" / "df3.cpp"
#: Largest |difference| allowed, relative to the reference tensor's largest |value| (at least 1).
TOLERANCE = 1e-4
FRAMES = 2000
RATE, HOP = 48000, 512
OUTPUT = "enhanced_audio_frame"
WEIGHT_OPS = {"Mul", "MatMul", "Conv", "FusedConv", "ConvTranspose", "GRU"}
CONVS = {"Conv", "FusedConv", "ConvTranspose"}


def weights(model):
    """[(node index, op, initializer name, array)] in the order nodes first use them."""
    inits = {t.name: numpy_helper.to_array(t) for t in model.graph.initializer}
    out, seen = [], set()
    for i, node in enumerate(model.graph.node):
        if node.op_type not in WEIGHT_OPS:
            continue
        for k, name in enumerate(node.input):
            a = inits.get(name)
            if a is None or a.dtype != np.float32:
                continue
            # Up to 8 values are constants the code writes out (scalings, the complex-multiply
            # signs), except a conv's bias, which is a weight of any size
            if a.size <= 8 and not (node.op_type in CONVS and k == 2):
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
    print(f"{'#':>3} {'node':>4} {'op':<14} {'shape':<18} name")
    for n, (i, op, name, a) in enumerate(entries):
        print(f"{n:>3} {i:>4} {op:<14} {str(list(a.shape)):<18} {name}")
    print(f"{sum(a.size for *_, a in entries)} floats in {len(entries)} weights -> {WEIGHTS.relative_to(ROOT)}")


def start_state(session):
    """Zeros, but for the two feature norms, which start on DF's ramps (as in the desktop's .npz)."""
    state = {}
    for i in session.get_inputs()[1:]:
        state[i.name] = np.zeros(i.shape, np.float32)
    state["erb_norm_state"][:] = np.linspace(-60, -90, 32)
    state["band_unit_norm_state"][:] = np.linspace(1e-3, 1e-4, 96).reshape(1, 96, 1)
    return state


def test_signal(seconds):
    """Voiced harmonics with a gliding pitch and syllable envelope, in noise that changes level,
    with a silent gap and a loud stretch: enough to move every part of the model."""
    rng = np.random.default_rng(42)
    t = np.arange(int(seconds * RATE)) / RATE
    pitch = 140 + 60 * np.sin(2 * np.pi * 0.3 * t) + 20 * np.sin(2 * np.pi * 5 * t)
    phase = 2 * np.pi * np.cumsum(pitch) / RATE
    voice = sum(np.sin(k * phase) / k for k in range(1, 60))
    voice *= np.clip(np.sin(2 * np.pi * 2.5 * t), 0, None) ** 2
    noise = rng.standard_normal(t.size) * (0.02 + 0.1 * (np.sin(2 * np.pi * 0.07 * t) + 1))
    x = 0.2 * voice + noise
    x[(t > 10) & (t < 12)] = 0
    x[(t > 17) & (t < 20)] *= 4
    return x.astype(np.float32)


def build():
    lib = Path(tempfile.mkdtemp()) / "libdf3.so"
    subprocess.run(["c++", "-std=c++17", "-O2", "-shared", "-fPIC", "-o", lib, SOURCE], check=True)
    so = ctypes.CDLL(str(lib))
    so.df3_create.restype = ctypes.c_void_p
    so.df3_create.argtypes = [ctypes.c_char_p, ctypes.c_size_t]
    so.df3_run.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
    so.df3_tensor.restype = ctypes.POINTER(ctypes.c_float)
    so.df3_tensor.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
    so.df3_destroy.argtypes = [ctypes.c_void_p]
    return so


def view(so, model, name, size):
    """df3.cpp's buffer for an ONNX tensor as a numpy array over the same memory, or None."""
    p = so.df3_tensor(model, name.encode())
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
    state = start_state(session)
    states = {s: "new_" + s for s in state}  # state input -> the output that updates it

    so = build()
    forced, free = (so.df3_create(blob, len(blob)) for _ in range(2))
    if not forced:
        sys.exit("df3_create rejected the weights file")
    out = np.zeros(HOP, np.float32)
    free_out = np.zeros_like(out)
    # What df3.cpp exposes: the state it owns, the intermediates it keeps, the output once it's done
    owned = [s for s in states if so.df3_tensor(forced, s.encode())]
    kept = [n for n in order if n not in states.values() and so.df3_tensor(forced, n.encode())]
    worst = {}   # tensor -> (error, frame), forced copy
    drift = []   # per frame, free-running copy: worst error over the output and its state
    seconds = 0.0

    # Its own state must start where ONNX Runtime's does
    for s in owned:
        e = error(view(so, free, s, state[s].size), state[s].ravel())
        if e > TOLERANCE:
            sys.exit(f"df3.cpp starts {s} {e:.2e} away from the desktop's start state")

    try:
        x = test_signal(FRAMES * HOP / RATE)
        for f in range(FRAMES):
            frame = np.ascontiguousarray(x[f * HOP:(f + 1) * HOP])
            for s in owned:
                view(so, forced, s, state[s].size)[:] = state[s].ravel()
            ref = dict(zip(names, session.run(names, {"input_frame": frame, **state})))
            so.df3_run(forced, frame.ctypes.data, out.ctypes.data)
            start = time.perf_counter()
            so.df3_run(free, frame.ctypes.data, free_out.ctypes.data)
            seconds += time.perf_counter() - start

            compared = {states[s]: view(so, forced, s, state[s].size) for s in owned}
            compared.update({n: view(so, forced, n, ref[n].size) for n in kept})
            for name, v in compared.items():
                e = error(v, ref[name].ravel())
                if e > worst.get(name, (-1, 0))[0]:
                    worst[name] = (e, f)
            drift.append(max([error(free_out, ref[OUTPUT].ravel())] * (OUTPUT in kept) +
                             [error(view(so, free, s, state[s].size), ref[states[s]].ravel()) for s in owned]))
            state = {s: ref[o] for s, o in states.items()}
    finally:
        so.df3_destroy(forced)
        so.df3_destroy(free)

    position = {name: i for i, name in enumerate(order)}
    print(f"{'tensor':<48} {'shape':<16} {'worst error':>11} {'at frame':>8}")
    for name, (e, f) in sorted(worst.items(), key=lambda kv: position.get(kv[0], -1)):
        mark = "  FAIL" if e > TOLERANCE else ""
        print(f"{name:<48} {str(list(ref[name].shape)):<16} {e:>11.2e} {f:>8}{mark}")
    quarter = FRAMES // 4
    print(f"own state for {FRAMES} frames: worst error {max(drift):.2e} "
          f"(first quarter {max(drift[:quarter]):.2e}, last {max(drift[-quarter:]):.2e})")
    missing = [s for s in states if s not in owned]
    print(f"df3.cpp: {1e3 * seconds / FRAMES:.3f} ms per frame on this machine; "
          f"output {'compared' if OUTPUT in kept else 'not written yet'}"
          + (f"; state not handled yet: {', '.join(missing)}" if missing else ""))
    failed = max(e for e, _ in worst.values()) > TOLERANCE or max(drift) > TOLERANCE
    print("FAIL" if failed else "OK")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    {"export": export, "check": check}.get(sys.argv[1] if len(sys.argv) > 1 else "", lambda: sys.exit(__doc__))()
