/**
 * DeepFilterNet3 forward pass, node for node what models/df3.onnx computes; see df3.h.
 *
 * Tensors keep their ONNX layouts with the size-1 axes dropped, so a [1, 64, 1, 32] tensor is
 * [channel][frequency]. Comments name the ONNX tensors the code produces.
 *
 * Per frame: analysis (window, FFT, the spectrum history), the ERB and complex features with
 * their running norms, the encoder, then two decoders from its embedding. The ERB decoder makes a
 * gain per ERB band for the spectrum two frames back; the DF decoder makes a 5-tap complex filter
 * that replaces its lowest 96 bins. Synthesis turns that spectrum back into audio.
 *
 * The five GRUs (7.9 of the model's 8.9 MB) run in int8 (GruI8): weights quantized per row by
 * the export, input and state per step. Everything else is float, as in the ONNX graph.
 */

#include "df3.h"
#include "nn.h"

#include <cmath>
#include <complex>
#include <cstdint>
#include <cstring>
#include <utility>
#include <vector>

namespace {

constexpr int kHop = 512, kFft = 1024, kBins = 513;
constexpr int kErb = 32;     // ERB bands
constexpr int kDf = 96;      // bins deep filtering works on
constexpr int kCh = 64;      // conv channels
constexpr int kH = 256;      // GRU hidden size
constexpr float kAlpha = 0.99f; // the feature norms' decay per frame

void relu(float *x, int n) {
  for (int i = 0; i < n; i++) x[i] = std::max(x[i], 0.f);
}

/** in [groups][k] -> out [groups][n], out[g] = in[g] · w[g]: the model's grouped linear layers. */
void grouped_linear(const float *in, float *out, const float *w, int groups, int k, int n) {
  for (int g = 0; g < groups; g++) {
    float *y = out + g * n;
    std::fill(y, y + n, 0.f);
    for (int i = 0; i < k; i++) axpy(y, w + (g * k + i) * n, in[g * k + i], n);
  }
}

/** out = a + b, n values (over whole 2-D arrays, which indexing their first row can't reach) */
void add(const float *a, const float *b, float *out, int n) {
  for (int i = 0; i < n; i++) out[i] = a[i] + b[i];
}

/** [rows][cols] -> [cols][rows] */
void transpose_into(const float *in, int rows, int cols, float *out) {
  for (int r = 0; r < rows; r++)
    for (int c = 0; c < cols; c++) out[c * rows + r] = in[r * cols + c];
}

/** Drops the oldest of a history's rows and appends row: history [rows][n], oldest first. */
void push(float *history, int rows, int n, const float *row) {
  memmove(history, history + n, (rows - 1) * n * sizeof(float));
  memcpy(history + (rows - 1) * n, row, n * sizeof(float));
}

/** In-place radix-2 FFT of kFft points, unnormalized, as ONNX's DFT. Twiddles computed in double. */
struct Fft {
  std::complex<float> twiddle[kFft / 2];
  int reversed[kFft];

  Fft() {
    for (int k = 0; k < kFft / 2; k++) twiddle[k] = std::polar(1.0, -2 * M_PI * k / kFft);
    constexpr int bits = 10; // log2(kFft)
    for (int n = 0; n < kFft; n++) {
      reversed[n] = 0;
      for (int b = 0; b < bits; b++) reversed[n] |= ((n >> b) & 1) << (bits - 1 - b);
    }
  }

