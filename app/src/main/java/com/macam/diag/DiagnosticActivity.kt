package com.macam.diag

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class DiagnosticActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var runBtn: Button
    private val report = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setTextIsSelectable(true); textSize = 12f; setPadding(24, 24, 24, 24) }
        runBtn = Button(this).apply { text = "Lancer le diagnostic"; setOnClickListener { start() } }
        val share = Button(this).apply { text = "Partager le rapport"; setOnClickListener { shareReport() } }
        val buttons = LinearLayout(this).apply {
            addView(runBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(share, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ScrollView(this@DiagnosticActivity).apply { addView(output) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        // Si le diagnostic précédent s'est interrompu (plantage), on réaffiche son rapport partiel.
        val previous = File(getExternalFilesDir(null), "rapport_diagnostic.txt")
        if (previous.exists() && previous.length() > 0 && !previous.readText().contains("FIN DU DIAGNOSTIC")) {
            report.append(previous.readText())
            output.text = "Rapport précédent interrompu :\n\n" + report
            return
        }
        output.text = "Ce test ouvre chaque caméra et enregistre de courtes vidéos de test (environ 1 minute). " +
            "Posez le téléphone et appuyez sur « Lancer le diagnostic »."
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1); return
        }
        runBtn.isEnabled = false
        report.clear()
        output.text = ""
        Thread {
            val file = File(getExternalFilesDir(null), "rapport_diagnostic.txt").apply { writeText("") }
            val log: (String) -> Unit = { line ->
                synchronized(report) { report.appendLine(line); runCatching { file.appendText(line + "\n") } }
                runOnUiThread { output.append(line + "\n") }
            }
            log("Ma Cam – diagnostic v0.2 | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            val cm = getSystemService(CameraManager::class.java)
            try {
                val sb = StringBuilder(); CameraInfo.report(this, cm, sb); log(sb.toString())
            } catch (e: Throwable) { log("ERREUR inventaire : $e") }
            val tests = StreamTests(this, log)
            try {
                tests.physicalStreams()
                tests.highSpeed()
                tests.tenBitRecording()
            } catch (e: Throwable) { log("ERREUR tests : $e") } finally { tests.close() }
            log("\n##### FIN DU DIAGNOSTIC #####")
            runOnUiThread { runBtn.isEnabled = true }
        }.start()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else output.text = "Permission caméra refusée : le diagnostic ne peut pas fonctionner."
    }

    private fun shareReport() {
        val text = synchronized(report) { report.toString() }.ifEmpty { return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, "Rapport Ma Cam")
        }, "Partager le rapport"))
    }
}
