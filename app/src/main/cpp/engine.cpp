// Ma Cam – moteur photo multi-images.
//
// Réimplémentation à partir des publications de Google (pas de code GCam) :
//  - Hasinoff et al., « Burst photography for high dynamic range and low-light imaging
//    on mobile cameras », SIGGRAPH Asia 2016 (alignement hiérarchique + fusion robuste) ;
//  - Liba et al., « Handheld Mobile Photography in Very Low Light », 2019 (mode nuit) ;
//  - Wronski et al., « Handheld Multi-Frame Super-Resolution », 2019 (super-résolution :
//    version simplifiée à noyaux isotropes).
//
// Trois modes : 0 = RAW HDR+, 1 = RAW super-résolution, 2 = YUV (téléphones sans RAW).
// Les images sont traitées une par une à leur arrivée (fusion en flux) : la mémoire ne
// dépend pas du nombre d'images de la rafale.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <thread>
#include <vector>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "MaCamEngine", __VA_ARGS__)

namespace {

// ------------------------------------------------------------------ utilitaires

template <class F>
void parallelFor(int n, F f) {
    if (n <= 0) return;
    int threads = std::max(1, std::min<int>((int)std::thread::hardware_concurrency(), n));
    std::atomic<int> next(0);
    auto worker = [&] { for (int i; (i = next++) < n;) f(i); };
    std::vector<std::thread> pool;
    for (int t = 1; t < threads; t++) pool.emplace_back(worker);
    worker();
    for (auto& t : pool) t.join();
}

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

struct Img {
    int w = 0, h = 0;
    std::vector<float> d;
    void alloc(int W, int H) { w = W; h = H; d.assign((size_t)W * H, 0.f); }
    float& operator()(int x, int y) { return d[(size_t)y * w + x]; }
    float at(int x, int y) const { return d[(size_t)clampi(y, 0, h - 1) * w + clampi(x, 0, w - 1)]; }
    float bilinear(float x, float y) const {
        int x0 = (int)std::floor(x), y0 = (int)std::floor(y);
        float fx = x - x0, fy = y - y0;
        return (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) +
               (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy;
    }
};

Img downsample(const Img& s, int f) {
    Img o; o.alloc(std::max(1, s.w / f), std::max(1, s.h / f));
    float inv = 1.f / (f * f);
    parallelFor(o.h, [&](int y) {
        for (int x = 0; x < o.w; x++) {
            float acc = 0;
            for (int j = 0; j < f; j++) {
                const float* row = &s.d[(size_t)(y * f + j) * s.w + x * f];
                for (int i = 0; i < f; i++) acc += row[i];
            }
            o(x, y) = acc * inv;
        }
    });
    return o;
}

/** Flou boîte séparable (rayon r), plusieurs passes ≈ gaussienne. */
void boxBlur(Img& im, int r, int passes) {
    Img tmp; tmp.alloc(im.w, im.h);
    for (int p = 0; p < passes; p++) {
        parallelFor(im.h, [&](int y) {
            float acc = 0; int n = 2 * r + 1;
            for (int i = -r; i <= r; i++) acc += im.at(i, y);
            for (int x = 0; x < im.w; x++) {
                tmp(x, y) = acc / n;
                acc += im.at(x + r + 1, y) - im.at(x - r, y);
            }
        });
        parallelFor(im.w, [&](int x) {
            float acc = 0; int n = 2 * r + 1;
            for (int i = -r; i <= r; i++) acc += tmp.at(x, i);
            for (int y = 0; y < im.h; y++) {
                im(x, y) = acc / n;
                acc += tmp.at(x, y + r + 1) - tmp.at(x, y - r);
            }
        });
    }
}

// ------------------------------------------------------------------ alignement

struct Off { float x = 0, y = 0; };

// Pyramide : L0 = image grise à demi-résolution, puis facteurs 2, 2, 4 (comme HDR+).
constexpr int kLevels = 4;
constexpr int kFactor[kLevels] = {1, 2, 2, 4};
constexpr int kTile[kLevels] = {16, 16, 16, 8};
constexpr int kStep[kLevels] = {8, 16, 16, 8};
constexpr int kRadius[kLevels] = {1, 4, 4, 4};

std::vector<Img> buildPyramid(Img l0) {
    std::vector<Img> p(kLevels);
    p[0] = std::move(l0);
    for (int l = 1; l < kLevels; l++) p[l] = downsample(p[l - 1], kFactor[l]);
    return p;
}

struct Grid { int gw = 0, gh = 0; std::vector<Off> off; };

inline int gridDim(int size, int l) { return std::max(1, (size - kTile[l]) / kStep[l] + 1); }

float tileCost(const Img& r, const Img& a, int ox, int oy, int ts, int dx, int dy) {
    float c = 0;
    bool inside = ox + dx >= 0 && oy + dy >= 0 && ox + dx + ts <= a.w && oy + dy + ts <= a.h &&
                  ox + ts <= r.w && oy + ts <= r.h;
    if (inside) {
        for (int y = 0; y < ts; y++) {
            const float* rr = &r.d[(size_t)(oy + y) * r.w + ox];
            const float* ar = &a.d[(size_t)(oy + y + dy) * a.w + ox + dx];
            for (int x = 0; x < ts; x++) c += std::fabs(rr[x] - ar[x]);
        }
    } else {
        for (int y = 0; y < ts; y++)
            for (int x = 0; x < ts; x++) c += std::fabs(r.at(ox + x, oy + y) - a.at(ox + x + dx, oy + y + dy));
    }
    return c;
}

/** Alignement hiérarchique par tuiles (du plus grossier au plus fin), sous-pixel au niveau 0. */
Grid align(const std::vector<Img>& ref, const std::vector<Img>& alt) {
    std::vector<Grid> g(kLevels);
    for (int l = kLevels - 1; l >= 0; l--) {
        const Img& R = ref[l]; const Img& A = alt[l];
        Grid& G = g[l];
        G.gw = gridDim(R.w, l); G.gh = gridDim(R.h, l);
        G.off.assign((size_t)G.gw * G.gh, Off());
        parallelFor(G.gh, [&](int i) {
            for (int j = 0; j < G.gw; j++) {
                int ox = j * kStep[l], oy = i * kStep[l], ts = kTile[l];
                int gx = 0, gy = 0;
                if (l < kLevels - 1) {
                    // Comme HDR+ : on essaie le déplacement de la tuile parente et de ses deux voisines
                    // les plus proches, et on garde le meilleur (robuste aux bords d'objets).
                    const Grid& P = g[l + 1];
                    int f = kFactor[l + 1], ps = kStep[l + 1];
                    float pcx = (ox + ts / 2.f) / f / ps, pcy = (oy + ts / 2.f) / f / ps;
                    int pj = clampi((int)pcx, 0, P.gw - 1), pi = clampi((int)pcy, 0, P.gh - 1);
                    int nj = clampi(pcx - pj < 0.5f ? pj - 1 : pj + 1, 0, P.gw - 1);
                    int ni = clampi(pcy - pi < 0.5f ? pi - 1 : pi + 1, 0, P.gh - 1);
                    const Off* cand[3] = {&P.off[(size_t)pi * P.gw + pj], &P.off[(size_t)pi * P.gw + nj], &P.off[(size_t)ni * P.gw + pj]};
                    float bestC = 1e30f;
                    for (const Off* c : cand) {
                        int cx = (int)std::lround(c->x * f), cy = (int)std::lround(c->y * f);
                        float cost = tileCost(R, A, ox, oy, ts, cx, cy);
                        if (cost < bestC) { bestC = cost; gx = cx; gy = cy; }
                    }
                }
                int r = kRadius[l];
                float best = 1e30f; int bx = 0, by = 0;
                float costs[9][9];
                for (int dy = -r; dy <= r; dy++)
                    for (int dx = -r; dx <= r; dx++) {
                        // Légère préférence pour le plus petit déplacement : évite les faux appariements
                        // dans les zones uniformes ou répétitives.
                        float c = tileCost(R, A, ox, oy, ts, gx + dx, gy + dy) *
                                  (1.f + 0.02f * (std::abs(gx + dx) + std::abs(gy + dy)) / (float)(1 << (2 * l)));
                        costs[dy + r][dx + r] = c;
                        if (c < best) { best = c; bx = dx; by = dy; }
                    }
                Off o; o.x = (float)(gx + bx); o.y = (float)(gy + by);
                if (l == 0) { // raffinement sous-pixel par ajustement parabolique
                    auto parab = [](float a, float b, float c) {
                        float den = a - 2 * b + c;
                        return den > 1e-6f ? clampf(0.5f * (a - c) / den, -0.5f, 0.5f) : 0.f;
                    };
                    if (bx > -r && bx < r) o.x += parab(costs[by + r][bx + r - 1], best, costs[by + r][bx + r + 1]);
                    if (by > -r && by < r) o.y += parab(costs[by + r - 1][bx + r], best, costs[by + r + 1][bx + r]);
                }
                G.off[(size_t)i * G.gw + j] = o;
            }
        });
    }
    return g[0];
}

// ------------------------------------------------------------------ moteur

enum Mode { RAW_HDR = 0, RAW_SR = 1, YUV = 2 };

struct Engine {
    int mode, W, H, cfa;
    float black[4], white, noiseS, noiseO, mergeC;
    int frames = 0;
    // Référence
    std::vector<uint16_t> ref;
    std::vector<uint8_t> refY, refU, refV;
    std::vector<Img> refPyr;
    // Accumulateurs HDR / YUV
    std::vector<float> acc, wsum, accU, accV, wsumC;
    // Super-résolution
    int cx = 0, cy = 0, cw = 0, ch = 0; float scale = 1;
    int OW = 0, OH = 0;
    std::vector<float> srAcc[3], srW[3];
    float yuvNoiseVar = 4;
    // Carte de correction du vignetage fournie par le capteur (gains R, Gpair, Gimpair, B par point de grille).
    int shRows = 0, shCols = 0;
    std::vector<float> shading;
    // Pixels défectueux connus (calibration personnelle) : indices dans la mosaïque.
    std::vector<uint8_t> hotMask;
    std::vector<int> hotList;
    std::vector<float> lut; int lutN = 0;

