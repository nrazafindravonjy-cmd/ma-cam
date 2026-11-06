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
                if (o != ORIENTATION_UNKNOWN) deviceOrientation = ((o + 45) / 90 * 90) % 360
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        installCrashLog()
        buildUi()
        showPreviousCrash()
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
        val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else startCamera()
    }

    override fun onPause() {
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
            cam!!.start()
        } else {
            val mode = when (tab) { Tab.NIGHT -> PhotoMode.NIGHT; Tab.PORTRAIT -> PhotoMode.PORTRAIT; else -> PhotoMode.PHOTO }
            photo = PhotoController(this, ::awaitPreviewSurface,
                onStatus = { t -> runOnUiThread { info.text = t; refreshButtons() } },
                onError = toast)
            photo!!.start(m = mode)
            showOverlay(null)
        }
        refreshRows()
        refreshButtons()
        photo?.handler?.post { runOnUiThread { refreshRows(); refreshButtons() } }
    }

    private fun switchTab(t: Tab) {
        if (t == tab || cam?.recording == true || photo?.busy == true) return
        cam?.release(); cam = null
        photo?.release(); photo = null
        tab = t
        startCamera()
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
        val lp = FrameLayout.LayoutParams(w, w * size.width / size.height, Gravity.CENTER)
        surfaceView.layoutParams = lp
        overlay.layoutParams = FrameLayout.LayoutParams(lp.width, lp.height, Gravity.CENTER)
    }

    // ------------------------------------------------------------------ interface

    private fun buildUi() {
        surfaceView = SurfaceView(this).apply { holder.addCallback(this@CameraActivity) }
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setPadding(24, 12, 24, 12)
            setBackgroundColor(0x88000000.toInt()); typeface = android.graphics.Typeface.MONOSPACE
        }
        lensRow = row(); modeRow = row()

        expoBtn = chip("Expo auto") { cycleExposure() }
        focusBtn = chip("MAP auto") { toggleFocus() }
        wbBtn = chip("BdB auto") { cycleWb() }
        eisBtn = chip("Stab. ON") { val c = cam ?: return@chip; c.settings.eis = !c.settings.eis; c.applySettings(); refreshButtons() }
        rateBtn = chip("100 Mb/s") { cycleBitrate() }
        gyroBtn = chip("Gyro ON") { val c = cam ?: return@chip; c.settings.gyroLog = !c.settings.gyroLog; refreshButtons() }
        val diagBtn = chip("Diagnostic") { startActivity(Intent(this, DiagnosticActivity::class.java)) }
        settingsRow = row().apply { listOf(expoBtn, focusBtn, wbBtn, eisBtn, rateBtn, gyroBtn).forEach { addView(it) } }
        zebraBtn = chip("Zébras") { cam?.let { it.tools.zebra = !it.tools.zebra; it.applySettings() }; refreshButtons() }
        peakBtn = chip("Peaking") { cam?.let { it.tools.peaking = !it.tools.peaking; it.applySettings() }; refreshButtons() }
        falseBtn = chip("Fausses couleurs") { cam?.let { it.tools.falseColor = !it.tools.falseColor; it.applySettings() }; refreshButtons() }
        flatBtn = chip("Profil plat") { cam?.let { it.settings.flat = !it.settings.flat; it.applySettings() }; refreshButtons() }
        toolsRow = row().apply { listOf(zebraBtn, peakBtn, falseBtn, flatBtn, diagBtn).forEach { addView(it) } }
        dngBtn = chip("DNG") { photo?.let { it.saveDng = !it.saveDng }; refreshButtons() }
        photoRow = row().apply { addView(dngBtn); addView(chip("Diagnostic") { startActivity(Intent(this@CameraActivity, DiagnosticActivity::class.java)) }) }
        tabRow = row().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            for (t in Tab.entries) addView(chip(t.label) { switchTab(t) })
        }

        evBar = slider(-24, 24) { v -> cam?.let { it.settings.evSteps = v; it.applySettings() } }
        isoBar = slider(0, 100) { v -> cam?.let { it.settings.iso = isoFromBar(v, it); it.applySettings() } }
        shutterBar = slider(0, shutters.size - 1) { v -> cam?.let { it.settings.exposureNs = 1_000_000_000L / shutters[v]; it.applySettings() } }
        focusBar = slider(0, 100) { v -> cam?.let { it.settings.focusDiopters = it.lens.minFocusDiopters * v / 100f; it.applySettings() } }
        manualPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 0, 24, 0)
            addLabeled("Correction EV", evBar); addLabeled("ISO", isoBar)
            addLabeled("Obturation", shutterBar); addLabeled("Mise au point (gauche = infini)", focusBar)
        }

        recordBtn = Button(this).apply {
            text = "●"; textSize = 30f; setTextColor(Color.RED)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            setOnClickListener { if (tab == Tab.VIDEO) toggleRecording() else photo?.capture(deviceOrientation) }
        }
        overlay = ImageView(this).apply { scaleType = ImageView.ScaleType.MATRIX }

        val previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
            addView(this@CameraActivity.overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
            addView(info, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101010.toInt())
            addView(scroll(lensRow)); addView(scroll(modeRow)); addView(scroll(settingsRow))
            addView(scroll(toolsRow)); addView(scroll(photoRow))
            addView(manualPanel)
            addView(recordBtn, LinearLayout.LayoutParams(180, 180).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, 12, 0, 8) })
            addView(scroll(tabRow).apply { isFillViewport = true }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 16 })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Color.BLACK)
            addView(previewFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        layoutPreview(Size(1920, 1080))
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(12, 6, 12, 6) }
    private fun scroll(v: View) = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(v) }

    private fun chip(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = 40f; setColor(0xFF2A2A2A.toInt()) }
        minWidth = 0; minimumWidth = 0; setPadding(28, 0, 28, 0)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, 96).apply { setMargins(6, 0, 6, 0) }
        setOnClickListener { onClick() }
    }

    private fun Button.selected(on: Boolean) {
        (background as GradientDrawable).setColor(if (on) 0xFFFFC107.toInt() else 0xFF2A2A2A.toInt())
        setTextColor(if (on) Color.BLACK else Color.WHITE)
    }

    private fun slider(min: Int, max: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.min = min; this.max = max
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun LinearLayout.addLabeled(label: String, bar: SeekBar) {
        addView(TextView(context).apply { text = label; setTextColor(Color.LTGRAY); textSize = 11f })
        addView(bar)
    }

    private fun refreshRows() {
        for (i in 0 until tabRow.childCount) (tabRow.getChildAt(i) as Button).selected(Tab.entries[i] == tab)
        photo?.let { p -> refreshPhotoRows(p); return }
        val c = cam ?: return
        lensRow.removeAllViews()
        val main = c.lenses.firstOrNull { !it.front && it.hasOis } ?: c.lenses.first()
        for (l in c.lenses) {
            val label = if (l.front) "Avant" else "%.1fx".format(Locale.US, l.eqMm / main.eqMm).replace(".0x", "x")
            lensRow.addView(chip(label) { if (!c.recording) { c.start(l, c.mode); handlerRefresh() } }.apply { selected(l == c.lens) })
        }
        modeRow.removeAllViews()
        for (m in Mode.entries) {
            if (!c.lens.supports(m)) continue
            modeRow.addView(chip(m.label) { if (!c.recording) { c.start(c.lens, m); handlerRefresh() } }.apply { selected(m == c.mode) })
        }
    }

    private fun refreshPhotoRows(p: PhotoController) {
        lensRow.removeAllViews(); modeRow.removeAllViews()
        if (p.mode == PhotoMode.PORTRAIT) return
        val main = p.lenses.firstOrNull { !it.front && it.hasOis } ?: p.lenses.first()
        for (l in p.lenses) {
            val label = if (l.front) "Avant" else "%.1fx".format(Locale.US, l.eqMm / main.eqMm).replace(".0x", "x")
            lensRow.addView(chip(label) { if (!p.busy) { p.start(l, p.mode, false); photoRefresh() } }
                .apply { selected(l == p.lens && !p.superRes) })
            // Zoom super-résolution 2× proposé juste après l'objectif principal.
            if (l == main && p.mode == PhotoMode.PHOTO)
                lensRow.addView(chip("2x SR") { if (!p.busy) { p.start(main, p.mode, true); photoRefresh() } }
                    .apply { selected(p.superRes) })
        }
    }

    private fun photoRefresh() { photo?.handler?.post { runOnUiThread { refreshRows(); refreshButtons() } } }

    /** Rafraîchit l'interface une fois que la caméra a appliqué le changement. */
    private fun handlerRefresh() { cam?.handler?.post { runOnUiThread { refreshRows(); refreshButtons() } } }

    private fun refreshButtons() {
        val video = tab == Tab.VIDEO
        for (r in listOf(modeRow, settingsRow, toolsRow)) (r.parent as View).visibility = if (video) View.VISIBLE else View.GONE
        (photoRow.parent as View).visibility = if (video) View.GONE else View.VISIBLE
        manualPanel.visibility = if (video) View.VISIBLE else View.GONE
        recordBtn.text = if (video) (if (cam?.recording == true) "■" else "●") else "◉"
        recordBtn.setTextColor(if (video) Color.RED else Color.BLACK)
        photo?.let { p ->
            dngBtn.selected(p.saveDng); dngBtn.isEnabled = p.mode != PhotoMode.PORTRAIT
            recordBtn.isEnabled = !p.busy
        }
        val c = cam ?: return
        zebraBtn.selected(c.tools.zebra); peakBtn.selected(c.tools.peaking); falseBtn.selected(c.tools.falseColor)
        flatBtn.selected(c.settings.flat); flatBtn.isEnabled = !c.mode.tenBit && !c.mode.highSpeed
        val st = c.settings
        val hs = c.mode.highSpeed
        expoBtn.text = when (st.exposure) { Exposure.AUTO -> "Expo auto"; Exposure.LOCK -> "Expo verrou"; Exposure.MANUAL -> "Expo manuelle" }
        expoBtn.selected(st.exposure != Exposure.AUTO)
        focusBtn.text = if (c.lens.fixedFocus) "MAP fixe" else if (st.manualFocus) "MAP manuelle" else "MAP auto"
        focusBtn.selected(st.manualFocus); focusBtn.isEnabled = !c.lens.fixedFocus && !hs
        wbBtn.text = wbModes[wbIndex].first; wbBtn.selected(wbIndex != 0)
        eisBtn.text = if (st.eis) "Stab. ON" else "Stab. OFF (Gyroflow)"; eisBtn.selected(!st.eis)
        rateBtn.text = "${st.bitrateMbps} Mb/s"
        gyroBtn.text = if (st.gyroLog) "Gyro ON" else "Gyro OFF"; gyroBtn.selected(st.gyroLog)
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
        info.text = "${c.mode.label}  ISO ${i.iso}  $shutter  MAP $focus$rec"
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
            c.stopRecording { msg -> runOnUiThread { setRecordingUi(false); Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
        }
    }

    // ------------------------------------------------------------------ journal de plantage

    private val crashFile get() = java.io.File(filesDir, "crash.txt")

    private fun installCrashLog() {
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
        recordBtn.text = if (on) "■" else "●"
        for (i in 0 until lensRow.childCount) lensRow.getChildAt(i).isEnabled = !on
        for (i in 0 until modeRow.childCount) modeRow.getChildAt(i).isEnabled = !on
        rateBtn.isEnabled = !on; gyroBtn.isEnabled = !on
    }
}