  void run(std::complex<float> *x) const {
    for (int n = 0; n < kFft; n++)
      if (n < reversed[n]) std::swap(x[n], x[reversed[n]]);
    for (int len = 2; len <= kFft; len *= 2) {
      int step = kFft / len;
      for (int s = 0; s < kFft; s += len)
        for (int k = 0; k < len / 2; k++) {
          std::complex<float> a = x[s + k], b = x[s + k + len / 2] * twiddle[k * step];
          x[s + k] = a + b;
          x[s + k + len / 2] = a - b;
        }
    }
  }
};

/**
 * Depthwise transposed conv over frequency, kernel 3, stride 2, padding 1, output padding 1:
 * in [ch][fin] -> out [ch][2 fin]. Input i adds to outputs 2 i - 1 .. 2 i + 1.
 */
void conv_transpose(const float *in, int ch, int fin, float *out, const float *w) {
  int fout = 2 * fin;
  for (int c = 0; c < ch; c++) {
    const float *x = in + c * fin;
    float *y = out + c * fout;
    std::fill(y, y + fout, 0.f);
    for (int k = 0; k < 3; k++) {
      // Inputs whose tap k lands inside the output: 0 <= 2 i + k - 1 < fout
      int lo = k == 0, hi = fin;
      axpy<2, 1>(y + 2 * lo + k - 1, x + lo, w[c * 3 + k], hi - lo);
    }
  }
}

/** pointwise() for R output rows: R rows × 8 columns of sums stay in registers while the input streams past. */
template <int R>
void pointwise_rows(const float *in, int cin, int f, float *out, const float *w, const float *b) {
  for (int c = 0; c < f; c += 8) {
    f32x4 acc[R][2];
    for (int j = 0; j < R; j++) acc[j][0] = acc[j][1] = splat4(b ? b[j] : 0.f);
    for (int i = 0; i < cin; i++) {
      f32x4 x0 = load4(in + i * f + c), x1 = load4(in + i * f + c + 4);
      for (int j = 0; j < R; j++) {
        f32x4 wv = splat4(w[j * cin + i]);
        acc[j][0] += wv * x0;
        acc[j][1] += wv * x1;
      }
    }
    for (int j = 0; j < R; j++) {
      store4(out + j * f + c, acc[j][0]);
      store4(out + j * f + c + 4, acc[j][1]);
    }
  }
}

/** 1×1 conv: out [cout][f] = b + w [cout][cin] · in [cin][f], b may be null; f % 8 == 0. */
void pointwise(const float *in, int cin, int f, float *out, int cout, const float *w, const float *b) {
  int o = 0;
  for (; o + 4 <= cout; o += 4) pointwise_rows<4>(in, cin, f, out + o * f, w + o * cin, b ? b + o : nullptr);
  for (; o < cout; o++) pointwise_rows<1>(in, cin, f, out + o * f, w + o * cin, b ? b + o : nullptr);
}

/** A conv's weights and bias (null if it has none). */
struct Conv {
  const float *w, *b = nullptr;
};

} // namespace

struct Df3 {
  std::vector<float> weights;
  Fft fft;

  // Weights, in file order
  const float *window, *erb_fb;
  Conv df_conv0, df_conv0p, df_conv1, df_conv1p;
  Conv df_convp, df_convpp;                              // DF decoder, on the c0 history
  Conv erb_conv0, erb_conv1, erb_conv1p, erb_conv2, erb_conv2p, erb_conv3, erb_conv3p;
  Conv conv0p, conv1p, conv2p, conv3p;                   // ERB decoder's skips from e0..e3
  const float *df_fc_emb, *emb_in, *emb_out;
  GruI8<kH> emb_gru;
  const float *erb_in, *df_skip, *df_in, *erb_out, *df_out;
  GruI8<kH> erb_gru[2], df_gru[2];
  Conv convt3, convt3p, convt2, convt2p, convt1, convt1p, mask;
  const float *erb_inv, *syn_scale, *ifft_a, *ifft_b;

  // State, in the ONNX inputs' layouts
  float erb_norm[kErb], unit_norm[kDf];
  float analysis_mem[kHop], synthesis_mem[kHop];
  float rolling_erb[3][kErb];       // ERB features of the last 3 frames, oldest first
  float rolling_feat[2][3][kDf];    // complex features (real, imaginary) of the last 3 frames
  float rolling_c0[kCh][5][kDf];    // the DF encoder's first conv, last 5 frames
  float spec_x[5][kBins][2];        // spectra of the last 5 frames
  float spec_y[7][kBins][2];        // ... and of the last 7
  float enc_hidden[kH], erb_hidden[2][kH], df_hidden[2][kH];

