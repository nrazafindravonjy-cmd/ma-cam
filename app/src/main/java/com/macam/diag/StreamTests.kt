package com.macam.diag

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.*
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.*
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Tests réels : on ouvre les caméras et on vérifie ce qui fonctionne vraiment. */
class StreamTests(private val ctx: Context, private val log: (String) -> Unit) {

    private val cm = ctx.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("cam").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    fun close() = thread.quitSafely()

    // ---------- utilitaires bloquants ----------

    @SuppressLint("MissingPermission")
    private fun open(id: String): CameraDevice {
        val latch = CountDownLatch(1)
        var dev: CameraDevice? = null
        var err: String? = null
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { dev = d; latch.countDown() }
            override fun onDisconnected(d: CameraDevice) { err = "déconnectée"; d.close(); latch.countDown() }
            override fun onError(d: CameraDevice, e: Int) { err = "erreur $e"; d.close(); latch.countDown() }
        }, handler)
        if (!latch.await(5, TimeUnit.SECONDS)) throw IllegalStateException("ouverture : délai dépassé")
        return dev ?: throw IllegalStateException("ouverture : $err")
    }

    private fun session(dev: CameraDevice, type: Int, outputs: List<OutputConfiguration>): CameraCaptureSession {
        val latch = CountDownLatch(1)
        var s: CameraCaptureSession? = null
        val cfg = SessionConfiguration(type, outputs, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(cs: CameraCaptureSession) { s = cs; latch.countDown() }
            override fun onConfigureFailed(cs: CameraCaptureSession) { latch.countDown() }
        })
        val supported = try { dev.isSessionConfigurationSupported(cfg).toString() } catch (e: Exception) { "inconnu" }
        log("   configuration déclarée supportée : $supported")
        dev.createCaptureSession(cfg)
        if (!latch.await(5, TimeUnit.SECONDS)) throw IllegalStateException("session : délai dépassé")
        return s ?: throw IllegalStateException("session refusée (onConfigureFailed)")
    }

    private fun pickSize(sizes: Array<Size>?, w: Int, h: Int): Size =
        sizes?.firstOrNull { it.width == w && it.height == h }
            ?: sizes?.filter { it.width <= w }?.maxByOrNull { it.width * it.height }
            ?: Size(640, 480)

    // ---------- Test 1 : accès séparé aux objectifs ----------

    fun physicalStreams() {
        log("\n##### TEST 1 : accès séparé aux objectifs physiques #####")
        for (logical in cm.cameraIdList) {
            val phys = cm.getCameraCharacteristics(logical).physicalCameraIds
            if (phys.isEmpty()) continue
            for (pid in phys) {
                log("→ Objectif $pid via caméra logique $logical")
                runCatchingLog { streamPhysical(logical, pid) }
                log("→ Objectif $pid ouvert directement")
                runCatchingLog {
                    val d = open(pid); d.close(); log("   OK : ouverture directe autorisée")
                }
            }
        }
    }

    private fun streamPhysical(logical: String, pid: String) {
        val map = cm.getCameraCharacteristics(pid).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = pickSize(map?.getOutputSizes(ImageFormat.YUV_420_888), 1920, 1080)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        val frames = AtomicInteger()
        val first = CountDownLatch(1)
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close(); frames.incrementAndGet(); first.countDown() }, handler)
        val dev = open(logical)
        try {
            val out = OutputConfiguration(reader.surface).apply { setPhysicalCameraId(pid) }
            val s = session(dev, SessionConfiguration.SESSION_REGULAR, listOf(out))
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(reader.surface) }
            s.setRepeatingRequest(req.build(), null, handler)
            val ok = first.await(4, TimeUnit.SECONDS)
            Thread.sleep(1000)
            s.stopRepeating(); s.close()
            log(if (ok) "   OK : images reçues ($size, ${frames.get()} images en ~1 s)" else "   ÉCHEC : aucune image reçue")
        } finally { dev.close(); reader.close() }
    }

    // ---------- Test 2 : haute vitesse (120 i/s et plus) ----------

    fun highSpeed() {
        log("\n##### TEST 2 : vidéo haute vitesse #####")
        for (id in cm.cameraIdList) {
            val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val sizes = map.highSpeedVideoSizes
            if (sizes.isNullOrEmpty()) { log("Caméra $id : aucune taille haute vitesse"); continue }
            // On teste la plus grande taille, à sa fréquence fixe la plus élevée.
            for (size in sizes.sortedByDescending { it.width * it.height }.take(2)) {
                val range = map.getHighSpeedVideoFpsRangesFor(size).filter { it.lower == it.upper }.maxByOrNull { it.upper } ?: continue
                log("→ Caméra $id : $size à ${range.upper} i/s")
                runCatchingLog { runHighSpeed(id, size, range) }
            }
        }
    }

    private fun runHighSpeed(id: String, size: Size, fps: Range<Int>) {
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 8,
            HardwareBuffer.USAGE_VIDEO_ENCODE)
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
        val dev = open(id)
        try {
            val s = session(dev, SessionConfiguration.SESSION_HIGH_SPEED, listOf(OutputConfiguration(reader.surface)))
                as CameraConstrainedHighSpeedCaptureSession
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
            }.build()
            val count = AtomicInteger()
            val cb = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult) { count.incrementAndGet() }
            }
            s.setRepeatingBurst(s.createHighSpeedRequestList(req), cb, handler)
            Thread.sleep(1000); count.set(0)
            val t0 = System.nanoTime(); Thread.sleep(2000)
            val measured = count.get() / ((System.nanoTime() - t0) / 1e9)
            s.stopRepeating(); s.close()
            log("   OK : %.0f i/s mesurés".format(measured))
        } finally { dev.close(); reader.close() }
    }

    // ---------- Test 3 : enregistrement vidéo 10 bits ----------

    fun tenBitRecording() {
        log("\n##### TEST 3 : enregistrement vidéo 10 bits HLG (HEVC) #####")
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
        } ?: return log("Aucune caméra arrière")
        val profiles = cm.getCameraCharacteristics(id).get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
        val hlgOk = profiles?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
        log("Caméra $id : HLG10 déclaré = $hlgOk")
        for ((profile, label) in listOf(DynamicRangeProfiles.HLG10 to "HLG10", DynamicRangeProfiles.STANDARD to "8 bits SDR")) {
            if (profile == DynamicRangeProfiles.HLG10 && !hlgOk) continue
            for (fps in listOf(30, 60)) {
                log("→ 3840x2160 à $fps i/s, $label")
                runCatchingLog { record(id, Size(3840, 2160), fps, profile, label) }
            }
        }
    }

    private fun record(id: String, size: Size, fps: Int, profile: Long, label: String) {
        val tenBit = profile != DynamicRangeProfiles.STANDARD
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (fps >= 60) 100_000_000 else 60_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (tenBit) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input: Surface = codec.createInputSurface()
        codec.start()

        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
        val file = File(dir, "test_${size.width}x${size.height}_${fps}fps_${if (tenBit) "hlg10" else "sdr"}.mp4")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val drain = Thread { drainEncoder(codec, muxer) }.apply { start() }

        val dev = open(id)
        try {
            val out = OutputConfiguration(input).apply { dynamicRangeProfile = profile }
            val s = session(dev, SessionConfiguration.SESSION_REGULAR, listOf(out))
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(input)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
            }.build()
            s.setRepeatingRequest(req, null, handler)
            Thread.sleep(4000)
            s.stopRepeating(); s.close()
        } finally {
            dev.close()
            codec.signalEndOfInputStream()
            drain.join(5000)
            codec.stop(); codec.release(); input.release()
        }
        verify(file, label)
    }

    private fun drainEncoder(codec: MediaCodec, muxer: MediaMuxer) {
        val info = MediaCodec.BufferInfo()
        var track = -1
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 100_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) muxer.writeSampleData(track, buf, info)
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            if (track >= 0) muxer.stop()
            muxer.release()
        }
    }

    private fun verify(file: File, label: String) {
        val ex = MediaExtractor().apply { setDataSource(file.absolutePath) }
        val f = ex.getTrackFormat(0)
        ex.selectTrack(0)
        var frames = 0; var first = -1L; var last = 0L
        while (true) {
            val t = ex.sampleTime
            if (t < 0) break
            if (first < 0) first = t
            last = t; frames++; ex.advance()
        }
        ex.release()
        val dur = (last - first) / 1e6
        val transfer = when (f.getIntegerOrNull(MediaFormat.KEY_COLOR_TRANSFER)) {
            MediaFormat.COLOR_TRANSFER_HLG -> "HLG"; MediaFormat.COLOR_TRANSFER_ST2084 -> "PQ"
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"; null -> "non indiqué"; else -> "autre"
        }
        val prof = when (f.getIntegerOrNull(MediaFormat.KEY_PROFILE)) {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main10 (10 bits)"
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main (8 bits)"
            null -> "non indiqué"; else -> "autre"
        }
        val mbps = file.length() * 8 / 1e6 / dur.coerceAtLeast(0.001)
        log("   OK ($label) : $frames images en %.2f s = %.1f i/s | profil $prof | transfert $transfer | %.0f Mb/s | %s"
            .format(dur, (frames - 1) / dur.coerceAtLeast(0.001), mbps, file.name))
    }

    private fun MediaFormat.getIntegerOrNull(k: String): Int? = if (containsKey(k)) getInteger(k) else null

    private inline fun runCatchingLog(block: () -> Unit) {
        try { block() } catch (e: Throwable) { log("   ÉCHEC : ${e.javaClass.simpleName}: ${e.message}") }
    }
}
