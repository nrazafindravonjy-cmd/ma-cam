package com.macam.photo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import com.macam.cam.Lens
import com.macam.cam.Metering
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

enum class PhotoMode { PHOTO, NIGHT, PORTRAIT }

/**
 * Appareil photo multi-images : HDR+ (PHOTO), Nuit/Astro (NIGHT), super-résolution (zoom « SR »)
 * et Portrait (extension Bokeh du fabricant).
 */
class PhotoController(
    private val ctx: Context,
    private val previewSurface: (Size) -> Surface,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val cm = ctx.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("photo").apply { start() }
    val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val worker = Executors.newSingleThreadExecutor()

    val lenses: List<Lens> = Lens.list(cm)
    var lens: Lens = Lens.main(lenses); private set
    private var currentJob: BurstJob? = null
    var mode = PhotoMode.PHOTO; private set
    /** Zoom super-résolution 2× sur l'objectif courant. */
    var superRes = false; private set
    var saveDng = false
    @Volatile var busy = false; private set
    /** Style personnel appris des retouches Lightroom (null = rendu Ma Cam). */
    @Volatile var style: com.macam.style.Style? = null
    /** Signer les photos (Content Credentials / C2PA). */
    @Volatile var authenticate = false
        set(v) { field = v; if (v) motion.start() else if (mode != PhotoMode.NIGHT) motion.stop() }

    /** Signataire pour cette capture, avec la description de la prise de vue. */
    private fun signerFor(p: Plan?, frames: Int): ((java.io.File, java.io.File) -> Unit)? {
        if (!authenticate || !com.macam.Pro.isPro) return null
        val motionSnap = motion.samples()
        val capture = org.json.JSONObject().apply {
            put("mode", mode.name.lowercase()); put("lens_eq_mm", Math.round(lens.eqMm))
            put("frames_merged", frames); put("zero_shutter_lag", p?.zsl == true)
            p?.let { put("iso", it.iso); put("exposure_ns", it.exposureNs) }
            put("captured_at", java.time.Instant.now().toString())
        }
        return { src, dst -> com.macam.auth.Authenticity.signJpeg(ctx, src, dst, capture, motionSnap) }
    }
    /** Appelée avec l'URI de chaque photo enregistrée (vignette de la galerie). */
    var onSaved: (android.net.Uri) -> Unit = {}
    /** Zone de mise au point/exposition choisie au toucher (null = automatique). */
    private var meteringRegion: Array<android.hardware.camera2.params.MeteringRectangle>? = null

    /**
     * Profil de l'appareil. Créé dès la construction : l'interface le lit avant que la caméra soit ouverte
     * (en arrière-plan). Corrige le plantage « lateinit property profile has not been initialized » (v0.11).
     */
    @Volatile var profile: DeviceProfile = DeviceProfile(ctx, lens.chars); private set
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var extSession: CameraExtensionSession? = null
    private var reader: ImageReader? = null
    private var preview: Surface? = null
    @Volatile private var last: CaptureResult? = null
    private val motion = MotionMeter(ctx)

    fun start(l: Lens = lens, m: PhotoMode = mode, sr: Boolean = superRes) = handler.post { reopen(l, m, sr) }

    /** Ferme tout, attend que la caméra soit réellement libérée, puis appelle [done]. */
    fun release(done: () -> Unit = {}) = handler.post {
        // Une photo en cours de traitement est terminée avec les images déjà reçues, pas perdue.
        currentJob?.let { j -> worker.execute { j.forceComplete() } }
        close(); motion.stop()
        whenClosed { done(); worker.shutdown(); thread.quitSafely() }
    }

    /**
     * Exécute [block] une fois la caméra précédente réellement fermée (onClosed), sans bloquer le fil caméra
     * (onClosed y est livré) ; au plus 1,5 s d'attente.
     */
    private fun whenClosed(waited: Int = 0, block: () -> Unit) {
        val c = closed
        if (c == null || c.count == 0L || waited >= 1500) block()
        else handler.postDelayed({ whenClosed(waited + 30, block) }, 30)
    }

    @Volatile private var closed: CountDownLatch? = null
    /** Calibration personnelle de l'objectif courant, si elle a été faite. */
    @Volatile private var calibration: com.macam.calib.SensorCalibration? = null

    // ---- Zéro délai (ZSL) : les dernières images du capteur sont gardées en mémoire en permanence.
    /** Actif en mode Photo : la rafale est déjà prise au moment de l'appui. */
    private val zsl get() = mode == PhotoMode.PHOTO && !fusion && profile.tier == DeviceProfile.Tier.HIGH

    // ---- Fusion de deux objectifs : ultra grand-angle + principal capturés au même instant (caméra logique).
    @Volatile var fusion = false; private set
    private var mainLens: Lens? = null
    private var readerMain: ImageReader? = null

    private fun fusionPossible(l: Lens): Boolean {
        val main = Lens.main(lenses)
        return mode == PhotoMode.PHOTO && !l.front && l != main && l.eqMm < main.eqMm * 0.85 &&
            l.parentId != l.id && main.parentId == l.parentId && profile.tier == DeviceProfile.Tier.HIGH &&
            !com.macam.cam.Quirks.has("noFusion:${l.parentId}")
    }
    private val zslDepth get() = profile.frames(false)
    private val ring = ArrayDeque<android.media.Image>()
    private val ringResults = LinkedHashMap<Long, TotalCaptureResult>()
    private var configureAttempt = 0

    private fun close() {
        clearRing()
        runCatching { readerMain?.close() }; readerMain = null
        runCatching { session?.close() }; session = null
        runCatching { extSession?.close() }; extSession = null
        device?.let { closed = CountDownLatch(1); runCatching { it.close() } }
        device = null
        runCatching { reader?.close() }; reader = null
    }

    @SuppressLint("MissingPermission")
    private fun reopen(l: Lens, m: PhotoMode, sr: Boolean) {
        close()
        // La caméra suivante ne s'ouvre qu'une fois la précédente fermée : sinon la surface d'aperçu est encore prise.
        whenClosed { doReopen(l, m, sr) }
    }

    private var fusionAttempt = 0

    @SuppressLint("MissingPermission")
    private fun doReopen(l: Lens, m: PhotoMode, sr: Boolean) {
        lens = l; mode = m
        profile = DeviceProfile(ctx, l.chars)
        calibration = com.macam.calib.SensorCalibration.load(ctx, l.id)
        fusion = fusionPossible(l)
        mainLens = if (fusion) Lens.main(lenses) else null
        superRes = sr && m == PhotoMode.PHOTO && profile.srScale > 0
        if (m == PhotoMode.NIGHT || authenticate) motion.start() else motion.stop()
        // Le mode Portrait passe par l'extension du fabricant, disponible sur la caméra logique.
        val id = if (m == PhotoMode.PORTRAIT) logicalId(l.front) ?: return onError("Portrait indisponible") else l.id
        // En fusion, on ouvre la caméra logique pour recevoir les deux objectifs en même temps.
        if (fusion) open(l.parentId, l.parentId, m) else open(id, if (m == PhotoMode.PORTRAIT) id else l.parentId, m)
    }

    /** Repli sur la caméra logique si l'objectif physique ne peut pas être ouvert directement. */
    @SuppressLint("MissingPermission")
    private fun open(id: String, parent: String, m: PhotoMode, attempt: Int = 0) {
        val retry = {
            if (id != parent) {
                // La caméra logique montre l'objectif principal : on le dit et on l'affiche comme tel.
                if (lens.id == id && lens != Lens.main(lenses)) { lens = Lens.main(lenses); onError("Objectif indisponible : retour au principal") }
                open(parent, parent, m)
            } else onError("Ouverture de la caméra impossible")
        }
        val busyRetry = { if (attempt < 5) handler.postDelayed({ open(id, parent, m, attempt + 1) }, 300) else retry() }
        try {
            cm.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    try { if (m == PhotoMode.PORTRAIT) configurePortrait(d) else if (fusion) configureFusion(d) else configure(d) }
                    catch (e: Exception) { onError("Session impossible : ${e.message}") }
                }
                override fun onDisconnected(d: CameraDevice) { d.close() }
                override fun onClosed(d: CameraDevice) { closed?.countDown() }
                override fun onError(d: CameraDevice, e: Int) {
                    d.close()
                    when {
                        e == ERROR_CAMERA_IN_USE || e == ERROR_MAX_CAMERAS_IN_USE -> busyRetry()
                        device == null -> retry()
                        else -> onError("Erreur caméra $e")
                    }
                }
            })
        } catch (e: CameraAccessException) { busyRetry() }
        catch (e: Exception) { retry() }
    }

    private fun logicalId(front: Boolean) = cm.cameraIdList.firstOrNull {
        (cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) == front
    }

    // ------------------------------------------------------------------ session standard

    private fun captureSize(): Pair<Int, Size> {
        val map = lens.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        if (profile.useRaw) {
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }?.let { return ImageFormat.RAW_SENSOR to it }
        }
        val maxPx = profile.maxYuvMp * 1e6
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)
            .filter { it.width * it.height <= maxPx && abs(it.width * 3 - it.height * 4) < it.width / 20 }
            .maxByOrNull { it.width * it.height }
            ?: map.getOutputSizes(ImageFormat.YUV_420_888).maxBy { it.width * it.height }
        return ImageFormat.YUV_420_888 to yuv
    }

    private fun previewSize(): Size {
        val map = lens.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
        return sizes.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }.maxByOrNull { it.width } ?: Size(1440, 1080)
    }

    private fun configure(d: CameraDevice) {
        val (fmt, size) = captureSize()
        // En ZSL, le lecteur garde zslDepth images + 2 de marge pour que le capteur ne se bloque jamais.
        val r = ImageReader.newInstance(size.width, size.height, fmt, if (zsl) zslDepth + 2 else 3)
        reader = r
        val pv = previewSurface(previewSize()); preview = pv
        if (!pv.isValid) return onError("Aperçu indisponible, réessayez")
        val outs = listOf(OutputConfiguration(pv), OutputConfiguration(r.surface))
        d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; startPreview() }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    // Surface d'aperçu encore tenue par la caméra précédente : nouvel essai.
                    if (configureAttempt++ < 2) handler.postDelayed({ device?.let { runCatching { reader?.close() }; configure(it) } }, 350)
                    else onError("Configuration photo refusée")
                }
            }))
        onStatus(statusLine())
    }

    /** Session logique : aperçu et image sur l'ultra grand-angle, image simultanée sur le principal. */
    private fun configureFusion(d: CameraDevice) {
        val main = mainLens ?: return configure(d)
        fun maxYuv(l: Lens) = l.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.YUV_420_888).filter { abs(it.width * 3 - it.height * 4) < it.width / 20 }.maxBy { it.width * it.height }
        val su = maxYuv(lens); val sm = maxYuv(main)
        val ru = ImageReader.newInstance(su.width, su.height, ImageFormat.YUV_420_888, 2)
        val rm = ImageReader.newInstance(sm.width, sm.height, ImageFormat.YUV_420_888, 2)
        reader = ru; readerMain = rm
        val pv = previewSurface(previewSize()); preview = pv
        if (!pv.isValid) return onError("Aperçu indisponible, réessayez")
        val outs = listOf(
            OutputConfiguration(pv).apply { setPhysicalCameraId(lens.id) },
            OutputConfiguration(ru.surface).apply { setPhysicalCameraId(lens.id) },
            OutputConfiguration(rm.surface).apply { setPhysicalCameraId(main.id) },
        )
        d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; fusionAttempt = 0; startPreview(); onStatus("Ultra grand-angle + fusion du principal") }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    // Un échec isolé peut être passager : on réessaie une fois avant de retenir l'incompatibilité.
                    if (fusionAttempt++ < 1) { handler.postDelayed({ reopen(lens, mode, false) }, 400); return }
                    com.macam.cam.Quirks.set("noFusion:${lens.parentId}")
                    fusionAttempt = 0
                    handler.post { reopen(lens, mode, false) }
                }
            }))
    }

    /** Capture simultanée des deux objectifs, puis fusion et enregistrement. */
    private fun captureFusion(rotation: Int, name: String) {
        val s = session ?: return; val d = device ?: return
        val ru = reader ?: return; val rm = readerMain ?: return; val main = mainLens ?: return
        var uw: android.graphics.Bitmap? = null; var mn: android.graphics.Bitmap? = null
        val both = CountDownLatch(2)
        ru.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { uw = LensFusion.toBitmap(it); it.close(); both.countDown() } }, handler)
        rm.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { mn = LensFusion.toBitmap(it); it.close(); both.countDown() } }, handler)
        onStatus("Fusion des deux objectifs…"); onProgress(0.3f)
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(ru.surface); addTarget(rm.surface); addTarget(preview!!)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        }.build()
        var result: TotalCaptureResult? = null
        s.capture(req, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult) { result = t }
        }, handler)
        worker.execute {
            try {
                if (!both.await(5, TimeUnit.SECONDS)) throw IllegalStateException("images manquantes")
                onProgress(0.7f)
                val base = uw!!; val det = mn!!
                val score = LensFusion.fuse(base, det, (lens.eqMm / main.eqMm).toFloat())
                det.recycle()
                val iso = result?.get(CaptureResult.SENSOR_SENSITIVITY); val exp = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                onSaved(Saver.saveJpeg(ctx, base, rotation, name, iso, exp, 2, signerFor(null, 2)))
                base.recycle()
                onProgress(-1f)
                celebrate(if (score >= 0.5f) "Photo enregistrée · 2 objectifs fusionnés" else "Photo enregistrée (fusion impossible sur cette scène)")
            } catch (e: Throwable) { onProgress(-1f); onError("Fusion impossible : ${e.message}") }
            finally { busy = false }
        }
    }

    /** Statut court et sans jargon (attention sélective, charge cognitive). */
    fun statusLine() = when (mode) {
        PhotoMode.PHOTO -> if (superRes) "Zoom 2× haute définition" else "HDR+"
        PhotoMode.NIGHT -> "Nuit · tenez le téléphone stable"
        PhotoMode.PORTRAIT -> "Portrait"
    }

    /** Progression d'une capture (0 → 1), ou -1 quand il n'y en a pas (gradation du but, Zeigarnik). */
    var onProgress: (Float) -> Unit = {}

    private fun previewRequest(d: CameraDevice): CaptureRequest.Builder =
        d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview!!)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            if (!lens.fixedFocus && lens.afPicture) set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            if (lens.hasOis) set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
            if (superRes && Build.VERSION.SDK_INT >= 30) set(CaptureRequest.CONTROL_ZOOM_RATIO, 2f)
            meteringRegion?.let { r ->
                if ((lens.chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) set(CaptureRequest.CONTROL_AF_REGIONS, r)
                if ((lens.chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) set(CaptureRequest.CONTROL_AE_REGIONS, r)
            }
        }

    /** Toucher pour faire la mise au point et l'exposition en (u, v), coordonnées capteur normalisées [0, 1]. */
    fun focusAt(u: Float, v: Float) = handler.post {
        val s = session ?: return@post; val d = device ?: return@post
        meteringRegion = Metering.region(lens.chars, u, v)
        try {
            val trigger = previewRequest(d).apply {
                if (!lens.fixedFocus) set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            }
            s.capture(trigger.build(), null, handler)
            startPreview()
        } catch (_: Exception) {}
    }

    private fun startPreview() {
        val s = session ?: return; val d = device ?: return; val r = reader
        val req = previewRequest(d)
        if (zsl && r != null) {
            req.addTarget(r.surface)
            if (r.imageFormat == ImageFormat.RAW_SENSOR)
                req.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            // Anneau d'images : on garde les plus récentes, on rend les plus anciennes au capteur.
            r.setOnImageAvailableListener({ rd ->
                val img = runCatching { rd.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                ring.addLast(img)
                while (ring.size > zslDepth) ring.removeFirst().close()
            }, handler)
        }
        s.setRepeatingRequest(req.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                last = res
                if (zsl) {
                    res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { ringResults[it] = res }
                    while (ringResults.size > zslDepth * 3) ringResults.remove(ringResults.keys.first())
                }
                if (!firstFrameSeen) { firstFrameSeen = true; onFirstFrame() }
            }
        }, handler)
    }

    private fun clearRing() { while (ring.isNotEmpty()) ring.removeFirst().close(); ringResults.clear() }

    /** Appelé à la première image affichée (mesure du temps de démarrage). */
    var onFirstFrame: () -> Unit = {}
    private var firstFrameSeen = false

    /**
     * Capture sans délai : les images de l'anneau, prises juste avant l'appui, forment la rafale.
     * La plus récente (la plus proche de l'appui) sert de référence.
     */
    private fun captureZsl(rotation: Int, name: String) {
        val frames = ArrayList<Pair<Frame, TotalCaptureResult?>>()
        while (ring.isNotEmpty()) {
            val img = ring.removeLast()
            val ts = img.timestamp
            frames += Frame.copy(img) to ringResults[ts]
            img.close()
        }
        val ref = frames.firstOrNull { it.second != null }?.second ?: (last as? TotalCaptureResult)
        val p = Plan(frames.size, ref?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0, ref?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            manual = false, label = "HDR+", zsl = true)
        onStatus("HDR+ · ${frames.size} images")
        onProgress(0f)
        val job = BurstJob(frames.size)
        currentJob = job
        job.onDone = { engine, result ->
            try { finishAndSave(engine, result, name, rotation, p) }
            catch (e: Throwable) { onError("Traitement impossible : ${e.message}") }
            finally { engine.close(); busy = false; onProgress(-1f); if (currentJob == job) currentJob = null }
        }
        val latch = CountDownLatch(0)
        for ((f, _) in frames) worker.execute { job.add(f, latch) { ref } }
    }

    // ------------------------------------------------------------------ capture multi-images

    private data class Plan(val frames: Int, val exposureNs: Long, val iso: Int, val manual: Boolean, val label: String, val zsl: Boolean = false)

    /** Choisit nombre d'images, temps de pose et ISO selon la scène et le tremblement mesuré. */
    private fun plan(): Plan {
        val night = mode == PhotoMode.NIGHT
        var n = profile.frames(night)
        val res = last
        val aeExp = res?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val aeIso = res?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        if (!profile.hasManual || aeExp == 0L || aeIso == 0) return Plan(n, 0, 0, false, "auto")
        val isoR = lens.isoRange
        val expR = lens.chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 1_000_000_000L)
        val brightness = aeExp.toDouble() * aeIso
        return if (!night) {
            // HDR+ : sous-exposition d'1 IL pour protéger les hautes lumières ; la fusion compense le bruit.
            var exp = aeExp / 2; var iso = aeIso
            if (exp < expR.lower) { exp = aeExp; iso = (aeIso / 2).coerceAtLeast(isoR.lower) }
            Plan(n, exp, iso, true, "HDR+")
        } else {
            // Nuit : pose limitée par le tremblement (flou ≤ 1,5 px), plus longue sur trépied.
            val omega = motion.omega()
            val pixelPitchMm = lens.chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!.width /
                lens.chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)!!.width
            val fPx = lens.focalMm / pixelPitchMm
            val tripod = omega < 0.015
            val dark = brightness > 0.06e9 * 1600 // ≈ 1/16 s à ISO 1600 ou plus sombre
            val maxExpS = when {
                tripod && dark -> 8.0                    // astrophotographie
                tripod -> 1.0
                else -> (1.5 / (omega * fPx)).coerceIn(1.0 / 15, 1.0 / 3)
            }
            if (tripod && dark) n = if (profile.tier == DeviceProfile.Tier.HIGH) 15 else n
            val target = brightness * 3 // plus lumineux : la fusion de nombreuses images autorise un gain fort
            var exp = minOf((target / isoR.lower).toLong(), (maxExpS * 1e9).toLong(), expR.upper)
            exp = exp.coerceAtLeast(expR.lower)
            val iso = (target / exp).toInt().coerceIn(isoR.lower, isoR.upper)
            Plan(n, exp, iso, true, if (tripod && dark) "Astro" else if (tripod) "Nuit trépied" else "Nuit")
        }
    }

    fun capture(deviceOrientation: Int) = handler.post {
        if (busy) return@post
        if (mode == PhotoMode.PORTRAIT) return@post capturePortrait(deviceOrientation)
        val s = session ?: return@post; val d = device ?: return@post; val r = reader ?: return@post
        busy = true
        val rotation = jpegRotation(deviceOrientation)
        val name = "MaCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        if (fusion) return@post captureFusion(rotation, name)
        if (zsl && ring.size >= 2) return@post captureZsl(rotation, name)
        val p = plan()
        val total = p.exposureNs * p.frames / 1e9
        onStatus(if (total > 1.5) "${p.label} · ne bougez pas · %.0f s".format(total) else "${p.label} · ${p.frames} images")
        onProgress(0f)
        // Compte à rebours pour les poses longues : une durée visible paraît plus courte (Parkinson).
        if (total > 1.5) {
            val end = android.os.SystemClock.uptimeMillis() + (total * 1000).toLong()
            handler.post(object : Runnable {
                override fun run() {
                    val left = (end - android.os.SystemClock.uptimeMillis()) / 1000
                    if (left <= 0 || !busy) return
                    onStatus("${p.label} · ne bougez pas · $left s")
                    handler.postDelayed(this, 1000)
                }
            })
        }

        val refResult = CountDownLatch(1)
        var firstResult: TotalCaptureResult? = null
        var received = 0
        val job = BurstJob(p.frames)
        currentJob = job
        // Garde-fou : si des images n'arrivent jamais (capture annulée), on termine avec celles reçues.
        val deadlineMs = p.exposureNs * p.frames / 1_000_000 + 15_000
        handler.postDelayed({ worker.execute { job.forceComplete() } }, deadlineMs)
        job.onDone = { engine, result ->
            try { finishAndSave(engine, result, name, rotation, p) }
            catch (e: Throwable) { onError("Traitement impossible : ${e.message}") }
            finally { engine.close(); busy = false; onProgress(-1f); if (currentJob == job) currentJob = null }
        }

        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireNextImage() ?: return@setOnImageAvailableListener
            // Copie immédiate puis libération : la caméra n'attend jamais le traitement.
            val frame = Frame.copy(img); img.close()
            received++
            if (received <= p.frames) worker.execute { job.add(frame, refResult) { firstResult } }
        }, handler)

        val reqs = (0 until p.frames).map {
            d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface); addTarget(preview!!)
                if (lens.hasOis) set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
                if (superRes && Build.VERSION.SDK_INT >= 30) set(CaptureRequest.CONTROL_ZOOM_RATIO, 2f)
                set(CaptureRequest.CONTROL_AWB_LOCK, true)
                if (r.imageFormat == ImageFormat.RAW_SENSOR)
                    set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                val focus = last?.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (!lens.fixedFocus && focus != null) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
                }
                if (p.manual) {
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, p.exposureNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, p.iso)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, p.exposureNs)
                } else set(CaptureRequest.CONTROL_AE_LOCK, true)
            }.build()
        }
        try {
            s.stopRepeating()
            s.captureBurst(reqs, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, rq: CaptureRequest, res: TotalCaptureResult) {
                    if (firstResult == null) { firstResult = res; refResult.countDown() }
                }
                override fun onCaptureSequenceCompleted(s: CameraCaptureSession, id: Int, f: Long) { startPreview() }
                override fun onCaptureFailed(s: CameraCaptureSession, rq: CaptureRequest, fl: CaptureFailure) { job.failed() }
                override fun onCaptureBufferLost(s: CameraCaptureSession, rq: CaptureRequest, t: Surface, n: Long) { job.failed() }
                override fun onCaptureSequenceAborted(s: CameraCaptureSession, id: Int) { worker.execute { job.forceComplete() }; startPreview() }
            }, handler)
        } catch (e: Exception) {
            busy = false; onError("Capture impossible : ${e.message}"); startPreview(); return@post
        }
    }

    /** Image copiée hors du tampon de la caméra. */
    private class Frame(val fmt: Int, val planes: Array<ByteBuffer>, val rowStrides: IntArray, val pixelStrides: IntArray, val w: Int, val h: Int) {
        companion object {
            fun copy(img: android.media.Image): Frame {
                val ps = img.planes
                return Frame(img.format, Array(ps.size) { i ->
                    val src = ps[i].buffer
                    ByteBuffer.allocateDirect(src.remaining()).order(ByteOrder.nativeOrder()).put(src).also { it.flip() }
                }, IntArray(ps.size) { ps[it].rowStride }, IntArray(ps.size) { ps[it].pixelStride }, img.width, img.height)
            }
        }
    }

    /** Fusionne les images au fil de l'eau sur le fil de travail. */
    private inner class BurstJob(private val expected: Int) {
        var engine: Engine? = null
        var onDone: ((Engine, TotalCaptureResult) -> Unit)? = null
        private var count = 0
        @Volatile private var failures = 0

        private var completed = false

        fun failed() = worker.execute { failures++; if (count + failures >= expected) complete() }

        fun add(f: Frame, refLatch: CountDownLatch, result: () -> TotalCaptureResult?) {
            if (completed) return // image arrivée après le garde-fou
            refLatch.await(3, TimeUnit.SECONDS)
            val res = result()
            val e = engine ?: createEngine(f, res).also { engine = it }
            if (f.fmt == ImageFormat.RAW_SENSOR) e.addRaw(f.planes[0], f.rowStrides[0])
            else e.addYuv(f.planes[0], f.rowStrides[0], f.planes[1], f.planes[2], f.rowStrides[1], f.pixelStrides[1])
            count++
            onProgress(count.toFloat() / expected)
            if (count + failures >= expected) complete()
        }

        /** Termine avec les images déjà fusionnées (garde-fou, fermeture de l'appli). Fil de travail uniquement. */
        fun forceComplete() { if (!completed) { if (engine == null) { completed = true; busy = false } else complete() } }

        /** Appelée sur le fil de travail uniquement. */
        private fun complete() {
            if (completed) return
            completed = true
            val e = engine ?: run { busy = false; onError("Aucune image reçue"); return }
            val done = onDone ?: return
            val res = last as? TotalCaptureResult
            val r = firstRes ?: res ?: run { e.close(); busy = false; onError("Métadonnées manquantes"); return }
            // Flow : sur un appareil puissant, on peut déjà reprendre une photo pendant le rendu
            // (le fil de travail traite les rafales l'une après l'autre).
            if (profile.tier == DeviceProfile.Tier.HIGH) { busy = false; onProgress(-1f); onStatus(statusLine()) }
            done(e, r)
        }

        private var firstRes: TotalCaptureResult? = null

        private fun createEngine(f: Frame, res: TotalCaptureResult?): Engine {
            firstRes = res
            val c = lens.chars
            val cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
            val pattern = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val dyn = res?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            // Calibration personnelle (si faite) : noir et bruit mesurés sur CE capteur, à l'ISO le plus proche.
            val iso = res?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
            val cal = calibration?.takeIf { it.width == f.w }
            val black = cal?.blackFor(iso) ?: FloatArray(4) { i ->
                dyn?.getOrNull(i) ?: (pattern?.getOffsetForIndex(i % 2, i / 2)?.toFloat() ?: 64f)
            }
            val white = (res?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
            val noise = res?.get(CaptureResult.SENSOR_NOISE_PROFILE)
            val ns = noise?.map { it.first }?.average()?.toFloat() ?: 1e-4f
            val no = cal?.readVarFor(iso) ?: noise?.map { it.second }?.average()?.toFloat() ?: 1e-6f
            val night = mode == PhotoMode.NIGHT
            return createEngineFor(f, cfa, black, white, ns, no, night).also { e ->
                if (f.fmt == ImageFormat.RAW_SENSOR) cal?.hotPixels?.takeIf { it.isNotEmpty() }?.let { e.setHotPixels(it) }
                // Correction du vignetage fournie par le capteur (les RAW ne sont pas corrigés).
                res?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { m ->
                    val g = FloatArray(m.rowCount * m.columnCount * 4)
                    m.copyGainFactors(g, 0)
                    e.setShading(g, m.rowCount, m.columnCount)
                }
            }
        }

        private fun createEngineFor(f: Frame, cfa: Int, black: FloatArray, white: Float, ns: Float, no: Float, night: Boolean): Engine {
            return when {
                f.fmt != ImageFormat.RAW_SENSOR -> Engine.create(Engine.Mode.YUV, f.w, f.h, 0, FloatArray(4), 255f, 0f, 0f, if (night) 10f else 6f)
                superRes -> {
                    val cw = (f.w / 2) and 1.inv(); val ch = (f.h / 2) and 1.inv()
                    Engine.create(Engine.Mode.RAW_SR, f.w, f.h, cfa, black, white, ns, no, 8f,
                        (f.w - cw) / 2, (f.h - ch) / 2, cw, ch, profile.srScale)
                }
                else -> Engine.create(Engine.Mode.RAW_HDR, f.w, f.h, cfa, black, white, ns, no, if (night) 16f else 8f)
            }
        }
    }

    private fun finishAndSave(engine: Engine, result: TotalCaptureResult, name: String, rotation: Int, p: Plan) {
        onStatus("Rendu…")
        val (w, h) = engine.outputSize.let { it[0] to it[1] }
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val wb = if (gains != null) floatArrayOf(gains.red, (gains.greenEven + gains.greenOdd) / 2, gains.blue) else floatArrayOf(2f, 1f, 1.6f)
        val t = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val ccm = FloatArray(9) { i -> t?.getElement(i % 3, i / 3)?.toFloat() ?: if (i % 4 == 0) 1f else 0f }
        val night = mode == PhotoMode.NIGHT
        val look = when {
            superRes -> Engine.Look(2f, 0.6f, 1.1f, 0.35f, 1f, 1.1f)
            night -> Engine.Look(1f, 0.62f, 1.05f, 0.35f, 3f, 1.05f)
            // ZSL : images à l'exposition normale (pas de sous-exposition) → pas de gain, compression plus forte.
            p.zsl -> Engine.Look(1f, 0.5f, 1.15f, 0.6f, 1f, 1.12f)
            else -> Engine.Look(2f, 0.55f, 1.15f, 0.6f, 1f, 1.12f)
        }
        // Copie de la mosaïque pour le DNG avant le rendu (qui libère les accumulateurs).
        var rawBuf: ByteBuffer? = null
        if (saveDng && !superRes) {
            rawBuf = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
            if (!engine.mergedRaw(rawBuf)) rawBuf = null
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val st = style?.takeIf { com.macam.Pro.isPro }
        st?.let { engine.setLut(it.lut(33), 33) }
        val finalLook = if (st != null) look.copy(detail = look.detail + st.clarity / 100f * 0.3f) else look
        if (!engine.render(bmp, wb, ccm, finalLook)) throw IllegalStateException("rendu refusé")
        val frames = engine.frames
        onSaved(Saver.saveJpeg(ctx, bmp, rotation, name, p.iso.takeIf { it > 0 }, p.exposureNs.takeIf { it > 0 }, frames, signerFor(p, frames)))
        bmp.recycle()
        rawBuf?.let { Saver.saveDng(ctx, lens.chars, result, it, Size(w, h), rotation, name) }
        onProgress(-1f)
        celebrate("Photo enregistrée${if (rawBuf != null) " + RAW" else ""}")
    }

    /** Fin positive (règle de l'apogée) : confirmation brève, puis retour au statut normal. */
    private fun celebrate(msg: String) {
        onStatus("✓ $msg")
        handler.postDelayed({ if (!busy) onStatus(statusLine()) }, 1800)
    }

    private fun jpegRotation(deviceOrientation: Int): Int {
        val sensor = lens.chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        return if (lens.front) (sensor - deviceOrientation + 360) % 360 else (sensor + deviceOrientation) % 360
    }

    // ------------------------------------------------------------------ Portrait (extension Bokeh)

    private fun configurePortrait(d: CameraDevice) {
        if (Build.VERSION.SDK_INT < 31) return onError("Portrait : Android 12 minimum")
        val ec = cm.getCameraExtensionCharacteristics(d.id)
        val ext = CameraExtensionCharacteristics.EXTENSION_BOKEH
        if (ext !in ec.supportedExtensions) return onError("Le fabricant ne fournit pas de mode Portrait")
        val pvSizes = ec.getExtensionSupportedSizes(ext, SurfaceTexture::class.java)
        val pvSize = pvSizes.filter { it.width * 3 == it.height * 4 }.maxByOrNull { if (it.width <= 1920) it.width else 0 }
            ?: pvSizes.firstOrNull() ?: return onError("Portrait : aucune taille d'aperçu")
        val jpeg = ec.getExtensionSupportedSizes(ext, ImageFormat.JPEG).maxByOrNull { it.width * it.height }
            ?: return onError("Portrait : aucune taille photo")
        val r = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2); reader = r
        val pv = previewSurface(pvSize); preview = pv
        val cfg = ExtensionSessionConfiguration(ext, listOf(OutputConfiguration(pv), OutputConfiguration(r.surface)), executor,
            object : CameraExtensionSession.StateCallback() {
                override fun onConfigured(s: CameraExtensionSession) {
                    extSession = s
                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(pv) }.build()
                    s.setRepeatingRequest(req, executor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
                }
                override fun onConfigureFailed(s: CameraExtensionSession) { onError("Portrait refusé par le fabricant") }
            })
        d.createExtensionSession(cfg)
        onStatus("Portrait (traitement du fabricant)")
    }

    private fun capturePortrait(deviceOrientation: Int) {
        val s = extSession ?: return; val d = device ?: return; val r = reader ?: return
        busy = true
        val name = "MaCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_portrait"
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireNextImage() ?: return@setOnImageAvailableListener
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            img.close()
            val signer = signerFor(null, 1)
            worker.execute {
                try { onSaved(Saver.saveJpegBytes(ctx, bytes, name, signer)); celebrate("Portrait enregistré") }
                catch (e: Exception) { onError("Enregistrement impossible : ${e.message}") }
                finally { busy = false }
            }
        }, handler)
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(r.surface)
            set(CaptureRequest.JPEG_ORIENTATION, jpegRotation(deviceOrientation))
            set(CaptureRequest.JPEG_QUALITY, 95.toByte())
        }.build()
        onStatus("Portrait : traitement…")
        try {
            s.capture(req, executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                override fun onCaptureFailed(s: CameraExtensionSession, rq: CaptureRequest) { busy = false; onError("Capture Portrait échouée") }
            })
        } catch (e: Exception) { busy = false; onError("Capture impossible : ${e.message}") }
    }
}

/** Vitesse angulaire moyenne (rad/s) sur la dernière seconde, pour adapter le temps de pose de nuit. */
class MotionMeter(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val values = ArrayDeque<Pair<Long, Double>>()
    private val vectors = ArrayDeque<FloatArray>()

    /** Mesures brutes (rad/s) de la dernière seconde : empreinte de mouvement de l'authenticité. */
    @Synchronized fun samples(): List<FloatArray> = vectors.map { it.copyOf() }
    @Volatile private var active = false

    fun start() {
        if (active) return
        active = true
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }
    fun stop() { active = false; sm.unregisterListener(this) }

    @Synchronized fun omega(): Double = if (values.isEmpty()) 0.05 else values.sumOf { it.second } / values.size

    @Synchronized override fun onSensorChanged(e: SensorEvent) {
        val w = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
        values.addLast(e.timestamp to w)
        vectors.addLast(floatArrayOf(e.values[0], e.values[1], e.values[2]))
        while (values.isNotEmpty() && e.timestamp - values.first().first > 1_000_000_000L) { values.removeFirst(); vectors.removeFirstOrNull() }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