  // Intermediates kept for the check
  float spec[kBins][2];             // /Mul_1_output_0: this frame's spectrum
  float delayed[kBins][2];          // /Gather_output_0: the spectrum 2 frames back, to be filtered
  float erb_feat[kErb];             // /Div_1_output_0
  float feat[kDf][2];               // /Div_2_output_0
  float e0[kCh][32], e1[kCh][16], e2[kCh][8], e3[kCh][8];
  float c0[kCh][kDf], c1[kCh][48];
  float df_emb[512];                // /enc/df_fc_emb/0/Reshape_1_output_0
  float combined[512];              // /enc/combine/Add_output_0
  float gru_in[kH];                 // /enc/emb_gru/linear_in/0/Reshape_1_output_0
  float emb[512];                   // /enc/emb_gru/linear_out/0/activation/Relu_output_0
  float df_conv[kDf][10];           // /df_dec/Transpose_output_0: the DF decoder's conv path
  float erb_gru_in[kH];             // /erb_dec/emb_gru/linear_in/Reshape_1_output_0
  float df_gru_in[kH];              // /df_dec/df_gru/linear_in/0/Reshape_1_output_0
  float skip[kH];                   // /df_dec/df_skip/Reshape_1_output_0
  float coefs[kDf][10];             // /df_dec/Add_1_output_0: per bin, 5 taps of (real, imaginary)
  float df_spec[kDf][2];            // opt_matmul_68: the filtered low bins
  float d3[kCh][8], d2[kCh][8], d1[kCh][16], d0[kCh][32]; // /erb_dec/Add_output_0 .. Add_3
  float erb_dec_in[kCh][8];         // /erb_dec/Transpose_output_0
  float gains[kErb];                // /erb_dec/conv0_out/2/Sigmoid_output_0
  float enhanced[kBins][2];         // opt_concat_30: the spectrum to synthesize
  float output[kHop];               // enhanced_audio_frame

  std::vector<std::pair<const char *, float *>> tensors;

  bool load(Reader &r) {
    auto conv = [&](Conv &c, size_t n, size_t bias) {
      c.w = r.take(n);
      if (bias) c.b = r.take(bias);
    };
    window = r.take(kFft);
    erb_fb = r.take(kBins * kErb);
    conv(df_conv0, kCh * 9, 0);
    conv(df_conv0p, kCh * kCh, kCh);
    conv(df_conv1, kCh * 3, 0);
    conv(df_conv1p, kCh * kCh, kCh);
    conv(df_convp, 10 * 32 * 5, 0);
    conv(df_convpp, 10 * 10, 10);
    conv(erb_conv0, kCh * 9, kCh);
    conv(erb_conv1, kCh * 3, 0);
    df_fc_emb = r.take(32 * 96 * 16);
    conv(conv0p, kCh, kCh);
    conv(erb_conv1p, kCh * kCh, kCh);
    conv(erb_conv2, kCh * 3, 0);
    conv(conv1p, kCh, kCh);
    conv(erb_conv2p, kCh * kCh, kCh);
    conv(erb_conv3, kCh * 3, 0);
    conv(conv2p, kCh, kCh);
    conv(erb_conv3p, kCh * kCh, kCh);
    conv(conv3p, kCh, kCh);
    emb_in = r.take(16 * 32 * 16);
    emb_gru.load(r, kH);
    emb_out = r.take(16 * 16 * 32);
    erb_in = r.take(16 * 32 * 16);
    df_skip = r.take(16 * 32 * 16);
    df_in = r.take(8 * 64 * 32);
    erb_gru[0].load(r, kH);
    df_gru[0].load(r, kH);
    erb_gru[1].load(r, kH);
    df_gru[1].load(r, kH);
    erb_out = r.take(16 * 16 * 32);
    df_out = r.take(16 * 16 * 60);
    conv(convt3, kCh * 3, 0);
    conv(convt3p, kCh * kCh, kCh);
    conv(convt2, kCh * 3, 0);
    conv(convt2p, kCh * kCh, kCh);
    conv(convt1, kCh * 3, 0);
    conv(convt1p, kCh * kCh, kCh);
    conv(mask, kCh * 3, 1);
    erb_inv = r.take(kErb * kBins);
    syn_scale = r.take(kBins);
    ifft_a = r.take(64 * 34);
    ifft_b = r.take(32 * 64 * 32);
    return r.ok && r.next == r.count;
  }

