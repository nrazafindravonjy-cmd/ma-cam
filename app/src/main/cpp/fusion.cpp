// Ma Cam – fusion de deux objectifs pris au même instant (ultra grand-angle + principal).
// Le centre de l'image ultra grand-angle est remplacé par l'image du principal (plus nette, moins bruitée) :
// mise à l'échelle selon le rapport des focales, alignement par corrélation, harmonisation des couleurs,
// raccord progressif sur les bords.

#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace fusion {

struct Rgb { int w = 0, h = 0; std::vector<float> d; float* px(int x, int y) { return &d[((size_t)y * w + x) * 3]; } };

/** Réduction par moyenne de zone vers (W, H). */
Rgb resize(const uint8_t* src, int sw, int sh, int sstride, int W, int H) {
    Rgb o; o.w = W; o.h = H; o.d.assign((size_t)W * H * 3, 0.f);
    for (int y = 0; y < H; y++) {
        int y0 = y * sh / H, y1 = std::max(y0 + 1, (y + 1) * sh / H);
        for (int x = 0; x < W; x++) {
            int x0 = x * sw / W, x1 = std::max(x0 + 1, (x + 1) * sw / W);
            float acc[3] = {0, 0, 0}; int n = 0;
            for (int yy = y0; yy < y1; yy++) {
                const uint8_t* r = src + (size_t)yy * sstride;
                for (int xx = x0; xx < x1; xx++) { acc[0] += r[xx * 4]; acc[1] += r[xx * 4 + 1]; acc[2] += r[xx * 4 + 2]; n++; }
            }
            float* p = o.px(x, y); p[0] = acc[0] / n; p[1] = acc[1] / n; p[2] = acc[2] / n;
        }
    }
    return o;
}

std::vector<float> luma(const uint8_t* src, int w, int h, int stride, int f, int& lw, int& lh) {
    lw = w / f; lh = h / f;
    std::vector<float> l((size_t)lw * lh);
    for (int y = 0; y < lh; y++) for (int x = 0; x < lw; x++) {
        float a = 0;
        for (int j = 0; j < f; j++) { const uint8_t* r = src + (size_t)(y * f + j) * stride; for (int i = 0; i < f; i++) { const uint8_t* p = r + (x * f + i) * 4; a += 0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2]; } }
        l[(size_t)y * lw + x] = a / (f * f);
    }
    return l;
}

/** Corrélation normalisée (centrée) du détail placé en (ox, oy) dans la base, sur une grille réduite. */
double ncc(const std::vector<float>& b, int bw, const std::vector<float>& d, int dw, int dh, int ox, int oy) {
    double sb = 0, sd = 0, sbb = 0, sdd = 0, sbd = 0; int n = 0;
    for (int y = 0; y < dh; y += 2) for (int x = 0; x < dw; x += 2) {
        double vb = b[(size_t)(y + oy) * bw + x + ox], vd = d[(size_t)y * dw + x];
        sb += vb; sd += vd; sbb += vb * vb; sdd += vd * vd; sbd += vb * vd; n++;
    }
    double cov = sbd / n - sb / n * sd / n, vb = sbb / n - (sb / n) * (sb / n), vd = sdd / n - (sd / n) * (sd / n);
    return cov / std::sqrt(std::max(vb * vd, 1e-9));
}

/**
 * Fusionne [detail] (principal) dans [base] (ultra grand-angle), en place.
 * [ratio] = part de la largeur de la base couverte par le détail (focale UGA / focale principale).
 * Renvoie le décalage trouvé (dx, dy) en pixels de la base, et la corrélation.
 */
