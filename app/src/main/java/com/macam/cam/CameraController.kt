package com.macam.cam

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.hypot

/** Un objectif utilisable (capteur physique ouvert directement). */
class Lens(val id: String, val chars: CameraCharacteristics, val front: Boolean) {
    val focalMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
    /** Focale équivalente 24×36. */
    val eqMm: Double = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!.let {
        focalMm * 43.27 / hypot(it.width.toDouble(), it.height.toDouble())
    }
    val fixedFocus = (chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) == 0f
    val minFocusDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val isoRange: Range<Int> = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
    val hasOis = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(1) == true
    val realtimeClock = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    fun supports(m: Mode): Boolean {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        if (m.highSpeed) {
            return map.highSpeedVideoSizes.contains(m.size) &&
                map.getHighSpeedVideoFpsRangesFor(m.size).contains(Range(m.fps, m.fps))
        }
        val fpsOk = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.contains(Range(m.fps, m.fps)) == true
        val sizeOk = map.getOutputSizes(MediaCodec::class.java)?.contains(m.size) == true
        val hdrOk = !m.tenBit || (Build.VERSION.SDK_INT >= 33 && chars.get(
            CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles
            ?.contains(DynamicRangeProfiles.HLG10) == true)
        return fpsOk && sizeOk && hdrOk
    }

    companion object {
        /** Objectifs arrière triés par focale, puis la caméra avant. */
        fun list(cm: CameraManager): List<Lens> {
            val back = ArrayList<Lens>(); val front = ArrayList<Lens>()
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                val isFront = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
                val ids = c.physicalCameraIds.ifEmpty { setOf(id) }
                for (pid in ids) {
                    val lens = runCatching { Lens(pid, cm.getCameraCharacteristics(pid), isFront) }.getOrNull() ?: continue
                    (if (isFront) front else back) += lens
                }
            }
            back.sortBy { it.eqMm }
            return back + front
        }
    }
}

enum class Mode(val label: String, val size: Size, val fps: Int, val tenBit: Boolean, val highSpeed: Boolean) {
    UHD30_HLG("4K30 HDR", Size(3840, 2160), 30, true, false),
    UHD60_HLG("4K60 HDR", Size(3840, 2160), 60, true, false),
    UHD30_SDR("4K30", Size(3840, 2160), 30, false, false),
    UHD60_SDR("4K60", Size(3840, 2160), 60, false, false),
    UHD120("4K120", Size(3840, 2160), 120, false, true),
    FHD240("1080p240", Size(1920, 1080), 240, false, true),
}

enum class Exposure { AUTO, LOCK, MANUAL }

/** Réglages modifiables depuis l'interface. */
data class Settings(
    var exposure: Exposure = Exposure.AUTO,
    var evSteps: Int = 0,
    var iso: Int = 100,
    var exposureNs: Long = 1_000_000_000L / 60,
    var manualFocus: Boolean = false,
    var focusDiopters: Float = 0f,
    var awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    var awbLock: Boolean = false,
    var eis: Boolean = true,
    var bitrateMbps: Int = 100,
    var gyroLog: Boolean = true,
)

/** Mesures en direct renvoyées par le capteur. */
data class LiveInfo(val iso: Int, val exposureNs: Long, val focusDiopters: Float, val readoutNs: Long)

