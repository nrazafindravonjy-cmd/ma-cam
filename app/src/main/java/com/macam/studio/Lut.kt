package com.macam.studio

import kotlin.math.pow

/** Table de correspondance 3D (format .cube d'Adobe/Resolve). Valeurs RGB en [0, 1], ordre R le plus rapide. */
class Lut(val name: String, val size: Int, val data: FloatArray) {

    companion object {
        /** Lit un fichier .cube (LUT_3D_SIZE, DOMAIN_MIN/MAX, puis N³ triplets). */
        fun parseCube(name: String, text: String): Lut {
            var size = 0
            var min = floatArrayOf(0f, 0f, 0f); var max = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val p = line.split(Regex("\\s+"))
                when (p[0].uppercase()) {
                    "TITLE", "LUT_1D_SIZE", "LUT_1D_INPUT_RANGE", "LUT_3D_INPUT_RANGE" -> {}
                    "LUT_3D_SIZE" -> size = p[1].toInt()
                    "DOMAIN_MIN" -> min = floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
                    "DOMAIN_MAX" -> max = floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
                    else -> if (p.size >= 3 && p[0].first().let { it.isDigit() || it == '-' || it == '.' }) {
                        for (c in 0 until 3) values += ((p[c].toFloat() - min[c]) / (max[c] - min[c])).coerceIn(0f, 1f)
                    }
                }
            }
            require(size in 2..128) { "LUT 3D invalide (LUT_3D_SIZE manquant)" }
            require(values.size == size * size * size * 3) { "LUT incomplète : ${values.size / 3} valeurs au lieu de ${size * size * size}" }
            return Lut(name, size, values.toFloatArray())
        }

        private fun build(name: String, n: Int = 33, f: (Float, Float, Float) -> FloatArray): Lut {
            val d = FloatArray(n * n * n * 3)
            var i = 0
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                val o = f(r / (n - 1f), g / (n - 1f), b / (n - 1f))
                d[i++] = o[0].coerceIn(0f, 1f); d[i++] = o[1].coerceIn(0f, 1f); d[i++] = o[2].coerceIn(0f, 1f)
            }
            return Lut(name, n, d)
        }

        private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
        private fun smooth(x: Float) = x * x * (3 - 2 * x)

        /** Look « Film chaud » : noirs légèrement relevés, hautes lumières douces, teinte chaude. */
        val warmFilm = build("Film chaud") { r, g, b ->
            val l = luma(r, g, b)
            val lift = 0.035f; val curve = { v: Float -> lift + (1 - lift) * (0.85f * smooth(v) + 0.15f * v) }
            floatArrayOf(curve(r) * 1.03f + 0.01f, curve(g) * 1.0f, curve(b) * 0.93f + 0.02f * (1 - l))
        }

        /** Look « Cinéma » : ombres bleu-vert, peaux et hautes lumières orangées, saturation maîtrisée. */
        val cinema = build("Cinéma") { r, g, b ->
            val l = luma(r, g, b)
            val sh = (1 - l).pow(2); val hi = l.pow(2)
            val c = { v: Float -> 0.02f + 0.96f * smooth(v.coerceIn(0f, 1f)) }
            val sat = 0.9f
            val rr = l + (r - l) * sat; val gg = l + (g - l) * sat; val bb = l + (b - l) * sat
            floatArrayOf(c(rr + 0.06f * hi - 0.03f * sh), c(gg + 0.01f * hi + 0.015f * sh), c(bb - 0.06f * hi + 0.05f * sh))
        }

        /** Conversion standard pour un tournage en « Profil Log » de Ma Cam (courbe inverse). */
        val fromMaCamLog = build("Log → standard") { r, g, b ->
            val k = 40.0
            val inv = { v: Float -> (((1 + k).pow(v.toDouble()) - 1) / k).toFloat() }
            // Retour au linéaire puis gamma 2.2 avec un léger contraste.
            val o = { v: Float -> smooth(inv(v).toDouble().pow(1 / 2.2).toFloat()) }
            floatArrayOf(o(r), o(g), o(b))
        }

        val builtIns = listOf(warmFilm, cinema, fromMaCamLog)
    }
}
