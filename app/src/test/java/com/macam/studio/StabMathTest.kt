package com.macam.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class StabMathTest {

    private fun log(vararg rows: String) = GyroLog.parse(
        "GYROFLOW IMU LOG\nversion,1.3\nnote,Ma Cam f=3000.0 aw=4096 o=90 front=0\ntscale,0.001\ngscale,1.0\nt,gx,gy,gz,ax,ay,az\n" + rows.joinToString("\n"))

    @Test fun parsesHeaderAndOptics() {
        val g = log("0,0.1,0,0,0,0,1", "2.5,0.2,0,0,0,0,1")
        assertEquals(2, g.tMs.size); assertEquals(2.5, g.tMs[1], 1e-9)
        assertEquals(3000.0, g.focalPx!!, 1e-9); assertEquals(90, g.sensorOrientation)
    }

    @Test fun integratesConstantRotation() {
        // 1 rad/s autour de Z pendant 1 s : la caméra doit avoir tourné de 1 rad.
        val rows = (0..1000).map { "$it,0,0,1.0,0,0,1" }.toTypedArray()
        val s = Stabilizer(log(*rows), smoothingS = 1e9, zoom = 1.0) // lissage infini : virtuel ≈ fixe
        val r = s.correction(1000.0)
        val angle = kotlin.math.acos(((r[0] + r[4] + r[8]) - 1) / 2)
        assertEquals(1.0, angle, 0.02)
    }

    @Test fun removesShakeButKeepsSlowPan() {
        // Panoramique lent (0,2 rad/s) + tremblement à 8 Hz : la correction doit rester petite en moyenne
        // (le panoramique est conservé) mais compenser le tremblement.
        val rows = (0..4000 step 2).map { t ->
            val w = 0.2 + 0.6 * sin(2 * PI * 8 * t / 1000.0)
            "$t,0,$w,0,0,0,1"
        }.toTypedArray()
        val s = Stabilizer(log(*rows), smoothingS = 0.4, zoom = 1.1)
        var maxCorr = 0.0
        for (t in 1000..3000 step 10) {
            val r = s.correction(t.toDouble())
            maxCorr = maxOf(maxCorr, abs(r[2]), abs(r[5]), abs(r[1]))
        }
        // Amplitude du tremblement ≈ 0,6/(2π·8) ≈ 0,012 rad : la correction doit être de cet ordre, pas du panoramique (0,4 rad).
        assertTrue("correction $maxCorr", maxCorr in 0.004..0.05)
    }

    @Test fun homographyIdentityWithoutMotion() {
        val rows = (0..100).map { "$it,0,0,0,0,0,1" }.toTypedArray()
        val h = Stabilizer(log(*rows), 0.5, zoom = 1.0).homography(50.0, 3000.0, 3840, 2160)
        val id = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        for (i in 0 until 9) assertEquals(id[i], h[i], 1e-6)
    }
}
