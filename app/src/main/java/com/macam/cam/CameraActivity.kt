package com.macam.cam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.*
import com.macam.R
import com.macam.diag.DiagnosticActivity
import com.macam.photo.PhotoController
import com.macam.photo.PhotoMode
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Onglets principaux. */
enum class Tab(val label: String) { PHOTO("Photo"), NIGHT("Nuit"), PORTRAIT("Portrait"), VIDEO("Vidéo") }

class CameraActivity : Activity(), SurfaceHolder.Callback {

    private var tab = Tab.PHOTO
    private var switching = false
    private var resumed = false
    private var photo: PhotoController? = null
    private lateinit var overlay: ImageView
    private lateinit var tabRow: LinearLayout
    private lateinit var settingsRow: LinearLayout
    private lateinit var photoRow: LinearLayout
    private lateinit var toolsRow: LinearLayout
    private lateinit var dngBtn: Button
    private lateinit var zebraBtn: Button
    private lateinit var peakBtn: Button
    private lateinit var falseBtn: Button
    private lateinit var flatBtn: Button

    private lateinit var surfaceView: SurfaceView
    private lateinit var info: TextView
    private lateinit var lensRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var manualPanel: LinearLayout
    private lateinit var recordBtn: Button
    private lateinit var expoBtn: Button
    private lateinit var focusBtn: Button
    private lateinit var wbBtn: Button
    private lateinit var eisBtn: Button
    private lateinit var rateBtn: Button
    private lateinit var gyroBtn: Button
    private lateinit var isoBar: SeekBar
    private lateinit var shutterBar: SeekBar
    private lateinit var focusBar: SeekBar
    private lateinit var evBar: SeekBar

    private var cam: CameraController? = null
    private var deviceOrientation = 0
    private var recordStart = 0L

    // Taille de surface demandée par la caméra, et signal quand elle est prête.
    @Volatile private var surfaceSize: Size? = null
    @Volatile private var sizeLatch: CountDownLatch? = null
    @Volatile private var wantedSize: Size? = null

