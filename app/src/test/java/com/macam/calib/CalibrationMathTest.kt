package com.macam.calib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CalibrationMathTest {
    private val w = 64; private val h = 48
    private val realBlack = floatArrayOf(63f, 64.5f, 64.5f, 66f)

    private fun darkFrames(n: Int, sigma: Double, hot: Set<Int>, leak: Float = 0f, seed: Long = 1): List<ShortArray> {
        val r = Random(seed)
        return List(n) {
            ShortArray(w * h) { i ->
                val c = ((i / w) and 1) * 2 + ((i % w) and 1)
                val v = if (i in hot) 400.0 else realBlack[c] + leak + r.nextGaussian() * sigma
                v.toInt().coerceIn(0, 1023).toShort()
            }
        }
    }

    @Test fun measuresBlackAndReadNoise() {
        val s = CalibrationMath.isoStats(darkFrames(4, 2.0, emptySet()), w, h, 1023f, floatArrayOf(64f, 64f, 64f, 64f))
        for (c in 0 until 4) assertEquals(realBlack[c] - 0.5f, s.black[c], 0.6f) // troncature entière ≈ −0,5
        // σ = 2 → variance 4, normalisée par (1023 − 64)²
        assertEquals(4.0 / (959.0 * 959.0), s.readVar.toDouble(), 1.5e-6)
        assertFalse(s.lightLeak)
    }

    @Test fun detectsLightLeak() {
        val s = CalibrationMath.isoStats(darkFrames(2, 2.0, emptySet(), leak = 60f), w, h, 1023f, floatArrayOf(64f, 64f, 64f, 64f))
        assertTrue(s.lightLeak)
    }

    @Test fun findsOnlyPersistentHotPixels() {
        val hot = setOf(5, 777, 2000)
        val frames = darkFrames(4, 2.0, hot)
        // Un pic de bruit sur une seule image ne doit pas être retenu.
        frames[0][1234] = 900
        val s = CalibrationMath.isoStats(frames, w, h, 1023f, floatArrayOf(64f, 64f, 64f, 64f))
        val found = CalibrationMath.hotPixels(frames, w, h, s.black, CalibrationMath.std(s.readVar) * 959f)
        assertArrayEquals(intArrayOf(5, 777, 2000), found)
    }
}
