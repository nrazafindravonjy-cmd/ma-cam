package com.macam.studio

import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Quaternion unitaire (w, x, y, z) : orientation de la caméra (repère caméra → monde). */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )
    fun conj() = Quat(w, -x, -y, -z)
    fun normalized(): Quat { val n = sqrt(w * w + x * x + y * y + z * z); return Quat(w / n, x / n, y / n, z / n) }
    fun dot(o: Quat) = w * o.w + x * o.x + y * o.y + z * o.z

    /** Matrice de rotation 3×3 (ligne par ligne). */
    fun matrix(): DoubleArray = doubleArrayOf(
        1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
        2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
        2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
    )

    companion object {
        val ID = Quat(1.0, 0.0, 0.0, 0.0)

        /** Rotation d'angle |v| autour de v (vecteur rotation, en radians). */
        fun fromRotVec(vx: Double, vy: Double, vz: Double): Quat {
            val a = sqrt(vx * vx + vy * vy + vz * vz)
            if (a < 1e-12) return Quat(1.0, vx / 2, vy / 2, vz / 2).normalized()
            val s = sin(a / 2) / a
            return Quat(cos(a / 2), vx * s, vy * s, vz * s)
        }

        fun slerp(a: Quat, b0: Quat, t: Double): Quat {
            var b = b0
            var d = a.dot(b)
            if (d < 0) { b = Quat(-b.w, -b.x, -b.y, -b.z); d = -d }
            if (d > 0.9995) return Quat(a.w + t * (b.w - a.w), a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), a.z + t * (b.z - a.z)).normalized()
            val th = acos(d.coerceIn(-1.0, 1.0)); val s = sin(th)
            val wa = sin((1 - t) * th) / s; val wb = sin(t * th) / s
            return Quat(wa * a.w + wb * b.w, wa * a.x + wb * b.x, wa * a.y + wb * b.y, wa * a.z + wb * b.z)
        }

        fun angle(a: Quat, b: Quat) = 2 * acos(kotlin.math.abs(a.dot(b)).coerceAtMost(1.0))
    }
}

/** Journal gyroscope (.gcsv écrit par Ma Cam) et métadonnées optiques. */
class GyroLog(
    val tMs: DoubleArray, val gx: DoubleArray, val gy: DoubleArray, val gz: DoubleArray,
    /** Focale en pixels du capteur complet, largeur active du capteur, orientation du capteur, caméra avant. */
    val focalPx: Double?, val activeWidth: Int?, val sensorOrientation: Int, val front: Boolean,
    val readoutMs: Double,
) {
    companion object {
        fun parse(text: String): GyroLog {
            val t = ArrayList<Double>(); val x = ArrayList<Double>(); val y = ArrayList<Double>(); val z = ArrayList<Double>()
            var tscale = 0.001; var gscale = 1.0; var readout = 0.0
            var focal: Double? = null; var active: Int? = null; var orient = 90; var front = false
            var data = false
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val parts = line.split(',')
                if (!data) {
                    when (parts[0]) {
                        "t" -> data = true
                        "tscale" -> tscale = parts[1].toDouble()
                        "gscale" -> gscale = parts[1].toDouble()
                        "frame_readout_time" -> readout = parts[1].toDouble()
                        "note" -> Regex("(\\w+)=([-\\d.]+)").findAll(line).forEach { m ->
                            val (k, v) = m.destructured
                            when (k) { "f" -> focal = v.toDouble(); "aw" -> active = v.toInt(); "o" -> orient = v.toInt(); "front" -> front = v == "1" }
                        }
                    }
                    continue
                }
                if (parts.size < 4) continue
                t += parts[0].toDouble() * tscale * 1000
                x += parts[1].toDouble() * gscale; y += parts[2].toDouble() * gscale; z += parts[3].toDouble() * gscale
            }
            return GyroLog(t.toDoubleArray(), x.toDoubleArray(), y.toDoubleArray(), z.toDoubleArray(), focal, active, orient, front, readout)
        }
    }
}

