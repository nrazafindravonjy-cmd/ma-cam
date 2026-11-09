package com.macam.studio

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.*

/**
 * Atelier vidéo : stabilisation gyroscopique, débruitage temporel et looks (LUT) appliqués après le tournage,
 * directement sur le téléphone.
 */
class StudioActivity : Activity() {

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    private val accent = 0xFF7FE8D6.toInt()
    private val accentInk = 0xFF0B1F1C.toInt()

    private var video: Uri? = null
    private var videoName = ""
    private var gyro: GyroLog? = null
    private var stab = 1          // 0 aucune, 1 douce, 2 forte
    private var denoise = 1       // 0 aucun, 1 léger, 2 fort
    private var lut: Lut? = null
    private var importedLut: Lut? = null
    private var studio: VideoStudio? = null

    private lateinit var title: TextView
    private lateinit var gyroInfo: TextView
    private lateinit var stabRow: LinearLayout
    private lateinit var dnRow: LinearLayout
    private lateinit var lookRow: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var go: Button
    private lateinit var pickBtn: Button
    private lateinit var multiInfo: TextView
    private lateinit var multiGo: Button
    /** Vidéos multicam et leur coupe de début (µs) pour commencer toutes au même instant. */
    private var multi: List<Pair<Uri, Long>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        intent.data?.let { load(it) } ?: pickVideo()
    }

    // ------------------------------------------------------------------ interface

    private fun glass(r: Int, fill: Int = 0x1FFFFFFF) = GradientDrawable().apply {
        cornerRadius = r.dp.toFloat(); setColor(fill); setStroke(1.dp, 0x3DFFFFFF)
    }

    private fun chip(label: String, on: Boolean, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; stateListAnimator = null
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = if (on) glass(18, accent) else glass(18)
        setTextColor(if (on) accentInk else Color.WHITE)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(16.dp, 0, 16.dp, 0)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, 42.dp).apply { setMargins(4.dp, 4.dp, 4.dp, 4.dp) }
        setOnClickListener { click() }
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 11f; letterSpacing = 0.12f; setTextColor(0x99FFFFFF.toInt()); setPadding(4.dp, 18.dp, 0, 4.dp)
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun buildUi() {
        title = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        gyroInfo = TextView(this).apply { textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); setPadding(4.dp, 2.dp, 0, 0) }
        stabRow = row(); dnRow = row(); lookRow = row()
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        status = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 10.dp, 0, 0) }
        go = Button(this).apply {
            text = "Traiter la vidéo"; isAllCaps = false; textSize = 16f; stateListAnimator = null
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = glass(26, accent); setTextColor(accentInk)
            setOnClickListener { if (studio == null) run() else studio?.cancelled = true }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 20.dp)
            background = glass(30, 0x26FFFFFF)
            addView(title); addView(gyroInfo)
            pickBtn = chip("Choisir une vidéo", false) { pickVideo() }.apply { layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, 40.dp).apply { topMargin = 10.dp } }
            addView(pickBtn)
            addView(header("STABILISATION")); addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(stabRow) })
            addView(header("DÉBRUITAGE")); addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(dnRow) })
            addView(header("LOOK")); addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; isHorizontalFadingEdgeEnabled = true; addView(lookRow) })
            addView(TextView(context).apply {
                text = "HDR conservé quand le téléphone le permet (sans look). Pour la stabilisation, filmez avec « Stab. Atelier »."
                textSize = 11f; setTextColor(0x80FFFFFF.toInt()); setPadding(4.dp, 14.dp, 0, 0)
            })
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1B1F24.toInt(), 0xFF070809.toInt()))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(14.dp, 18.dp, 14.dp, 24.dp)
                addView(TextView(context).apply { text = "ATELIER"; textSize = 12f; letterSpacing = 0.2f; setTextColor(accent); setPadding(6.dp, 0, 0, 10.dp) })
                addView(card)
                addView(go, LinearLayout.LayoutParams(MATCH_PARENT, 56.dp).apply { topMargin = 18.dp })
                addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
                addView(status)
                addView(TextView(context).apply { text = "MULTICAM"; textSize = 12f; letterSpacing = 0.2f; setTextColor(accent); setPadding(6.dp, 28.dp, 0, 10.dp) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20.dp, 18.dp, 20.dp, 18.dp)
                    background = glass(30, 0x26FFFFFF)
                    addView(TextView(context).apply {
                        text = "Plusieurs téléphones, même scène (iPhone compris) : Ma Cam aligne les vidéos sur leur son, " +
                            "puis exporte des copies qui commencent au même instant, à l'image près."
                        textSize = 13f; setTextColor(0xCCFFFFFF.toInt())
                    })
                    addView(chip("Synchroniser des vidéos…", false) { pickMulti() }.apply {
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, 42.dp).apply { topMargin = 12.dp }
                    })
                    multiInfo = TextView(context).apply { textSize = 13f; setTextColor(Color.WHITE); setPadding(4.dp, 10.dp, 0, 0) }
                    addView(multiInfo)
                    multiGo = chip("Exporter synchronisées", true) { exportMulti() }.apply {
                        visibility = View.GONE
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, 42.dp).apply { topMargin = 10.dp }
                    }
                    addView(multiGo)
                })
            })
        })
        refresh()
    }

    private fun refresh() {
        title.text = if (video == null) "Aucune vidéo" else videoName
        gyroInfo.text = when {
            video == null -> ""
            gyro?.eis == true -> "Journal trouvé, mais la stabilisation du téléphone était active : résultat moins précis"
            gyro != null -> "Journal gyroscope trouvé · ${gyro!!.tMs.size} mesures"
            else -> "Pas de journal gyroscope : stabilisation indisponible"
        }
        if (gyro == null) stab = 0
        pickBtn.text = if (video == null) "Choisir une vidéo" else "Choisir une autre vidéo"
        stabRow.removeAllViews()
        listOf("Aucune", "Douce", "Forte").forEachIndexed { i, l ->
            stabRow.addView(chip(l, stab == i) { stab = i; refresh() }.apply { isEnabled = gyro != null || i == 0; alpha = if (isEnabled) 1f else 0.4f })
        }
        dnRow.removeAllViews()
        listOf("Aucun", "Léger", "Fort").forEachIndexed { i, l -> dnRow.addView(chip(l, denoise == i) { denoise = i; refresh() }) }
        lookRow.removeAllViews()
        lookRow.addView(chip("Aucun", lut == null) { lut = null; refresh() })
        for (b in Lut.builtIns) lookRow.addView(chip(b.name, lut == b) { lut = b; refresh() })
        importedLut?.let { im -> lookRow.addView(chip(im.name, lut == im) { lut = im; refresh() }) }
        lookRow.addView(chip("Importer .cube…", false) { pickLut() })
        go.isEnabled = video != null; go.alpha = if (video != null) 1f else 0.5f
    }

    // ------------------------------------------------------------------ fichiers

    private fun pickVideo() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
    }, REQ_VIDEO)

    private fun pickLut() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
    }, REQ_LUT)

    private fun pickMulti() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }, REQ_MULTI)

    /** Aligne toutes les vidéos sur la première par leur son, puis calcule la coupe de chacune. */
    private fun syncMulti(uris: List<Uri>) {
        if (uris.size < 2) { multiInfo.text = "Choisissez au moins 2 vidéos."; return }
        multiInfo.text = "Analyse du son de ${uris.size} vidéos…"; multiGo.visibility = View.GONE
        Thread {
            val text = try {
                val envs = uris.mapIndexed { i, u ->
                    runOnUiThread { multiInfo.text = "Analyse du son ${i + 1}/${uris.size}…" }
                    val (pcm, rate) = MultiCam.decodeAudio(this, u) ?: throw IllegalStateException("${displayName(u)} n'a pas de son")
                    AudioSync.envelope(pcm, rate)
                }
                // Début de chaque vidéo sur la ligne de temps de la première (ms), corrigé de l'écart audio/vidéo du fichier.
                val matches = envs.map { AudioSync.align(envs[0], it) }
                val starts = uris.indices.map { i -> matches[i].offsetMs + MultiCam.firstVideoUs(this, uris[i]) / 1000 }
                val common = starts.max()
                multi = uris.indices.map { i -> uris[i] to (common - starts[i]) * 1000L }
                runOnUiThread { multiGo.visibility = View.VISIBLE }
                uris.indices.joinToString("\n") { i ->
                    val m = matches[i]
                    val conf = if (i == 0) "référence" else if (m.confidence < 0.15f) "⚠ correspondance faible" else "fiable"
                    "${displayName(uris[i])} : coupe ${"%.3f".format(java.util.Locale.US, (common - starts[i]) / 1000.0)} s ($conf)"
                }
            } catch (e: Exception) { "Synchronisation impossible : ${e.message}" }
            runOnUiThread { multiInfo.text = text }
        }.start()
    }

    /** Exporte chaque vidéo coupée au même instant, avec le débruitage et le look choisis (et sa stabilisation si possible). */
    private fun exportMulti() {
        val items = multi.ifEmpty { return }
        multiGo.isEnabled = false
        Thread {
            val done = ArrayList<String>()
            items.forEachIndexed { i, (u, trim) ->
                val name = displayName(u).substringBeforeLast('.')
                val g = if (stab > 0) gyroFor(name) else null
                val opts = VideoStudio.Options(stabilize = g != null, smoothingS = if (stab == 2) 1.2 else 0.5,
                    zoom = if (stab == 2) 1.15 else 1.08, denoise = when (denoise) { 1 -> 0.45f; 2 -> 0.75f; else -> 0f },
                    lut = lut, trimStartUs = trim)
                runCatching {
                    VideoStudio(this).process(u, g, opts, "${name}_sync") { f ->
                        runOnUiThread { multiInfo.text = "Export ${i + 1}/${items.size} · ${(f * 100).toInt()} %" }
                    }
                }.onSuccess { done += "${name}_sync.mp4" }
            }
            runOnUiThread {
                multiGo.isEnabled = true
                multiInfo.text = "✓ ${done.size}/${items.size} vidéos synchronisées dans DCIM/MaCam :\n" + done.joinToString("\n") +
                    "\nPosez-les toutes au même point de départ dans votre logiciel de montage."
            }
        }.start()
    }

    @Deprecated("API Activity simple, sans dépendance AndroidX")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_MULTI && data != null) {
            val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
            return syncMulti(uris)
        }
        val uri = data?.data ?: return
        when (req) {
            REQ_VIDEO -> load(uri)
            REQ_LUT -> try {
                val name = displayName(uri).substringBeforeLast('.')
                importedLut = Lut.parseCube(name, contentResolver.openInputStream(uri)!!.bufferedReader().readText())
                lut = importedLut; refresh()
            } catch (e: Exception) { Toast.makeText(this, "LUT illisible : ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "video"

    /** Charge la vidéo et retrouve son journal gyroscope (même nom, dans Download/MaCam). */
    private fun load(uri: Uri) {
        video = uri
        videoName = displayName(uri)
        gyro = gyroFor(videoName.substringBeforeLast('.'))
        stab = if (gyro != null) maxOf(stab, 1) else 0
        status.text = ""
        refresh()
    }

    /** Journal gyroscope de la vidéo [base] (même nom, dans Download/MaCam), s'il existe. */
    private fun gyroFor(base: String): GyroLog? = runCatching {
            val gcsv = "$base.gcsv"
            contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(gcsv), null)?.use { c ->
                if (!c.moveToFirst()) null else {
                    val u = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                    GyroLog.parse(contentResolver.openInputStream(u)!!.bufferedReader().readText())
                }
            }
        }.getOrNull()

    // ------------------------------------------------------------------ traitement

    private fun run() {
        val v = video ?: return
        val opts = VideoStudio.Options(
            stabilize = stab > 0,
            smoothingS = if (stab == 2) 1.2 else 0.5,
            zoom = if (stab == 2) 1.15 else 1.08,
            denoise = when (denoise) { 1 -> 0.45f; 2 -> 0.75f; else -> 0f },
            lut = lut,
        )
        val s = VideoStudio(this); studio = s
        val t0 = android.os.SystemClock.elapsedRealtime()
        go.text = "Annuler"; progress.visibility = View.VISIBLE; progress.progress = 0
        status.text = "Traitement…"
        val outName = videoName.substringBeforeLast('.') + "_atelier"
        Thread {
            val result = runCatching { s.process(v, if (stab > 0) gyro else null, opts, outName) { f ->
                runOnUiThread {
                    progress.progress = (f * 1000).toInt()
                    // Temps restant estimé (rend l'attente prévisible).
                    val el = (android.os.SystemClock.elapsedRealtime() - t0) / 1000.0
                    val left = if (f > 0.03f) (el / f * (1 - f)).toInt() else -1
                    status.text = "Traitement… ${(f * 100).toInt()} %" + if (left >= 0) " · encore ${left / 60} min ${left % 60} s" else ""
                }
            } }
            runOnUiThread {
                studio = null; go.text = "Traiter la vidéo"
                result.onSuccess { uri ->
                    val secs = (android.os.SystemClock.elapsedRealtime() - t0) / 1000
                    val range = when { s.lastRunHdr -> " · HDR conservé"; s.lastRunSourceHdr -> " · convertie en SDR"; else -> "" }
                    status.text = "✓ Vidéo enregistrée en ${secs / 60} min ${secs % 60} s$range\nDCIM/MaCam/$outName.mp4"
                    go.text = "Ouvrir la vidéo"
                    go.setOnClickListener {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    }
                }.onFailure { e ->
                    progress.visibility = View.GONE
                    status.text = if (s.cancelled) "Traitement annulé" else "Échec : ${e.message}"
                    go.setOnClickListener { if (studio == null) run() else studio?.cancelled = true }
                }
            }
        }.start()
    }

    override fun onDestroy() { studio?.cancelled = true; super.onDestroy() }

    companion object {
        private const val REQ_VIDEO = 1
        private const val REQ_LUT = 2
        private const val REQ_MULTI = 3
    }
}