void fuse(uint8_t* base, int bw, int bh, int bstride, const uint8_t* det, int dw, int dh, int dstride,
          float ratio, int out[2], float& score) {
    int rw = (int)std::lround(bw * ratio), rh = (int)std::lround((double)rw * dh / dw);
    rw = std::min(rw, bw); rh = std::min(rh, bh);
    // Alignement à 1/8 puis affinage à 1/2.
    const int f = 8;
    int blw, blh; auto bl = luma(base, bw, bh, bstride, f, blw, blh);
    Rgb small = resize(det, dw, dh, dstride, rw / f, rh / f);
    std::vector<float> dl((size_t)small.w * small.h);
    for (size_t i = 0; i < dl.size(); i++) { float* p = &small.d[i * 3]; dl[i] = 0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2]; }
    int cx = (blw - small.w) / 2, cy = (blh - small.h) / 2, rad = std::max(4, blw / 12);
    double best = -2; int bx = cx, by = cy;
    for (int oy = std::max(0, cy - rad); oy <= std::min(blh - small.h, cy + rad); oy++)
        for (int ox = std::max(0, cx - rad); ox <= std::min(blw - small.w, cx + rad); ox++) {
            double c = ncc(bl, blw, dl, small.w, small.h, ox, oy);
            if (c > best) { best = c; bx = ox; by = oy; }
        }
    int px = bx * f, py = by * f;
    // Affinage (±f px) à pleine résolution réduite d'un facteur 2.
    {
        int f2 = 2, lw2, lh2; auto bl2 = luma(base, bw, bh, bstride, f2, lw2, lh2);
        Rgb s2 = resize(det, dw, dh, dstride, rw / f2, rh / f2);
        std::vector<float> d2((size_t)s2.w * s2.h);
        for (size_t i = 0; i < d2.size(); i++) { float* p = &s2.d[i * 3]; d2[i] = 0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2]; }
        double b2 = -2; int rx = px / f2, ry = py / f2;
        for (int oy = std::max(0, py / f2 - f); oy <= std::min(lh2 - s2.h, py / f2 + f); oy++)
            for (int ox = std::max(0, px / f2 - f); ox <= std::min(lw2 - s2.w, px / f2 + f); ox++) {
                double c = ncc(bl2, lw2, d2, s2.w, s2.h, ox, oy);
                if (c > b2) { b2 = c; rx = ox; ry = oy; }
            }
        px = rx * f2; py = ry * f2; best = b2;
        // rw/rh impairs : rx·2 peut dépasser d'un pixel ; on garde la zone dans l'image.
        px = std::clamp(px, 0, bw - rw); py = std::clamp(py, 0, bh - rh);
    }
    out[0] = px - (bw - rw) / 2; out[1] = py - (bh - rh) / 2; score = (float)best;
    if (best < 0.5) return; // pas de correspondance fiable : on garde l'ultra grand-angle seul

    Rgb d = resize(det, dw, dh, dstride, rw, rh);
    // Harmonisation des couleurs : moyenne et écart-type du détail ramenés sur ceux de la base (même zone).
    double mb[3] = {0}, md[3] = {0}, vb[3] = {0}, vd[3] = {0}; size_t n = 0;
    for (int y = 0; y < rh; y += 4) for (int x = 0; x < rw; x += 4) {
        const uint8_t* p = base + (size_t)(y + py) * bstride + (x + px) * 4; float* q = d.px(x, y);
        for (int c = 0; c < 3; c++) { mb[c] += p[c]; md[c] += q[c]; vb[c] += p[c] * p[c]; vd[c] += q[c] * q[c]; }
        n++;
    }
    float gain[3], off[3];
    for (int c = 0; c < 3; c++) {
        mb[c] /= n; md[c] /= n;
        double sb = std::sqrt(std::max(vb[c] / n - mb[c] * mb[c], 1.0)), sd = std::sqrt(std::max(vd[c] / n - md[c] * md[c], 1.0));
        gain[c] = (float)std::clamp(sb / sd, 0.7, 1.4); off[c] = (float)(mb[c] - gain[c] * md[c]);
    }
    // Raccord progressif : poids 0 au bord de la zone → 1 à l'intérieur (6 % de la largeur).
    float feather = std::max(8.f, rw * 0.06f);
    for (int y = 0; y < rh; y++) {
        uint8_t* row = base + (size_t)(y + py) * bstride;
        float wy = std::min(1.f, std::min(y, rh - 1 - y) / feather);
        for (int x = 0; x < rw; x++) {
            float wx = std::min(1.f, std::min(x, rw - 1 - x) / feather);
            float w = wx * wy; w = w * w * (3 - 2 * w);
            if (w <= 0) continue;
            uint8_t* p = row + (x + px) * 4; float* q = d.px(x, y);
            for (int c = 0; c < 3; c++) {
                float v = gain[c] * q[c] + off[c];
                p[c] = (uint8_t)std::clamp((int)std::lround(p[c] * (1 - w) + v * w), 0, 255);
            }
        }
    }
}

