package com.macam.cam

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size

/**
 * Choix de l'encodeur vidéo selon ce que CE téléphone sait réellement faire : HEVC si possible (10 bits pour le
 * HDR), sinon H.264, que tous les appareils Android encodent. Le débit demandé est plafonné à la capacité du codec.
 */
object Codecs {
    data class Choice(val mime: String, val maxBitrate: Int, val tenBit: Boolean)

    private val encoders by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder } }
    private val cache = HashMap<String, Choice?>()

    /** Meilleur encodeur pour [size] à [fps] ; null si aucun (le mode n'est alors pas proposé). */
    @Synchronized fun pick(size: Size, fps: Int, tenBit: Boolean): Choice? = cache.getOrPut("${size}x$fps:$tenBit") {
        val mimes = if (tenBit) listOf(MediaFormat.MIMETYPE_VIDEO_HEVC)
                    else listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
        for (mime in mimes) {
            // Matériel d'abord : un encodeur logiciel ne tient pas la 4K en temps réel.
            val cands = encoders.filter { mime in it.supportedTypes.map(String::lowercase) }
                .sortedByDescending { if (android.os.Build.VERSION.SDK_INT >= 29) it.isHardwareAccelerated else true }
            for (info in cands) {
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                val v = caps.videoCapabilities ?: continue
                val ok = runCatching { v.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) }.getOrDefault(false) ||
                    runCatching { v.isSizeSupported(size.width, size.height) && v.supportedFrameRates.upper >= fps }.getOrDefault(false)
                if (!ok) continue
                if (tenBit && caps.profileLevels.none {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 }) continue
                return@getOrPut Choice(mime, v.bitrateRange.upper, tenBit)
            }
        }
        null
    }
}
