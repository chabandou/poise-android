/**
 * GTCRN forward pass, node for node what models/gtcrn.onnx computes; see gtcrn.h.
 *
 * Tensors keep their ONNX layouts with the size-1 axes dropped, so a [1, 16, 1, 33] tensor is
 * [channel][frequency]. Comments name the ONNX tensors the code produces.
 */

#include "gtcrn.h"

#include <cmath>
#include <cstdint>
#include <cstring>
#include <algorithm>
#include <utility>
#include <vector>

namespace {

constexpr int kBins = 257, kLow = 65, kBands = 129, kHigh = kBins - kLow, kHighBands = kBands - kLow;

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
};

float prelu_slope(Reader &r) {
  const float *a = r.take(1);
  return a ? *a : 0;
}

void prelu(float *x, int n, float slope) {
  for (int i = 0; i < n; i++) x[i] = x[i] < 0 ? slope * x[i] : x[i];
}

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

/** w [rows][cols] -> [cols][rows], so sums over a row of w run as a loop over output values. */
std::vector<float> transpose(const float *w, int rows, int cols) {
  std::vector<float> t;
  if (!w) return t;
  t.resize(rows * cols);
  for (int r = 0; r < rows; r++)
    for (int c = 0; c < cols; c++) t[c * rows + r] = w[r * cols + c];
  return t;
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

/** An ONNX GRU's weights, W and R transposed to [input][gate]. */
struct Gru {
  std::vector<float> w, r;
  const float *b;

  void load(Reader &reader, int in_size) {
    w = transpose(reader.take(48 * in_size), 48, in_size);
    r = transpose(reader.take(48 * 16), 48, 16);
    b = reader.take(96);
  }
};

/** One step of an ONNX GRU, hidden size 16, gates z, r, h, linear_before_reset. Updates h. */
void gru(const Gru &p, int in_size, const float *x, float *h) {
  constexpr int H = 16;
  float gx[3 * H], gh[3 * H];
  for (int g = 0; g < 3 * H; g++) {
    gx[g] = p.b[g];
    gh[g] = p.b[3 * H + g];
  }
  for (int i = 0; i < in_size; i++)
    for (int g = 0; g < 3 * H; g++) gx[g] += p.w[i * 3 * H + g] * x[i];
  for (int i = 0; i < H; i++)
    for (int g = 0; g < 3 * H; g++) gh[g] += p.r[i * 3 * H + g] * h[i];
  for (int j = 0; j < H; j++) {
    float z = sigmoid(gx[j] + gh[j]);
    float r = sigmoid(gx[H + j] + gh[H + j]);
    float n = tanh_fast(gx[2 * H + j] + r * gh[2 * H + j]);
    h[j] = (1 - z) * n + z * h[j];
  }
}

/**
 * Grouped temporal conv block: channels 0-7 go through a frequency conv, a depthwise conv over
 * this frame and two earlier ones (dilation frames apart), a 1×1 conv and a GRU-driven gain;
 * channels 8-15 pass through; the two halves come out interleaved.
 */
struct Block {
  int dilation, start; // its history: conv cache frames start .. start + 2 * dilation - 1
  const float *conv_w, *conv_b, *depth_w, *depth_b, *point_w, *point_b;
  Gru tra;
  const float *gain_w, *gain_b;
  float conv_slope, depth_slope;
  float out[16][33];

  bool load(Reader &r, int d) {
    dilation = d;
    start = d == 1 ? 0 : d == 2 ? 2 : 6;
    conv_w = r.take(16 * 8 * 3);
    conv_b = r.take(16);
    conv_slope = prelu_slope(r);
    depth_w = r.take(16 * 3 * 3);
    depth_b = r.take(16);
    depth_slope = prelu_slope(r);
    point_w = r.take(8 * 16);
    point_b = r.take(8);
    tra.load(r, 8);
    gain_w = r.take(16 * 8);
    gain_b = r.take(8);
    return r.ok;
  }

  /** in: [16][33]; history: this side's conv cache [16][16][33]; hidden: its tra cache [16]. */
  void run(const float *in, float (*history)[16][33], float *hidden) {
    float h[16][33], depth[16][33], point[8][33];
    conv(in, 8, 33, h[0], 16, 33, conv_w, conv_b, 3, 1, 1, 1);
    prelu(h[0], 16 * 33, conv_slope);

    int last = start + 2 * dilation - 1;
    for (int c = 0; c < 16; c++) {
      const float *taps[3] = {history[c][start], history[c][start + dilation], h[c]};
      for (int f = 0; f < 33; f++) depth[c][f] = depth_b[c];
      for (int t = 0; t < 3; t++)
        for (int j = 0; j < 3; j++) {
          float wv = depth_w[(c * 3 + t) * 3 + j];
          if (j == 0) axpy(depth[c] + 1, taps[t], wv, 32);
          else axpy(depth[c], taps[t] + j - 1, wv, j == 1 ? 33 : 32);
        }
      memmove(history[c][start], history[c][start + 1], (last - start) * 33 * sizeof(float));
      memcpy(history[c][last], h[c], 33 * sizeof(float));
    }
    prelu(depth[0], 16 * 33, depth_slope);
    conv(depth[0], 16, 33, point[0], 8, 33, point_w, point_b, 1, 1, 0, 1);

    float energy[8];
    for (int c = 0; c < 8; c++) {
      float s = 0;
      for (int f = 0; f < 33; f++) s += point[c][f] * point[c][f];
      energy[c] = s / 33;
    }
    gru(tra, 8, energy, hidden);
    for (int c = 0; c < 8; c++) {
      float s = gain_b[c];
      for (int i = 0; i < 16; i++) s += hidden[i] * gain_w[i * 8 + c];
      float gain = sigmoid(s);
      for (int f = 0; f < 33; f++) {
        out[2 * c][f] = point[c][f] * gain;
        out[2 * c + 1][f] = in[(8 + c) * 33 + f];
      }
    }
  }
};

/** Transposed conv over frequency, kernel 1×5, stride 2, padding 2: in [cin][fin] -> out [cout][2 fin - 1]. */
void conv_transpose(const float *in, int cin, int fin, float *out, int cout, const float *w,
                    const float *b, int groups) {
  int fout = 2 * fin - 1, cin_g = cin / groups, cout_g = cout / groups;
  for (int o = 0; o < cout; o++)
    for (int f = 0; f < fout; f++) out[o * fout + f] = b[o];
  for (int c = 0; c < cin; c++) {
    int g = c / cin_g;
    const float *x = in + c * fin;
    for (int o = 0; o < cout_g; o++) {
      float *y = out + (g * cout_g + o) * fout;
      for (int k = 0; k < 5; k++) {
        // Inputs that land inside the output, 0 <= 2 i + k - 2 < fout: i from lo to hi - 1
        int lo = k < 2, hi = k > 2 ? fin - 1 : fin;
        axpy<2, 1>(y + 2 * lo + k - 2, x + lo, w[(c * cout_g + o) * 5 + k], hi - lo);
      }
    }
  }
}

/** Linear layer: out [n][16] = in [n][16] × w [16][16] + b. */
void linear16(const float (*in)[16], int n, const float *w, const float *b, float (*out)[16]) {
  for (int t = 0; t < n; t++) {
    for (int j = 0; j < 16; j++) out[t][j] = b[j];
    for (int i = 0; i < 16; i++)
      for (int j = 0; j < 16; j++) out[t][j] += in[t][i] * w[i * 16 + j];
  }
}

/** LayerNormalization over all 33×16 values at once, then x += the result. */
void add_layer_norm(const float (*y)[16], const float *scale, const float *bias, float (*x)[16]) {
  constexpr int n = 33 * 16;
  const float *v = &y[0][0];
  float *out = &x[0][0];
  float mean = 0, var = 0;
  for (int i = 0; i < n; i++) mean += v[i];
  mean /= n;
  for (int i = 0; i < n; i++) var += (v[i] - mean) * (v[i] - mean);
  float inv = 1 / std::sqrt(var / n + 1e-8f);
  for (int i = 0; i < n; i++) out[i] += (v[i] - mean) * inv * scale[i] + bias[i];
}

/**
 * Dual-path GRU block on [33 bands][16 channels]: a GRU across the bands, run both ways at once
 * as one GRU over [band t, band 32 - t], then a GRU over time per band; each is followed by a
 * linear layer and a normalization, and added to its input.
 */
struct DualPath {
  Gru intra, inter;
  const float *intra_fc_w, *intra_fc_b, *intra_scale, *intra_bias;
  const float *inter_fc_w, *inter_fc_b, *inter_scale, *inter_bias;
  float mid[33][16]; // after the band GRU: onnx::Transpose_1573 / onnx::Transpose_2121
  float out[33][16]; // onnx::Shape_1774 / onnx::Transpose_2322

  bool load(Reader &r) {
    intra.load(r, 32);
    intra_fc_w = r.take(16 * 16);
    intra_fc_b = r.take(16);
    intra_scale = r.take(33 * 16);
    intra_bias = r.take(33 * 16);
    inter.load(r, 16);
    inter_fc_w = r.take(16 * 16);
    inter_fc_b = r.take(16);
    inter_scale = r.take(33 * 16);
    inter_bias = r.take(33 * 16);
    return r.ok;
  }

  /** in: [33][16]; hidden: its inter cache [33][16]. */
  void run(const float (*in)[16], float (*hidden)[16]) {
    float y[33][16], joined[33][16], fc[33][16];
    float h[16] = {};
    for (int t = 0; t < 33; t++) {
      float x[32];
      memcpy(x, in[t], 16 * sizeof(float));
      memcpy(x + 16, in[32 - t], 16 * sizeof(float));
      gru(intra, 32, x, h);
      memcpy(y[t], h, sizeof h);
    }
    // Units 0-7 run forward, 8-15 backward; [a][b][e] (2, 2, 4) units are reordered to [b][a][e]
    for (int t = 0; t < 33; t++)
      for (int a = 0; a < 2; a++)
        for (int b = 0; b < 2; b++)
          for (int e = 0; e < 4; e++) {
            int i = a * 8 + b * 4 + e;
            joined[t][b * 8 + a * 4 + e] = i < 8 ? y[t][i] : y[32 - t][i];
          }
    linear16(joined, 33, intra_fc_w, intra_fc_b, fc);
    memcpy(mid, in, sizeof mid);
    add_layer_norm(fc, intra_scale, intra_bias, mid);

    for (int f = 0; f < 33; f++) {
      gru(inter, 16, mid[f], hidden[f]);
      memcpy(y[f], hidden[f], 16 * sizeof(float));
    }
    linear16(y, 33, inter_fc_w, inter_fc_b, fc);
    memcpy(out, mid, sizeof out);
    add_layer_norm(fc, inter_scale, inter_bias, out);
  }
};

} // namespace

