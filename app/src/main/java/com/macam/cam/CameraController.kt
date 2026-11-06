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
class Lens(val id: String, val chars: CameraCharacteristics, val front: Boolean, val parentId: String = id) {
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
                    val lens = runCatching { Lens(pid, cm.getCameraCharacteristics(pid), isFront, id) }.getOrNull() ?: continue
                    (if (isFront) front else back) += lens
                }
            }
            back.sortBy { it.eqMm }
            return back + front
        }

        /** Objectif principal : arrière stabilisé si possible (beaucoup de modèles d'entrée de gamme n'ont pas d'OIS). */
        fun main(list: List<Lens>) = list.firstOrNull { !it.front && it.hasOis } ?: list.firstOrNull { !it.front } ?: list.first()
    }
}

enum class Mode(val label: String, val size: Size, val fps: Int, val tenBit: Boolean, val highSpeed: Boolean) {
    UHD30_HLG("4K30 HDR", Size(3840, 2160), 30, true, false),
    UHD60_HLG("4K60 HDR", Size(3840, 2160), 60, true, false),
    UHD30_SDR("4K30", Size(3840, 2160), 30, false, false),
    UHD60_SDR("4K60", Size(3840, 2160), 60, false, false),
    UHD120("4K120", Size(3840, 2160), 120, false, true),
    FHD30_HLG("1080p30 HDR", Size(1920, 1080), 30, true, false),
    FHD60_HLG("1080p60 HDR", Size(1920, 1080), 60, true, false),
    FHD30_SDR("1080p30", Size(1920, 1080), 30, false, false),
    FHD60_SDR("1080p60", Size(1920, 1080), 60, false, false),
    FHD120("1080p120", Size(1920, 1080), 120, false, true),
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
    /** Courbe plate type « log » (modes SDR uniquement). */
    var flat: Boolean = false,
)

/** Mesures en direct renvoyées par le capteur. */
data class LiveInfo(val iso: Int, val exposureNs: Long, val focusDiopters: Float, val readoutNs: Long)