  void reset() {
    // The norms start on DF's ramps: erb_norm from -60 to -90 dB, unit_norm from 1e-3 to 1e-4
    for (int b = 0; b < kErb; b++) erb_norm[b] = float(-60.0 + b * (-30.0 / (kErb - 1)));
    for (int f = 0; f < kDf; f++) unit_norm[f] = float(1e-3 + f * (-9e-4 / (kDf - 1)));
    memset(analysis_mem, 0, sizeof analysis_mem);
    memset(synthesis_mem, 0, sizeof synthesis_mem);
    memset(rolling_erb, 0, sizeof rolling_erb);
    memset(rolling_feat, 0, sizeof rolling_feat);
    memset(rolling_c0, 0, sizeof rolling_c0);
    memset(spec_x, 0, sizeof spec_x);
    memset(spec_y, 0, sizeof spec_y);
    memset(enc_hidden, 0, sizeof enc_hidden);
    memset(erb_hidden, 0, sizeof erb_hidden);
    memset(df_hidden, 0, sizeof df_hidden);
  }

  /** Window, FFT and the spectrum history; then the ERB and complex features, each frame's added to its history. */
  void analysis(const float *in) {
    std::complex<float> z[kFft];
    for (int n = 0; n < kHop; n++) {
      z[n] = analysis_mem[n] * window[n];
      z[kHop + n] = in[n] * window[kHop + n];
    }
    memcpy(analysis_mem, in, sizeof analysis_mem); // new_analysis_mem
    fft.run(z);
    for (int k = 0; k < kBins; k++) {
      spec[k][0] = z[k].real() * (1.f / kFft);
      spec[k][1] = z[k].imag() * (1.f / kFft);
    }
    memcpy(delayed, spec_y[5], sizeof delayed); // from the history before this frame joins it
    push(spec_y[0][0], 7, kBins * 2, spec[0]);
    push(spec_x[0][0], 5, kBins * 2, spec[0]);

    // ERB features: band power in dB, minus its running mean, over 40
    float erb[kErb] = {};
    for (int k = 0; k < kBins; k++) axpy(erb, erb_fb + k * kErb, spec[k][0] * spec[k][0] + spec[k][1] * spec[k][1], kErb);
    for (int b = 0; b < kErb; b++) {
      float db = std::log(erb[b] + 1e-10f) / 2.3025851249694824f * 10.f;
      erb_norm[b] = db * (1 - kAlpha) + erb_norm[b] * kAlpha;
      erb_feat[b] = (db - erb_norm[b]) / 40.f;
    }
    push(rolling_erb[0], 3, kErb, erb_feat);

    // Complex features: the low bins over the square root of their running mean magnitude
    for (int f = 0; f < kDf; f++) {
      float mag = std::sqrt(spec[f][0] * spec[f][0] + spec[f][1] * spec[f][1]);
      unit_norm[f] = mag * (1 - kAlpha) + unit_norm[f] * kAlpha;
      float s = std::sqrt(unit_norm[f]);
      feat[f][0] = spec[f][0] / s;
      feat[f][1] = spec[f][1] / s;
    }
    for (int c = 0; c < 2; c++) {
      float row[kDf];
      for (int f = 0; f < kDf; f++) row[f] = feat[f][c];
      push(rolling_feat[c][0], 3, kDf, row);
    }
  }

