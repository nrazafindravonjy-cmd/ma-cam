package com.macam.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleTest {
    private val xmpAttrs = """<x:xmpmeta><rdf:Description crs:Exposure2012="+0.50" crs:Contrast2012="+20"
        crs:Highlights2012="-60" crs:Shadows2012="+40" crs:Vibrance="+15" crs:Clarity2012="+10"/></x:xmpmeta>"""
    private val xmpElems = "<crs:Exposure2012>-0.50</crs:Exposure2012><crs:Saturation>-20</crs:Saturation>"

    @Test fun parsesAttributesAndElements() {
        val a = Style.parseXmp(xmpAttrs)
        assertEquals(0.5f, a.exposure, 1e-6f); assertEquals(-60f, a.highlights, 1e-6f); assertEquals(15f, a.vibrance, 1e-6f)
        val b = Style.parseXmp(xmpElems)
        assertEquals(-0.5f, b.exposure, 1e-6f); assertEquals(-20f, b.saturation, 1e-6f)
    }

    @Test fun averagesEdits() {
        val s = Style.average(listOf(Style.parseXmp(xmpAttrs), Style.parseXmp(xmpElems)))
        assertEquals(0f, s.exposure, 1e-6f); assertEquals(2, s.sources); assertEquals(-30f, s.highlights, 1e-6f)
    }

    @Test fun neutralStyleIsIdentity() {
        val lut = Style().lut(9)
        var maxErr = 0f; var i = 0
        for (b in 0 until 9) for (g in 0 until 9) for (r in 0 until 9) {
            maxErr = maxOf(maxErr, kotlin.math.abs(lut[i++] - r / 8f), kotlin.math.abs(lut[i++] - g / 8f), kotlin.math.abs(lut[i++] - b / 8f))
        }
        assertTrue("écart $maxErr", maxErr < 1e-3f)
    }

    @Test fun exposureBrightensAndHighlightsRecover() {
        val mid = Style(exposure = 1f).apply(0.4f, 0.4f, 0.4f)[1]
        assertTrue(mid > 0.5f)
        val hi = Style(highlights = -100f).apply(0.9f, 0.9f, 0.9f)[1]
        assertTrue("hautes lumières $hi", hi < 0.9f)
    }
}
