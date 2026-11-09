package com.macam.style

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Style personnel appris de vos retouches Lightroom (fichiers .xmp de presets ou de photos retouchées) :
 * on fait la moyenne des réglages, puis on les traduit en table de couleurs 3D appliquée à la prise de vue.
 * Valeurs dans les unités Lightroom (−100…+100, exposition en IL).
 */
data class Style(
    val exposure: Float = 0f, val contrast: Float = 0f, val highlights: Float = 0f, val shadows: Float = 0f,
    val whites: Float = 0f, val blacks: Float = 0f, val vibrance: Float = 0f, val saturation: Float = 0f,
    val clarity: Float = 0f,
    val shadowHue: Float = 0f, val shadowSat: Float = 0f, val highlightHue: Float = 0f, val highlightSat: Float = 0f,
    val sources: Int = 0,
) {
    fun toJson(): String = JSONObject().apply {
        put("exposure", exposure); put("contrast", contrast); put("highlights", highlights); put("shadows", shadows)
        put("whites", whites); put("blacks", blacks); put("vibrance", vibrance); put("saturation", saturation); put("clarity", clarity)
        put("shadowHue", shadowHue); put("shadowSat", shadowSat); put("highlightHue", highlightHue); put("highlightSat", highlightSat)
        put("sources", sources)
    }.toString()

    /** Résumé lisible du style appris. */
    fun describe(): String = buildList {
        fun sgn(v: Float) = if (v >= 0) "+${"%.0f".format(v)}" else "%.0f".format(v)
        if (abs(exposure) > 0.05f) add("expo ${"%+.1f".format(exposure)} IL")
        if (abs(contrast) > 2) add("contraste ${sgn(contrast)}")
        if (abs(highlights) > 2) add("hautes lumières ${sgn(highlights)}")
        if (abs(shadows) > 2) add("ombres ${sgn(shadows)}")
        if (abs(vibrance) > 2) add("vibrance ${sgn(vibrance)}")
        if (abs(saturation) > 2) add("saturation ${sgn(saturation)}")
        if (abs(clarity) > 2) add("clarté ${sgn(clarity)}")
        if (shadowSat > 2 || highlightSat > 2) add("virage partiel")
    }.ifEmpty { listOf("neutre") }.joinToString(", ")

    /** Table de couleurs [n]³ (R le plus rapide), sur des valeurs sRGB [0, 1]. */
    fun lut(n: Int = 33): FloatArray {
        val out = FloatArray(n * n * n * 3); var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val o = apply(r / (n - 1f), g / (n - 1f), b / (n - 1f))
            out[i++] = o[0]; out[i++] = o[1]; out[i++] = o[2]
        }
        return out
    }

    private fun toLinear(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    private fun toSrgb(v: Float) = min(1f, max(0f, if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1 / 2.4f) - 0.055f))
    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** Rendu d'un pixel sRGB selon le style. */
    fun apply(r0: Float, g0: Float, b0: Float): FloatArray {
        // Exposition, en lumière linéaire.
        val k = 2f.pow(exposure)
        var r = toSrgb(toLinear(r0) * k); var g = toSrgb(toLinear(g0) * k); var b = toSrgb(toLinear(b0) * k)
        // Tonalité sur la luminance : hautes lumières / ombres / blancs / noirs, puis contraste en S.
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        var nl = l
        nl += highlights / 100f * 0.25f * smooth(0.5f, 1f, l) * (1 - l) * 2
        nl += shadows / 100f * 0.25f * (1 - smooth(0f, 0.5f, l)) * l * 2
        nl += whites / 100f * 0.12f * smooth(0.7f, 1f, l)
        nl += blacks / 100f * 0.12f * (1 - smooth(0f, 0.3f, l))
        val c = contrast / 100f
        nl = (nl + c * (nl - 0.5f) * (1 - abs(2 * nl - 1)) * 0.9f).coerceIn(0f, 1f)
        val gain = if (l > 1e-4f) nl / l else 1f
        r *= gain; g *= gain; b *= gain
        // Saturation globale et vibrance (agit surtout sur les couleurs peu saturées).
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val sat = if (mx > 1e-4f) (mx - mn) / mx else 0f
        val s = 1 + saturation / 100f + vibrance / 100f * (1 - sat)
        r = y + (r - y) * s; g = y + (g - y) * s; b = y + (b - y) * s
        // Virage partiel : teinte ajoutée aux ombres et aux hautes lumières.
        fun tint(hue: Float, amt: Float, w: Float) {
            if (amt <= 0f || w <= 0f) return
            val h = hue / 60f; val x = 1 - abs(h % 2 - 1)
            val (tr, tg, tb) = when (h.toInt() % 6) { 0 -> Triple(1f, x, 0f); 1 -> Triple(x, 1f, 0f); 2 -> Triple(0f, 1f, x)
                3 -> Triple(0f, x, 1f); 4 -> Triple(x, 0f, 1f); else -> Triple(1f, 0f, x) }
            val a = amt / 100f * 0.15f * w
            r += (tr - 0.5f) * a; g += (tg - 0.5f) * a; b += (tb - 0.5f) * a
        }
        tint(shadowHue, shadowSat, 1 - smooth(0f, 0.5f, nl))
        tint(highlightHue, highlightSat, smooth(0.5f, 1f, nl))
        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    companion object {
        fun fromJson(s: String): Style = JSONObject(s).let { j ->
            fun f(k: String) = j.optDouble(k, 0.0).toFloat()
            Style(f("exposure"), f("contrast"), f("highlights"), f("shadows"), f("whites"), f("blacks"), f("vibrance"),
                f("saturation"), f("clarity"), f("shadowHue"), f("shadowSat"), f("highlightHue"), f("highlightSat"), j.optInt("sources"))
        }

        /** Lit les réglages Camera Raw (crs:…) d'un fichier XMP, sous forme d'attributs ou d'éléments. */
        fun parseXmp(xmp: String): Style {
            fun v(vararg names: String): Float {
                for (n in names) {
                    Regex("crs:$n\\s*=\\s*\"([^\"]*)\"").find(xmp)?.let { return it.groupValues[1].trim().toFloatOrNull() ?: 0f }
                    Regex("<crs:$n>([^<]*)</crs:$n>").find(xmp)?.let { return it.groupValues[1].trim().toFloatOrNull() ?: 0f }
                }
                return 0f
            }
            return Style(
                exposure = v("Exposure2012", "Exposure"), contrast = v("Contrast2012", "Contrast"),
                highlights = v("Highlights2012", "HighlightRecovery"), shadows = v("Shadows2012", "Shadows"),
                whites = v("Whites2012"), blacks = v("Blacks2012"), vibrance = v("Vibrance"), saturation = v("Saturation"),
                clarity = v("Clarity2012", "Clarity"),
                shadowHue = v("SplitToningShadowHue", "ColorGradeShadowHue"), shadowSat = v("SplitToningShadowSaturation", "ColorGradeShadowSat"),
                highlightHue = v("SplitToningHighlightHue", "ColorGradeHighlightHue"), highlightSat = v("SplitToningHighlightSaturation", "ColorGradeHighlightSat"),
                sources = 1,
            )
        }

        /** Moyenne de plusieurs retouches : votre style « habituel ». */
        fun average(list: List<Style>): Style {
            if (list.isEmpty()) return Style()
            fun avg(f: (Style) -> Float) = list.map(f).average().toFloat()
            return Style(avg { it.exposure }, avg { it.contrast }, avg { it.highlights }, avg { it.shadows }, avg { it.whites },
                avg { it.blacks }, avg { it.vibrance }, avg { it.saturation }, avg { it.clarity }, avg { it.shadowHue },
                avg { it.shadowSat }, avg { it.highlightHue }, avg { it.highlightSat }, list.size)
        }
    }
}
