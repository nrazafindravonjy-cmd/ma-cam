package com.macam.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AudioSyncTest {
    /** « Événement » sonore : bruit d'ambiance + quelques attaques (voix, pas, applaudissements). */
    private fun scene(seconds: Int, rate: Int, seed: Long): FloatArray {
        val r = Random(seed); val s = FloatArray(seconds * rate)
        for (i in s.indices) s[i] = (r.nextGaussian() * 0.02).toFloat()
        repeat(seconds * 3) {
            val at = r.nextInt(s.size - rate / 5); val amp = 0.2 + r.nextDouble() * 0.6
            for (k in 0 until rate / 10) s[at + k] += (r.nextGaussian() * amp * Math.exp(-k / (rate / 50.0))).toFloat()
        }
        return s
    }

    /** Ce qu'enregistre un téléphone démarré [startMs] après le début, avec son propre micro (gain, bruit). */
    private fun phone(world: FloatArray, rate: Int, startMs: Int, lengthS: Int, gain: Float, seed: Long): FloatArray {
        val r = Random(seed); val from = startMs * rate / 1000
        return FloatArray(lengthS * rate) { i -> world[from + i] * gain + (r.nextGaussian() * 0.03).toFloat() }
    }

    @Test fun findsOffsetBetweenTwoPhones() {
        val rate = 16_000
        val world = scene(40, rate, 1)
        val a = phone(world, rate, 2_000, 30, 1.0f, 2)   // téléphone A démarre à 2,000 s
        val b = phone(world, rate, 5_437, 30, 0.35f, 3)  // téléphone B démarre à 5,437 s, micro moins sensible
        val m = AudioSync.align(AudioSync.envelope(a, rate), AudioSync.envelope(b, rate))
        assertEquals(3_437.0, m.offsetMs.toDouble(), 2.0)
        assertTrue("confiance ${m.confidence}", m.confidence > 0.3f)
    }

    @Test fun negativeOffsetWhenSecondStartedFirst() {
        val rate = 16_000
        val world = scene(40, rate, 9)
        val a = phone(world, rate, 6_000, 25, 1f, 4)
        val b = phone(world, rate, 1_250, 25, 0.8f, 5)
        assertEquals(-4_750.0, AudioSync.align(AudioSync.envelope(a, rate), AudioSync.envelope(b, rate)).offsetMs.toDouble(), 2.0)
    }

    @Test fun fftRoundTrip() {
        val re = DoubleArray(16) { it.toDouble() }; val im = DoubleArray(16)
        AudioSync.fft(re, im, false); AudioSync.fft(re, im, true)
        for (i in 0 until 16) assertEquals(i.toDouble(), re[i], 1e-9)
    }
}
