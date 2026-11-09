package com.macam.photo

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** Pont vers le moteur natif (app/src/main/cpp/engine.cpp). */
class Engine private constructor(private var handle: Long) : AutoCloseable {

    enum class Mode(val id: Int) { RAW_HDR(0), RAW_SR(1), YUV(2) }

    val frames get() = nFrames(handle)
    val outputSize: IntArray get() = nOutputSize(handle)

    fun addRaw(buf: ByteBuffer, rowStride: Int) = nAddRaw(handle, buf, rowStride)
    fun addYuv(y: ByteBuffer, yStride: Int, u: ByteBuffer, v: ByteBuffer, uvStride: Int, uvPixelStride: Int) =
        nAddYuv(handle, y, yStride, u, v, uvStride, uvPixelStride)
    fun mergedRaw(out: ByteBuffer) = nGetMergedRaw(handle, out)
    fun setShading(gains: FloatArray, rows: Int, cols: Int) = nSetShading(handle, gains, rows, cols)
    fun setHotPixels(idx: IntArray) = nSetHotPixels(handle, idx)

    fun render(bmp: Bitmap, wb: FloatArray, ccm: FloatArray, p: Look) =
        nRender(handle, bmp, wb, ccm, p.exposure, p.compression, p.detail, p.sharpen, p.chroma, p.saturation)

    override fun close() { if (handle != 0L) { nRelease(handle); handle = 0 } }

    /** Réglages de rendu (« look »). */
    data class Look(
        val exposure: Float, val compression: Float, val detail: Float,
        val sharpen: Float, val chroma: Float, val saturation: Float,
    )

    companion object {
        init { System.loadLibrary("macam") }

        fun create(mode: Mode, w: Int, h: Int, cfa: Int, black: FloatArray, white: Float,
                   noiseS: Float, noiseO: Float, mergeC: Float,
                   cropX: Int = 0, cropY: Int = 0, cropW: Int = 0, cropH: Int = 0, scale: Float = 1f) =
            Engine(nCreate(mode.id, w, h, cfa, black, white, noiseS, noiseO, mergeC, cropX, cropY, cropW, cropH, scale))

        @JvmStatic private external fun nCreate(mode: Int, w: Int, h: Int, cfa: Int, black: FloatArray, white: Float,
            noiseS: Float, noiseO: Float, mergeC: Float, cx: Int, cy: Int, cw: Int, ch: Int, scale: Float): Long
        @JvmStatic private external fun nAddRaw(h: Long, buf: ByteBuffer, rowStride: Int)
        @JvmStatic private external fun nAddYuv(h: Long, y: ByteBuffer, yStride: Int, u: ByteBuffer, v: ByteBuffer,
            uvStride: Int, uvPixelStride: Int)
        @JvmStatic private external fun nFrames(h: Long): Int
        @JvmStatic private external fun nOutputSize(h: Long): IntArray
        @JvmStatic private external fun nGetMergedRaw(h: Long, out: ByteBuffer): Boolean
        @JvmStatic private external fun nRender(h: Long, bmp: Bitmap, wb: FloatArray, ccm: FloatArray, exposure: Float,
            compression: Float, detail: Float, sharpen: Float, chroma: Float, saturation: Float): Boolean
        @JvmStatic private external fun nSetShading(h: Long, gains: FloatArray, rows: Int, cols: Int)
        @JvmStatic private external fun nSetHotPixels(h: Long, idx: IntArray)
        @JvmStatic private external fun nRelease(h: Long)
    }
}
