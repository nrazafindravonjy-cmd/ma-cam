package com.macam.cam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.MeteringRectangle

object Metering {
    /** Zone carrée (10 % du capteur) autour de (u, v), en coordonnées capteur normalisées. */
    fun region(chars: CameraCharacteristics, u: Float, v: Float): Array<MeteringRectangle>? {
        val a = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val half = minOf(a.width(), a.height()) / 10
        val cx = (u.coerceIn(0f, 1f) * a.width()).toInt(); val cy = (v.coerceIn(0f, 1f) * a.height()).toInt()
        val x = (cx - half).coerceIn(0, a.width() - 2 * half); val y = (cy - half).coerceIn(0, a.height() - 2 * half)
        return arrayOf(MeteringRectangle(x, y, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX - 1))
    }

    /**
     * Convertit un point de l'aperçu (écran en portrait, [0, 1]) en coordonnées capteur [0, 1],
     * selon l'orientation du capteur et le miroir de la caméra avant.
     */
    fun viewToSensor(nx: Float, ny: Float, sensorOrientation: Int, front: Boolean): Pair<Float, Float> {
        val x = if (front) 1 - nx else nx
        return when (sensorOrientation) {
            90 -> ny to 1 - x
            270 -> 1 - ny to x
            180 -> 1 - x to 1 - ny
            else -> x to ny
        }
    }
}