struct Gtcrn {
  std::vector<float> weights;
  const float *erb_merge; // [192][64]
  const float *sfe;       // [9][1][3], 3 groups
  const float *conv1_w, *conv1_b, *conv2_w, *conv2_b;
  float conv1_slope, conv2_slope;

  // State, in the ONNX cache layouts
  float conv_cache[2][16][16][33];
  float tra_cache[2][3][16];
  float inter_cache[2][33][16];

  float feat[3][kBins];        // feat: magnitude, real, imaginary
  float bands[3][kBands];      // input: the 192 high bins merged into 64 bands
  float sfe_out[9][kBands];    // input.3: each band with its two neighbours
  float conv1_out[16][65];     // input.11
  float conv2_out[16][33];     // onnx::Slice_377
  Block encoder[3];            // outputs onnx::Shape_561, onnx::Shape_848, onnx::Transpose_1135
  float middle_in[33][16];     // onnx::Shape_1243: the encoder output as [band][channel]
  DualPath middle[2];
  Block decoder[3];
  // Decoder block inputs, each the previous output plus the matching encoder output:
  // onnx::Shape_2343, onnx::Shape_2662, onnx::Shape_2981, then onnx::ConvTranspose_3300
  float decoder_in[4][16][33];
  const float *up1_w, *up1_b, *up2_w, *up2_b, *erb_split;
  float up1_slope;
  float up1_out[16][65];       // onnx::ConvTranspose_3305, with the input.11 skip added
  float mask_bands[2][kBands]; // m_feat
  float mask[2][kBins];        // onnx::Gather_3351: real and imaginary mask

