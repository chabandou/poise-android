/**
 * Building blocks shared by the hand-written models (gtcrn.cpp, df3.cpp): the weights file, convs
 * over frequency, GRUs and fast activations. No Android dependencies, so the desktop checks
 * (scripts/native-*.py) build them too.
 */

#ifndef POISE_NN_H
#define POISE_NN_H

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>
#ifdef __ARM_NEON
#include <arm_neon.h>
#endif

namespace {

/**
 * Parses a weights file: little-endian uint32 count, count uint32 sizes, then the float32 values.
 * An int8 weight takes its size in bytes, padded to a multiple of 4, among them (Reader::take_i8).
 * False if the sizes don't fit the file.
 */
inline bool read_weights(const void *data, size_t size, std::vector<uint32_t> &sizes, std::vector<float> &values) {
  uint32_t count;
  if (size < 4) return false;
  memcpy(&count, data, 4);
  size_t header = 4 + size_t(count) * 4;
  if (size < header || (size - header) % 4) return false;
  sizes.resize(count);
  memcpy(sizes.data(), static_cast<const char *>(data) + 4, count * 4);
  values.resize((size - header) / 4);
  memcpy(values.data(), static_cast<const char *>(data) + header, size - header);
  return true;
}

/** Hands out the weights in file order, failing on the first one whose size isn't as expected. */
struct Reader {
  const uint32_t *sizes;
  uint32_t count, next = 0;
  const float *values, *end;
  bool ok = true;

  const float *take(size_t n) {
    if (!ok || next >= count || sizes[next] != n || values + n > end) {
      ok = false;
      return nullptr;
    }
    next++;
    const float *w = values;
    values += n;
    return w;
  }

  /** n int8 values, stored as n bytes padded to whole floats. */
  const int8_t *take_i8(size_t n) {
    size_t words = (n + 3) / 4;
    if (!ok || next >= count || sizes[next] != n || values + words > end) {
      ok = false;
      return nullptr;
    }
    next++;
    auto *w = reinterpret_cast<const int8_t *>(values);
    values += words;
    return w;
  }
};

/** y[i * SY] += w * x[i * SX] for i < n: the inner loop of every conv and band mapping. */
template <int SY = 1, int SX = 1>
void axpy(float *__restrict y, const float *__restrict x, float w, int n) {
  for (int i = 0; i < n; i++) y[i * SY] += w * x[i * SX];
}

/**
 * Conv with a 1×k kernel, over frequency only: in [cin][fin] -> out [cout][fout].
 * Each kernel tap is added to a whole output row at once, which the compiler vectorizes.
 */
void conv(const float *in, int cin, int fin, float *out, int cout, int fout, const float *w,
          const float *b, int k, int stride, int pad, int groups) {
  int cin_g = cin / groups, cout_g = cout / groups;
  for (int o = 0; o < cout; o++) {
    float *y = out + o * fout;
    const float *x = in + (o / cout_g) * cin_g * fin;
    for (int f = 0; f < fout; f++) y[f] = b ? b[o] : 0;
    for (int i = 0; i < cin_g; i++) {
      for (int j = 0; j < k; j++) {
        // Outputs whose tap j lands inside the input: 0 <= f * stride + j - pad < fin
        int lo = j >= pad ? 0 : (pad - j + stride - 1) / stride;
        int hi = std::min(fout, (fin - 1 + pad - j) / stride + 1);
        float wv = w[(o * cin_g + i) * k + j];
        const float *xs = x + i * fin + lo * stride + j - pad;
        if (lo < hi) (stride == 1 ? axpy<1, 1> : axpy<1, 2>)(y + lo, xs, wv, hi - lo);
      }
    }
  }
}

/**
 * Four floats as one value: GCC and Clang compile its arithmetic to NEON on the phone and SSE on
 * the desktop, so the desktop check runs the same code as the app.
 */
typedef float f32x4 __attribute__((vector_size(16)));

inline f32x4 load4(const float *p) {
  f32x4 v;
  memcpy(&v, p, sizeof v); // any alignment; compiles to one load
  return v;
}

inline void store4(float *p, f32x4 v) { memcpy(p, &v, sizeof v); }

inline f32x4 splat4(float x) { return f32x4{x, x, x, x}; }

inline float sum4(f32x4 v) { return (v[0] + v[1]) + (v[2] + v[3]); }

/**
 * y[r] = b[r] + w[r] · x for w [rows][cols], as ONNX stores it; cols % 8 == 0. One row at a time,
 * with two sums: on the A73 (realme C67) reading four rows at once ran 3x slower.
 */
inline void matvec(const float *w, const float *x, const float *b, float *y, int rows, int cols) {
  for (int r = 0; r < rows; r++, w += cols) {
    f32x4 a = {}, c = {};
    for (int k = 0; k < cols; k += 8) {
      a += load4(w + k) * load4(x + k);
      c += load4(w + k + 4) * load4(x + k + 4);
    }
    y[r] = b[r] + sum4(a + c);
  }
}

/** q = round(x / scale) with scale = max |x| / 127, so q is in [-127, 127]; returns scale. */
inline float quantize(const float *x, int n, int8_t *q) {
  float m = 0;
  for (int i = 0; i < n; i++) m = std::max(m, std::fabs(x[i]));
  if (m == 0) {
    memset(q, 0, n);
    return 0;
  }
  float inv = 127 / m;
  for (int i = 0; i < n; i++) q[i] = int8_t(std::lrint(x[i] * inv));
  return m / 127;
}

/**
 * y[r] = b[r] + ws[r] * xs * (w[r] · x) for int8 w [rows][cols] and x; cols % 32 == 0. The sums
 * are exact integers, so NEON and the plain loop agree. Two products fit in int16 as long as
 * neither side is -128. On the A73 this is 2.5x as fast as matvec, on the A53 2.8x.
 */
inline void matvec_i8(const int8_t *w, const float *ws, const int8_t *x, float xs, const float *b, float *y,
                      int rows, int cols) {
  for (int r = 0; r < rows; r++, w += cols) {
#ifdef __ARM_NEON
    int32x4_t a = vdupq_n_s32(0), c = vdupq_n_s32(0);
    for (int k = 0; k < cols; k += 32) {
      int8x16_t w0 = vld1q_s8(w + k), x0 = vld1q_s8(x + k), w1 = vld1q_s8(w + k + 16), x1 = vld1q_s8(x + k + 16);
      a = vpadalq_s16(a, vmlal_high_s8(vmull_s8(vget_low_s8(w0), vget_low_s8(x0)), w0, x0));
      c = vpadalq_s16(c, vmlal_high_s8(vmull_s8(vget_low_s8(w1), vget_low_s8(x1)), w1, x1));
    }
    int32_t sum = vaddvq_s32(vaddq_s32(a, c));
#else
    int32_t sum = 0;
    for (int k = 0; k < cols; k++) sum += w[k] * x[k];
#endif
    y[r] = b[r] + ws[r] * xs * float(sum);
  }
}

/**
 * tanh as a ratio of two polynomials (Eigen's float approximation), within 3.3e-7 of std::tanh.
 * Unlike std::tanh it's inline arithmetic, so loops over it vectorize. Past ±9 tanh is ±1 in float.
 */
inline float tanh_fast(float x) {
  x = std::min(9.f, std::max(-9.f, x));
  float x2 = x * x;
  float p = x2 * -2.76076847742355e-16f + 2.00018790482477e-13f;
  p = x2 * p + -8.60467152213735e-11f;
  p = x2 * p + 5.12229709037114e-08f;
  p = x2 * p + 1.48572235717979e-05f;
  p = x2 * p + 6.37261928875436e-04f;
  p = x2 * p + 4.89352455891786e-03f;
  float q = x2 * 1.19825839466702e-06f + 1.18534705686654e-04f;
  q = x2 * q + 2.26843463243900e-03f;
  q = x2 * q + 4.89352518554385e-03f;
  return x * p / q;
}

inline float sigmoid(float x) { return 0.5f + 0.5f * tanh_fast(0.5f * x); }

/** An ONNX GRU's weights, hidden size H, in the weights file's memory: W [3 H][input], R [3 H][H], biases. */
template <int H>
struct Gru {
  const float *w, *r, *b;

