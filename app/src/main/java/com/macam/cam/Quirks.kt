package com.macam.cam

import android.content.Context
import android.content.SharedPreferences

/**
 * Compatibilité qui s'auto-répare : chaque fois qu'un réglage échoue sur ce téléphone (objectif physique
 * non ouvrable, flux d'analyse refusé, mode vidéo refusé…), on le retient pour ne plus le retenter.
 * Le rapport (Diagnostic) les liste, et peut être partagé volontairement.
 */
object Quirks {
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("macam_quirks", Context.MODE_PRIVATE) }

    fun has(key: String) = prefs?.getBoolean(key, false) == true
    fun set(key: String) { prefs?.edit()?.putBoolean(key, true)?.apply() }
    fun all(): List<String> = prefs?.all?.filterValues { it == true }?.keys?.sorted() ?: emptyList()
    fun reset() { prefs?.edit()?.clear()?.apply() }

    fun noDirectOpen(id: String) = "noDirectOpen:$id"
    fun noAnalysis(id: String, tenBit: Boolean) = "noAnalysis:$id:${if (tenBit) "10bit" else "8bit"}"
    fun badMode(id: String, mode: String) = "badMode:$id:$mode"
}
