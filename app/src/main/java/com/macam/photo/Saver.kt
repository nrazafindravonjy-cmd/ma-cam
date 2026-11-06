package com.macam.photo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.ExifInterface
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.nio.ByteBuffer

/** Enregistre les photos dans DCIM/MaCam (galerie). */
object Saver {

    fun exifOrientation(deg: Int) = when ((deg % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun insert(ctx: Context, name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/MaCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Impossible de créer $name")
        try {
            ctx.contentResolver.openOutputStream(uri)!!.use(write)
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            ctx.contentResolver.delete(uri, null, null); throw e
        }
    }

    /** JPEG qualité 95 avec orientation et métadonnées EXIF. */
    fun saveJpeg(ctx: Context, bmp: Bitmap, rotation: Int, name: String, iso: Int?, exposureNs: Long?, frames: Int) {
        val tmp = File(ctx.cacheDir, "$name.jpg")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(tmp.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation(rotation).toString())
            setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            setAttribute(ExifInterface.TAG_SOFTWARE, "Ma Cam ($frames images fusionnées)")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,
                java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            iso?.let { setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString()) }
            exposureNs?.let { setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (it / 1e9).toString()) }
            saveAttributes()
        }
        try { insert(ctx, "$name.jpg", "image/jpeg") { out -> tmp.inputStream().use { it.copyTo(out) } } }
        finally { tmp.delete() }
    }

    fun saveJpegBytes(ctx: Context, bytes: ByteArray, name: String) =
        insert(ctx, "$name.jpg", "image/jpeg") { it.write(bytes) }

    /** DNG à partir de la mosaïque fusionnée : un RAW 16 bits bien moins bruité qu'un RAW simple. */
    fun saveDng(ctx: Context, chars: CameraCharacteristics, result: CaptureResult, raw: ByteBuffer,
                size: Size, rotation: Int, name: String) {
        DngCreator(chars, result).use { dng ->
            dng.setOrientation(exifOrientation(rotation))
            insert(ctx, "$name.dng", "image/x-adobe-dng") { out -> raw.rewind(); dng.writeByteBuffer(out, size, raw, 0) }
        }
    }
}
