package com.macam.cam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Enregistre la vidéo (HEVC, 8 ou 10 bits HLG) et le son (AAC) dans DCIM/MaCam.
 *
 * Cycle de vie :
 *  1. [arm]   : l'encodeur vidéo est configuré à l'avance et sa surface est intégrée à la session
 *               caméra, pour que l'enregistrement démarre instantanément ;
 *  2. [begin] : création du fichier, démarrage des encodeurs et du micro ;
 *  3. [finish]: arrêt, finalisation du MP4 puis publication dans la galerie (toujours exécuté,
 *               même en cas d'erreur, pour ne jamais laisser de fichier invisible).
 *
 * Les horodatages vidéo viennent du capteur ; [clock] doit renvoyer la même base de temps (ns).
 */
class Recorder(
    private val ctx: Context,
    private val size: Size,
    private val fps: Int,
    private val bitrate: Int,
    private val tenBit: Boolean,
    private val clock: () -> Long,
) {
    lateinit var inputSurface: Surface; private set
    var baseName = ""; private set

    /** Horodatage capteur (ns) de la première image enregistrée, pour aligner le gyroscope. */
    @Volatile var firstVideoTimestampNs = -1L; private set

    private lateinit var video: MediaCodec
    private var audio: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var startNs = 0L
    @Volatile private var audioRunning = false
    private val threads = mutableListOf<Thread>()

    // État du multiplexeur : on attend que les deux pistes soient connues avant de démarrer.
    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var expectedTracks = 2
    private var muxerStarted = false
    private var samplesWritten = 0
    private val pending = ArrayList<Triple<Boolean, ByteBuffer, MediaCodec.BufferInfo>>()

    fun arm() {
        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        video.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // Temps réel + cadence explicite : indispensable pour tenir 120/240 i/s.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            if (tenBit) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            } else {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = video.createInputSurface()
    }

    @SuppressLint("MissingPermission")
    fun begin(name: String, orientationHint: Int) {
        baseName = name
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/MaCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val u = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Impossible de créer le fichier vidéo")
        uri = u
        pfd = ctx.contentResolver.openFileDescriptor(u, "rw")!!
        muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            .apply { setOrientationHint(orientationHint) }

        audioRecord = try {
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, min * 4).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Exception) { Log.w(TAG, "micro indisponible", e); null }
        if (audioRecord != null) {
            audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } else expectedTracks = 1 // pas de micro : vidéo seule plutôt qu'aucun fichier

        startNs = clock()
        video.start()
        threads += Thread({ guarded { drain(video, true) } }, "video-drain")
        audio?.let { a ->
            a.start()
            audioRunning = true
            threads += Thread({ guarded { feedAudio(a) } }, "audio-feed")
            threads += Thread({ guarded { drain(a, false) } }, "audio-drain")
        }
        threads.forEach { it.start() }
    }

    /** Arrête et publie le fichier. Ne lance jamais d'exception. Renvoie l'URI, ou null si rien n'a été écrit. */
    fun finish(): Uri? {
        guarded { video.signalEndOfInputStream() }
        audioRunning = false
        threads.forEach { it.join(4000) }
        guarded { audioRecord?.stop() }; guarded { audioRecord?.release() }
        guarded { video.stop() }; guarded { video.release() }
        guarded { audio?.stop() }; guarded { audio?.release() }
        guarded { inputSurface.release() }
        synchronized(lock) { if (muxerStarted) guarded { muxer?.stop() } }
        guarded { muxer?.release() }
        guarded { pfd?.close() }
        val u = uri ?: return null
        return if (samplesWritten > 0) {
            guarded { ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) }
            u
        } else {
            guarded { ctx.contentResolver.delete(u, null, null) }
            null
        }
    }

    /** Libère un encodeur préparé mais jamais utilisé. */
    fun disarm() {
        guarded { video.release() }
        guarded { inputSurface.release() }
    }

    private fun feedAudio(a: MediaCodec) {
        val rec = audioRecord!!
        var samples = 0L
        rec.startRecording()
        while (true) {
            val idx = a.dequeueInputBuffer(10_000)
            if (idx < 0) continue
            if (!audioRunning) { // toujours terminer par une fin de flux, sinon le fichier n'est jamais finalisé
                a.queueInputBuffer(idx, 0, 0, startNs / 1000 + samples * 1_000_000 / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            val buf = a.getInputBuffer(idx)!!
            buf.clear()
            val read = rec.read(buf, minOf(buf.remaining(), 4096)).coerceAtLeast(0)
            a.queueInputBuffer(idx, 0, read, startNs / 1000 + samples * 1_000_000 / SAMPLE_RATE, 0)
            samples += read / 4 // 2 canaux × 16 bits
        }
    }

    private fun drain(codec: MediaCodec, isVideo: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 100_000)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(lock) {
                    val t = muxer!!.addTrack(codec.outputFormat)
                    if (isVideo) videoTrack = t else audioTrack = t
                    if ((if (videoTrack >= 0) 1 else 0) + (if (audioTrack >= 0) 1 else 0) == expectedTracks) {
                        muxer!!.start(); muxerStarted = true
                        for ((v, b, i) in pending) { muxer!!.writeSampleData(if (v) videoTrack else audioTrack, b, i); samplesWritten++ }
                        pending.clear()
                    }
                }
            } else if (idx >= 0) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 &&
                    info.presentationTimeUs * 1000 >= startNs) {
                    if (isVideo && firstVideoTimestampNs < 0) firstVideoTimestampNs = info.presentationTimeUs * 1000
                    write(isVideo, codec.getOutputBuffer(idx)!!, info)
                }
                codec.releaseOutputBuffer(idx, false)
                if (eos) return
            }
        }
    }

    private fun write(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        // Horodatages relatifs au début de l'enregistrement, communs aux deux pistes.
        val rel = MediaCodec.BufferInfo().apply {
            set(0, info.size, info.presentationTimeUs - startNs / 1000, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
        }
        buf.position(info.offset); buf.limit(info.offset + info.size)
        synchronized(lock) {
            if (muxerStarted) { muxer!!.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, rel); samplesWritten++ }
            else pending += Triple(isVideo, ByteBuffer.allocate(info.size).put(buf).flip() as ByteBuffer, rel)
        }
    }

    /** Une erreur dans un fil d'arrière-plan ne doit jamais faire planter l'application. */
    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "ignoré", t) }
    }

    companion object {
        private const val TAG = "Recorder"
        private const val SAMPLE_RATE = 48_000
    }
}
