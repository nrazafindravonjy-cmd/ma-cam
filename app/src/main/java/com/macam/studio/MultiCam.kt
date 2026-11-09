package com.macam.studio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Lecture du son des vidéos pour la synchronisation multicam. */
object MultiCam {

    /** Son mono (premières [maxSeconds] s) et sa fréquence d'échantillonnage. */
    fun decodeAudio(ctx: Context, uri: Uri, maxSeconds: Int = 120): Pair<FloatArray, Int>? {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
        val ex = MediaExtractor()
        try {
            ex.setDataSource(pfd.fileDescriptor)
            val idx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") } ?: return null
            val fmt = ex.getTrackFormat(idx); ex.selectTrack(idx)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(fmt, null, null, 0); dec.start()
            val out = FloatArrayBuilder(rate * maxSeconds)
            var floatPcm = false
            val info = MediaCodec.BufferInfo(); var inDone = false
            loop@ while (out.size < rate * maxSeconds) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val b = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    b.position(info.offset); b.limit(info.offset + info.size)
                    if (floatPcm) { val fb = b.asFloatBuffer(); while (fb.remaining() >= ch) { var s = 0f; repeat(ch) { s += fb.get() }; out.add(s / ch) } }
                    else { val sb = b.asShortBuffer(); while (sb.remaining() >= ch) { var s = 0f; repeat(ch) { s += sb.get() / 32768f }; out.add(s / ch) } }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                }
            }
            runCatching { dec.stop() }; dec.release()
            return out.toArray() to rate
        } finally { ex.release(); pfd.close() }
    }

    /** Premier horodatage vidéo (µs) : le décalage audio se compte depuis le début de la vidéo. */
    fun firstVideoUs(ctx: Context, uri: Uri): Long = ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
        val ex = MediaExtractor(); ex.setDataSource(pfd.fileDescriptor)
        val v = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        val a = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        fun first(t: Int?): Long { if (t == null) return 0; ex.selectTrack(t); val s = ex.sampleTime; ex.unselectTrack(t); return s }
        val r = first(v) - first(a); ex.release(); r
    }

    private class FloatArrayBuilder(cap: Int) {
        private var a = FloatArray(maxOf(16, cap)); var size = 0
        fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size)
    }
}
