package com.macam.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class LutTest {
    @Test fun parsesIdentityCube() {
        val n = 2
        val body = buildString {
            append("TITLE \"id\"\n# commentaire\nLUT_3D_SIZE $n\n")
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) append("$r.0 $g.0 $b.0\n")
        }
        val lut = Lut.parseCube("id", body)
        assertEquals(2, lut.size)
        // Dernier point = blanc ; R varie le plus vite.
        assertEquals(1f, lut.data[lut.data.size - 1], 0f)
        assertEquals(1f, lut.data[3], 0f); assertEquals(0f, lut.data[4], 0f)
    }

    @Test fun builtInsAreComplete() {
        for (l in Lut.builtIns) assertEquals(l.size * l.size * l.size * 3, l.data.size)
    }
}
