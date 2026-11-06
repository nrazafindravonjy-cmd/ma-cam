package com.macam.photo

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata

/**
 * Adapte le traitement à la puissance du téléphone : un haut de gamme fusionne 8 à 15 images RAW,
 * un modèle d'entrée de gamme fusionne moins d'images, en YUV, et à résolution réduite.
 */
class DeviceProfile(ctx: Context, chars: CameraCharacteristics) {

    enum class Tier { HIGH, MID, LOW }

    val ramGb: Double = ActivityManager.MemoryInfo().also {
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }.totalMem / 1e9

    private val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
    private val level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)

    val hasRaw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps
    val hasManual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
    val cores = Runtime.getRuntime().availableProcessors()

    val tier = when {
        hasRaw && ramGb >= 6 && cores >= 8 -> Tier.HIGH
        (hasRaw || level != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) && ramGb >= 3 -> Tier.MID
        else -> Tier.LOW
    }

    /** RAW seulement si la mémoire le permet (une image RAW 12 Mpx ≈ 25 Mo). */
    val useRaw = hasRaw && tier != Tier.LOW

    fun frames(night: Boolean) = when (tier) {
        Tier.HIGH -> if (night) 13 else 8
        Tier.MID -> if (night) 8 else 6
        Tier.LOW -> if (night) 6 else 4
    }

    /** Facteur de super-résolution (0 = indisponible). */
    val srScale = when {
        !useRaw -> 0f
        ramGb >= 8 -> 2f
        ramGb >= 5 -> 1.5f
        else -> 0f
    }

    /** Taille maximale traitée en YUV (en mégapixels), pour tenir en mémoire sur les petits modèles. */
    val maxYuvMp = when (tier) { Tier.HIGH -> 50.0; Tier.MID -> 12.0; Tier.LOW -> 8.0 }

    override fun toString() = "$tier · %.0f Go · %d cœurs · %s".format(ramGb, cores, if (useRaw) "RAW" else "YUV")
}
