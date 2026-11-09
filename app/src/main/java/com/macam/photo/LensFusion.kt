package com.macam.photo

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer

/** Fusion ultra grand-angle + principal pris au même instant (voir fusion.cpp). */
object LensFusion {
    init { System.loadLibrary("macam") }

    /** Image YUV de la caméra → bitmap RGBA (copie native). */
    fun toBitmap(img: Image): Bitmap {
        val p = img.planes
        val bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
        nYuvToBitmap(p[0].buffer, p[0].rowStride, p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride, bmp)
        return bmp
    }

    /** Fusionne [main] dans [ultraWide] (en place). Renvoie la corrélation obtenue (0 à 1). */
    fun fuse(ultraWide: Bitmap, main: Bitmap, ratio: Float): Float = (nFuse(ultraWide, main, ratio)?.get(2) ?: 0) / 1000f

    @JvmStatic private external fun nYuvToBitmap(y: ByteBuffer, ys: Int, u: ByteBuffer, v: ByteBuffer, uvs: Int, uvp: Int, bmp: Bitmap): Boolean
    @JvmStatic private external fun nFuse(base: Bitmap, detail: Bitmap, ratio: Float): IntArray?
}
