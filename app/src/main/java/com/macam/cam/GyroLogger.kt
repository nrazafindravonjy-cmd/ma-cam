package com.macam.cam

import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import java.util.Locale

/**
 * Enregistre gyroscope + accéléromètre pendant la vidéo, puis écrit un fichier .gcsv
 * (format « Gyroflow IMU log ») à placer à côté de la vidéo dans Gyroflow.
 */
class GyroLogger(private val ctx: Context) : SensorEventListener {

    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("gyro").apply { start() }
    private val samples = ArrayList<FloatArray>(200_000) // t(ns, en float64 séparé), gx, gy, gz, ax, ay, az
    private val times = ArrayList<Long>(200_000)
    private val accel = FloatArray(3)
    @Volatile private var running = false

    val available get() = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    fun start() {
        synchronized(samples) { samples.clear(); times.clear() }
        running = true
        val h = Handler(thread.looper)
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!running) return
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> System.arraycopy(e.values, 0, accel, 0, 3)
            Sensor.TYPE_GYROSCOPE -> synchronized(samples) {
                times += e.timestamp
                samples += floatArrayOf(e.values[0], e.values[1], e.values[2], accel[0], accel[1], accel[2])
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /**
     * Arrête la capture et écrit Download/MaCam/<baseName>.gcsv.
     * [zeroNs] = horodatage de la première image vidéo ; [readoutMs] = temps de lecture du capteur.
     */
    fun stopAndSave(baseName: String, zeroNs: Long, readoutMs: Double, lensInfo: String): Int {
        running = false
        sm.unregisterListener(this)
        val sb = StringBuilder()
        sb.append("GYROFLOW IMU LOG\nversion,1.3\nid,macam_android\n")
        // Axes du téléphone (Android) : X vers la droite, Y vers le haut, Z vers l'écran.
        sb.append("orientation,XYZ\nnote,Ma Cam\nvendor,Xiaomi\n")
        sb.append("videofilename,$baseName.mp4\nlens_info,$lensInfo\n")
        sb.append("frame_readout_time,%.3f\nframe_readout_direction,0\n".format(Locale.US, readoutMs))
        sb.append("tscale,0.001\ngscale,1.0\nascale,%.8f\n".format(Locale.US, 1.0 / SensorManager.GRAVITY_EARTH))
        sb.append("t,gx,gy,gz,ax,ay,az\n")
        val n: Int
        synchronized(samples) {
            n = times.size
            for (i in 0 until n) {
                val s = samples[i]
                val tMs = (times[i] - zeroNs) / 1e6
                sb.append("%.3f,%.6f,%.6f,%.6f,%.4f,%.4f,%.4f\n".format(Locale.US, tMs, s[0], s[1], s[2], s[3], s[4], s[5]))
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$baseName.gcsv")
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/MaCam")
        }
        ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
        }
        return n
    }

    fun release() { sm.unregisterListener(this); thread.quitSafely() }
}
