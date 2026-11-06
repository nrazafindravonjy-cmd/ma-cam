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
 * Enregistre la vidéo (HEVC, 8 ou 10 bits HLG) et le son (AAC) dans un MP4 placé dans DCIM/MaCam.
 * Les horodatages vidéo viennent du capteur ; [clock] doit renvoyer la même base de temps (en ns)
 * pour que le son reste synchronisé.
 */
class Recorder(
    private val ctx: Context,
    val baseName: String,
    private val size: Size,
    private val fps: Int,
    private val bitrate: Int,
    private val tenBit: Boolean,
    private val orientationHint: Int,
    private val clock: () -> Long,
) {
    lateinit var inputSurface: Surface; private set

    /** Horodatage capteur (ns) de la première image enregistrée, pour aligner le gyroscope. */
    @Volatile var firstVideoTimestampNs = -1L; private set

    private lateinit var video: MediaCodec
    private lateinit var audio: MediaCodec
    private var audioRecord: AudioRecord? = null
    private lateinit var muxer: MediaMuxer
    private lateinit var pfd: ParcelFileDescriptor
    private lateinit var uri: Uri
    private var startNs = 0L
    @Volatile private var audioRunning = false
    private val threads = mutableListOf<Thread>()

    // État du multiplexeur : on attend que les deux pistes soient connues avant de démarrer.
    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val pending = ArrayList<Triple<Boolean, ByteBuffer, MediaCodec.BufferInfo>>()

    fun prepare() {
        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        video.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
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

        audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audio.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$baseName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/MaCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Impossible de créer le fichier vidéo")
        pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")!!
        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(orientationHint)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        startNs = clock()
        video.start()
        audio.start()
        threads += Thread({ drain(video, true) }, "video-drain")
        audioRecord = try {
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 4)
        } catch (e: Exception) { Log.w(TAG, "micro indisponible", e); null }
        audioRunning = true
        threads += Thread({ feedAudio() }, "audio-feed")
        threads += Thread({ drain(audio, false) }, "audio-drain")
        threads.forEach { it.start() }
    }

    /** Arrête proprement et publie le fichier. Renvoie l'URI de la vidéo. */
    fun stop(): Uri {
        video.signalEndOfInputStream()
        audioRunning = false
        threads.forEach { it.join(5000) }
        runCatching { audioRecord?.stop() }; audioRecord?.release()
        runCatching { video.stop() }; video.release()
        runCatching { audio.stop() }; audio.release()
        inputSurface.release()
        synchronized(lock) { if (muxerStarted) runCatching { muxer.stop() } }
        muxer.release()
        pfd.close()
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun feedAudio() {
        val rec = audioRecord
        var samples = 0L
        rec?.startRecording()
        val silence = ByteArray(4096)
        while (true) {
            val running = audioRunning
            val idx = audio.dequeueInputBuffer(10_000)
            if (idx < 0) { if (!running) break else continue }
            val buf = audio.getInputBuffer(idx)!!
            buf.clear()
            val read = if (rec != null) rec.read(buf, minOf(buf.remaining(), 4096)) else {
                Thread.sleep(21); buf.put(silence, 0, minOf(buf.remaining(), silence.size)); silence.size
            }
            val ptsUs = (startNs / 1000) + samples * 1_000_000 / SAMPLE_RATE
            if (read > 0) samples += read / 4 // 2 canaux × 16 bits
            audio.queueInputBuffer(idx, 0, maxOf(read, 0), ptsUs,
                if (running) 0 else MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            if (!running) break
        }
    }

    private fun drain(codec: MediaCodec, isVideo: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 100_000)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(lock) {
                    val t = muxer.addTrack(codec.outputFormat)
                    if (isVideo) videoTrack = t else audioTrack = t
                    if (videoTrack >= 0 && audioTrack >= 0) {
                        muxer.start(); muxerStarted = true
                        for ((v, b, i) in pending) muxer.writeSampleData(if (v) videoTrack else audioTrack, b, i)
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
                if (eos) break
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
            if (muxerStarted) muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, rel)
            else pending += Triple(isVideo, ByteBuffer.allocate(info.size).put(buf).flip() as ByteBuffer, rel)
        }
    }

    companion object {
        private const val TAG = "Recorder"
        private const val SAMPLE_RATE = 48_000
    }
}
