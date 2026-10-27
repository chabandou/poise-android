#!/usr/bin/env bash
# Rebuilds app/libs/onnxruntime-poise-1.16.3.aar and app/src/main/assets/gtcrn.ort from
# models/gtcrn.onnx: ONNX Runtime 1.16.3 as a minimal build with only the operators GTCRN uses.
# Usage: scripts/build-ort.sh [work dir, default ../onnxruntime-poise]. Needs uv, patchelf, ninja.
set -euo pipefail
cd "$(dirname "$0")/.."
APP=$PWD
WORK=$(realpath -m "${1:-../onnxruntime-poise}")
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
mkdir -p "$WORK" && cd "$WORK"

# The converter must be the runtime's version, so the model format matches
if [ ! -d .venv ]; then
  uv venv -q --python 3.11 .venv
  uv pip install -q --python .venv/bin/python onnxruntime==1.16.3 onnx==1.15.0 "numpy<2" flatbuffers
  # Newer glibc refuses to load this wheel because it asks for an executable stack
  patchelf --clear-execstack .venv/lib/python3.11/site-packages/onnxruntime/capi/onnxruntime_pybind11_state*.so
fi
# ORT format with the CPU optimizations baked in, plus the list of operators and types it needs
.venv/bin/python -I -m onnxruntime.tools.convert_onnx_models_to_ort "$APP/models/gtcrn.onnx" \
  --output_dir . --enable_type_reduction --target_platform arm

if [ ! -d onnxruntime ]; then
  git clone -q --depth 1 --branch v1.16.3 --recursive --shallow-submodules https://github.com/microsoft/onnxruntime.git
fi
# GitLab regenerated its archives, so the pinned hash of the Eigen zip changed (same commit)
sed -i 's/be8be39fdbc6e60e94fa7870b280707069b5b81a/32b145f525a8308d7ab1c09388b2e288312d8eba/' onnxruntime/cmake/deps.txt
# Left in the source tree by the protobuf step, which fails on a second run if they exist
rm -rf onnxruntime/{abseil,absl_src.tar.gz,protobuf,protobuf_src.tar.gz}

# The official "Mobile" settings, arm64 only. No XNNPACK: in a 1.16 minimal build it aborts on
# its NHWC Conv nodes, and it only saved 0.3 ms per frame. The linker flag gives 16 KB pages.
cat > build_settings.json <<'JSON'
{
    "build_abis": ["arm64-v8a"],
    "android_min_sdk_version": 29,
    "android_target_sdk_version": 34,
    "build_variant": "Mobile",
    "build_params": [
        "--android", "--parallel", "6", "--cmake_generator=Ninja",
        "--build_java", "--build_shared_lib", "--minimal_build=extended",
        "--disable_rtti", "--disable_ml_ops", "--disable_exceptions",
        "--enable_reduced_operator_type_support", "--skip_tests",
        "--cmake_extra_defines", "CMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
    ]
}
JSON
(cd onnxruntime && CMAKE_POLICY_VERSION_MINIMUM=3.5 ../.venv/bin/python \
  tools/ci_build/github/android/build_aar_package.py \
  --android_sdk_path "$SDK" --android_ndk_path "$SDK/ndk/25.1.8937393" \
  --build_dir "$WORK/build" --config MinSizeRel \
  --include_ops_by_config "$WORK/gtcrn.required_operators_and_types.config" "$WORK/build_settings.json")

cp build/aar_out/MinSizeRel/com/microsoft/onnxruntime/onnxruntime-mobile/1.16.3/onnxruntime-mobile-1.16.3.aar \
  "$APP/app/libs/onnxruntime-poise-1.16.3.aar"
cp gtcrn.ort "$APP/app/src/main/assets/gtcrn.ort"