    private val shutters = longArrayOf(4000, 2000, 1000, 500, 250, 240, 120, 100, 60, 50, 30) // 1/x s
    private val wbModes = listOf(
        "BdB auto" to CameraMetadata.CONTROL_AWB_MODE_AUTO,
        "BdB verrou" to -1,
        "Jour" to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        "Nuageux" to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        "Ombre" to CameraMetadata.CONTROL_AWB_MODE_SHADE,
        "Tungstène" to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        "Fluo" to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
    )
    private var wbIndex = 0

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val d = ((o + 45) / 90 * 90) % 360
                if (d != deviceOrientation) { deviceOrientation = d; rotateIcons(d) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        installCrashLog()
        Quirks.init(this)
        com.macam.Pro.init(this)
        // Mémoire : on rouvre le dernier mode et l'état du panneau de réglages.
        tab = runCatching { Tab.valueOf(prefs.getString("tab", "PHOTO")!!) }.getOrDefault(Tab.PHOTO)
        settingsOpen = prefs.getBoolean("settingsOpen", false)
        hdr = prefs.getBoolean("hdr", true)
        buildUi()
        loadLatestThumbnail()
        // Paradoxe de l'utilisateur actif : pas de mode d'emploi, une seule astuce au premier lancement.
        if (!prefs.getBoolean("hintShown", false)) {
            prefs.edit().putBoolean("hintShown", true).apply()
            info.postDelayed({ info.text = "Touchez pour la netteté · glissez pour changer de mode" }, 1500)
        }
        showPreviousCrash()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        getSystemService(android.hardware.SensorManager::class.java).let { sm ->
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(faceDown, it, android.hardware.SensorManager.SENSOR_DELAY_NORMAL) }
        }
        blurHandler.postDelayed(blurTick, 300)
        orientationListener.enable()
        val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else startCamera()
    }

    /** Copie l'aperçu ~12 fois par seconde en très basse définition pour le flou des panneaux. */
    private val blurTick = object : Runnable {
        override fun run() {
            val sv = surfaceView
            if (sv.holder.surface.isValid && sv.width > 0) {
                val small = android.graphics.Bitmap.createBitmap(maxOf(1, sv.width / 8), maxOf(1, sv.height / 8), android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    android.view.PixelCopy.request(sv, small, { r -> if (r == android.view.PixelCopy.SUCCESS) Backdrop.update(small) }, blurHandler)
                } catch (_: Exception) {}
            }
            blurHandler.postDelayed(this, 80)
        }
    }

    // ------------------------------------------------------------------ calibration automatique

    /**
     * Téléphone posé face contre la table, application ouverte (Android interdit la caméra en arrière-plan) :
     * après 3 s immobile, on calibre le capteur si ce n'est pas fait depuis 30 jours.
     */
    private val faceDown = object : android.hardware.SensorEventListener {
        var since = 0L
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val down = e.values[2] < -8.8f && kotlin.math.abs(e.values[0]) < 1.5f && kotlin.math.abs(e.values[1]) < 1.5f
            val now = android.os.SystemClock.uptimeMillis()
            if (!down) { since = 0; return }
            if (since == 0L) since = now
            if (now - since > 3000 && !calibrating && tab != Tab.VIDEO && photo?.busy != true && calibrationDue()) {
                since = Long.MAX_VALUE / 2
                runCalibration()
            }
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }
    private var calibrating = false

    private fun calibrationDue(): Boolean {
        if (!com.macam.Pro.isPro) return false
        val last = prefs.getLong("calibratedAt", 0)
        return System.currentTimeMillis() - last > 30L * 24 * 3600 * 1000
    }

    private fun runCalibration() {
        calibrating = true
        info.text = "Calibration du capteur… ne bougez pas"
        val restart = {
            Thread {
                val msg = try {
                    val r = com.macam.calib.CalibrationRunner(this) { s -> runOnUiThread { info.text = s } }.run()
                    prefs.edit().putLong("calibratedAt", System.currentTimeMillis()).apply()
                    "✓ Capteur calibré (${r.sumOf { it.hotPixels.size }} pixels défectueux corrigés)"
                } catch (e: Exception) { e.message ?: "Calibration interrompue" }
                runOnUiThread { calibrating = false; info.text = msg; if (resumed) startCamera() }
            }.start()
        }
        val c = cam; val p = photo
        cam = null; photo = null
        when { c != null -> c.release { restart() }; p != null -> p.release { restart() }; else -> restart() }
    }

    override fun onPause() {
        setStabilityMeter(false)
        getSystemService(android.hardware.SensorManager::class.java).unregisterListener(faceDown)
        resumed = false
        blurHandler.removeCallbacks(blurTick)
        orientationListener.disable()
        cam?.release(); cam = null
        photo?.release(); photo = null
        setRecordingUi(false)
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else info.text = "La permission caméra est nécessaire."
    }

    private fun startCamera() {
        if (cam != null || photo != null) return
        val toast: (String) -> Unit = { e -> runOnUiThread { Toast.makeText(this, e, Toast.LENGTH_LONG).show() } }
        if (tab == Tab.VIDEO) {
            cam = CameraController(this, ::awaitPreviewSurface,
                onInfo = { i -> runOnUiThread { showInfo(i) } },
                onError = toast,
                onOverlay = { bmp -> runOnUiThread { showOverlay(bmp) } })
            cam!!.onFirstFrame = ::recordStartup
            cam!!.start()
        } else {
            val mode = when (tab) { Tab.NIGHT -> PhotoMode.NIGHT; Tab.PORTRAIT -> PhotoMode.PORTRAIT; else -> PhotoMode.PHOTO }
            photo = PhotoController(this, ::awaitPreviewSurface,
                onStatus = { t -> runOnUiThread { info.text = t; refreshButtons() } },
                onError = toast)
            photo!!.onSaved = { uri -> runOnUiThread { showThumbnail(uri) } }
            photo!!.onFirstFrame = ::recordStartup
            photo!!.authenticate = prefs.getBoolean("authenticate", false) && com.macam.Pro.isPro
            photo!!.style = if (prefs.getBoolean("styleOn", true)) loadStyle() else null
            photo!!.onProgress = { f -> runOnUiThread { progress = f; styleShutter() } }
            photo!!.start(m = mode)
            showOverlay(null)
        }
        refreshRows()
        refreshButtons()
        photo?.handler?.post { runOnUiThread { refreshRows(); refreshButtons() } }
    }

    private fun switchTab(t: Tab) {
        if (t == tab || switching || cam?.recording == true || photo?.busy == true) return
        // On attend que l'ancienne caméra soit réellement fermée avant d'ouvrir la suivante :
        // les deux partagent la surface d'aperçu, qui ne peut servir qu'à une session à la fois.
        switching = true
        val ready = { runOnUiThread { switching = false; if (resumed) startCamera() } }
        val oldCam = cam; val oldPhoto = photo
        cam = null; photo = null
        when {
            oldCam != null -> oldCam.release(ready)
            oldPhoto != null -> oldPhoto.release(ready)
            else -> ready()
        }
        tab = t
        prefs.edit().putString("tab", t.name).apply()
        // Réponse immédiate (< 400 ms, seuil de Doherty) : un voile masque la réouverture de la caméra.
        curtain.animate().cancel(); curtain.alpha = 1f
        curtain.animate().alpha(0f).setStartDelay(450).setDuration(300).start()
        for (i in 0 until tabRow.childCount) (tabRow.getChildAt(i) as Button).selected(Tab.entries[i] == t)
    }

    /** Affiche le calque des outils pro, tourné et mis à l'échelle comme l'aperçu. */
    private fun showOverlay(bmp: android.graphics.Bitmap?) {
        if (bmp == null) { overlay.setImageDrawable(null); return }
        val c = cam ?: return
        overlay.setImageBitmap(bmp)
        val vw = surfaceView.width.toFloat(); val vh = surfaceView.height.toFloat()
        val rot = c.lens.chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        overlay.imageMatrix = android.graphics.Matrix().apply {
            postTranslate(-bmp.width / 2f, -bmp.height / 2f)
            postRotate(rot.toFloat())
            if (c.lens.front) postScale(-1f, 1f)
            val (rw, rh) = if (rot % 180 == 0) bmp.width to bmp.height else bmp.height to bmp.width
            postScale(vw / rw, vh / rh)
            postTranslate(vw / 2, vh / 2)
        }
    }

    // ------------------------------------------------------------------ aperçu

    /** Appelée depuis le thread caméra : règle la taille de la surface et attend qu'elle soit prête. */
    private fun awaitPreviewSurface(size: Size): Surface {
        val holder = surfaceView.holder
        if (surfaceSize != size || !holder.surface.isValid) {
            val latch = CountDownLatch(1)
            sizeLatch = latch; wantedSize = size
            runOnUiThread {
                holder.setFixedSize(size.width, size.height)
                layoutPreview(size)
            }
            latch.await(2, TimeUnit.SECONDS)
        }
        return holder.surface
    }

    override fun surfaceCreated(h: SurfaceHolder) {}
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) {
        surfaceSize = Size(w, hgt)
        if (wantedSize == surfaceSize) sizeLatch?.countDown()
    }
    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceSize = null }

    /** L'aperçu est en paysage (16:9) mais l'écran est en portrait : on affiche en 9:16. */
    private fun layoutPreview(size: Size) {
        val w = resources.displayMetrics.widthPixels
        // L'aperçu part du haut : l'info flotte dessus, et le bas passe sous la carte de verre.
        val tall = size.width * 3 > size.height * 4 + 1
        // Photo 4:3 : ancrée par le bas, ses 70 derniers dp passent sous la carte de verre.
        val lp = FrameLayout.LayoutParams(w, w * size.width / size.height, if (tall) Gravity.TOP else Gravity.BOTTOM)
        if (!tall) lp.bottomMargin = 150.dp
        surfaceView.layoutParams = lp
        overlay.layoutParams = FrameLayout.LayoutParams(lp.width, lp.height, lp.gravity).apply { bottomMargin = lp.bottomMargin }
    }

    // ------------------------------------------------------------------ interface

    // ------------------------------------------------------------------ design « verre dépoli »

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    /** Accent « menthe glacée » : la signature visuelle de Ma Cam. */
    private val accent = 0xFF7FE8D6.toInt()
    private val accentInk = 0xFF0B1F1C.toInt()
    private var settingsOpen = false
    private lateinit var gearBtn: Button
    private lateinit var panelGlass: GlassView
    private lateinit var infoGlass: GlassView
    private lateinit var thumb: ImageView
    private lateinit var focusRing: View
    private lateinit var flash: View
    private var thumbUri: android.net.Uri? = null
    private var busyPulse: android.animation.ObjectAnimator? = null
    private var progress = -1f
    private var hdr = true
    private lateinit var flipBtn: Button
    private lateinit var curtain: View
    private lateinit var imageHeader: TextView
    private lateinit var aidsHeader: TextView
    private lateinit var hdrBtn: Button
    private lateinit var authBtn: Button
    private lateinit var styleBtn: Button
    private lateinit var clapBtn: Button
    private lateinit var stabBar: View
    private lateinit var stabFill: View
    private var stabMeter: StabilityMeter? = null
    private var iconRotation = 0f
    private val prefs by lazy { getSharedPreferences("macam", MODE_PRIVATE) }
    private val blurHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Panneau translucide : voile clair + liseré lumineux, comme une plaque de verre sur l'aperçu. */
    private fun glass(radiusDp: Int, fill: Int = 0x1FFFFFFF, stroke: Int = 0x3DFFFFFF, topOnly: Boolean = false) =
        GradientDrawable().apply {
            // Verre de pastille : un léger dégradé vertical imite la lumière qui glisse sur la surface.
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(lighten(fill), fill)
            setStroke(1.dp, stroke)
            val r = radiusDp.dp.toFloat()
            if (topOnly) cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else cornerRadius = r
        }

    private fun lighten(c: Int): Int {
        val a = (c ushr 24); if (a == 0) return c
        return ((minOf(255, a + 0x18)) shl 24) or (c and 0xFFFFFF)
    }

    private fun buildUi() {
        surfaceView = SurfaceView(this).apply { holder.addCallback(this@CameraActivity) }
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f; letterSpacing = 0.04f
            setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        // Les zooms partagent une même piste (connectivité unifiée) : on comprend qu'ils forment un choix unique.
        lensRow = row().apply { background = GradientDrawable().apply { cornerRadius = 26.dp.toFloat(); setColor(0x26000000) } }
        modeRow = row()

        expoBtn = chip("Exposition", R.drawable.ic_exposure) { cycleExposure() }
        focusBtn = chip("Netteté", R.drawable.ic_focus) { toggleFocus() }
        wbBtn = chip("Blancs", R.drawable.ic_wb) { cycleWb() }
        eisBtn = chip("Stab.", R.drawable.ic_stab) {
            val c = cam ?: return@chip
            c.settings.eis = !c.settings.eis
            if (c.settings.eis && c.settings.gyroLog) { c.settings.gyroLog = false; info.text = "Stabilisation du téléphone · journal Atelier coupé" }
            c.applySettings(); refreshButtons()
        }
        rateBtn = chip("Qualité", R.drawable.ic_bitrate) { cycleBitrate() }
        // Les deux stabilisations s'excluent : on choisit celle du téléphone OU celle de l'Atelier.
        gyroBtn = chip("Stab. Atelier", R.drawable.ic_gyro) {
            val c = cam ?: return@chip
            c.settings.gyroLog = !c.settings.gyroLog
            if (c.settings.gyroLog && c.settings.eis) { c.settings.eis = false; c.applySettings(); info.text = "Stabilisation Atelier · stabilisation du téléphone coupée" }
            refreshButtons()
        }
        val diagBtn = chip("Diagnostic", R.drawable.ic_diag) { startActivity(Intent(this, DiagnosticActivity::class.java)) }
        hdrBtn = chip("HDR", R.drawable.ic_exposure) { toggleHdr() }
        settingsRow = row().apply { listOf(hdrBtn, expoBtn, focusBtn, wbBtn, eisBtn, rateBtn).forEach { addView(it) } }
        zebraBtn = chip("Zébras", R.drawable.ic_zebra) { cam?.let { it.tools.zebra = !it.tools.zebra; it.applySettings() }; refreshButtons() }
        peakBtn = chip("Peaking", R.drawable.ic_peaking) { cam?.let { it.tools.peaking = !it.tools.peaking; it.applySettings() }; refreshButtons() }
        falseBtn = chip("Couleurs", R.drawable.ic_falsecolor) { cam?.let { it.tools.falseColor = !it.tools.falseColor; it.applySettings() }; refreshButtons() }
        flatBtn = chip("Profil Log", R.drawable.ic_flat) { cam?.let { it.settings.flat = !it.settings.flat; it.applySettings() }; refreshButtons() }
        val studioBtn = chip("Atelier", R.drawable.ic_studio) { openStudio(null) }
        clapBtn = chip("Clap", R.drawable.ic_clap) {
            cam?.let { it.settings.syncClap = !it.settings.syncClap; if (it.settings.syncClap) info.text = "Clap sonore au début de chaque vidéo (synchro multicam)" }
            refreshButtons()
        }
        toolsRow = row().apply { listOf(studioBtn, zebraBtn, peakBtn, falseBtn, flatBtn, gyroBtn, clapBtn).forEach { addView(it) } }
        dngBtn = chip("RAW", R.drawable.ic_raw) { photo?.let { it.saveDng = !it.saveDng }; refreshButtons() }
        styleBtn = chip("Mon style", R.drawable.ic_flat) {
            com.macam.Pro.gate(this, "Style personnel") {
                if (prefs.getString("style", null) == null) importStyle()
                else {
                    val on = !prefs.getBoolean("styleOn", true)
                    prefs.edit().putBoolean("styleOn", on).apply()
                    photo?.style = if (on) loadStyle() else null
                    info.text = if (on) "Mon style : ${loadStyle()?.describe()}" else "Rendu Ma Cam"
                    refreshButtons()
                }
            }
        }.apply {
            tooltipText = "Mon style (appui long : importer des retouches Lightroom .xmp)"
            setOnLongClickListener { com.macam.Pro.gate(this@CameraActivity, "Style personnel") { importStyle() }; true }
        }
        authBtn = chip("Authentifier", R.drawable.ic_shield) {
            com.macam.Pro.gate(this, "Authenticité") {
                val on = !prefs.getBoolean("authenticate", false)
                prefs.edit().putBoolean("authenticate", on).apply()
                photo?.authenticate = on
                if (on) info.text = "Photos signées (Content Credentials) · clé dans la puce sécurisée"
                refreshButtons()
            }
        }
        photoRow = row().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(dngBtn); addView(authBtn); addView(styleBtn)
        }
        val tabIcons = mapOf(Tab.PHOTO to R.drawable.ic_photo, Tab.NIGHT to R.drawable.ic_night,
            Tab.PORTRAIT to R.drawable.ic_portrait, Tab.VIDEO to R.drawable.ic_video)
        tabRow = row().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            background = GradientDrawable().apply { cornerRadius = 24.dp.toFloat(); setColor(0x33000000) }
            for (t in Tab.entries) addView(chip(t.label, tabIcons[t]) { switchTab(t) })
        }

        evBar = slider(-24, 24) { v -> cam?.let { it.settings.evSteps = v; it.applySettings() } }
        isoBar = slider(0, 100) { v -> cam?.let { it.settings.iso = isoFromBar(v, it); it.applySettings() } }
        shutterBar = slider(0, shutters.size - 1) { v -> cam?.let { it.settings.exposureNs = 1_000_000_000L / shutters[v]; it.applySettings() } }
        focusBar = slider(0, 100) { v -> cam?.let { it.settings.focusDiopters = it.lens.minFocusDiopters * v / 100f; it.applySettings() } }
        manualPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(20.dp, 0, 20.dp, 0)
            addLabeled("Correction EV", evBar); addLabeled("ISO", isoBar)
            addLabeled("Obturation", shutterBar); addLabeled("Mise au point (gauche = infini)", focusBar)
        }

        recordBtn = Button(this).apply {
            text = ""; contentDescription = "Déclencheur"
            setOnClickListener { shutter() }
        }
        styleShutter()
        overlay = ImageView(this).apply { scaleType = ImageView.ScaleType.MATRIX }

        gearBtn = Button(this).apply {
            contentDescription = "Réglages"; stateListAnimator = null; text = ""
            val d = getDrawable(R.drawable.ic_tune)!!.mutate().apply { setBounds(0, 0, 22.dp, 22.dp) }
            setCompoundDrawablesRelative(d, null, null, null); setPadding(11.dp, 0, 0, 0)
            background = glass(23)
            tooltipText = "Réglages (appui long : diagnostic)"
            setOnLongClickListener { startActivity(Intent(this@CameraActivity, DiagnosticActivity::class.java)); true }
            setOnClickListener {
                settingsOpen = !settingsOpen
                prefs.edit().putBoolean("settingsOpen", settingsOpen).apply()
                refreshButtons()
            }
        }
        // Vignette de la dernière capture (convention des applis photo) : un toucher l'ouvre.
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = glass(23); clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) }
            }
            contentDescription = "Dernière capture"; tooltipText = "Ouvrir la dernière capture"
            setOnClickListener { openLastCapture() }
            setOnLongClickListener { openStudio(thumbUri?.takeIf { it.toString().contains("video") }); true }
        }
        focusRing = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(2.dp, accent) }
            alpha = 0f; isClickable = false
        }
        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f; isClickable = false }
        curtain = View(this).apply { setBackgroundColor(0xFF050607.toInt()); alpha = 0f; isClickable = false; isFocusable = false }
        // Titres de section (région commune, chunking) dans les réglages dépliés.
        imageHeader = sectionHeader("IMAGE"); aidsHeader = sectionHeader("AIDES AU TOURNAGE")
        // Caméra avant : bouton « retourner » (modèle mental courant), plus mélangée aux zooms.
        flipBtn = Button(this).apply {
            contentDescription = "Changer de caméra"; tooltipText = "Caméra avant / arrière"; stateListAnimator = null; text = ""
            val d = getDrawable(R.drawable.ic_flip)!!.mutate().apply { setBounds(0, 0, 22.dp, 22.dp) }
            setCompoundDrawablesRelative(d, null, null, null); setPadding(11.dp, 0, 0, 0)
            background = glass(23)
            setOnClickListener { flipCamera() }
        }
        val shutterRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(thumb, LinearLayout.LayoutParams(46.dp, 46.dp))
            addView(recordBtn, LinearLayout.LayoutParams(76.dp, 76.dp).apply { setMargins(36.dp, 0, 36.dp, 0) })
            addView(flipBtn, LinearLayout.LayoutParams(46.dp, 46.dp))
        }

        // Carte de verre flottante (détachée des bords) qui floute réellement l'aperçu situé derrière.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12.dp, 0, 12.dp)
            addView(scroll(lensRow).center()); addView(scroll(modeRow).center())
            addView(imageHeader); addView(scroll(settingsRow))
            addView(aidsHeader); addView(scroll(toolsRow)); addView(scroll(photoRow).center())
            addView(manualPanel)
            addView(shutterRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(0, 8.dp, 0, 10.dp) })
            addView(tabRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        panelGlass = GlassView(this, 30.dp.toFloat()).apply { addView(content) }
        // Jauge de stabilité (Stab. Atelier) : fine barre sous l'info.
        stabFill = View(this).apply { background = GradientDrawable().apply { cornerRadius = 3.dp.toFloat(); setColor(accent) } }
        stabBar = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = 3.dp.toFloat(); setColor(0x33FFFFFF) }
            addView(stabFill, FrameLayout.LayoutParams(0, MATCH_PARENT))
            visibility = View.GONE; contentDescription = "Stabilité"
        }
        infoGlass = GlassView(this, 19.dp.toFloat(), tint = 0x40101418).apply { elevation = 4.dp.toFloat(); addView(info) }
        Backdrop.source = surfaceView
        installGestures()

        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(0xFF050607.toInt())
            addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
            addView(this@CameraActivity.overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
            addView(flash, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(stabBar, FrameLayout.LayoutParams(140.dp, 6.dp, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 58.dp })
            addView(focusRing, FrameLayout.LayoutParams(72.dp, 72.dp))
            addView(infoGlass, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 12.dp })
            addView(panelGlass, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(10.dp, 0, 10.dp, 10.dp) })
            addView(gearBtn, FrameLayout.LayoutParams(46.dp, 46.dp, Gravity.TOP or Gravity.END).apply { setMargins(0, 8.dp, 12.dp, 0) })
            addView(curtain, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
        layoutPreview(Size(1440, 1080))
    }

    /**
     * Remplit l'interface avec des valeurs d'exemple, sans caméra, pour produire des captures du design
     * (utilisé par le test DesignScreenshotTest ; l'image fournie remplace l'aperçu en direct).
     */
    @androidx.annotation.VisibleForTesting
    internal fun showDemo(t: Tab, scene: android.graphics.Bitmap, status: String) {
        tab = t
        layoutPreview(if (t == Tab.VIDEO) Size(1920, 1080) else Size(1440, 1080))
        overlay.scaleType = ImageView.ScaleType.CENTER_CROP
        overlay.setImageBitmap(scene)
        settingsOpen = t == Tab.VIDEO || t == Tab.PHOTO
        // Sans caméra, le flou part de l'image d'exemple (même cadrage que l'aperçu).
        val pw = resources.displayMetrics.widthPixels
        val ph = pw * (if (t == Tab.VIDEO) 1920 else 1440) / 1080
        val small = android.graphics.Bitmap.createBitmap(pw / 8, ph / 8, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(small).apply {
            val sc = maxOf(small.width.toFloat() / scene.width, small.height.toFloat() / scene.height)
            translate((small.width - scene.width * sc) / 2, (small.height - scene.height * sc) / 2); scale(sc, sc)
            drawBitmap(scene, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        Backdrop.update(small)
        thumb.setImageBitmap(circle(small))
        info.text = status
        for (i in 0 until tabRow.childCount) (tabRow.getChildAt(i) as Button).selected(Tab.entries[i] == t)
        lensRow.removeAllViews(); modeRow.removeAllViews()
        if (t != Tab.PORTRAIT) {
            for (l in listOf("0.6x", "1x", "5x")) {
                lensRow.addView(lensChip(l) {}.apply { selected(l == "1x") })
                if (l == "1x" && t == Tab.PHOTO) lensRow.addView(lensChip("2x") {}.apply { selected(false) })
            }
        }
        if (t == Tab.VIDEO) for (k in listOf("4K30", "4K60", "4K120", "1080p240"))
            modeRow.addView(chip(k) {}.apply { selected(k == "4K30") })
        hdrBtn.selected(true)
        imageHeader.visibility = if (t == Tab.VIDEO) View.VISIBLE else View.GONE
        aidsHeader.visibility = imageHeader.visibility
        if (t == Tab.NIGHT) { progress = 0.62f; styleShutter() }
        refreshButtons()
        expoBtn.text = "Expo auto"; focusBtn.text = "Netteté auto"; wbBtn.text = "Blancs auto"; rateBtn.text = "100 Mb/s"
        listOf(expoBtn, focusBtn, wbBtn, rateBtn, falseBtn, flatBtn, peakBtn, dngBtn).forEach { it.selected(false) }
        listOf(eisBtn, gyroBtn, zebraBtn).forEach { it.selected(true) }
        evBar.progress = 0
        for (i in 0 until manualPanel.childCount) manualPanel.getChildAt(i).visibility = if (i < 2) View.VISIBLE else View.GONE
    }

    /** Jauge active en vidéo avec « Stab. Atelier » : vert = rattrapable, ambre = limite, rouge = trop fort. */
    private fun setStabilityMeter(on: Boolean) {
        if (on && stabMeter == null && resumed) {
            val c = cam ?: return
            stabMeter = StabilityMeter(this) { r -> runOnUiThread { showStability(r) } }.also { m ->
                val phys = c.lens.chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
                val px = c.lens.chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)!!
                val fSensor = c.lens.focalMm / (phys.width / px.width)
                m.setOptics(fSensor.toDouble() * c.mode.size.width / px.width, c.mode.size.width, c.mode.size.height)
                m.start()
            }
            stabBar.visibility = View.VISIBLE
        } else if (!on) {
            stabMeter?.stop(); stabMeter = null
            if (::stabBar.isInitialized) stabBar.visibility = View.GONE
        }
    }

    private fun showStability(r: Float) {
        val w = (stabBar.width * r.coerceIn(0.02f, 1f)).toInt()
        stabFill.layoutParams = stabFill.layoutParams.apply { width = w }
        (stabFill.background as GradientDrawable).setColor(when { r < 0.6f -> accent; r < 1f -> 0xFFFFC94D.toInt(); else -> 0xFFFF5A4F.toInt() })
        stabBar.contentDescription = "Stabilité ${(100 - (r * 100).coerceAtMost(100f)).toInt()} %"
    }

    // ------------------------------------------------------------------ gestes et retours (UX)

    /** Déclenchement unique (bouton ou touches de volume), avec vibration et flash de confirmation. */
    private fun shutter() {
        if (!recordBtn.isEnabled) return
        recordBtn.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        if (tab == Tab.VIDEO) toggleRecording()
        else photo?.let { p ->
            if (p.busy) return
            flash.animate().cancel()
            flash.alpha = 0.55f
            flash.animate().alpha(0f).setDuration(220).start()
            p.capture(deviceOrientation)
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == android.view.KeyEvent.KEYCODE_CAMERA) && event.repeatCount == 0) { shutter(); return true }
        return super.onKeyDown(keyCode, event)
    }

    /** Toucher = mise au point ; pincer = zoom (vidéo) ; balayer = changer de mode. */
    private fun installGestures() {
        val tap = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { focusAt(e.x, e.y); return true }
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (kotlin.math.abs(vx) < 2 * kotlin.math.abs(vy) || kotlin.math.abs(vx) < 800) return false
                val i = Tab.entries.indexOf(tab) + if (vx < 0) 1 else -1
                if (i in Tab.entries.indices) switchTab(Tab.entries[i])
                return true
            }
        })
        val pinch = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                val c = cam ?: return false
                val r = c.zoomRange
                c.settings.zoom = (c.settings.zoom * d.scaleFactor).coerceIn(maxOf(1f, r.lower), r.upper)
                c.applySettings()
                info.text = "Zoom ×%.1f".format(Locale.US, c.settings.zoom)
                return true
            }
        })
        overlay.setOnTouchListener { _, e -> pinch.onTouchEvent(e); if (!pinch.isInProgress) tap.onTouchEvent(e); true }
    }

    private fun focusAt(x: Float, y: Float) {
        val v = overlay
        if (v.width == 0) return
        val lens = cam?.lens ?: photo?.lens ?: return
        val sensor = lens.chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val (u, w) = Metering.viewToSensor(x / v.width, y / v.height, sensor, lens.front)
        cam?.focusAt(u, w); photo?.focusAt(u, w)
        // Anneau de mise au point animé à l'endroit touché.
        focusRing.animate().cancel()
        focusRing.x = v.x + x - 36.dp; focusRing.y = v.y + y - 36.dp
        focusRing.scaleX = 1.4f; focusRing.scaleY = 1.4f; focusRing.alpha = 1f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(900).setDuration(300).start()
        }.start()
    }

    /** Pulsation du déclencheur pendant le traitement multi-images (l'appli travaille). */
    private fun setBusy(on: Boolean) {
        if (on && busyPulse == null) {
            busyPulse = android.animation.ObjectAnimator.ofFloat(recordBtn, "alpha", 1f, 0.35f).apply {
                duration = 600; repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE; start()
            }
        } else if (!on && busyPulse != null) { busyPulse?.cancel(); busyPulse = null; recordBtn.alpha = 1f }
    }

    /** L'interface reste en portrait ; les icônes pivotent pour rester lisibles téléphone tourné. */
    private fun rotateIcons(deg: Int) {
        val target = -(if (deg == 270) -90 else deg).toFloat()
        iconRotation = target
        val views = mutableListOf<View>(gearBtn, thumb)
        for (i in 0 until lensRow.childCount) views += lensRow.getChildAt(i)
        views.forEach { it.animate().rotation(target).setDuration(250).start() }
    }

    private fun showThumbnail(uri: android.net.Uri) {
        thumbUri = uri
        Thread {
            val bmp = runCatching { contentResolver.loadThumbnail(uri, Size(160, 160), null) }.getOrNull()
            runOnUiThread { if (bmp != null) { thumb.setImageBitmap(circle(bmp)); thumb.scaleX = 0.6f; thumb.scaleY = 0.6f
                thumb.animate().scaleX(1f).scaleY(1f).setDuration(220).start() } }
        }.start()
    }

    /** Au lancement : vignette de la dernière photo ou vidéo de Ma Cam. */
    private fun loadLatestThumbnail() = Thread {
        val found = listOf(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI).mapNotNull { base ->
            runCatching {
                contentResolver.query(base, arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DATE_ADDED),
                    "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("DCIM/MaCam%"),
                    "${android.provider.MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    if (c.moveToFirst()) android.content.ContentUris.withAppendedId(base, c.getLong(0)) to c.getLong(1) else null
                }
            }.getOrNull()
        }.maxByOrNull { it.second }
        found?.let { runOnUiThread { showThumbnail(it.first) } }
    }.start()

    /** Recadrage circulaire dans l'image elle-même (rond sur tous les appareils). */
    private fun circle(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val n = minOf(src.width, src.height)
        val out = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply { setTranslate(-(src.width - n) / 2f, -(src.height - n) / 2f) })
            }
        }
        android.graphics.Canvas(out).drawCircle(n / 2f, n / 2f, n / 2f, p)
        return out
    }

    // ------------------------------------------------------------------ style personnel

    private fun loadStyle() = prefs.getString("style", null)?.let { runCatching { com.macam.style.Style.fromJson(it) }.getOrNull() }

    /** Importe une ou plusieurs retouches Lightroom (.xmp) et en apprend le style moyen. */
    private fun importStyle() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }, REQ_STYLE)

    @Deprecated("API Activity simple")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_STYLE || data == null) return
        val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        val styles = uris.mapNotNull { u -> runCatching { com.macam.style.Style.parseXmp(contentResolver.openInputStream(u)!!.bufferedReader().readText()) }.getOrNull() }
        if (styles.isEmpty()) { Toast.makeText(this, "Aucun réglage Lightroom trouvé dans ces fichiers", Toast.LENGTH_LONG).show(); return }
        val s = com.macam.style.Style.average(styles)
        prefs.edit().putString("style", s.toJson()).putBoolean("styleOn", true).apply()
        photo?.style = s
        info.text = "Mon style (${s.sources} retouche${if (s.sources > 1) "s" else ""}) : ${s.describe()}"
        refreshButtons()
    }

    /** Atelier : stabilisation, débruitage et looks après le tournage. */
    private fun openStudio(video: android.net.Uri?) = com.macam.Pro.gate(this, "Atelier") {
        startActivity(Intent(this, com.macam.studio.StudioActivity::class.java).apply {
            data = video; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun openLastCapture() {
        val u = thumbUri ?: return Toast.makeText(this, "Aucune capture pour l'instant", Toast.LENGTH_SHORT).show()
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, "Aucune application pour ouvrir ce fichier", Toast.LENGTH_SHORT).show() }
    }

    /** Déclencheur dessiné : anneau blanc + disque (photo), disque rouge (vidéo), carré rouge (enregistrement). */
    private fun styleShutter() {
        if (!::recordBtn.isInitialized) return
        val video = tab == Tab.VIDEO
        val rec = cam?.recording == true
        val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22FFFFFF); setStroke(4.dp, Color.WHITE) }
        val core = GradientDrawable().apply {
            if (rec) { shape = GradientDrawable.RECTANGLE; cornerRadius = 6.dp.toFloat() } else shape = GradientDrawable.OVAL
            setColor(if (video) 0xFFFF3B30.toInt() else Color.WHITE)
        }
        val inset = if (rec) 24.dp else 9.dp
        val layers = mutableListOf<android.graphics.drawable.Drawable>(ring, core)
        if (!video && progress >= 0f) layers += ProgressArc(progress, accent, 4.dp.toFloat())
        recordBtn.background = android.graphics.drawable.LayerDrawable(layers.toTypedArray()).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
        recordBtn.alpha = if (recordBtn.isEnabled) 1f else 0.45f
    }

    private fun HorizontalScrollView.center() = apply {
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(10.dp, 4.dp, 10.dp, 4.dp)
    }
    /** Rangée défilante : les bords s'estompent pour signaler qu'il y a d'autres options. */
    private fun scroll(v: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(28.dp)
        addView(v)
    }

    /** Pastille de verre avec icône à trait fin. */
    private fun chip(label: String, icon: Int? = null, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setTextColor(Color.WHITE)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        background = glass(18)
        stateListAnimator = null
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(12.dp, 0, 14.dp, 0)
        icon?.let {
            val d = getDrawable(it)!!.mutate().apply { setBounds(0, 0, 18.dp, 18.dp) }
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = 6.dp
        }
        // Cible tactile ≥ 40 dp (+ marges = 48 dp), info-bulle à l'appui long, description pour TalkBack.
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, 40.dp).apply { setMargins(4.dp, 4.dp, 4.dp, 4.dp) }
        tooltipText = label; contentDescription = label
        setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); onClick() }
    }

    private fun Button.selected(on: Boolean) {
        background = if (on) glass(18, fill = accent, stroke = 0x66FFFFFF) else glass(18)
        val fg = if (on) accentInk else Color.WHITE
        setTextColor(fg)
        compoundDrawablesRelative[0]?.setTint(fg)
        alpha = if (isEnabled) 1f else 0.4f
    }

    private fun slider(min: Int, max: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.min = min; this.max = max
        progressTintList = android.content.res.ColorStateList.valueOf(accent)
        thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x55FFFFFF)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun LinearLayout.addLabeled(label: String, bar: SeekBar) {
        addView(TextView(context).apply { text = label; setTextColor(0xB3FFFFFF.toInt()); textSize = 11f; letterSpacing = 0.05f })
        addView(bar)
    }

    private fun refreshRows() {
        for (i in 0 until tabRow.childCount) (tabRow.getChildAt(i) as Button).selected(Tab.entries[i] == tab)
        photo?.let { p -> refreshPhotoRows(p); return }
        val c = cam ?: return
        lensRow.removeAllViews()
        val main = Lens.main(c.lenses)
        for (l in c.lenses.filter { it.front == c.lens.front }) {
            lensRow.addView(lensChip(zoomLabel(l, main)) { if (!c.recording) { c.start(l, variant(l, c.mode)); handlerRefresh() } }.apply { selected(l == c.lens) })
        }
        (lensRow.parent as View).visibility = if (lensRow.childCount > 1) View.VISIBLE else View.GONE
        // Loi de Hick : un seul choix par cadence ; le HDR est un interrupteur séparé.
        modeRow.removeAllViews()
        val shown = mutableSetOf<String>()
        for (m in Mode.entries) {
            val key = m.label.removeSuffix(" HDR")
            if (key in shown || (!c.lens.supports(m) && !c.lens.supports(twin(m)))) continue
            shown += key
            modeRow.addView(chip(key) { if (!c.recording) { c.start(c.lens, variant(c.lens, m)); handlerRefresh() } }
                .apply { selected(c.mode.label.removeSuffix(" HDR") == key) })
        }
    }

    private fun refreshPhotoRows(p: PhotoController) {
        lensRow.removeAllViews(); modeRow.removeAllViews()
        if (p.mode == PhotoMode.PORTRAIT) return
        val main = Lens.main(p.lenses)
        for (l in p.lenses.filter { it.front == p.lens.front }) {
            lensRow.addView(lensChip(zoomLabel(l, main)) { if (!p.busy) { p.start(l, p.mode, false); photoRefresh() } }
                .apply { selected(l == p.lens && !p.superRes) })
            // Zoom 2× haute définition : même forme que les autres zooms (similarité).
            if (l == main && p.mode == PhotoMode.PHOTO && p.profile.srScale > 0)
                lensRow.addView(lensChip("2x") { if (!p.busy) { p.start(main, p.mode, true); photoRefresh() } }
                    .apply { selected(p.superRes); tooltipText = "Zoom 2× haute définition (multi-images)" })
        }
        (lensRow.parent as View).visibility = if (lensRow.childCount > 1) View.VISIBLE else View.GONE
    }

    private fun zoomLabel(l: Lens, main: Lens) = "%.1fx".format(Locale.US, l.eqMm / main.eqMm).replace(".0x", "x")

    /** Mode jumeau (même cadence, HDR inversé). */
    private fun twin(m: Mode) = Mode.entries.firstOrNull { it.size == m.size && it.fps == m.fps && it.highSpeed == m.highSpeed && it.tenBit != m.tenBit } ?: m

    /** Variante du mode respectant l'interrupteur HDR quand l'objectif le permet. */
    private fun variant(l: Lens, m: Mode): Mode {
        val want = if (m.tenBit == hdr) m else twin(m)
        return if (l.supports(want)) want else m
    }

    private fun toggleHdr() {
        val c = cam ?: return
        if (c.recording) return
        hdr = !hdr
        prefs.edit().putBoolean("hdr", hdr).apply()
        c.start(c.lens, variant(c.lens, c.mode)); handlerRefresh()
    }

    private fun flipCamera() {
        cam?.let { c ->
            if (c.recording) return
            val target = if (c.lens.front) Lens.main(c.lenses) else c.lenses.firstOrNull { it.front } ?: return
            c.start(target, variant(target, c.mode)); handlerRefresh()
        }
        photo?.let { p ->
            if (p.busy) return
            val target = if (p.lens.front) Lens.main(p.lenses) else p.lenses.firstOrNull { it.front } ?: return
            p.start(target, p.mode, false); photoRefresh()
        }
        flipBtn.animate().rotationBy(180f).setDuration(250).start()
    }

    private fun sectionHeader(t: String) = TextView(this).apply {
        text = t; textSize = 10f; letterSpacing = 0.12f; setTextColor(0x99FFFFFF.toInt())
        setPadding(22.dp, 8.dp, 0, 2.dp)
    }

    /** Pastille ronde pour le choix d'objectif (0.6x, 1x, 5x…). */
    private fun lensChip(label: String, onClick: () -> Unit) = chip(label, null, onClick).apply {
        setPadding(0, 0, 0, 0); textSize = 11f
        layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp).apply { setMargins(5.dp, 2.dp, 5.dp, 2.dp) }
        background = glass(22)
        rotation = iconRotation
    }

    private fun photoRefresh() { photo?.handler?.post { runOnUiThread { refreshRows(); refreshButtons() } } }

    /** Rafraîchit l'interface une fois que la caméra a appliqué le changement. */
    private fun handlerRefresh() { cam?.handler?.post { runOnUiThread { refreshRows(); refreshButtons() } } }

    private fun refreshButtons() {
        val video = tab == Tab.VIDEO
        val vis = { b: Boolean -> if (b) View.VISIBLE else View.GONE }
        (modeRow.parent as View).visibility = vis(video)
        // Réglages détaillés repliés derrière le bouton ⚙ pour laisser l'image respirer.
        for (r in listOf(settingsRow, toolsRow)) (r.parent as View).visibility = vis(video && settingsOpen)
        imageHeader.visibility = vis(video && settingsOpen); aidsHeader.visibility = vis(video && settingsOpen)
        (photoRow.parent as View).visibility = vis(!video && settingsOpen)
        manualPanel.visibility = vis(video && settingsOpen)
        gearBtn.selected(settingsOpen)
        styleShutter()
        photo?.let { p ->
            dngBtn.selected(p.saveDng); dngBtn.isEnabled = p.mode != PhotoMode.PORTRAIT
            authBtn.selected(p.authenticate)
            styleBtn.selected(p.style != null)
            recordBtn.isEnabled = !p.busy
            setBusy(p.busy)
        }
        if (photo == null) setBusy(false)
        // Enregistrement : l'info devient rouge, visible d'un coup d'œil.
        infoGlass.tint = if (cam?.recording == true) 0xB3C62828.toInt() else 0x40101418
        val c = cam ?: run { setStabilityMeter(false); return }
        setStabilityMeter(c.settings.gyroLog)
        clapBtn.selected(c.settings.syncClap)
        hdrBtn.selected(c.mode.tenBit); hdrBtn.isEnabled = c.lens.supports(twin(c.mode)) || c.mode.tenBit
        zebraBtn.selected(c.tools.zebra); peakBtn.selected(c.tools.peaking); falseBtn.selected(c.tools.falseColor)
        flatBtn.selected(c.settings.flat); flatBtn.isEnabled = !c.mode.tenBit && !c.mode.highSpeed
        val st = c.settings
        val hs = c.mode.highSpeed
        expoBtn.text = when (st.exposure) { Exposure.AUTO -> "Expo auto"; Exposure.LOCK -> "Expo verrouillée"; Exposure.MANUAL -> "Expo manuelle" }
        expoBtn.selected(st.exposure != Exposure.AUTO)
        focusBtn.text = if (c.lens.fixedFocus) "Netteté fixe" else if (st.manualFocus) "Netteté manuelle" else "Netteté auto"
        focusBtn.selected(st.manualFocus); focusBtn.isEnabled = !c.lens.fixedFocus && !hs
        wbBtn.text = wbModes[wbIndex].first; wbBtn.selected(wbIndex != 0)
        eisBtn.text = if (st.eis) "Stabilisation" else "Stab. coupée"; eisBtn.selected(!st.eis)
        rateBtn.text = "${st.bitrateMbps} Mb/s"
        gyroBtn.text = "Stab. Atelier"; gyroBtn.selected(st.gyroLog)
        listOf(expoBtn, wbBtn, eisBtn).forEach { it.isEnabled = !hs }

        val manual = st.exposure == Exposure.MANUAL && !hs
        evBar.visibility = if (st.exposure == Exposure.AUTO && !hs) View.VISIBLE else View.GONE
        isoBar.visibility = if (manual) View.VISIBLE else View.GONE
        shutterBar.visibility = if (manual) View.VISIBLE else View.GONE
        focusBar.visibility = if (st.manualFocus && !hs) View.VISIBLE else View.GONE
        for (i in 0 until manualPanel.childCount step 2) {
            manualPanel.getChildAt(i).visibility = manualPanel.getChildAt(i + 1).visibility
        }
        evBar.progress = st.evSteps
        isoBar.progress = isoToBar(st.iso, c)
        shutterBar.progress = shutters.indices.minByOrNull { kotlin.math.abs(1_000_000_000L / shutters[it] - st.exposureNs) } ?: 0
        focusBar.progress = if (c.lens.minFocusDiopters > 0) (st.focusDiopters / c.lens.minFocusDiopters * 100).roundToInt() else 0
    }

    // Échelle logarithmique pour l'ISO.
    private fun isoFromBar(v: Int, c: CameraController): Int {
        val r = c.lens.isoRange
        return (r.lower * (r.upper.toDouble() / r.lower).pow(v / 100.0)).roundToInt()
    }
    private fun isoToBar(iso: Int, c: CameraController): Int {
        val r = c.lens.isoRange
        return (ln(iso.toDouble() / r.lower) / ln(r.upper.toDouble() / r.lower) * 100).roundToInt().coerceIn(0, 100)
    }

    private fun cycleExposure() {
        val c = cam ?: return
        val st = c.settings
        st.exposure = Exposure.entries[(st.exposure.ordinal + 1) % Exposure.entries.size]
        if (st.exposure == Exposure.MANUAL) c.lastInfo?.let { st.iso = it.iso; st.exposureNs = it.exposureNs }
        c.applySettings(); refreshButtons()
    }

    private fun toggleFocus() {
        val c = cam ?: return
        c.settings.manualFocus = !c.settings.manualFocus
        if (c.settings.manualFocus) c.lastInfo?.let { c.settings.focusDiopters = it.focusDiopters }
        c.applySettings(); refreshButtons()
    }

    private fun cycleWb() {
        val c = cam ?: return
        wbIndex = (wbIndex + 1) % wbModes.size
        val mode = wbModes[wbIndex].second
        c.settings.awbLock = mode == -1
        c.settings.awbMode = if (mode == -1) CameraMetadata.CONTROL_AWB_MODE_AUTO else mode
        c.applySettings(); refreshButtons()
    }

    private fun cycleBitrate() {
        val c = cam ?: return
        val opts = listOf(50, 75, 100)
        c.settings.bitrateMbps = opts[(opts.indexOf(c.settings.bitrateMbps) + 1) % opts.size]
        c.reconfigure() // l'encodeur préparé doit être recréé avec le nouveau débit
        refreshButtons()
    }

    private fun showInfo(i: LiveInfo) {
        val c = cam ?: return
        val shutter = if (i.exposureNs > 0) "1/${(1e9 / i.exposureNs).roundToInt()}" else "-"
        val focus = if (c.lens.fixedFocus) "fixe" else if (i.focusDiopters <= 0.01f) "∞" else "%.2f m".format(Locale.US, 1 / i.focusDiopters)
        val rec = if (c.recording) {
            val s = (SystemClock.elapsedRealtime() - recordStart) / 1000
            "  ● REC %02d:%02d".format(Locale.US, s / 60, s % 60)
        } else ""
        // Mémoire de travail : les réglages non automatiques restent visibles même panneau fermé.
        val st = c.settings
        val badges = buildList {
            if (st.exposure == Exposure.LOCK) add("Expo 🔒")
            if (st.exposure == Exposure.MANUAL) add("Expo M")
            if (st.manualFocus) add("Netteté M")
            if (st.flat) add("Log")
            if (st.zoom > 1.05f) add("×%.1f".format(Locale.US, st.zoom))
        }.joinToString(" · ")
        info.text = buildString {
            append(c.mode.label)
            append("   ISO ${i.iso} · $shutter · $focus")
            if (badges.isNotEmpty()) append("   $badges")
            append(rec)
        }
    }

    private fun toggleRecording() {
        val c = cam ?: return
        recordBtn.isEnabled = false
        if (!c.recording) {
            val back = !c.lens.front
            val sensor = c.lens.chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val hint = if (back) (sensor + deviceOrientation) % 360 else (sensor - deviceOrientation + 360) % 360
            c.startRecording(hint) { ok -> runOnUiThread { if (ok) recordStart = SystemClock.elapsedRealtime(); setRecordingUi(ok) } }
        } else {
            c.stopRecording { msg -> runOnUiThread {
                setRecordingUi(false)
                // Fin positive et discrète (règle de l'apogée) ; le détail reste disponible en appui long.
                info.text = if (c.lastSaved != null) "✓ Vidéo enregistrée" else msg
                info.tooltipText = msg
                c.lastSaved?.let { showThumbnail(it) }
            } }
        }
    }

    // ------------------------------------------------------------------ journal de plantage

    private val crashFile get() = java.io.File(filesDir, "crash.txt")

    /** Temps entre le lancement du processus et la première image (barrière : < 1 s). */
    private fun recordStartup() {
        if (startupRecorded) return
        startupRecorded = true
        val ms = android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()
        val hist = (prefs.getString("startupMs", "") ?: "").split(',').filter { it.isNotBlank() }.takeLast(9) + ms.toString()
        prefs.edit().putString("startupMs", hist.joinToString(",")).apply()
    }
    private var startupRecorded = false

    private companion object {
        @Volatile var watchdogStarted = false
        const val REQ_STYLE = 41
    }

    /**
     * Détecteur de blocage (ANR) : si le fil principal ne répond plus pendant 5 s, sa pile d'appels est
     * enregistrée et affichée au lancement suivant, comme un plantage.
     */
    private fun startAnrWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        val main = android.os.Handler(mainLooper)
        Thread({
            var tick = 0L
            while (true) {
                val seen = java.util.concurrent.atomic.AtomicLong(-1)
                val sent = ++tick
                main.post { seen.set(sent) }
                Thread.sleep(5000)
                if (seen.get() != sent && !isFinishing) {
                    runCatching {
                        crashFile.writeText("BLOCAGE (ANR) > 5 s | Ma Cam ${packageManager.getPackageInfo(packageName, 0).versionName} | " +
                            "${android.os.Build.MODEL}\n" + mainLooper.thread.stackTrace.joinToString("\n") { "\tat $it" })
                    }
                }
            }
        }, "anr-watchdog").apply { isDaemon = true; start() }
    }

    private fun installCrashLog() {
        startAnrWatchdog()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                crashFile.writeText("Ma Cam ${packageManager.getPackageInfo(packageName, 0).versionName} | " +
                    "${android.os.Build.MODEL} | Android ${android.os.Build.VERSION.RELEASE}\nFil : ${t.name}\n" +
                    android.util.Log.getStackTraceString(e))
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun showPreviousCrash() {
        val f = crashFile
        if (!f.exists()) return
        val text = f.readText(); f.delete()
        android.app.AlertDialog.Builder(this)
            .setTitle("L'application a planté la dernière fois")
            .setMessage(text.take(1500))
            .setPositiveButton("Partager") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, "Partager le rapport de plantage"))
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun setRecordingUi(on: Boolean) {
        if (!::recordBtn.isInitialized) return
        recordBtn.isEnabled = true
        styleShutter()
        for (i in 0 until lensRow.childCount) lensRow.getChildAt(i).isEnabled = !on
        for (i in 0 until modeRow.childCount) modeRow.getChildAt(i).isEnabled = !on
        rateBtn.isEnabled = !on; gyroBtn.isEnabled = !on
    }
}

/** Arc de progression autour du déclencheur (gradation du but). */
private class ProgressArc(private val f: Float, color: Int, private val w: Float) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = w; this.color = color; strokeCap = android.graphics.Paint.Cap.ROUND
    }
    override fun draw(c: android.graphics.Canvas) {
        val r = android.graphics.RectF(bounds).apply { inset(w / 2, w / 2) }
        c.drawArc(r, -90f, 360f * f.coerceIn(0.02f, 1f), false, p)
    }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
    @Deprecated("") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