/** YUV 4:2:0 (plans quelconques) → RGBA 8 bits, BT.601 pleine échelle (sortie JPEG des capteurs). */
void yuvToRgba(const uint8_t* Y, int ys, const uint8_t* U, const uint8_t* V, int uvs, int uvp, int w, int h, uint8_t* out, int os) {
    for (int y = 0; y < h; y++) {
        uint8_t* o = out + (size_t)y * os;
        for (int x = 0; x < w; x++) {
            float yy = Y[(size_t)y * ys + x];
            float u = U[(size_t)(y / 2) * uvs + (x / 2) * uvp] - 128.f, v = V[(size_t)(y / 2) * uvs + (x / 2) * uvp] - 128.f;
            o[x * 4] = (uint8_t)std::clamp((int)(yy + 1.402f * v), 0, 255);
            o[x * 4 + 1] = (uint8_t)std::clamp((int)(yy - 0.344136f * u - 0.714136f * v), 0, 255);
            o[x * 4 + 2] = (uint8_t)std::clamp((int)(yy + 1.772f * u), 0, 255);
            o[x * 4 + 3] = 255;
        }
    }
}

}  // namespace fusion

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_macam_photo_LensFusion_nYuvToBitmap(JNIEnv* env, jclass, jobject y, jint ys, jobject u, jobject v,
        jint uvs, jint uvp, jobject bmp) {
    AndroidBitmapInfo info; void* px;
    if (AndroidBitmap_getInfo(env, bmp, &info) < 0 || AndroidBitmap_lockPixels(env, bmp, &px) < 0) return JNI_FALSE;
    fusion::yuvToRgba((uint8_t*)env->GetDirectBufferAddress(y), ys, (uint8_t*)env->GetDirectBufferAddress(u),
        (uint8_t*)env->GetDirectBufferAddress(v), uvs, uvp, info.width, info.height, (uint8_t*)px, info.stride);
    AndroidBitmap_unlockPixels(env, bmp);
    return JNI_TRUE;
}

/** Renvoie [dx, dy, corrélation × 1000] ; la base est modifiée en place. */
JNIEXPORT jintArray JNICALL Java_com_macam_photo_LensFusion_nFuse(JNIEnv* env, jclass, jobject base, jobject detail, jfloat ratio) {
    AndroidBitmapInfo bi, di; void *bp, *dp;
    AndroidBitmap_getInfo(env, base, &bi); AndroidBitmap_getInfo(env, detail, &di);
    if (AndroidBitmap_lockPixels(env, base, &bp) < 0) return nullptr;
    if (AndroidBitmap_lockPixels(env, detail, &dp) < 0) { AndroidBitmap_unlockPixels(env, base); return nullptr; }
    int off[2]; float score = 0;
    fusion::fuse((uint8_t*)bp, bi.width, bi.height, bi.stride, (const uint8_t*)dp, di.width, di.height, di.stride, ratio, off, score);
    AndroidBitmap_unlockPixels(env, detail); AndroidBitmap_unlockPixels(env, base);
    jint r[3] = {off[0], off[1], (jint)(score * 1000)};
    jintArray a = env->NewIntArray(3); env->SetIntArrayRegion(a, 0, 3, r);
    return a;
}

}  // extern "C"
