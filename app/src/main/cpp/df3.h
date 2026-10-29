/**
 * DeepFilterNet3 forward pass, one 512-sample frame (10.7 ms at 48 kHz) per call, ported from
 * models/df3.onnx.
 *
 * No Android dependencies, so scripts/native-df3.py can build it on the desktop and check it
 * against ONNX Runtime.
 */

#ifndef DF3_H
#define DF3_H

#include <cstddef>

extern "C" {

struct Df3;

/** weights: the bytes of assets/df3.weights. Null if they aren't what the code expects. */
Df3 *df3_create(const void *weights, size_t size);
void df3_destroy(Df3 *model);

/** Back to the start state: silence before the first frame. */
void df3_reset(Df3 *model);

/** in and out: 512 samples. Output sample n is input sample n - 1536. */
void df3_run(Df3 *model, const float *in, float *out);

/**
 * For the desktop check: the buffer holding a tensor of the ONNX graph, by its name and in its
 * layout, or null if the code doesn't keep it. A state input's name (e.g. "enc_hidden") gives
 * that state: writable, and after a run it holds what the next run reads. Any other name is an
 * intermediate as it was at the end of the last run.
 */
float *df3_tensor(Df3 *model, const char *name);

} // extern "C"

#endif // DF3_H
