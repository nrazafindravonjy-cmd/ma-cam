package com.macam.cam

import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import kotlin.math.abs
import kotlin.math.ln

/**
 * Aides à l'exposition et à la mise au point, calculées sur un flux basse définition
 * et affichées par-dessus l'aperçu.
 */
class ProTools {
    @Volatile var zebra = false
    @Volatile var peaking = false
    @Volatile var falseColor = false
    val anyEnabled get() = zebra || peaking || falseColor

    private var pixels = IntArray(0)
    private var luma = IntArray(0)
    private var phase = 0

    /** Convertit l'image d'analyse (YUV 8 bits ou P010 10 bits) en calque ARGB (même orientation que le capteur). */
    fun render(img: Image, bmp: Bitmap): Boolean {
        val w = img.width; val h = img.height
        if (bmp.width != w || bmp.height != h) return false
        if (pixels.size != w * h) { pixels = IntArray(w * h); luma = IntArray(w * h) }
        val plane = img.planes[0]
        val buf = plane.buffer; val rs = plane.rowStride; val ps = plane.pixelStride
        val tenBit = ps == 2 // P010 : 16 bits par échantillon, valeur sur les 10 bits de poids fort
        for (y in 0 until h) {
            val row = y * rs
            for (x in 0 until w) {
                luma[y * w + x] = if (tenBit) {
                    val lo = buf.get(row + 2 * x).toInt() and 0xFF
                    val hi = buf.get(row + 2 * x + 1).toInt() and 0xFF
                    ((hi shl 8) or lo) shr 8 // 10 bits → 8 bits
                } else buf.get(row + x * ps).toInt() and 0xFF
            }
        }
        phase = (phase + 1) % 8
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val l = luma[i]
            var c = Color.TRANSPARENT
            if (falseColor) c = falseColorOf(l)
            if (zebra && l >= ZEBRA_LEVEL && ((x + y + phase) / 3) % 2 == 0) c = 0xCCFFFFFF.toInt()
            if (peaking && x in 1 until w - 1 && y in 1 until h - 1) {
                val gx = luma[i + 1] - luma[i - 1]; val gy = luma[i + w] - luma[i - w]
                if (abs(gx) + abs(gy) > PEAK_LEVEL) c = 0xFFFF3030.toInt()
            }
            pixels[i] = c
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return true
    }

    /** Fausses couleurs (proches des conventions des moniteurs de tournage). */
    private fun falseColorOf(l: Int): Int {
        val ire = l * 100 / 255
        return when {
            ire < 3 -> 0xFF7A2BB8.toInt()      // noir bouché
            ire < 10 -> 0xFF2060FF.toInt()     // ombres profondes
            ire in 38..48 -> 0xFF30C030.toInt() // gris moyen / peau sombre
            ire in 52..62 -> 0xFFFF80C0.toInt() // peau claire
            ire in 85..97 -> 0xFFFFD000.toInt() // hautes lumières
            ire > 97 -> 0xFFFF2020.toInt()     // brûlé
            else -> Color.argb(255, l, l, l)
        }
    }

    companion object {
        const val ZEBRA_LEVEL = 242 // ≈ 95 %
        const val PEAK_LEVEL = 60

        /** Courbe « plate » de type log : relève les ombres et adoucit les hautes lumières pour l'étalonnage. */
        fun flatCurve(points: Int): TonemapCurve {
            val n = points.coerceIn(2, 64)
            val k = 40.0
            val c = FloatArray(n * 2)
            for (i in 0 until n) {
                val x = i / (n - 1.0)
                c[2 * i] = x.toFloat()
                c[2 * i + 1] = (ln(1 + k * x) / ln(1 + k)).toFloat()
            }
            return TonemapCurve(c, c.copyOf(), c.copyOf())
        }
    }
}