class CameraController(
    private val ctx: Context,
    private val previewSurface: (Size) -> Surface,
    private val onInfo: (LiveInfo) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val cm = ctx.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    val lenses: List<Lens> = Lens.list(cm)
    var lens: Lens = lenses.first { !it.front && it.hasOis } ; private set
    var mode = Mode.UHD30_HLG; private set
    val settings = Settings()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var preview: Surface? = null
    private var recorder: Recorder? = null
    private val gyro = GyroLogger(ctx)
    @Volatile var lastInfo: LiveInfo? = null; private set
    private var lastInfoPost = 0L

    val recording get() = recorder != null

    // ---------------------------------------------------------------- ouverture

    fun start(l: Lens = lens, m: Mode = mode) = handler.post { reopen(l, m) }

    fun stop() = handler.post {
        if (recording) stopRecordingInternal()
        closeDevice()
    }

    fun release() { stop(); handler.post { gyro.release(); thread.quitSafely() } }

    private fun closeDevice() {
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
    }

    @SuppressLint("MissingPermission")
    private fun reopen(l: Lens, m: Mode) {
        closeDevice()
        lens = l
        mode = if (l.supports(m)) m else Mode.entries.first { l.supports(it) }
        settings.manualFocus = settings.manualFocus && !l.fixedFocus
        try {
            cm.openCamera(l.id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; configure(withRecorder = null) }
                override fun onDisconnected(d: CameraDevice) { d.close(); if (device == d) device = null }
                override fun onError(d: CameraDevice, e: Int) { d.close(); onError("Erreur caméra $e") }
            })
        } catch (e: Exception) { onError("Ouverture impossible : ${e.message}") }
    }

    private fun configure(withRecorder: Recorder?) {
        val dev = device ?: return
        runCatching { session?.close() }; session = null
        // En haute vitesse, l'aperçu doit avoir exactement la taille de la vidéo.
        val pv = previewSurface(if (mode.highSpeed) mode.size else Size(1920, 1080))
        preview = pv
        val outs = listOfNotNull(pv, withRecorder?.inputSurface).map { s ->
            OutputConfiguration(s).apply {
                if (mode.tenBit && Build.VERSION.SDK_INT >= 33) dynamicRangeProfile = DynamicRangeProfiles.HLG10
            }
        }
        val type = if (mode.highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR
        dev.createCaptureSession(SessionConfiguration(type, outs, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                // L'encodeur doit tourner avant que la caméra lui envoie des images.
                withRecorder?.let { r ->
                    r.start()
                    if (settings.gyroLog && gyro.available) gyro.start()
                }
                applySettingsInternal()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                onError("Configuration refusée pour ${mode.label} sur cet objectif")
            }
        }))
    }

    // ---------------------------------------------------------------- réglages

    fun applySettings() = handler.post { applySettingsInternal() }

    private fun applySettingsInternal() {
        val s = session ?: return
        val dev = device ?: return
        val st = settings
        val targets = listOfNotNull(preview, recorder?.inputSurface)
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        targets.forEach { b.addTarget(it) }
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.fps, mode.fps))
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        if (!mode.highSpeed) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (st.eis) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            if (lens.hasOis) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

            when (st.exposure) {
                Exposure.MANUAL -> {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, st.iso.coerceIn(lens.isoRange.lower, lens.isoRange.upper))
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, minOf(st.exposureNs, 1_000_000_000L / mode.fps))
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / mode.fps)
                }
                else -> {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, st.evSteps)
                    b.set(CaptureRequest.CONTROL_AE_LOCK, st.exposure == Exposure.LOCK)
                }
            }
            if (!lens.fixedFocus) {
                if (st.manualFocus) {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, st.focusDiopters.coerceIn(0f, lens.minFocusDiopters))
                } else b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            b.set(CaptureRequest.CONTROL_AWB_MODE, st.awbMode)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, st.awbLock)
        } else if (!lens.fixedFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        val cb = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                val info = LiveInfo(
                    res.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                    res.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0,
                    res.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
                    res.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0,
                )
                lastInfo = info
                val now = SystemClock.uptimeMillis()
                if (now - lastInfoPost > 200) { lastInfoPost = now; onInfo(info) }
            }
        }
        try {
            if (s is CameraConstrainedHighSpeedCaptureSession) s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()), cb, handler)
            else s.setRepeatingRequest(b.build(), cb, handler)
        } catch (e: Exception) { onError("Réglage refusé : ${e.message}") }
    }

    // ---------------------------------------------------------------- enregistrement

    fun startRecording(orientationHint: Int, done: (Boolean) -> Unit) = handler.post {
        if (device == null || recording) { done(false); return@post }
        try {
            val name = "MaCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val clock: () -> Long = if (lens.realtimeClock) SystemClock::elapsedRealtimeNanos else System::nanoTime
            val r = Recorder(ctx, name, mode.size, mode.fps, settings.bitrateMbps * 1_000_000, mode.tenBit, orientationHint, clock)
            r.prepare()
            recorder = r
            configure(withRecorder = r)
            done(true)
        } catch (e: Exception) {
            recorder = null
            onError("Enregistrement impossible : ${e.message}"); done(false)
        }
    }

    /** Renvoie un résumé de ce qui a été enregistré. */
    fun stopRecording(done: (String) -> Unit) = handler.post { done(stopRecordingInternal()) }

    private fun stopRecordingInternal(): String {
        val r = recorder ?: return ""
        runCatching { session?.stopRepeating(); session?.close() }; session = null
        recorder = null
        val msg = StringBuilder()
        try {
            r.stop()
            msg.append("Vidéo : DCIM/MaCam/${r.baseName}.mp4")
        } catch (e: Exception) { msg.append("Erreur à l'arrêt : ${e.message}") }
        if (settings.gyroLog && gyro.available) {
            // Ramène l'horodatage vidéo dans l'horloge des capteurs (elapsedRealtime).
            var zero = r.firstVideoTimestampNs
            if (!lens.realtimeClock) zero += SystemClock.elapsedRealtimeNanos() - System.nanoTime()
            val readoutMs = (lastInfo?.readoutNs ?: 0L) / 1e6
            val n = gyro.stopAndSave(r.baseName, zero, readoutMs, "%.0fmm".format(Locale.US, lens.eqMm))
            msg.append("\nGyroscope : Download/MaCam/${r.baseName}.gcsv ($n mesures)")
        }
        if (device != null) configure(withRecorder = null)
        return msg.toString()
    }
}