  std::vector<std::pair<const char *, float *>> tensors;

  bool load(Reader &r) {
    erb_merge = r.take(kHigh * (kBands - kLow));
    sfe = r.take(9 * 3);
    conv1_w = r.take(16 * 9 * 5);
    conv1_b = r.take(16);
    conv1_slope = prelu_slope(r);
    conv2_w = r.take(16 * 8 * 5);
    conv2_b = r.take(16);
    conv2_slope = prelu_slope(r);
    encoder[0].load(r, 1);
    encoder[1].load(r, 2);
    encoder[2].load(r, 5);
    middle[0].load(r);
    middle[1].load(r);
    decoder[0].load(r, 5);
    decoder[1].load(r, 2);
    decoder[2].load(r, 1);
    up1_w = r.take(16 * 8 * 5);
    up1_b = r.take(16);
    up1_slope = prelu_slope(r);
    up2_w = r.take(16 * 2 * 5);
    up2_b = r.take(2);
    erb_split = r.take(kHighBands * kHigh);
    return r.ok && r.next == r.count && r.values == r.end;
  }

  void run(const float *mix, float *enh) {
    for (int k = 0; k < kBins; k++) {
      float re = mix[2 * k], im = mix[2 * k + 1];
      feat[0][k] = std::sqrt(re * re + im * im + 1e-12f);
      feat[1][k] = re;
      feat[2][k] = im;
    }
    for (int c = 0; c < 3; c++) {
      memcpy(bands[c], feat[c], kLow * sizeof(float));
      float *y = bands[c] + kLow;
      for (int b = 0; b < kHighBands; b++) y[b] = 0;
      for (int k = 0; k < kHigh; k++) axpy(y, erb_merge + k * kHighBands, feat[c][kLow + k], kHighBands);
    }
    conv(bands[0], 3, kBands, sfe_out[0], 9, kBands, sfe, nullptr, 3, 1, 1, 3);
    conv(sfe_out[0], 9, kBands, conv1_out[0], 16, 65, conv1_w, conv1_b, 5, 2, 2, 1);
    prelu(conv1_out[0], 16 * 65, conv1_slope);
    conv(conv1_out[0], 16, 65, conv2_out[0], 16, 33, conv2_w, conv2_b, 5, 2, 2, 2);
    prelu(conv2_out[0], 16 * 33, conv2_slope);
    for (int i = 0; i < 3; i++)
      encoder[i].run(i ? encoder[i - 1].out[0] : conv2_out[0], conv_cache[0], tra_cache[0][i]);
    for (int c = 0; c < 16; c++)
      for (int f = 0; f < 33; f++) middle_in[f][c] = encoder[2].out[c][f];
    middle[0].run(middle_in, inter_cache[0]);
    middle[1].run(middle[0].out, inter_cache[1]);

    const float (*skips[4])[33] = {encoder[2].out, encoder[1].out, encoder[0].out, conv2_out};
    for (int i = 0; i < 4; i++) {
      for (int c = 0; c < 16; c++)
        for (int f = 0; f < 33; f++)
          decoder_in[i][c][f] = (i ? decoder[i - 1].out[c][f] : middle[1].out[f][c]) + skips[i][c][f];
      if (i < 3) decoder[i].run(decoder_in[i][0], conv_cache[1], tra_cache[1][i]);
    }
    conv_transpose(decoder_in[3][0], 16, 33, up1_out[0], 16, up1_w, up1_b, 2);
    prelu(up1_out[0], 16 * 65, up1_slope);
    for (int c = 0; c < 16; c++)
      for (int f = 0; f < 65; f++) up1_out[c][f] += conv1_out[c][f];
    conv_transpose(up1_out[0], 16, 65, mask_bands[0], 2, up2_w, up2_b, 1);

    for (int c = 0; c < 2; c++) {
      for (int k = 0; k < kBands; k++) mask_bands[c][k] = tanh_fast(mask_bands[c][k]);
      memcpy(mask[c], mask_bands[c], kLow * sizeof(float));
      float *y = mask[c] + kLow;
      for (int k = 0; k < kHigh; k++) y[k] = 0;
      for (int b = 0; b < kHighBands; b++) axpy(y, erb_split + b * kHigh, mask_bands[c][kLow + b], kHigh);
    }
    for (int k = 0; k < kBins; k++) {
      float re = mix[2 * k], im = mix[2 * k + 1];
      enh[2 * k] = re * mask[0][k] - im * mask[1][k];
      enh[2 * k + 1] = im * mask[0][k] + re * mask[1][k];
    }
  }