    /** Gain de vignetage au photosite (x, y), interpolé bilinéairement sur la grille. */
    float shadingGain(int x, int y) const {
        if (shading.empty()) return 1.f;
        float gx = (x + 0.5f) / W * (shCols - 1), gy = (y + 0.5f) / H * (shRows - 1);
        int x0 = clampi((int)gx, 0, shCols - 2), y0 = clampi((int)gy, 0, shRows - 2);
        float fx = clampf(gx - x0, 0, 1), fy = clampf(gy - y0, 0, 1);
        int c = colorAt(x, y);
        // Canal de la carte : R=0, B=3, vert = moyenne des deux verts.
        auto at = [&](int cx, int cy) {
            const float* g = &shading[((size_t)cy * shCols + cx) * 4];
            return c == 0 ? g[0] : c == 2 ? g[3] : 0.5f * (g[1] + g[2]);
        };
        return (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) + (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy;
    }

    // Couleur du photosite (x, y) : 0 = R, 1 = G, 2 = B.
    int colorAt(int x, int y) const {
        static const int pat[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
        return pat[cfa][(y & 1) * 2 + (x & 1)];
    }
    float blackAt(int x, int y) const { return black[(y & 1) * 2 + (x & 1)]; }
};

Img grayFromRaw(const uint16_t* raw, int W, int H) {
    Img g; g.alloc(W / 2, H / 2);
    parallelFor(g.h, [&](int y) {
        const uint16_t* r0 = raw + (size_t)(2 * y) * W;
        const uint16_t* r1 = r0 + W;
        for (int x = 0; x < g.w; x++)
            g(x, y) = 0.25f * (r0[2 * x] + r0[2 * x + 1] + r1[2 * x] + r1[2 * x + 1]);
    });
    return g;
}

Img grayFromY(const uint8_t* y8, int W, int H) {
    Img g; g.alloc(W / 2, H / 2);
    parallelFor(g.h, [&](int y) {
        const uint8_t* r0 = y8 + (size_t)(2 * y) * W;
        const uint8_t* r1 = r0 + W;
        for (int x = 0; x < g.w; x++)
            g(x, y) = 0.25f * (r0[2 * x] + r0[2 * x + 1] + r1[2 * x] + r1[2 * x + 1]);
    });
    return g;
}

inline float window32(int i) { return 0.5f - 0.5f * std::cos(2.f * (float)M_PI * (i + 0.5f) / 32.f); }

/** Poids de fusion robuste (filtre de Wiener spatial de HDR+). Renvoie 1 - A. */
inline float wienerKeep(float d2, float var, float c) { return 1.f - d2 / (d2 + c * var + 1e-12f); }

// --- RAW : fusion HDR+ en flux -------------------------------------------------

void mergeRawHdr(Engine& e, const uint16_t* alt, const Grid& g) {
    float range = e.white - 0.25f * (e.black[0] + e.black[1] + e.black[2] + e.black[3]);
    float win[32];
    for (int i = 0; i < 32; i++) win[i] = window32(i);
    for (int phase = 0; phase < 4; phase++) { // les tuiles d'une même phase ne se chevauchent pas
        int pi = phase >> 1, pj = phase & 1;
        int rows = (g.gh - pi + 1) / 2, cols = (g.gw - pj + 1) / 2;
        parallelFor(rows * cols, [&](int k) {
            int i = pi + 2 * (k / cols), j = pj + 2 * (k % cols);
            int rx = 16 * j, ry = 16 * i;
            if (rx + 32 > e.W || ry + 32 > e.H) return;
            const Off& o = g.off[(size_t)i * g.gw + j];
            int dx = 2 * (int)std::lround(o.x), dy = 2 * (int)std::lround(o.y); // pair : conserve la mosaïque
            float d2 = 0, mean = 0;
            for (int y = 0; y < 32; y++)
                for (int x = 0; x < 32; x++) {
                    float r = e.ref[(size_t)(ry + y) * e.W + rx + x];
                    float a = alt[(size_t)clampi(ry + y + dy, 0, e.H - 1) * e.W + clampi(rx + x + dx, 0, e.W - 1)];
                    d2 += (a - r) * (a - r); mean += r;
                }
            d2 /= 1024; mean /= 1024;
            float xn = std::max(0.f, (mean - e.black[0]) / range);
            float var = std::max(1e-9f, e.noiseS * xn + e.noiseO) * range * range;
            float keep = wienerKeep(d2, var, e.mergeC);
            for (int y = 0; y < 32; y++)
                for (int x = 0; x < 32; x++) {
                    size_t idx = (size_t)(ry + y) * e.W + rx + x;
                    float r = e.ref[idx];
                    float a = alt[(size_t)clampi(ry + y + dy, 0, e.H - 1) * e.W + clampi(rx + x + dx, 0, e.W - 1)];
                    float w = win[x] * win[y];
                    e.acc[idx] += w * (r + keep * (a - r));
                    e.wsum[idx] += w;
                }
        });
    }
}

void addRefRawHdr(Engine& e) {
    e.acc.assign((size_t)e.W * e.H, 0.f);
    e.wsum.assign((size_t)e.W * e.H, 0.f);
    float win[32];
    for (int i = 0; i < 32; i++) win[i] = window32(i);
    int gw = gridDim(e.W / 2, 0), gh = gridDim(e.H / 2, 0);
    for (int phase = 0; phase < 4; phase++) {
        int pi = phase >> 1, pj = phase & 1;
        int rows = (gh - pi + 1) / 2, cols = (gw - pj + 1) / 2;
        parallelFor(rows * cols, [&](int k) {
            int i = pi + 2 * (k / cols), j = pj + 2 * (k % cols);
            int rx = 16 * j, ry = 16 * i;
            if (rx + 32 > e.W || ry + 32 > e.H) return;
            for (int y = 0; y < 32; y++)
                for (int x = 0; x < 32; x++) {
                    size_t idx = (size_t)(ry + y) * e.W + rx + x;
                    float w = win[x] * win[y];
                    e.acc[idx] += w * e.ref[idx];
                    e.wsum[idx] += w;
                }
        });
    }
}

// --- RAW : super-résolution (accumulation sous-pixel par noyaux) ---------------

void splatSr(Engine& e, const uint16_t* raw, const Grid* g, bool isRef) {
    const float sigma[3] = {0.9f, 0.55f, 0.9f}; // R et B sont deux fois plus espacés que G
    float range[4];
    for (int i = 0; i < 4; i++) range[i] = std::max(1.f, e.white - e.black[i]);
    float rangeAvg = 0.25f * (range[0] + range[1] + range[2] + range[3]);
    int margin = 48;
    int y0 = std::max(0, e.cy - margin), y1 = std::min(e.H, e.cy + e.ch + margin);
    int x0 = std::max(0, e.cx - margin), x1 = std::min(e.W, e.cx + e.cw + margin);
    const int band = 256;
    int bands = (y1 - y0 + band - 1) / band;
    for (int phase = 0; phase < 2; phase++) {
        int n = (bands - phase + 1) / 2;
        parallelFor(n, [&](int b) {
            int by0 = y0 + (phase + 2 * b) * band, by1 = std::min(y1, by0 + band);
            for (int y = by0; y < by1; y++) {
                for (int x = x0; x < x1; x++) {
                    float ox = 0, oy = 0, keep = 1;
                    if (!isRef) {
                        int j = clampi((x - 8) / 16, 0, g->gw - 1), i = clampi((y - 8) / 16, 0, g->gh - 1);
                        const Off& o = g->off[(size_t)i * g->gw + j];
                        ox = 2 * o.x; oy = 2 * o.y;
                        // Robustesse : écart local entre l'image et la référence recalée.
                        int ix = clampi(x - (int)std::lround(ox), 0, e.W - 1), iy = clampi(y - (int)std::lround(oy), 0, e.H - 1);
                        ix = (ix & ~1) | (x & 1); iy = (iy & ~1) | (y & 1);
                        float d = (float)raw[(size_t)y * e.W + x] - (float)e.ref[(size_t)clampi(iy, 0, e.H - 1) * e.W + clampi(ix, 0, e.W - 1)];
                        float xn = std::max(0.f, ((float)e.ref[(size_t)clampi(iy, 0, e.H - 1) * e.W + clampi(ix, 0, e.W - 1)] - e.black[0]) / rangeAvg);
                        float var = std::max(1e-9f, e.noiseS * xn + e.noiseO) * rangeAvg * rangeAvg;
                        keep = wienerKeep(d * d, var, e.mergeC * 4);
                        if (keep < 0.05f) continue;
                    }
                    int c = e.colorAt(x, y);
                    int bi = (y & 1) * 2 + (x & 1);
                    float v = ((float)raw[(size_t)y * e.W + x] - e.black[bi]) / range[bi] * e.shadingGain(x, y);
                    float rx = x - ox, ry = y - oy; // position dans la référence
                    float s = sigma[c], rad = 2.f * s;
                    int u0 = (int)std::ceil((rx - rad - e.cx + 0.5f) * e.scale - 0.5f);
                    int u1 = (int)std::floor((rx + rad - e.cx + 0.5f) * e.scale - 0.5f);
                    int v0 = (int)std::ceil((ry - rad - e.cy + 0.5f) * e.scale - 0.5f);
                    int v1 = (int)std::floor((ry + rad - e.cy + 0.5f) * e.scale - 0.5f);
                    u0 = std::max(u0, 0); v0 = std::max(v0, 0);
                    u1 = std::min(u1, e.OW - 1); v1 = std::min(v1, e.OH - 1);
                    float inv2s2 = 1.f / (2 * s * s);
                    for (int vv = v0; vv <= v1; vv++) {
                        float py = e.cy + (vv + 0.5f) / e.scale - 0.5f - ry;
                        for (int uu = u0; uu <= u1; uu++) {
                            float px = e.cx + (uu + 0.5f) / e.scale - 0.5f - rx;
                            float w = keep * std::exp(-(px * px + py * py) * inv2s2);
                            size_t o = (size_t)vv * e.OW + uu;
                            e.srAcc[c][o] += w * v;
                            e.srW[c][o] += w;
                        }
                    }
                }
            }
        });
    }
}

// --- YUV (téléphones sans RAW) -------------------------------------------------

void mergeYuv(Engine& e, const uint8_t* y8, const uint8_t* u8, const uint8_t* v8, const Grid* g) {
    float win32[32], win16[16];
    for (int i = 0; i < 32; i++) win32[i] = window32(i);
    for (int i = 0; i < 16; i++) win16[i] = 0.5f - 0.5f * std::cos(2.f * (float)M_PI * (i + 0.5f) / 16.f);
    int gw = gridDim(e.W / 2, 0), gh = gridDim(e.H / 2, 0);
    int CW = e.W / 2, CH = e.H / 2;
    for (int phase = 0; phase < 4; phase++) {
        int pi = phase >> 1, pj = phase & 1;
        int rows = (gh - pi + 1) / 2, cols = (gw - pj + 1) / 2;
        parallelFor(rows * cols, [&](int k) {
            int i = pi + 2 * (k / cols), j = pj + 2 * (k % cols);
            int rx = 16 * j, ry = 16 * i;
            if (rx + 32 > e.W || ry + 32 > e.H) return;
            int dx = 0, dy = 0; float keep = 1;
            if (g) {
                const Off& o = g->off[(size_t)i * g->gw + j];
                dx = 2 * (int)std::lround(o.x); dy = 2 * (int)std::lround(o.y);
                float d2 = 0;
                for (int y = 0; y < 32; y++)
                    for (int x = 0; x < 32; x++) {
                        float r = e.refY[(size_t)(ry + y) * e.W + rx + x];
                        float a = y8[(size_t)clampi(ry + y + dy, 0, e.H - 1) * e.W + clampi(rx + x + dx, 0, e.W - 1)];
                        d2 += (a - r) * (a - r);
                    }
                keep = wienerKeep(d2 / 1024, e.yuvNoiseVar, e.mergeC);
            }
            for (int y = 0; y < 32; y++)
                for (int x = 0; x < 32; x++) {
                    size_t idx = (size_t)(ry + y) * e.W + rx + x;
                    float r = e.refY[idx];
                    float a = g ? y8[(size_t)clampi(ry + y + dy, 0, e.H - 1) * e.W + clampi(rx + x + dx, 0, e.W - 1)] : r;
                    float w = win32[x] * win32[y];
                    e.acc[idx] += w * (r + keep * (a - r));
                    e.wsum[idx] += w;
                }
            int crx = rx / 2, cry = ry / 2, cdx = dx / 2, cdy = dy / 2;
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++) {
                    size_t idx = (size_t)(cry + y) * CW + crx + x;
                    size_t aidx = (size_t)clampi(cry + y + cdy, 0, CH - 1) * CW + clampi(crx + x + cdx, 0, CW - 1);
                    float ru = e.refU[idx], rv = e.refV[idx];
                    float au = g ? u8[aidx] : ru, av = g ? v8[aidx] : rv;
                    float w = win16[x] * win16[y];
                    e.accU[idx] += w * (ru + keep * (au - ru));
                    e.accV[idx] += w * (rv + keep * (av - rv));
                    e.wsumC[idx] += w;
                }
        });
    }
}

// ------------------------------------------------------------------ rendu

/** Dématriçage Malvar-He-Cutler (2004) sur la mosaïque fusionnée, normalisée [0,1]. */
void demosaic(const Engine& e, const std::vector<float>& raw, std::vector<float>& rgb) {
    int W = e.W, H = e.H;
    rgb.assign((size_t)W * H * 3, 0.f);
    auto P = [&](int x, int y) { return raw[(size_t)clampi(y, 0, H - 1) * W + clampi(x, 0, W - 1)]; };
    parallelFor(H, [&](int y) {
        for (int x = 0; x < W; x++) {
            int c = e.colorAt(x, y);
            float C = P(x, y);
            float out[3]; out[c] = C;
            float N = P(x, y - 1), S = P(x, y + 1), Wl = P(x - 1, y), E = P(x + 1, y);
            float NN = P(x, y - 2), SS = P(x, y + 2), WW = P(x - 2, y), EE = P(x + 2, y);
            float NW = P(x - 1, y - 1), NE = P(x + 1, y - 1), SW = P(x - 1, y + 1), SE = P(x + 1, y + 1);
            if (c == 1) {
                float horiz = (5 * C + 4 * (Wl + E) - (WW + EE) - (NW + NE + SW + SE) + 0.5f * (NN + SS)) / 8;
                float vert = (5 * C + 4 * (N + S) - (NN + SS) - (NW + NE + SW + SE) + 0.5f * (WW + EE)) / 8;
                int hc = e.colorAt(x + 1, y); // couleur des voisins horizontaux
                out[hc] = horiz; out[2 - hc] = vert;
            } else {
                out[1] = (4 * C + 2 * (N + S + Wl + E) - (NN + SS + WW + EE)) / 8;
                out[2 - c] = (6 * C + 2 * (NW + NE + SW + SE) - 1.5f * (NN + SS + WW + EE)) / 8;
            }
            float* o = &rgb[((size_t)y * W + x) * 3];
            o[0] = std::max(0.f, out[0]); o[1] = std::max(0.f, out[1]); o[2] = std::max(0.f, out[2]);
        }
    });
}

struct RenderParams {
    float wb[3], ccm[9];
    float exposure, compression, detail, sharpen, chroma, saturation;
    bool linearInput; // false pour le YUV (déjà gamma)
    const float* lut = nullptr; int lutN = 0; // style personnel : table 3D sur valeurs sRGB
};

/** Interpolation trilinéaire dans une table 3D n³ (R le plus rapide). */
inline void applyLut(float* c, const float* lut, int n) {
    float f[3]; int i0[3], i1[3]; float t[3];
    for (int k = 0; k < 3; k++) {
        f[k] = clampf(c[k], 0.f, 1.f) * (n - 1);
        i0[k] = std::min((int)f[k], n - 2); i1[k] = i0[k] + 1; t[k] = f[k] - i0[k];
    }
    auto L = [&](int r, int g, int b, int ch) { return lut[(((size_t)b * n + g) * n + r) * 3 + ch]; };
    for (int ch = 0; ch < 3; ch++) {
        float c00 = L(i0[0], i0[1], i0[2], ch) * (1 - t[0]) + L(i1[0], i0[1], i0[2], ch) * t[0];
        float c10 = L(i0[0], i1[1], i0[2], ch) * (1 - t[0]) + L(i1[0], i1[1], i0[2], ch) * t[0];
        float c01 = L(i0[0], i0[1], i1[2], ch) * (1 - t[0]) + L(i1[0], i0[1], i1[2], ch) * t[0];
        float c11 = L(i0[0], i1[1], i1[2], ch) * (1 - t[0]) + L(i1[0], i1[1], i1[2], ch) * t[0];
        f[ch] = (c00 * (1 - t[1]) + c10 * t[1]) * (1 - t[2]) + (c01 * (1 - t[1]) + c11 * t[1]) * t[2];
    }
    c[0] = f[0]; c[1] = f[1]; c[2] = f[2];
}

inline float srgbGamma(float v) {
    v = clampf(v, 0.f, 1.f);
    return v <= 0.0031308f ? 12.92f * v : 1.055f * std::pow(v, 1.f / 2.4f) - 0.055f;
}

/** Chaîne de rendu commune : couleurs, tonalité locale, courbe, débruitage chroma, netteté. */
void render(std::vector<float>& rgb, int W, int H, const RenderParams& p, uint8_t* out, int stride) {
    size_t n = (size_t)W * H;
    if (p.linearInput) {
        parallelFor(H, [&](int y) {
            for (int x = 0; x < W; x++) {
                float* c = &rgb[((size_t)y * W + x) * 3];
                float r = c[0] * p.wb[0], g = c[1] * p.wb[1], b = c[2] * p.wb[2];
                c[0] = std::max(0.f, (p.ccm[0] * r + p.ccm[1] * g + p.ccm[2] * b) * p.exposure);
                c[1] = std::max(0.f, (p.ccm[3] * r + p.ccm[4] * g + p.ccm[5] * b) * p.exposure);
                c[2] = std::max(0.f, (p.ccm[6] * r + p.ccm[7] * g + p.ccm[8] * b) * p.exposure);
            }
        });
    }
    // Tonalité locale : on compresse la base (basses fréquences) du log-luminance et on garde le détail.
    Img logL; logL.alloc(W, H);
    parallelFor(H, [&](int y) {
        for (int x = 0; x < W; x++) {
            const float* c = &rgb[((size_t)y * W + x) * 3];
            logL(x, y) = std::log2(0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2] + 1e-4f);
        }
    });
    Img base = downsample(logL, 8);
    boxBlur(base, 4, 3);
    // Ancre : luminance médiane (approchée par la moyenne de la base).
    double sum = 0;
    for (float v : base.d) sum += v;
    float anchor = (float)(sum / base.d.size());
    // Ramène en partie la scène vers un gris moyen (exposition automatique douce).
    float target = p.linearInput ? 0.4f * anchor + 0.6f * std::log2(0.18f) : anchor;
    parallelFor(H, [&](int y) {
        for (int x = 0; x < W; x++) {
            float b = base.bilinear((x + 0.5f) / 8 - 0.5f, (y + 0.5f) / 8 - 0.5f);
            float l = logL(x, y);
            float nl = target + (b - anchor) * p.compression + (l - b) * p.detail;
            float gain = std::exp2(nl - l);
            float* c = &rgb[((size_t)y * W + x) * 3];
            for (int k = 0; k < 3; k++) {
                float v = c[k] * gain;
                if (p.linearInput) {
                    v = v * (1 + v / 16.f) / (1 + v); // Reinhard étendu (blanc à 4)
                    v = srgbGamma(v);
                }
                c[k] = clampf(v, 0.f, 1.f);
            }
            if (p.lut) applyLut(c, p.lut, p.lutN);
        }
    });
    { Img().d.swap(logL.d); Img().d.swap(base.d); } // libère la mémoire au plus tôt
    // YCbCr : saturation, débruitage chroma, netteté de la luminance.
    Img Y, Cb, Cr; Y.alloc(W, H); Cb.alloc(W, H); Cr.alloc(W, H);
    parallelFor(H, [&](int y) {
        for (int x = 0; x < W; x++) {
            const float* c = &rgb[((size_t)y * W + x) * 3];
            float yy = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2];
            Y(x, y) = yy;
            Cb(x, y) = (c[2] - yy) * p.saturation;
            Cr(x, y) = (c[0] - yy) * p.saturation;
        }
    });
    std::vector<float>().swap(rgb); // l'image RGB n'est plus nécessaire
    if (p.chroma > 0) {
        int r = (int)std::lround(p.chroma);
        boxBlur(Cb, r, 2); boxBlur(Cr, r, 2);
    }
    Img blurY = Y; boxBlur(blurY, 1, 2);
    parallelFor(H, [&](int y) {
        uint8_t* row = out + (size_t)y * stride;
        for (int x = 0; x < W; x++) {
            float yy = Y(x, y) + p.sharpen * (Y(x, y) - blurY(x, y));
            float r = yy + Cr(x, y), b = yy + Cb(x, y);
            float g = (yy - 0.299f * r - 0.114f * b) / 0.587f;
            row[4 * x + 0] = (uint8_t)std::lround(clampf(r, 0, 1) * 255);
            row[4 * x + 1] = (uint8_t)std::lround(clampf(g, 0, 1) * 255);
            row[4 * x + 2] = (uint8_t)std::lround(clampf(b, 0, 1) * 255);
            row[4 * x + 3] = 255;
        }
    });
    (void)n;
}

}  // namespace