  void encoder() {
    float t96[kCh][kDf], t48[kCh][48], t16[kCh][16], t8[kCh][8];

    // ERB path: e0 [64][32] -> e1 [64][16] -> e2 [64][8] -> e3 [64][8]. The first conv's 3×3
    // kernel spans the 3 frames of history, which sit as 3 input rows
    conv(rolling_erb[0], 3, kErb, e0[0], kCh, 32, erb_conv0.w, erb_conv0.b, 3, 1, 1, 1);
    relu(e0[0], kCh * 32);
    conv(e0[0], kCh, 32, t16[0], kCh, 16, erb_conv1.w, nullptr, 3, 2, 1, kCh);
    pointwise(t16[0], kCh, 16, e1[0], kCh, erb_conv1p.w, erb_conv1p.b);
    relu(e1[0], kCh * 16);
    conv(e1[0], kCh, 16, t8[0], kCh, 8, erb_conv2.w, nullptr, 3, 2, 1, kCh);
    pointwise(t8[0], kCh, 8, e2[0], kCh, erb_conv2p.w, erb_conv2p.b);
    relu(e2[0], kCh * 8);
    conv(e2[0], kCh, 8, t8[0], kCh, 8, erb_conv3.w, nullptr, 3, 1, 1, kCh);
    pointwise(t8[0], kCh, 8, e3[0], kCh, erb_conv3p.w, erb_conv3p.b);
    relu(e3[0], kCh * 8);

    // DF path: c0 [64][96] (also kept 5 frames, for the DF decoder) -> c1 [64][48]. Two groups:
    // real and imaginary, 3 frames each
    conv(rolling_feat[0][0], 6, kDf, t96[0], kCh, kDf, df_conv0.w, nullptr, 3, 1, 1, 2);
    pointwise(t96[0], kCh, kDf, c0[0], kCh, df_conv0p.w, df_conv0p.b);
    relu(c0[0], kCh * kDf);
    for (int c = 0; c < kCh; c++) push(rolling_c0[c][0], 5, kDf, c0[c]);
    conv(c0[0], kCh, kDf, t48[0], kCh, 48, df_conv1.w, nullptr, 3, 2, 1, kCh);
    pointwise(t48[0], kCh, 48, c1[0], kCh, df_conv1p.w, df_conv1p.b);
    relu(c1[0], kCh * 48);

    // Both paths to 512 values, frequency-major, summed
    float c1t[48 * kCh], e3t[8 * kCh];
    transpose_into(c1[0], kCh, 48, c1t);
    grouped_linear(c1t, df_emb, df_fc_emb, 32, 96, 16);
    relu(df_emb, 512);
    transpose_into(e3[0], kCh, 8, e3t);
    for (int i = 0; i < 512; i++) combined[i] = e3t[i] + df_emb[i];

    // Embedding GRU between two grouped linears; its output is its new hidden state
    grouped_linear(combined, gru_in, emb_in, 16, 32, 16);
    relu(gru_in, kH);
    gru(emb_gru, kH, gru_in, enc_hidden);
    grouped_linear(enc_hidden, emb, emb_out, 16, 16, 32);
    relu(emb, 512);
  }

  /** Deep filtering coefficients from the embedding and c0's history, then the filter itself. */
  void df_decoder() {
    // Conv path: a 5×1 kernel over c0's 5 frames (2 groups of 32 channels), then 1×1
    float t[10][kDf], p[10][kDf];
    for (int g = 0; g < 2; g++)
      pointwise(rolling_c0[g * 32][0], 32 * 5, kDf, t[g * 5], 5, df_convp.w + g * 5 * 32 * 5, nullptr);
    pointwise(t[0], 10, kDf, p[0], 10, df_convpp.w, df_convpp.b);
    relu(p[0], 10 * kDf);
    transpose_into(p[0], 10, kDf, df_conv[0]);

    // GRU path: two stacked GRUs between grouped linears, plus a skip around them
    grouped_linear(emb, df_gru_in, df_in, 8, 64, 32);
    relu(df_gru_in, kH);
    grouped_linear(emb, skip, df_skip, 16, 32, 16);
    gru(df_gru[0], kH, df_gru_in, df_hidden[0]);
    gru(df_gru[1], kH, df_hidden[0], df_hidden[1]);
    float sum[kH], lin[16 * 60];
    for (int i = 0; i < kH; i++) sum[i] = df_hidden[1][i] + skip[i];
    grouped_linear(sum, lin, df_out, 16, 16, 60);
    for (int f = 0; f < kDf; f++)
      for (int i = 0; i < 10; i++) coefs[f][i] = tanh_fast(lin[f * 10 + i]) + df_conv[f][i];

    // The filter: bin f of the last 5 frames' spectra (spec_x, oldest first) times tap t's
    // complex coefficient, summed over the taps
    for (int f = 0; f < kDf; f++) {
      float s[2][2] = {}; // s[c][d]: spectrum part c times coefficient part d
      for (int t = 0; t < 5; t++)
        for (int c = 0; c < 2; c++)
          for (int d = 0; d < 2; d++) s[c][d] += spec_x[t][f][c] * coefs[f][t * 2 + d];
      df_spec[f][0] = s[0][0] - s[1][1];
      df_spec[f][1] = s[0][1] + s[1][0];
    }
  }

