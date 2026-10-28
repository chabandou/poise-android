/**
 * GTCRN forward pass, one 16 ms frame per call, ported from models/gtcrn.onnx.
 *
 * No Android dependencies, so scripts/native-gtcrn.py can build it on the desktop and check it
 * against ONNX Runtime.
 */

#ifndef GTCRN_H
#define GTCRN_H

#include <cstddef>

extern "C" {

struct Gtcrn;

/** weights: the bytes of assets/gtcrn.weights. Null if they aren't what the code expects. */
Gtcrn *gtcrn_create(const void *weights, size_t size);
void gtcrn_destroy(Gtcrn *model);

/** Clears the state carried from frame to frame. */
void gtcrn_reset(Gtcrn *model);

/** mix and enh: 257 bins of (real, imaginary), the model's [1, 257, 1, 2] layout. */
void gtcrn_run(Gtcrn *model, const float *mix, float *enh);

/**
 * For the desktop check: the buffer holding a tensor of the ONNX graph, by its name and in its
 * layout, or null if the code doesn't keep it. "conv_cache", "tra_cache" and "inter_cache" are the
 * state: writable, and after a run they hold what the next run reads. Any other name is an
 * intermediate as it was at the end of the last run.
 */
float *gtcrn_tensor(Gtcrn *model, const char *name);

} // extern "C"

#endif // GTCRN_H