// ------------------------------------------------------------------ JNI

extern "C" {

JNIEXPORT jlong JNICALL Java_com_macam_photo_Engine_nCreate(JNIEnv* env, jclass, jint mode, jint w, jint h, jint cfa,
        jfloatArray black, jfloat white, jfloat noiseS, jfloat noiseO, jfloat mergeC,
        jint cx, jint cy, jint cw, jint ch, jfloat scale) {
    auto* e = new Engine();
    e->mode = mode; e->W = w & ~1; e->H = h & ~1; e->cfa = cfa;
    env->GetFloatArrayRegion(black, 0, 4, e->black);
    e->white = white; e->noiseS = noiseS; e->noiseO = noiseO; e->mergeC = mergeC;
    if (mode == RAW_SR) {
        e->cx = cx & ~1; e->cy = cy & ~1; e->cw = cw; e->ch = ch; e->scale = scale;
        e->OW = (int)(cw * scale); e->OH = (int)(ch * scale);
        for (int c = 0; c < 3; c++) {
            e->srAcc[c].assign((size_t)e->OW * e->OH, 0.f);
            e->srW[c].assign((size_t)e->OW * e->OH, 0.f);
        }
    }
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL Java_com_macam_photo_Engine_nAddRaw(JNIEnv* env, jclass, jlong h, jobject buf, jint rowStride) {
    auto* e = reinterpret_cast<Engine*>(h);
    auto* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(buf));
    std::vector<uint16_t> frame((size_t)e->W * e->H);
    for (int y = 0; y < e->H; y++) std::memcpy(&frame[(size_t)y * e->W], src + (size_t)y * rowStride, (size_t)e->W * 2);
    // Pixels défectueux connus (calibration) : remplacés par la moyenne des voisins de même couleur.
    for (int idx : e->hotList) {
        int x = idx % e->W, y = idx / e->W;
        int sum = 0, n = 0;
        const int d[4][2] = {{-2, 0}, {2, 0}, {0, -2}, {0, 2}};
        for (auto& o : d) {
            int xx = x + o[0], yy = y + o[1];
            if (xx < 0 || yy < 0 || xx >= e->W || yy >= e->H) continue;
            size_t j = (size_t)yy * e->W + xx;
            if (!e->hotMask.empty() && e->hotMask[j]) continue;
            sum += frame[j]; n++;
        }
        if (n) frame[idx] = (uint16_t)(sum / n);
    }
    if (e->frames == 0) {
        e->ref = std::move(frame);
        e->refPyr = buildPyramid(grayFromRaw(e->ref.data(), e->W, e->H));
        if (e->mode == RAW_HDR) addRefRawHdr(*e);
        else splatSr(*e, e->ref.data(), nullptr, true);
    } else {
        auto pyr = buildPyramid(grayFromRaw(frame.data(), e->W, e->H));
        Grid g = align(e->refPyr, pyr);
        if (e->mode == RAW_HDR) mergeRawHdr(*e, frame.data(), g);
        else splatSr(*e, frame.data(), &g, false);
    }
    e->frames++;
}

JNIEXPORT void JNICALL Java_com_macam_photo_Engine_nAddYuv(JNIEnv* env, jclass, jlong h,
        jobject yb, jint yStride, jobject ub, jobject vb, jint uvStride, jint uvPixelStride) {
    auto* e = reinterpret_cast<Engine*>(h);
    auto* ys = static_cast<uint8_t*>(env->GetDirectBufferAddress(yb));
    auto* us = static_cast<uint8_t*>(env->GetDirectBufferAddress(ub));
    auto* vs = static_cast<uint8_t*>(env->GetDirectBufferAddress(vb));
    int CW = e->W / 2, CH = e->H / 2;
    std::vector<uint8_t> Y((size_t)e->W * e->H), U((size_t)CW * CH), V((size_t)CW * CH);
    for (int y = 0; y < e->H; y++) std::memcpy(&Y[(size_t)y * e->W], ys + (size_t)y * yStride, e->W);
    for (int y = 0; y < CH; y++)
        for (int x = 0; x < CW; x++) {
            U[(size_t)y * CW + x] = us[(size_t)y * uvStride + x * uvPixelStride];
            V[(size_t)y * CW + x] = vs[(size_t)y * uvStride + x * uvPixelStride];
        }
    if (e->frames == 0) {
        e->refY = std::move(Y); e->refU = std::move(U); e->refV = std::move(V);
        e->refPyr = buildPyramid(grayFromY(e->refY.data(), e->W, e->H));
        // Estimation du bruit : écart absolu médian du laplacien (approché par la moyenne).
        double s = 0; int cnt = 0;
        for (int y = 1; y < e->H - 1; y += 4)
            for (int x = 1; x < e->W - 1; x += 4, cnt++) {
                const uint8_t* p = &e->refY[(size_t)y * e->W + x];
                s += std::fabs(4.0 * p[0] - p[-1] - p[1] - p[-e->W] - p[e->W]);
            }
        float sigma = (float)(s / std::max(1, cnt)) / 4.f;
        e->yuvNoiseVar = std::max(1.f, sigma * sigma);
        e->acc.assign((size_t)e->W * e->H, 0.f); e->wsum.assign((size_t)e->W * e->H, 0.f);
        e->accU.assign((size_t)CW * CH, 0.f); e->accV.assign((size_t)CW * CH, 0.f); e->wsumC.assign((size_t)CW * CH, 0.f);
        mergeYuv(*e, nullptr, nullptr, nullptr, nullptr);
    } else {
        auto pyr = buildPyramid(grayFromY(Y.data(), e->W, e->H));
        Grid g = align(e->refPyr, pyr);
        mergeYuv(*e, Y.data(), U.data(), V.data(), &g);
    }
    e->frames++;
}

JNIEXPORT jint JNICALL Java_com_macam_photo_Engine_nFrames(JNIEnv*, jclass, jlong h) {
    return reinterpret_cast<Engine*>(h)->frames;
}

JNIEXPORT jintArray JNICALL Java_com_macam_photo_Engine_nOutputSize(JNIEnv* env, jclass, jlong h) {
    auto* e = reinterpret_cast<Engine*>(h);
    jint s[2] = {e->mode == RAW_SR ? e->OW : e->W, e->mode == RAW_SR ? e->OH : e->H};
    jintArray a = env->NewIntArray(2);
    env->SetIntArrayRegion(a, 0, 2, s);
    return a;
}

/** Mosaïque fusionnée en RAW16 (même niveau de noir que le capteur), pour un DNG. */
JNIEXPORT jboolean JNICALL Java_com_macam_photo_Engine_nGetMergedRaw(JNIEnv* env, jclass, jlong h, jobject out) {
    auto* e = reinterpret_cast<Engine*>(h);
    if (e->mode != RAW_HDR || e->frames == 0) return JNI_FALSE;
    auto* dst = static_cast<uint16_t*>(env->GetDirectBufferAddress(out));
    parallelFor(e->H, [&](int y) {
        for (int x = 0; x < e->W; x++) {
            size_t i = (size_t)y * e->W + x;
            float v = e->wsum[i] > 1e-4f ? e->acc[i] / e->wsum[i] : e->ref[i];
            dst[i] = (uint16_t)clampf(std::round(v), 0, e->white);
        }
    });
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_macam_photo_Engine_nRender(JNIEnv* env, jclass, jlong h, jobject bitmap,
        jfloatArray wb, jfloatArray ccm, jfloat exposure, jfloat compression, jfloat detail,
        jfloat sharpen, jfloat chroma, jfloat saturation) {
    auto* e = reinterpret_cast<Engine*>(h);
    if (e->frames == 0) return JNI_FALSE;
    RenderParams p{};
    env->GetFloatArrayRegion(wb, 0, 3, p.wb);
    env->GetFloatArrayRegion(ccm, 0, 9, p.ccm);
    p.exposure = exposure; p.compression = compression; p.detail = detail;
    p.sharpen = sharpen; p.chroma = chroma; p.saturation = saturation;
    p.linearInput = e->mode != YUV;

    std::vector<float> rgb;
    int W, H;
    if (e->mode == RAW_HDR) {
        W = e->W; H = e->H;
        std::vector<float> raw((size_t)W * H);
        parallelFor(H, [&](int y) {
            for (int x = 0; x < W; x++) {
                size_t i = (size_t)y * W + x;
                float v = e->wsum[i] > 1e-4f ? e->acc[i] / e->wsum[i] : e->ref[i];
                float b = e->blackAt(x, y);
                raw[i] = std::max(0.f, (v - b) / (e->white - b) * e->shadingGain(x, y));
            }
        });
        std::vector<float>().swap(e->acc); std::vector<float>().swap(e->wsum);
        demosaic(*e, raw, rgb);
    } else if (e->mode == RAW_SR) {
        W = e->OW; H = e->OH;
        rgb.assign((size_t)W * H * 3, 0.f);
        // Repli pour les pixels peu couverts : moyenne par blocs de 8×8.
        for (int c = 0; c < 3; c++) {
            Img coarse; coarse.alloc((W + 7) / 8, (H + 7) / 8);
            Img cw; cw.alloc(coarse.w, coarse.h);
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++) {
                    size_t i = (size_t)y * W + x;
                    coarse(x / 8, y / 8) += e->srAcc[c][i];
                    cw(x / 8, y / 8) += e->srW[c][i];
                }
            for (size_t i = 0; i < coarse.d.size(); i++) coarse.d[i] = cw.d[i] > 1e-6f ? coarse.d[i] / cw.d[i] : 0;
            parallelFor(H, [&](int y) {
                for (int x = 0; x < W; x++) {
                    size_t i = (size_t)y * W + x;
                    float w = e->srW[c][i];
                    float fb = coarse.bilinear((x + 0.5f) / 8 - 0.5f, (y + 0.5f) / 8 - 0.5f);
                    float v = w > 1e-6f ? e->srAcc[c][i] / w : fb;
                    float t = clampf(w / 0.3f, 0, 1); // transition douce vers le repli
                    rgb[i * 3 + c] = std::max(0.f, t * v + (1 - t) * fb);
                }
            });
            std::vector<float>().swap(e->srAcc[c]); std::vector<float>().swap(e->srW[c]);
        }
    } else {
        W = e->W; H = e->H;
        int CW = W / 2;
        rgb.assign((size_t)W * H * 3, 0.f);
        parallelFor(H, [&](int y) {
            for (int x = 0; x < W; x++) {
                size_t i = (size_t)y * W + x, ci = (size_t)(y / 2) * CW + x / 2;
                float Y = e->wsum[i] > 1e-4f ? e->acc[i] / e->wsum[i] : e->refY[i];
                float U = (e->wsumC[ci] > 1e-4f ? e->accU[ci] / e->wsumC[ci] : e->refU[ci]) - 128;
                float V = (e->wsumC[ci] > 1e-4f ? e->accV[ci] / e->wsumC[ci] : e->refV[ci]) - 128;
                rgb[i * 3 + 0] = clampf((Y + 1.402f * V) / 255, 0, 1);
                rgb[i * 3 + 1] = clampf((Y - 0.344136f * U - 0.714136f * V) / 255, 0, 1);
                rgb[i * 3 + 2] = clampf((Y + 1.772f * U) / 255, 0, 1);
            }
        });
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || (int)info.width != W || (int)info.height != H ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    void* px;
    if (AndroidBitmap_lockPixels(env, bitmap, &px) < 0) return JNI_FALSE;
    if (!e->lut.empty()) { p.lut = e->lut.data(); p.lutN = e->lutN; }
    render(rgb, W, H, p, static_cast<uint8_t*>(px), (int)info.stride);
    AndroidBitmap_unlockPixels(env, bitmap);
    LOG("rendu %dx%d à partir de %d images", W, H, e->frames);
    return JNI_TRUE;
}

/** Carte de vignetage du capteur : rows × cols points, 4 gains chacun (R, Gpair, Gimpair, B). */
JNIEXPORT void JNICALL Java_com_macam_photo_Engine_nSetShading(JNIEnv* env, jclass, jlong h, jfloatArray gains, jint rows, jint cols) {
    auto* e = reinterpret_cast<Engine*>(h);
    if (rows < 2 || cols < 2) return;
    e->shRows = rows; e->shCols = cols;
    e->shading.resize((size_t)rows * cols * 4);
    env->GetFloatArrayRegion(gains, 0, rows * cols * 4, e->shading.data());
}

/** Pixels défectueux mesurés par la calibration personnelle (indices dans la mosaïque). */
JNIEXPORT void JNICALL Java_com_macam_photo_Engine_nSetHotPixels(JNIEnv* env, jclass, jlong h, jintArray idx) {
    auto* e = reinterpret_cast<Engine*>(h);
    jsize n = env->GetArrayLength(idx);
    e->hotList.resize(n);
    env->GetIntArrayRegion(idx, 0, n, e->hotList.data());
    e->hotMask.assign((size_t)e->W * e->H, 0);
    for (int i : e->hotList) if (i >= 0 && (size_t)i < e->hotMask.size()) e->hotMask[i] = 1;
    e->hotList.erase(std::remove_if(e->hotList.begin(), e->hotList.end(),
        [&](int i) { return i < 0 || (size_t)i >= e->hotMask.size(); }), e->hotList.end());
}

/** Table de couleurs 3D (style personnel), appliquée en fin de rendu. */
JNIEXPORT void JNICALL Java_com_macam_photo_Engine_nSetLut(JNIEnv* env, jclass, jlong h, jfloatArray data, jint n) {
    auto* e = reinterpret_cast<Engine*>(h);
    if (n < 2 || env->GetArrayLength(data) != n * n * n * 3) return;
    e->lutN = n; e->lut.resize((size_t)n * n * n * 3);
    env->GetFloatArrayRegion(data, 0, n * n * n * 3, e->lut.data());
}

JNIEXPORT void JNICALL Java_com_macam_photo_Engine_nRelease(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<Engine*>(h);
}

}  // extern "C"