  /** ERB gains from the embedding and the encoder's ERB path (U-Net skips), applied to the delayed spectrum. */
  void erb_decoder() {
    grouped_linear(emb, erb_gru_in, erb_in, 16, 32, 16);
    relu(erb_gru_in, kH);
    gru(erb_gru[0], kH, erb_gru_in, erb_hidden[0]);
    gru(erb_gru[1], kH, erb_hidden[0], erb_hidden[1]);
    float lin[512];
    grouped_linear(erb_hidden[1], lin, erb_out, 16, 16, 32);
    relu(lin, 512);
    transpose_into(lin, 8, kCh, erb_dec_in[0]);

    // Skips: a per-channel scale and bias of each encoder stage, then ReLU
    auto skip = [](const float *e, const Conv &c, int n, float *out) {
      for (int ch = 0; ch < kCh; ch++)
        for (int f = 0; f < n; f++) out[ch * n + f] = std::max(c.w[ch] * e[ch * n + f] + c.b[ch], 0.f);
    };
    float s[kCh][32], t[kCh][32], u[kCh][32];
    skip(e3[0], conv3p, 8, s[0]);
    add(s[0], erb_dec_in[0], d3[0], kCh * 8);

    conv(d3[0], kCh, 8, t[0], kCh, 8, convt3.w, nullptr, 3, 1, 1, kCh);
    pointwise(t[0], kCh, 8, u[0], kCh, convt3p.w, convt3p.b);
    relu(u[0], kCh * 8);
    skip(e2[0], conv2p, 8, s[0]);
    add(s[0], u[0], d2[0], kCh * 8);

    conv_transpose(d2[0], kCh, 8, t[0], convt2.w);
    pointwise(t[0], kCh, 16, u[0], kCh, convt2p.w, convt2p.b);
    relu(u[0], kCh * 16);
    skip(e1[0], conv1p, 16, s[0]);
    add(s[0], u[0], d1[0], kCh * 16);

    conv_transpose(d1[0], kCh, 16, t[0], convt1.w);
    pointwise(t[0], kCh, 32, u[0], kCh, convt1p.w, convt1p.b);
    relu(u[0], kCh * 32);
    skip(e0[0], conv0p, 32, s[0]);
    add(s[0], u[0], d0[0], kCh * 32);

    conv(d0[0], kCh, 32, gains, 1, kErb, mask.w, mask.b, 3, 1, 1, 1);
    for (int b = 0; b < kErb; b++) gains[b] = sigmoid(gains[b]);
  }

  /**
   * Inverse FFT and overlap-add, as the rewritten graph does it: per-bin scaling, then two
   * MatMul stages (a 32 × 32 FFT with the synthesis window folded into its weights).
   */
  void synthesis() {
    // [513][2] scaled, padded to [544][2] and laid out [2][544] = 34 rows of 32
    float rows[34][32] = {};
    for (int k = 0; k < kBins; k++)
      for (int c = 0; c < 2; c++) {
        int n = c * 544 + k;
        rows[n / 32][n % 32] = enhanced[k][c] * syn_scale[k];
      }
    float m[64][32] = {}; // opt_matmul_9 = ifft_a [64][34] · rows
    for (int i = 0; i < 64; i++)
      for (int r = 0; r < 34; r++) axpy(m[i], rows[r], ifft_a[i * 34 + r], 32);
    // Row a of the second stage takes m rows a and 32 + a, then opt_c_15[a] [64][32]
    float s[32][32] = {};
    for (int a = 0; a < 32; a++)
      for (int b = 0; b < 2; b++)
        for (int j = 0; j < 32; j++) axpy(s[a], ifft_b + (a * 64 + b * 32 + j) * 32, m[b * 32 + a][j], 32);
    // Sample m * 32 + a of the windowed frame is s[a][m]; overlap-add its first half
    for (int n = 0; n < kHop; n++) output[n] = s[n % 32][n / 32] + synthesis_mem[n];
    for (int n = 0; n < kHop; n++) synthesis_mem[n] = s[(kHop + n) % 32][(kHop + n) / 32];
  }

