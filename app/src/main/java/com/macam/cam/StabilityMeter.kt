package com.macam.cam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Jauge de stabilité en direct : estime l'écart angulaire que la stabilisation de l'Atelier devra corriger
 * (orientation réelle − trajectoire lissée) et le compare à la marge permise par le recadrage.
 * Rapport < 1 : rattrapable ; ≥ 1 : l'image sortira du cadre.
 */
class StabilityMeter(ctx: Context, private val onRatio: (Float) -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val angle = DoubleArray(3); private val smooth = DoubleArray(3)
    private var lastTs = 0L; private var lastPost = 0L
    private val window = ArrayDeque<Pair<Long, Double>>()
    /** Écart maximal permis (rad), fixé par [setOptics]. */
    @Volatile private var allowed = 0.03

    /** Marge du réglage « Douce » de l'Atelier (recadrage 8 %), pour la focale [fPx] et l'image [w]×[h]. */
    fun setOptics(fPx: Double, w: Int, h: Int, zoom: Double = 1.08) {
        allowed = StabilityMath.allowedAngle(fPx, w, h, zoom)
    }

    fun start() { sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sm.unregisterListener(this); lastTs = 0; window.clear() }

    override fun onSensorChanged(e: SensorEvent) {
        if (lastTs != 0L) {
            val dt = (e.timestamp - lastTs) / 1e9
            val a = 1 - exp(-dt / StabilityMath.SMOOTHING_S)
            for (i in 0 until 3) { angle[i] += e.values[i] * dt; smooth[i] += (angle[i] - smooth[i]) * a }
            val dev = sqrt((0 until 3).sumOf { (angle[it] - smooth[it]).let { d -> d * d } })
            window.addLast(e.timestamp to dev)
            while (window.isNotEmpty() && e.timestamp - window.first().first > 1_000_000_000L) window.removeFirst()
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastPost > 150) { lastPost = now; onRatio((window.maxOf { it.second } / allowed).toFloat()) }
        }
        lastTs = e.timestamp
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

object StabilityMath {
    /** Même lissage que l'Atelier en réglage « Douce ». */
    const val SMOOTHING_S = 0.5

    fun allowedAngle(fPx: Double, w: Int, h: Int, zoom: Double): Double = atan((1 - 1 / zoom) * minOf(w, h) / 2 / fPx)
}
