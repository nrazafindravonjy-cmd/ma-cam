package com.macam.cam

import org.junit.Assert.assertEquals
import org.junit.Test

class StabilityMathTest {
    @Test fun marginMatchesCropGeometry() {
        // 4K, focale 3000 px, recadrage 8 % : décalage permis = (1 − 1/1,08) × 1080 px ≈ 80 px → atan(80/3000).
        val a = StabilityMath.allowedAngle(3000.0, 3840, 2160, 1.08)
        assertEquals(kotlin.math.atan((1 - 1 / 1.08) * 1080 / 3000), a, 1e-12)
        assertEquals(0.0267, a, 0.001)
    }
}