  void run(const float *in, float *out) {
    analysis(in);
    encoder();
    df_decoder();
    erb_decoder();
    // ERB gains spread to bins on the delayed spectrum, but the low bins come from the filter
    float g[kBins] = {};
    for (int b = 0; b < kErb; b++) axpy(g, erb_inv + b * kBins, gains[b], kBins);
    for (int k = 0; k < kBins; k++)
      for (int c = 0; c < 2; c++) enhanced[k][c] = k < kDf ? df_spec[k][c] : delayed[k][c] * g[k];
    synthesis();
    memcpy(out, output, sizeof output);
  }
};

extern "C" {

Df3 *df3_create(const void *weights, size_t size) {
  auto *m = new Df3;
  std::vector<uint32_t> sizes;
  if (!read_weights(weights, size, sizes, m->weights)) {
    delete m;
    return nullptr;
  }
  Reader r{sizes.data(), uint32_t(sizes.size()), 0, m->weights.data(), m->weights.data() + m->weights.size()};
  if (!m->load(r)) {
    delete m;
    return nullptr;
  }
  m->reset();
  m->tensors = {
      {"erb_norm_state", m->erb_norm},
      {"band_unit_norm_state", m->unit_norm},
      {"analysis_mem", m->analysis_mem},
      {"rolling_erb_buf", m->rolling_erb[0]},
      {"rolling_feat_spec_buf", m->rolling_feat[0][0]},
      {"rolling_c0_buf", m->rolling_c0[0][0]},
      {"rolling_spec_buf_x", m->spec_x[0][0]},
      {"rolling_spec_buf_y", m->spec_y[0][0]},
      {"enc_hidden", m->enc_hidden},
      {"/Mul_1_output_0", m->spec[0]},
      {"/Gather_output_0", m->delayed[0]},
      {"/Div_1_output_0", m->erb_feat},
      {"/Div_2_output_0", m->feat[0]},
      {"/enc/erb_conv0/2/Relu_output_0", m->e0[0]},
      {"/enc/erb_conv1/3/Relu_output_0", m->e1[0]},
      {"/enc/erb_conv2/Relu_output_0", m->e2[0]},
      {"/enc/erb_conv3/3/Relu_output_0", m->e3[0]},
      {"/enc/df_conv0/3/Relu_output_0", m->c0[0]},
      {"/enc/df_conv1/Relu_output_0", m->c1[0]},
      {"/enc/df_fc_emb/0/Reshape_1_output_0", m->df_emb},
      {"/enc/combine/Add_output_0", m->combined},
      {"/enc/emb_gru/linear_in/0/Reshape_1_output_0", m->gru_in},
      {"/enc/emb_gru/linear_out/0/activation/Relu_output_0", m->emb},
      {"synthesis_mem", m->synthesis_mem},
      {"erb_dec_hidden", m->erb_hidden[0]},
      {"df_dec_hidden", m->df_hidden[0]},
      {"/df_dec/Transpose_output_0", m->df_conv[0]},
      {"/erb_dec/emb_gru/linear_in/Reshape_1_output_0", m->erb_gru_in},
      {"/df_dec/df_gru/linear_in/0/Reshape_1_output_0", m->df_gru_in},
      {"/df_dec/df_skip/Reshape_1_output_0", m->skip},
      {"/df_dec/Add_1_output_0", m->coefs[0]},
      {"opt_matmul_68", m->df_spec[0]},
      {"/erb_dec/Transpose_output_0", m->erb_dec_in[0]},
      {"/erb_dec/Add_output_0", m->d3[0]},
      {"/erb_dec/Add_1_output_0", m->d2[0]},
      {"/erb_dec/Add_2_output_0", m->d1[0]},
      {"/erb_dec/Add_3_output_0", m->d0[0]},
      {"/erb_dec/conv0_out/2/Sigmoid_output_0", m->gains},
      {"opt_concat_30", m->enhanced[0]},
      {"enhanced_audio_frame", m->output},
  };
  return m;
}

void df3_destroy(Df3 *model) { delete model; }

void df3_reset(Df3 *model) { model->reset(); }

void df3_run(Df3 *model, const float *in, float *out) { model->run(in, out); }

float *df3_tensor(Df3 *model, const char *name) {
  for (auto &[n, p] : model->tensors)
    if (!strcmp(n, name)) return p;
  return nullptr;
}

} // extern "C"
