# Legacy Heavier Model Implementation

This document preserves the implementation details of the heavier, original DeepFilterNet-based model ("Legacy") which was replaced by the lightweight GTCRN model.

## Overview
- **Model**: DeepFilterNet (custom ONNX export)
- **File**: `denoiser_model.onnx` (~10 MB)
- **Class**: `PoiseProcessor.kt`
- **Characteristics**: Waveform-based, larger state (~45k floats), higher latency, higher quality in some scenarios but significantly heavier on CPU/battery.

## Implementation Details

### 1. Processor Class (`PoiseProcessor.kt`)
The `PoiseProcessor` class handled the ONNX runtime session and native state management.
Key parameters:
- `FRAME_SIZE`: 480 samples (10ms at 48kHz)
- `STATE_SIZE`: 45304 floats
- Input/Output: 48kHz waveform

```kotlin
class PoiseProcessor(context: Context) {
    // Loaded "denoiser_model.onnx"
    // Managed native states via JNI
    // required extensive JNI bridge methods (nativeInit, nativeProcessPreInference, etc.)
}
```

### 2. Audio Pipeline Integration (`AudioPipeline.kt`)
The pipeline supported switching via an enum:
```kotlin
enum class ProcessorModel {
    GTCRN, // Lightweight
    LEGACY // DeepFilterNet (Heavy)
}
```

Switching logic in `processAudioLoop()` handled the different frame sizes (480 for Legacy vs 256 for GTCRN) and sample rate requirements (Legacy ran natively at 48kHz, GTCRN required downsampling to 16kHz).

### 3. Native Bridge (`jni_bridge.cpp`)
The legacy model required specific JNI methods for:
- `nativeProcessPreInference`: VAD and buffering
- `nativeCheckVAD`: Signal energy analysis
- `nativePostProcess`: Limiter and DC removal

## Restoration Guide
To restore this implementation:
1.  Ensure `denoiser_model.onnx` is present in `app/src/main/assets/`.
2.  Revert `AudioPipeline.kt` to default to `ProcessorModel.LEGACY` or re-implement the switching logic if removed.
3.  Ensure `PoiseProcessor.kt` and its corresponding JNI methods in `jni_bridge.cpp` are present.
