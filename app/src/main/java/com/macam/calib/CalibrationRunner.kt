package com.macam.calib

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.macam.cam.Lens
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Prend les images noires (objectif couvert) et calcule la calibration de chaque objectif RAW.
 * À lancer sur un fil d'arrière-plan, caméra libre (aucun autre contrôleur ouvert).
 */
class CalibrationRunner(private val ctx: Context, private val onStatus: (String) -> Unit) {

    private val cm = ctx.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("calib").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    class LightLeak : Exception("Trop de lumière : posez le téléphone objectif contre une surface opaque")

    /** Calibre tous les objectifs RAW ; renvoie ceux qui ont réussi. */
    fun run(): List<SensorCalibration> {
        val done = ArrayList<SensorCalibration>()
        try {
            val lenses = Lens.list(cm).filter { l ->
                l.chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.let {
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in it && CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in it
                } == true
            }
            lenses.forEachIndexed { i, l ->
                onStatus("Calibration du capteur ${i + 1}/${lenses.size}…")
                runCatching { calibrate(l) }.onSuccess { c -> SensorCalibration.save(ctx, c); done += c }
                    .onFailure { if (it is LightLeak) throw it }
            }
        } finally { thread.quitSafely() }
        return done
    }

    @SuppressLint("MissingPermission")
    private fun calibrate(lens: Lens): SensorCalibration {
        val c = lens.chars
        val size = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width * it.height }
        val white = (c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
        val pattern = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val nominal = FloatArray(4) { pattern?.getOffsetForIndex(it % 2, it / 2)?.toFloat() ?: 64f }
        val isoR = lens.isoRange
        val isos = listOf(isoR.lower, 400, 1600, minOf(isoR.upper, 6400)).filter { it in isoR.lower..isoR.upper }.distinct().sorted()

        // Ouverture
        val opened = CountDownLatch(1); var dev: CameraDevice? = null
        cm.openCamera(lens.id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { dev = d; opened.countDown() }
            override fun onDisconnected(d: CameraDevice) { d.close(); opened.countDown() }
            override fun onError(d: CameraDevice, e: Int) { d.close(); opened.countDown() }
        })
        opened.await(4, TimeUnit.SECONDS)
        val d = dev ?: throw IllegalStateException("objectif ${lens.id} indisponible")
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 6)
        try {
            val ready = CountDownLatch(1); var session: CameraCaptureSession? = null
            d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(reader.surface)), executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; ready.countDown() }
                    override fun onConfigureFailed(s: CameraCaptureSession) { ready.countDown() }
                }))
            ready.await(4, TimeUnit.SECONDS)
            val s = session ?: throw IllegalStateException("session refusée")

            val black = HashMap<Int, FloatArray>(); val readVar = HashMap<Int, Float>()
            var hot = IntArray(0)
            for (iso in isos) {
                val frames = grab(s, d, reader, iso, 4)
                val st = CalibrationMath.isoStats(frames, size.width, size.height, white, nominal)
                if (st.lightLeak) throw LightLeak()
                black[iso] = st.black; readVar[iso] = st.readVar
                // Pixels défectueux mesurés à l'ISO le plus élevé, où ils ressortent le mieux.
                if (iso == isos.last()) hot = CalibrationMath.hotPixels(frames, size.width, size.height, st.black,
                    CalibrationMath.std(st.readVar) * (white - nominal.average().toFloat()))
            }
            s.close()
            return SensorCalibration(lens.id, black, readVar, hot, size.width, System.currentTimeMillis())
        } finally { reader.close(); d.close() }
    }

    /** [n] images noires à [iso], pose 1/30 s, copiées en mémoire. */
    private fun grab(s: CameraCaptureSession, d: CameraDevice, reader: ImageReader, iso: Int, n: Int): List<ShortArray> {
        val out = ArrayList<ShortArray>()
        val got = CountDownLatch(n)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            val p = img.planes[0]; val w = img.width; val h = img.height
            val data = ShortArray(w * h)
            val buf = p.buffer.order(java.nio.ByteOrder.nativeOrder())
            for (y in 0 until h) { buf.position(y * p.rowStride); buf.asShortBuffer().get(data, y * w, w) }
            img.close()
            synchronized(out) { out += data }
            got.countDown()
        }, handler)
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, 33_000_000L)
            set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        }.build()
        s.captureBurst(List(n) { req }, null, handler)
        got.await(8, TimeUnit.SECONDS)
        return out
    }
}