class CameraController(
    private val ctx: Context,
    private val previewSurface: (Size) -> Surface,
    private val onInfo: (LiveInfo) -> Unit,
    private val onError: (String) -> Unit,
    /** Reçoit le calque des outils pro (zébras, peaking, fausses couleurs), orienté comme le capteur. */
    private val onOverlay: (android.graphics.Bitmap?) -> Unit = {},
) {
    val tools = ProTools()
    private var analysis: android.media.ImageReader? = null
    private val overlayBmps = arrayOfNulls<android.graphics.Bitmap>(2)
    private var overlayFlip = false
    private var lastOverlay = 0L
    /** Désactivé pour la session courante si le pilote refuse le flux d'analyse. */
    private var analysisAllowed = true
    private val cm = ctx.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    val lenses: List<Lens> = Lens.list(cm)
    var lens: Lens = Lens.main(lenses); private set
    var mode = Mode.UHD30_HLG; private set
    val settings = Settings()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var preview: Surface? = null
    /** Encodeur préparé et intégré à la session, prêt à enregistrer. */
    private var armed: Recorder? = null
    /** Enregistrement en cours. */
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
        runCatching { analysis?.close() }; analysis = null
        armed?.disarm(); armed = null
        runCatching { device?.close() }; device = null
    }

    @SuppressLint("MissingPermission")
    private fun reopen(l: Lens, m: Mode) {
        closeDevice()
        lens = l
        mode = if (l.supports(m)) m else Mode.entries.firstOrNull { l.supports(it) }
            ?: return onError("Aucun mode vidéo compatible avec cet objectif")
        settings.manualFocus = settings.manualFocus && !l.fixedFocus
        analysisAllowed = true
        openWithFallback(l.id, l.parentId)
    }

    /** Certains fabricants interdisent d'ouvrir un objectif physique : on se replie sur la caméra logique. */
    @SuppressLint("MissingPermission")
    private fun openWithFallback(id: String, parent: String) {
        val retry = { if (id != parent) openWithFallback(parent, parent) else onError("Ouverture impossible") }
        try {
            cm.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; configure() }
                override fun onDisconnected(d: CameraDevice) { d.close(); if (device == d) device = null }
                override fun onError(d: CameraDevice, e: Int) { d.close(); if (device == null) retry() else onError("Erreur caméra $e") }
            })
        } catch (e: Exception) { retry() }
    }

    /** (Re)crée la session : aperçu + encodeur préparé pour un démarrage instantané. */
    fun reconfigure() = handler.post { if (!recording) configure() }

    private fun configure() {
        val dev = device ?: return
        runCatching { session?.close() }; session = null
        armed?.disarm(); armed = null
        // En haute vitesse, l'aperçu doit avoir exactement la taille de la vidéo.
        val pv = previewSurface(if (mode.highSpeed) mode.size else Size(1920, 1080))
        preview = pv
        val clock: () -> Long = if (lens.realtimeClock) SystemClock::elapsedRealtimeNanos else System::nanoTime
        val rec = try {
            Recorder(ctx, mode.size, mode.fps, settings.bitrateMbps * 1_000_000, mode.tenBit, clock).apply { arm() }
        } catch (e: Exception) { onError("Encodeur indisponible : ${e.message}"); null }
        armed = rec
        runCatching { analysis?.close() }; analysis = null
        val an = if (analysisAllowed && !mode.highSpeed) createAnalysisReader() else null
        analysis = an
        val outs = listOfNotNull(pv, rec?.inputSurface, an?.surface).map { s ->
            OutputConfiguration(s).apply {
                if (mode.tenBit && Build.VERSION.SDK_INT >= 33) dynamicRangeProfile = DynamicRangeProfiles.HLG10
            }
        }
        val type = if (mode.highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR
        if (!pv.isValid) { onError("Aperçu indisponible, réessayez"); return }
        try { dev.createCaptureSession(SessionConfiguration(type, outs, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { session = s; applySettingsInternal() }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (an != null) { // on réessaie sans le flux d'analyse (outils pro indisponibles)
                    analysisAllowed = false
                    onError("Outils pro indisponibles dans ce mode")
                    configure()
                } else onError("Configuration refusée pour ${mode.label} sur cet objectif")
            }
        })) } catch (e: Exception) { onError("Session impossible : ${e.message}") }
    }

    /** Petit flux basse définition pour les outils pro (P010 en HDR, YUV sinon). */
    private fun createAnalysisReader(): android.media.ImageReader? {
        val map = lens.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val fmt = if (mode.tenBit) android.graphics.ImageFormat.YCBCR_P010 else android.graphics.ImageFormat.YUV_420_888
        val size = map.getOutputSizes(fmt)?.filter { it.width * 9 == it.height * 16 && it.width >= 480 }
            ?.minByOrNull { it.width } ?: return null
        val r = android.media.ImageReader.newInstance(size.width, size.height, fmt, 2)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.uptimeMillis()
                if (tools.anyEnabled && now - lastOverlay > 66) { // ~15 i/s suffisent pour un calque
                    lastOverlay = now
                    // Double tampon : on n'écrit jamais dans le bitmap affiché.
                    overlayFlip = !overlayFlip
                    val bmp = overlayBmps[if (overlayFlip) 1 else 0]?.takeIf { it.width == img.width && it.height == img.height }
                        ?: android.graphics.Bitmap.createBitmap(img.width, img.height, android.graphics.Bitmap.Config.ARGB_8888)
                            .also { overlayBmps[if (overlayFlip) 1 else 0] = it }
                    if (tools.render(img, bmp)) onOverlay(bmp)
                }
            } finally { img.close() }
        }, handler)
        return r
    }

    // ---------------------------------------------------------------- réglages

    fun applySettings() = handler.post { applySettingsInternal() }

    private fun applySettingsInternal() {
        val s = session ?: return
        val dev = device ?: return
        val st = settings
        // Le flux d'analyse n'est demandé que si un outil est actif (économie d'énergie).
        val targets = listOfNotNull(preview, recorder?.inputSurface, analysis?.surface?.takeIf { tools.anyEnabled })
        if (!tools.anyEnabled) onOverlay(null)
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
            if (st.flat && !mode.tenBit) {
                val pts = lens.chars.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
                if (pts >= 2) {
                    b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                    b.set(CaptureRequest.TONEMAP_CURVE, ProTools.flatCurve(minOf(pts, 64)))
                }
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
        val r = armed
        if (device == null || session == null || r == null || recording) { done(false); return@post }
        try {
            r.begin("MaCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()), orientationHint)
            armed = null
            recorder = r
            if (settings.gyroLog && gyro.available) gyro.start()
            applySettingsInternal() // ajoute l'encodeur aux cibles : démarrage immédiat
            done(true)
        } catch (e: Exception) {
            // Nettoie le fichier à moitié créé et prépare un nouvel encodeur.
            armed = null
            Thread { r.finish() }.start()
            configure()
            onError("Enregistrement impossible : ${e.message}"); done(false)
        }
    }

    /** Arrête l'enregistrement ; [done] reçoit un résumé une fois le fichier publié. */
    fun stopRecording(done: (String) -> Unit) = handler.post { stopRecordingInternal(done) }

    private fun stopRecordingInternal(done: (String) -> Unit = {}) {
        val r = recorder ?: return
        recorder = null
        applySettingsInternal() // l'aperçu continue sans l'encodeur
        val withGyro = settings.gyroLog && gyro.available
        val realtime = lens.realtimeClock
        val readoutMs = (lastInfo?.readoutNs ?: 0L) / 1e6
        val lensInfo = "%.0fmm".format(Locale.US, lens.eqMm)
        // Finalisation hors du fil caméra : l'aperçu ne gèle pas pendant l'écriture du fichier.
        Thread {
            val msg = StringBuilder()
            val uri = r.finish()
            msg.append(if (uri != null) "Vidéo enregistrée : DCIM/MaCam/${r.baseName}.mp4" else "Échec : aucune image enregistrée")
            if (withGyro) {
                var zero = r.firstVideoTimestampNs
                // Ramène l'horodatage vidéo dans l'horloge des capteurs (elapsedRealtime).
                if (!realtime) zero += SystemClock.elapsedRealtimeNanos() - System.nanoTime()
                val n = runCatching { gyro.stopAndSave(r.baseName, zero, readoutMs, lensInfo) }.getOrDefault(0)
                msg.append("\nGyroscope : Download/MaCam/${r.baseName}.gcsv ($n mesures)")
            }
            done(msg.toString())
            // Prépare l'encodeur suivant (la surface de l'ancien a disparu).
            handler.post { if (device != null && !recording) configure() }
        }.start()
    }
}