  void reset() {
    memset(conv_cache, 0, sizeof conv_cache);
    memset(tra_cache, 0, sizeof tra_cache);
    memset(inter_cache, 0, sizeof inter_cache);
  }
};

extern "C" {

Gtcrn *gtcrn_create(const void *weights, size_t size) {
  uint32_t count;
  if (size < 4) return nullptr;
  memcpy(&count, weights, 4);
  size_t header = 4 + size_t(count) * 4;
  if (size < header || (size - header) % 4) return nullptr;

  auto *m = new Gtcrn;
  std::vector<uint32_t> sizes(count);
  memcpy(sizes.data(), static_cast<const char *>(weights) + 4, count * 4);
  m->weights.resize((size - header) / 4);
  memcpy(m->weights.data(), static_cast<const char *>(weights) + header, size - header);
  Reader r{sizes.data(), count, 0, m->weights.data(), m->weights.data() + m->weights.size()};
  if (!m->load(r)) {
    delete m;
    return nullptr;
  }
  m->reset();
  m->tensors = {
      {"conv_cache", m->conv_cache[0][0][0]},
      {"tra_cache", m->tra_cache[0][0]},
      {"inter_cache", m->inter_cache[0][0]},
      {"feat", m->feat[0]},
      {"input", m->bands[0]},
      {"input.3", m->sfe_out[0]},
      {"input.11", m->conv1_out[0]},
      {"onnx::Slice_377", m->conv2_out[0]},
      {"onnx::Shape_561", m->encoder[0].out[0]},
      {"onnx::Shape_848", m->encoder[1].out[0]},
      {"onnx::Transpose_1135", m->encoder[2].out[0]},
      {"onnx::Shape_1243", m->middle_in[0]},
      {"onnx::Transpose_1573", m->middle[0].mid[0]},
      {"onnx::Shape_1774", m->middle[0].out[0]},
      {"onnx::Transpose_2121", m->middle[1].mid[0]},
      {"onnx::Transpose_2322", m->middle[1].out[0]},
      {"onnx::Shape_2343", m->decoder_in[0][0]},
      {"onnx::Shape_2662", m->decoder_in[1][0]},
      {"onnx::Shape_2981", m->decoder_in[2][0]},
      {"onnx::ConvTranspose_3300", m->decoder_in[3][0]},
      {"onnx::ConvTranspose_3305", m->up1_out[0]},
      {"m_feat", m->mask_bands[0]},
      {"onnx::Gather_3351", m->mask[0]},
  };
  return m;
}

void gtcrn_destroy(Gtcrn *model) { delete model; }

void gtcrn_reset(Gtcrn *model) { model->reset(); }

void gtcrn_run(Gtcrn *model, const float *mix, float *enh) { model->run(mix, enh); }

float *gtcrn_tensor(Gtcrn *model, const char *name) {
  for (auto &[n, p] : model->tensors)
    if (!strcmp(n, name)) return p;
  return nullptr;
}

} // extern "C"
