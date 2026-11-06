package com.macam.diag

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Size

/** Lecture statique des capacités de toutes les caméras (logiques et physiques). */
object CameraInfo {

    private val CAPS = mapOf(
        0 to "BACKWARD_COMPATIBLE", 1 to "MANUAL_SENSOR", 2 to "MANUAL_POST_PROCESSING",
        3 to "RAW", 4 to "PRIVATE_REPROCESSING", 5 to "READ_SENSOR_SETTINGS",
        6 to "BURST_CAPTURE", 7 to "YUV_REPROCESSING", 8 to "DEPTH_OUTPUT",
        9 to "CONSTRAINED_HIGH_SPEED_VIDEO", 10 to "MOTION_TRACKING",
        11 to "LOGICAL_MULTI_CAMERA", 12 to "MONOCHROME", 13 to "SECURE_IMAGE_DATA",
        14 to "SYSTEM_CAMERA", 15 to "OFFLINE_PROCESSING",
        16 to "ULTRA_HIGH_RESOLUTION_SENSOR", 17 to "REMOSAIC_REPROCESSING",
        18 to "DYNAMIC_RANGE_TEN_BIT", 19 to "STREAM_USE_CASE", 20 to "COLOR_SPACE_PROFILES"
    )

    val DYN_PROFILES = linkedMapOf(
        1L to "STANDARD", 2L to "HLG10", 4L to "HDR10", 8L to "HDR10_PLUS",
        16L to "DOLBY_VISION_10B_REF", 32L to "DOLBY_VISION_10B_REF_PO",
        64L to "DOLBY_VISION_10B_OEM", 128L to "DOLBY_VISION_10B_OEM_PO",
        256L to "DOLBY_VISION_8B_REF", 512L to "DOLBY_VISION_8B_REF_PO",
        1024L to "DOLBY_VISION_8B_OEM", 2048L to "DOLBY_VISION_8B_OEM_PO"
    )

    private val EXTENSIONS = mapOf(
        0 to "AUTOMATIC", 1 to "FACE_RETOUCH", 2 to "BOKEH", 3 to "HDR", 4 to "NIGHT",
        5 to "EYES_FREE_VIDEOGRAPHY"
    )

    /** Toutes les caméras : celles listées + les physiques cachées derrière les logiques. */
    fun allCameraIds(cm: CameraManager): List<String> {
        val ids = LinkedHashSet<String>()
        for (id in cm.cameraIdList) {
            ids += id
            ids += cm.getCameraCharacteristics(id).physicalCameraIds
        }
        return ids.toList()
    }

