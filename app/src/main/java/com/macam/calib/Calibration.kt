package com.macam.calib

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Calibration personnelle du capteur, mesurée sur des images noires (téléphone face contre une table) :
 * niveau de noir réel par canal, bruit de lecture à chaque ISO et carte des pixels défectueux.
 * Le moteur s'en sert à la place des valeurs génériques du fabricant.
 */
class SensorCalibration(
    val lensId: String,
    /** ISO → niveaux de noir mesurés (4 canaux de la mosaïque, ordre ligne × 2 + colonne). */
    val black: Map<Int, FloatArray>,
    /** ISO → variance du bruit de lecture, normalisée par (blanc − noir)². */
    val readVar: Map<Int, Float>,
    /** Indices (y × largeur + x) des pixels défectueux. */
    val hotPixels: IntArray,
    val width: Int,
    val timestamp: Long,
) {
    private fun nearest(iso: Int, keys: Set<Int>) = keys.minByOrNull { abs(kotlin.math.ln(it.toDouble() / iso)) }

    fun blackFor(iso: Int): FloatArray? = nearest(iso, black.keys)?.let { black[it] }
    fun readVarFor(iso: Int): Float? = nearest(iso, readVar.keys)?.let { readVar[it] }

    fun toJson(): String = JSONObject().apply {
        put("lens", lensId); put("width", width); put("time", timestamp)
        put("black", JSONObject().apply { black.forEach { (iso, b) -> put(iso.toString(), JSONArray(b.map { it.toDouble() })) } })
        put("readVar", JSONObject().apply { readVar.forEach { (iso, v) -> put(iso.toString(), v.toDouble()) } })
    }.toString()

    companion object {
        private fun dir(ctx: Context) = File(ctx.filesDir, "calibration").apply { mkdirs() }

        fun save(ctx: Context, c: SensorCalibration) {
            File(dir(ctx), "${c.lensId}.json").writeText(c.toJson())
            File(dir(ctx), "${c.lensId}.hot").outputStream().use { o ->
                val b = java.nio.ByteBuffer.allocate(c.hotPixels.size * 4); c.hotPixels.forEach { b.putInt(it) }; o.write(b.array())
            }
        }

        fun load(ctx: Context, lensId: String): SensorCalibration? = runCatching {
            val j = JSONObject(File(dir(ctx), "$lensId.json").readText())
            val black = HashMap<Int, FloatArray>(); val rv = HashMap<Int, Float>()
            j.getJSONObject("black").let { o -> o.keys().forEach { k -> val a = o.getJSONArray(k); black[k.toInt()] = FloatArray(4) { a.getDouble(it).toFloat() } } }
            j.getJSONObject("readVar").let { o -> o.keys().forEach { k -> rv[k.toInt()] = o.getDouble(k).toFloat() } }
            val hotBytes = File(dir(ctx), "$lensId.hot").readBytes()
            val bb = java.nio.ByteBuffer.wrap(hotBytes)
            SensorCalibration(lensId, black, rv, IntArray(hotBytes.size / 4) { bb.getInt() }, j.getInt("width"), j.getLong("time"))
        }.getOrNull()

        fun all(ctx: Context): List<String> = dir(ctx).listFiles()?.filter { it.extension == "json" }?.map { it.nameWithoutExtension } ?: emptyList()
    }
}

/** Calculs de calibration, sans dépendance Android (testés sur des images synthétiques). */
object CalibrationMath {

    data class IsoStats(val black: FloatArray, val readVar: Float, val lightLeak: Boolean)

    /**
     * Statistiques d'une série d'images noires au même ISO. [white] et [nominalBlack] servent à normaliser
     * le bruit et à détecter une fuite de lumière (image pas assez noire).
     */
    fun isoStats(frames: List<ShortArray>, w: Int, h: Int, white: Float, nominalBlack: FloatArray, referenceBlack: FloatArray? = null): IsoStats {
        val sum = DoubleArray(4); val sq = DoubleArray(4); val n = LongArray(4)
        for (f in frames) {
            var y = 0
            while (y < h) {
                var x = 0
                val row = y * w
                while (x < w) {
                    val c = (y and 1) * 2 + (x and 1)
                    val v = (f[row + x].toInt() and 0xFFFF).toDouble()
                    sum[c] += v; sq[c] += v * v; n[c]++
                    x += 1
                }
                y += 1
            }
        }
        val black = FloatArray(4) { (sum[it] / n[it]).toFloat() }
        // Bruit : variance robuste par canal (les pixels chauds sont rares et pèsent peu sur la moyenne des 4 canaux).
        val varAvg = (0 until 4).map { sq[it] / n[it] - (sum[it] / n[it]).let { m -> m * m } }.sorted().let { (it[0] + it[1] + it[2] + it[3]) / 4 }
        val range = (white - nominalBlack.average()).coerceAtLeast(1.0)
        // Fuite de lumière : le niveau monte avec l'ISO (la lumière est amplifiée), pas le noir du capteur.
        // On compare donc au noir mesuré à l'ISO le plus bas, pas au noir théorique du fabricant.
        val leak = referenceBlack != null && (0 until 4).any { black[it] - referenceBlack[it] > 0.02 * range }
        return IsoStats(black, (varAvg / (range * range)).toFloat(), leak)
    }

    /**
     * Pixels défectueux : ceux qui dépassent nettement le noir de leur canal sur TOUTES les images
     * (un pic de bruit isolé ne se répète pas ; un pixel chaud, si).
     */
    fun hotPixels(frames: List<ShortArray>, w: Int, h: Int, black: FloatArray, sigma: Float, k: Float = 8f): IntArray {
        if (frames.isEmpty()) return IntArray(0)
        val out = ArrayList<Int>()
        val thr = FloatArray(4) { black[it] + maxOf(k * sigma, 8f) }
        for (i in 0 until w * h) {
            val c = ((i / w) and 1) * 2 + ((i % w) and 1)
            if (frames.all { (it[i].toInt() and 0xFFFF) > thr[c] }) out += i
        }
        return out.toIntArray()
    }

    fun std(variance: Float) = sqrt(variance.toDouble()).toFloat()
}