  void load(Reader &reader, int in_size) {
    w = reader.take(3 * H * in_size);
    r = reader.take(3 * H * H);
    b = reader.take(6 * H);
  }
};

/**
 * Gru with int8 weights, each row with its own scale (W and R as written by the export), and its
 * input and hidden state quantized to int8 on every step. Input size at most H.
 */
template <int H>
struct GruI8 {
  const int8_t *w, *r;
  const float *ws, *rs, *b;

  void load(Reader &reader, int in_size) {
    w = reader.take_i8(3 * H * in_size);
    ws = reader.take(3 * H);
    r = reader.take_i8(3 * H * H);
    rs = reader.take(3 * H);
    b = reader.take(6 * H);
  }
};

template <int H>
void gate_inputs(const Gru<H> &p, int in_size, const float *x, const float *h, float *gx, float *gh) {
  matvec(p.w, x, p.b, gx, 3 * H, in_size);
  matvec(p.r, h, p.b + 3 * H, gh, 3 * H, H);
}

template <int H>
void gate_inputs(const GruI8<H> &p, int in_size, const float *x, const float *h, float *gx, float *gh) {
  int8_t q[H];
  float s = quantize(x, in_size, q);
  matvec_i8(p.w, p.ws, q, s, p.b, gx, 3 * H, in_size);
  s = quantize(h, H, q);
  matvec_i8(p.r, p.rs, q, s, p.b + 3 * H, gh, 3 * H, H);
}

/** One step of an ONNX GRU (Gru or GruI8), gates z, r, h, linear_before_reset. Updates h. */
template <int H, template <int> class P>
void gru(const P<H> &p, int in_size, const float *x, float *h) {
  float gx[3 * H], gh[3 * H];
  gate_inputs(p, in_size, x, h, gx, gh);
  for (int j = 0; j < H; j++) {
    float z = sigmoid(gx[j] + gh[j]);
    float r = sigmoid(gx[H + j] + gh[H + j]);
    float n = tanh_fast(gx[2 * H + j] + r * gh[2 * H + j]);
    h[j] = (1 - z) * n + z * h[j];
  }
}

} // namespace

#endif // POISE_NN_H