    fun report(ctx: Context, cm: CameraManager, sb: StringBuilder) {
        val listed = cm.cameraIdList.toSet()
        for (id in allCameraIds(cm)) {
            sb.appendLine("\n=== Caméra $id ${if (id in listed) "(listée)" else "(physique, cachée)"} ===")
            try {
                describe(cm, id, sb)
            } catch (e: Exception) {
                sb.appendLine("ERREUR lecture : ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        sb.appendLine("\n=== Extensions caméra du fabricant (mode nuit/HDR/portrait Xiaomi) ===")
        for (id in cm.cameraIdList) {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    val ext = cm.getCameraExtensionCharacteristics(id).supportedExtensions
                    sb.appendLine("Caméra $id : " + if (ext.isEmpty()) "AUCUNE" else ext.joinToString { EXTENSIONS[it] ?: "EXT_$it" })
                }
            } catch (e: Exception) {
                sb.appendLine("Caméra $id : ERREUR ${e.message}")
            }
        }
        encoders(sb)
    }

    private fun describe(cm: CameraManager, id: String, sb: StringBuilder) {
        val c = cm.getCameraCharacteristics(id)
        fun <T> g(k: CameraCharacteristics.Key<T>): T? = try { c.get(k) } catch (_: Exception) { null }

        val facing = when (g(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> "ARRIÈRE"; CameraMetadata.LENS_FACING_FRONT -> "AVANT"; else -> "EXTERNE"
        }
        val level = when (g(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            0 -> "LIMITED"; 1 -> "FULL"; 2 -> "LEGACY"; 3 -> "LEVEL_3"; 4 -> "EXTERNAL"; else -> "?"
        }
        sb.appendLine("Orientation : $facing | Niveau : $level")
        sb.appendLine("Focales (mm) : ${g(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()}")
        sb.appendLine("Ouvertures : f/${g(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString(", f/")}")
        val phys = g(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val px = g(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        sb.appendLine("Capteur : ${phys?.width} x ${phys?.height} mm | Matrice : ${px?.width} x ${px?.height} (${px?.let { "%.1f".format(it.width * it.height / 1e6) }} Mpx)")
        if (Build.VERSION.SDK_INT >= 31) {
            val pxMax = g(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
            if (pxMax != null) sb.appendLine("Matrice pleine résolution : ${pxMax.width} x ${pxMax.height}")
        }
        val minFocus = g(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        sb.appendLine("Mise au point : ${if (minFocus == null || minFocus == 0f) "FIXE" else "variable, min %.1f cm".format(100f / minFocus)}")
        sb.appendLine("ISO : ${g(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)} | Exposition (ns) : ${g(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)}")
        if (Build.VERSION.SDK_INT >= 30) sb.appendLine("Plage de zoom : ${g(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)}")
        sb.appendLine("Capacités : ${g(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.joinToString { CAPS[it] ?: "CAP_$it" }}")
        sb.appendLine("Stabilisation optique : ${g(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(1) == true}")
        sb.appendLine("Stabilisation vidéo : ${g(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.joinToString {
            when (it) { 0 -> "OFF"; 1 -> "ON"; 2 -> "PREVIEW_STABILIZATION"; else -> "MODE_$it" }
        }}")
        sb.appendLine("Plages FPS : ${g(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString()}")
        val phyIds = c.physicalCameraIds
        if (phyIds.isNotEmpty()) sb.appendLine("Caméras physiques : $phyIds")

        val map = g(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            fun max(sizes: Array<Size>?) = sizes?.maxByOrNull { it.width.toLong() * it.height }
            sb.appendLine("Max JPEG : ${max(map.getOutputSizes(ImageFormat.JPEG))} | Max YUV : ${max(map.getOutputSizes(ImageFormat.YUV_420_888))}")
            sb.appendLine("Tailles RAW : ${map.getOutputSizes(ImageFormat.RAW_SENSOR)?.joinToString() ?: "aucune"}")
            val hs = map.highSpeedVideoSizes
            if (hs.isNullOrEmpty()) sb.appendLine("Haute vitesse : aucune")
            else for (s in hs) sb.appendLine("Haute vitesse $s : ${map.getHighSpeedVideoFpsRangesFor(s).joinToString()}")
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val mapMax = g(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            if (mapMax != null) {
                sb.appendLine("Pleine résolution JPEG : ${mapMax.getOutputSizes(ImageFormat.JPEG)?.joinToString()}")
                sb.appendLine("Pleine résolution RAW : ${mapMax.getOutputSizes(ImageFormat.RAW_SENSOR)?.joinToString()}")
            } else sb.appendLine("Pleine résolution (50 Mpx…) : NON exposée")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val dr = g(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            sb.appendLine("Profils dynamiques : ${dr?.supportedProfiles?.joinToString { DYN_PROFILES[it] ?: "P$it" } ?: "aucun"}")
        }
    }

    private fun encoders(sb: StringBuilder) {
        sb.appendLine("\n=== Encodeurs vidéo matériels ===")
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder || (Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated)) continue
            for (type in info.supportedTypes) {
                if (!type.startsWith("video/")) continue
                val caps = info.getCapabilitiesForType(type)
                val v = caps.videoCapabilities
                val main10 = caps.profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                }
                val fps4k = try { v.getSupportedFrameRatesFor(3840, 2160).upper.toInt() } catch (_: Exception) { 0 }
                sb.appendLine("${info.name} [$type] débit max ${v.bitrateRange.upper / 1_000_000} Mb/s | 4K max ${fps4k} i/s${if (main10) " | 10 bits" else ""}")
            }
        }
    }
}