/**
 * Stabilisation par gyroscope (même principe que Gyroflow) :
 * 1. intégration de la vitesse angulaire → orientation réelle de la caméra ;
 * 2. lissage aller-retour (sans déphasage) → trajectoire virtuelle ;
 * 3. limitation de l'écart pour rester dans la marge de recadrage ;
 * 4. pour chaque image : homographie H = K · Rréelᵀ·Rvirtuelle · K'⁻¹ (haut et bas de l'image,
 *    pour corriger aussi l'obturateur roulant).
 */
class Stabilizer(private val log: GyroLog, private val smoothingS: Double, val zoom: Double) {

    private val times = log.tMs
    private val real: Array<Quat>
    private val virt: Array<Quat>

    init {
        val n = times.size
        real = Array(n) { Quat.ID }
        for (i in 1 until n) {
            val dt = (times[i] - times[i - 1]) / 1000.0
            val (cx, cy, cz) = toCamera(log.gx[i], log.gy[i], log.gz[i])
            real[i] = (real[i - 1] * Quat.fromRotVec(cx * dt, cy * dt, cz * dt)).normalized()
        }
        // Lissage exponentiel aller puis retour (phase nulle).
        val fwd = Array(n) { Quat.ID }
        if (n > 0) fwd[0] = real[0]
        for (i in 1 until n) fwd[i] = Quat.slerp(fwd[i - 1], real[i], alpha(times[i] - times[i - 1]))
        virt = fwd.copyOf()
        for (i in n - 2 downTo 0) virt[i] = Quat.slerp(virt[i + 1], fwd[i], alpha(times[i + 1] - times[i]))
    }

    /** Limite l'écart réel/virtuel à [maxAngle] (radians) : l'image corrigée reste dans la marge. */
    fun limit(maxAngle: Double) {
        for (i in virt.indices) {
            val a = Quat.angle(real[i], virt[i])
            if (a > maxAngle) virt[i] = Quat.slerp(real[i], virt[i], maxAngle / a)
        }
    }

    private fun alpha(dtMs: Double) = 1 - exp(-dtMs / 1000.0 / smoothingS)

    /** Vitesse angulaire du téléphone (axes Android) → repère caméra (x droite, y bas, z vers la scène). */
    private fun toCamera(x: Double, y: Double, z: Double): Triple<Double, Double, Double> = when (log.sensorOrientation) {
        90 -> Triple(-y, -x, -z)
        270 -> Triple(y, x, -z)
        180 -> Triple(x, -y, -z)
        else -> Triple(-x, y, -z)
    }.let { if (log.front) Triple(-it.first, it.second, -it.third) else it }

    private fun at(arr: Array<Quat>, tMs: Double): Quat {
        if (times.isEmpty()) return Quat.ID
        if (tMs <= times[0]) return arr[0]
        if (tMs >= times.last()) return arr.last()
        var lo = 0; var hi = times.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (times[m] <= tMs) lo = m else hi = m }
        val f = (tMs - times[lo]) / (times[hi] - times[lo])
        return Quat.slerp(arr[lo], arr[hi], f)
    }

    /** Correction réel→virtuel (Rréelᵀ · Rvirtuelle) à l'instant t. */
    fun correction(tMs: Double): DoubleArray = (at(real, tMs).conj() * at(virt, tMs)).matrix()

    /**
     * Homographie (3×3, ligne par ligne) qui donne, pour un pixel de sortie, la position dans l'image source.
     * [fPx] = focale en pixels de la vidéo, [w]×[h] = taille de l'image.
     */
    fun homography(tMs: Double, fPx: Double, w: Int, h: Int): DoubleArray {
        val r = correction(tMs)
        val cx = w / 2.0; val cy = h / 2.0
        val fo = fPx * zoom
        // K_in · R · K_out⁻¹
        val kInv = doubleArrayOf(1 / fo, 0.0, -cx / fo, 0.0, 1 / fo, -cy / fo, 0.0, 0.0, 1.0)
        val k = doubleArrayOf(fPx, 0.0, cx, 0.0, fPx, cy, 0.0, 0.0, 1.0)
        return mul(k, mul(r, kInv))
    }

    /** Écart angulaire maximal permis par le recadrage [zoom]. */
    fun maxAngleFor(fPx: Double, w: Int, h: Int): Double {
        val shift = (1 - 1 / zoom) * minOf(w, h) / 2
        return atan(shift / fPx)
    }

    companion object {
        fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
            val r = i / 3; val c = i % 3
            a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
    }
}
