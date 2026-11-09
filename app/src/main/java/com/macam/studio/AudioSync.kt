package com.macam.studio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synchronisation de plusieurs vidéos par leur son (comme un « clap » de cinéma, mais automatique) :
 * on compare l'enveloppe des attaques sonores (indépendante du micro et du volume) par corrélation croisée.
 */
object AudioSync {
    /** Fréquence de l'enveloppe : 1 kHz → précision d'environ 1 ms. */
    const val ENV_RATE = 1000

    /**
     * Enveloppe des attaques : dérivée (filtre passe-haut), valeur absolue, moyenne par blocs de 1 ms,
     * puis centrage et normalisation.
     */
    fun envelope(pcm: FloatArray, sampleRate: Int): FloatArray {
        val block = maxOf(1, sampleRate / ENV_RATE)
        val n = pcm.size / block
        val e = FloatArray(n)
        var prev = 0f
        for (i in 0 until n) {
            var acc = 0f
            for (j in 0 until block) { val v = pcm[i * block + j]; acc += abs(v - prev); prev = v }
            e[i] = acc / block
        }
        // Compression logarithmique : un son fort ne doit pas écraser les autres attaques.
        for (i in e.indices) e[i] = kotlin.math.ln(1 + 50 * e[i])
        val mean = e.average().toFloat()
        var sq = 0.0
        for (i in e.indices) { e[i] -= mean; sq += e[i] * e[i] }
        val norm = sqrt(sq).toFloat().coerceAtLeast(1e-9f)
        for (i in e.indices) e[i] /= norm
        return e
    }

    data class Match(
        /** Décalage de B par rapport à A, en ms : B a commencé [offsetMs] ms après A (négatif = avant). */
        val offsetMs: Int,
        /** Confiance : corrélation au pic (0 à 1). */
        val confidence: Float,
    )

    /** Trouve le décalage qui aligne l'enveloppe [b] sur [a]. */
    fun align(a: FloatArray, b: FloatArray, maxLagMs: Int = 120_000): Match {
        val n = Integer.highestOneBit((a.size + b.size) * 2 - 1).let { if (it < (a.size + b.size)) it * 2 else it }
        val ar = DoubleArray(n); val ai = DoubleArray(n); val br = DoubleArray(n); val bi = DoubleArray(n)
        for (i in a.indices) ar[i] = a[i].toDouble()
        for (i in b.indices) br[i] = b[i].toDouble()
        fft(ar, ai, false); fft(br, bi, false)
        // Corrélation croisée = IFFT(A × conj(B)).
        for (i in 0 until n) {
            val re = ar[i] * br[i] + ai[i] * bi[i]; val im = ai[i] * br[i] - ar[i] * bi[i]
            ar[i] = re; ai[i] = im
        }
        fft(ar, ai, true)
        var best = Double.NEGATIVE_INFINITY; var lag = 0
        for (k in 0 until n) {
            val l = if (k < n / 2) k else k - n // décalage signé
            if (abs(l) > maxLagMs) continue
            if (ar[k] > best) { best = ar[k]; lag = l }
        }
        // corr[l] = Σ a[i+l]·b[i] : un événement à l'instant t de B est à t + l dans A, donc B a commencé l ms après A.
        return Match(lag, best.toFloat().coerceIn(0f, 1f))
    }

    /** FFT radix-2 en place (taille puissance de 2) ; [inverse] divise par n. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * if (inverse) 1 else -1
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    /** Bref balayage 2→4 kHz (50 ms) joué au début d'un enregistrement : repère sonore commun. */
    fun clap(sampleRate: Int = 48_000): ShortArray {
        val n = sampleRate / 20
        return ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val f = 2000 + 2000 * t / 0.05
            val env = sin(PI * i / n)
            (sin(2 * PI * f * t) * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
