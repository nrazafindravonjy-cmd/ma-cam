package com.macam.studio

import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.*
import android.net.Uri
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Atelier vidéo : décodage → GPU (stabilisation gyroscopique + obturateur roulant, débruitage temporel,
 * LUT 3D) → réencodage HEVC. Le son est recopié sans réencodage.
 */
class VideoStudio(private val ctx: Context) {

    data class Options(
        val stabilize: Boolean,
        /** Constante de temps du lissage (s) : plus grand = plus fluide. */
        val smoothingS: Double = 0.6,
        /** Recadrage pour la marge de stabilisation (1.1 = 10 %). */
        val zoom: Double = 1.1,
        /** Force du débruitage temporel, 0 (aucun) à 0.85. */
        val denoise: Float = 0f,
        val lut: Lut? = null,
        /** Début coupé (µs depuis la première image) : synchronisation multicam à l'image près. */
        val trimStartUs: Long = 0,
    )

    @Volatile var cancelled = false
    /** Le dernier traitement a-t-il conservé le HDR ? (pour informer l'utilisateur) */
    @Volatile var lastRunHdr = false; private set
    @Volatile var lastRunSourceHdr = false; private set

    /** Traite [input] et renvoie l'URI de la vidéo produite dans DCIM/MaCam. */
    fun process(input: Uri, gyro: GyroLog?, opts: Options, outName: String, progress: (Float) -> Unit): Uri {
        val pfdIn = ctx.contentResolver.openFileDescriptor(input, "r")!!
        val ex = MediaExtractor().apply { setDataSource(pfdIn.fileDescriptor) }
        val vIdx = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        val aIdx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        val vFmt = ex.getTrackFormat(vIdx)
        val w = vFmt.getInteger(MediaFormat.KEY_WIDTH); val h = vFmt.getInteger(MediaFormat.KEY_HEIGHT)
        val durUs = if (vFmt.containsKey(MediaFormat.KEY_DURATION)) vFmt.getLong(MediaFormat.KEY_DURATION) else 1L
        val rotation = if (vFmt.containsKey(MediaFormat.KEY_ROTATION)) vFmt.getInteger(MediaFormat.KEY_ROTATION) else 0
        val fps = if (vFmt.containsKey(MediaFormat.KEY_FRAME_RATE)) vFmt.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
        val srcBitrate = runCatching { (ctx.contentResolver.openFileDescriptor(input, "r")!!.use { it.statSize } * 8 * 1_000_000 / durUs).toInt() }
            .getOrDefault(50_000_000)

        val stab = if (opts.stabilize && gyro != null && gyro.tMs.size > 10)
            Stabilizer(gyro, opts.smoothingS, opts.zoom) else null
        // Focale en pixels de la vidéo : focale capteur × (largeur vidéo / largeur active du capteur).
        // Focale de la vidéo en pixels : mesurée (journaux récents) ou déduite de la focale équivalente 24×36.
        val fPx = gyro?.focalPx?.let { f -> f * w / (gyro.activeWidth ?: w).toDouble() }
            ?: gyro?.eqMm?.let { eq -> eq / 43.27 * 1.25 * maxOf(w, h) }
            ?: (0.75 * maxOf(w, h))
        stab?.limit(stab.maxAngleFor(fPx, w, h))
        val readoutMs = gyro?.readoutMs ?: 0.0

        // HDR conservé si la source est HLG, qu'aucun look SDR n'est demandé et que le GPU sait le faire.
        val srcHlg = vFmt.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
            vFmt.getInteger(MediaFormat.KEY_COLOR_TRANSFER) == MediaFormat.COLOR_TRANSFER_HLG
        // Le HDR n'est tenté que si un encodeur 10 bits existe : la surface GPU 10 bits HLG doit l'alimenter.
        val hdrCodec = com.macam.cam.Codecs.pick(android.util.Size(w, h), fps, true)
        val egl = Egl(tryHdr = srcHlg && opts.lut == null && hdrCodec != null)
        // Encodeur : HEVC 10 bits si HDR, sinon HEVC ou H.264 (universel).
        val choice = (if (egl.hdr) hdrCodec else null)
            ?: com.macam.cam.Codecs.pick(android.util.Size(w, h), fps, false)
            ?: com.macam.cam.Codecs.Choice(MediaFormat.MIMETYPE_VIDEO_AVC, 20_000_000, false)
        val hdr = egl.hdr
        lastRunHdr = hdr
        lastRunSourceHdr = srcHlg

        val enc = MediaCodec.createEncoderByType(choice.mime)
        enc.configure(MediaFormat.createVideoFormat(choice.mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, srcBitrate.coerceIn(8_000_000, 100_000_000).coerceAtMost(choice.maxBitrate))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Traitement hors temps réel : on demande au codec d'aller aussi vite que possible.
            setInteger(MediaFormat.KEY_PRIORITY, 1)
            setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            if (hdr) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        egl.attach(enc.createInputSurface())
        enc.start()
        val gl = Pipeline(w, h, opts.lut, hdr)

        // Décodeur vers une texture GPU
        val frameThread = HandlerThread("frames").apply { start() }
        val st = SurfaceTexture(gl.oesTex)
        val frameLock = Object(); var frameReady = false
        st.setOnFrameAvailableListener({ synchronized(frameLock) { frameReady = true; frameLock.notifyAll() } }, Handler(frameThread.looper))
        val decSurface = Surface(st)
        ex.selectTrack(vIdx)
        val dec = MediaCodec.createDecoderByType(vFmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(MediaFormat(vFmt).apply {
            setInteger(MediaFormat.KEY_PRIORITY, 1)
            setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        }, decSurface, null, 0)
        dec.start()

        // Sortie
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$outName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/MaCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val outUri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        val pfdOut = ctx.contentResolver.openFileDescriptor(outUri, "rw")!!
        val mux = MediaMuxer(pfdOut.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(rotation) }
        var vTrack = -1; var aTrack = -1; var muxing = false
        val aFmt = aIdx?.let { ex.getTrackFormat(it) }

        val info = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        var inputDone = false; var decodeDone = false; var encodeDone = false
        var firstPts = -1L; var ok = false

        fun drainEncoder(endOfStream: Boolean) {
            while (true) {
                val i = enc.dequeueOutputBuffer(encInfo, if (endOfStream) 10_000 else 0)
                if (i == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!endOfStream) return else continue }
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    vTrack = mux.addTrack(enc.outputFormat)
                    if (aFmt != null) aTrack = mux.addTrack(aFmt)
                    mux.start(); muxing = true
                } else if (i >= 0) {
                    val b = enc.getOutputBuffer(i)!!
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                    if (encInfo.size > 0 && muxing) { b.position(encInfo.offset); b.limit(encInfo.offset + encInfo.size); mux.writeSampleData(vTrack, b, encInfo) }
                    enc.releaseOutputBuffer(i, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encodeDone = true; return }
                }
            }
        }

        try {
            while (!decodeDone) {
                if (cancelled) throw InterruptedException("Annulé")
                if (!inputDone) {
                    val ii = dec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(ii)!!, 0)
                        if (n < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { dec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val pts = info.presentationTimeUs
                    if (firstPts < 0 && info.size > 0) firstPts = pts
                    // Images avant le point de synchronisation : décodées mais ni affichées ni encodées.
                    val render = info.size > 0 && pts - firstPts >= opts.trimStartUs
                    dec.releaseOutputBuffer(oi, render)
                    if (render) {
                        synchronized(frameLock) {
                            val deadline = System.currentTimeMillis() + 2500
                            while (!frameReady && System.currentTimeMillis() < deadline) frameLock.wait(100)
                            frameReady = false
                        }
                        st.updateTexImage()
                        val tMs = (info.presentationTimeUs - firstPts) / 1000.0
                        val hTop = stab?.homography(tMs, fPx, w, h)
                        val hBot = stab?.homography(tMs + readoutMs, fPx, w, h)
                        gl.draw(st, hTop, hBot, opts.denoise)
                        EGLExt.eglPresentationTimeANDROID(egl.display, egl.surface, (pts - firstPts - opts.trimStartUs) * 1000)
                        egl.swap()
                        drainEncoder(false)
                        progress((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 0.98f))
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decodeDone = true
                }
            }
            enc.signalEndOfInputStream()
            while (!encodeDone) drainEncoder(true)

            // Son recopié tel quel (pas de perte de qualité).
            if (aIdx != null && aTrack >= 0) {
                val ax = MediaExtractor().apply { setDataSource(pfdIn.fileDescriptor); selectTrack(aIdx) }
                val buf = ByteBuffer.allocate(1 shl 20); val ai = MediaCodec.BufferInfo()
                val start = maxOf(firstPts, 0) + opts.trimStartUs // même origine que la vidéo
                while (true) {
                    val n = ax.readSampleData(buf, 0); if (n < 0) break
                    if (ax.sampleTime < start) { ax.advance(); continue }
                    ai.set(0, n, ax.sampleTime - start, if (ax.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(aTrack, buf, ai); ax.advance()
                }
                ax.release()
            }
            ok = true
        } finally {
            runCatching { dec.stop() }; dec.release()
            runCatching { enc.stop() }; enc.release()
            gl.release(); st.release(); decSurface.release(); egl.release(); frameThread.quitSafely()
            if (muxing) runCatching { mux.stop() }
            mux.release(); pfdOut.close(); ex.release(); pfdIn.close()
            if (ok) ctx.contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            else ctx.contentResolver.delete(outUri, null, null)
        }
        progress(1f)
        return outUri
    }

    // ------------------------------------------------------------------ EGL

    /**
     * Contexte GPU. En HDR : tampon 10 bits (RGBA 10-10-10-2) et surface déclarée HLG/BT.2020, à condition que
     * le téléphone expose EGL_EXT_gl_colorspace_bt2020_hlg et GL_EXT_YUV_target ; sinon repli en SDR 8 bits.
     */
    private class Egl(tryHdr: Boolean) {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        lateinit var surface: EGLSurface; private set
        private val context: EGLContext
        private val config: EGLConfig
        private val pbuffer: EGLSurface
        private var window: Surface? = null
        val hdr: Boolean

        init {
            val v = IntArray(2); EGL14.eglInitialize(display, v, 0, v, 1)
            val eglExt = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: ""
            val wantHdr = tryHdr && "EGL_EXT_gl_colorspace_bt2020_hlg" in eglExt
            fun choose(bits: IntArray): EGLConfig? {
                val attrs = intArrayOf(EGL14.EGL_RED_SIZE, bits[0], EGL14.EGL_GREEN_SIZE, bits[1], EGL14.EGL_BLUE_SIZE, bits[2], EGL14.EGL_ALPHA_SIZE, bits[3],
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
                val cfg = arrayOfNulls<EGLConfig>(8); val n = IntArray(1)
                EGL14.eglChooseConfig(display, attrs, 0, cfg, 0, cfg.size, n, 0)
                // eglChooseConfig accepte « au moins » ces tailles : on exige la taille exacte du rouge.
                return cfg.take(n[0]).firstOrNull { c ->
                    val r = IntArray(1); EGL14.eglGetConfigAttrib(display, c, EGL14.EGL_RED_SIZE, r, 0); r[0] == bits[0]
                }
            }
            val hdrCfg = if (wantHdr) choose(intArrayOf(10, 10, 10, 2)) else null
            config = hdrCfg ?: choose(intArrayOf(8, 8, 8, 8))!!
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "EGL indisponible" }
            val glExt = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
            hdr = hdrCfg != null && "GL_EXT_YUV_target" in glExt
        }

        fun attach(win: Surface) {
            window = win
            val attrs = if (hdr) intArrayOf(0x309D /* EGL_GL_COLORSPACE_KHR */, 0x3540 /* BT2020_HLG_EXT */, EGL14.EGL_NONE)
                        else intArrayOf(EGL14.EGL_NONE)
            surface = EGL14.eglCreateWindowSurface(display, config, win, attrs, 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Surface d'encodage indisponible" }
        }

        fun swap() = EGL14.eglSwapBuffers(display, surface)

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (::surface.isInitialized) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display); window?.release()
        }
    }

    // ------------------------------------------------------------------ passes GPU

    private class Pipeline(private val w: Int, private val h: Int, lut: Lut?, private val hdr: Boolean) {
        val oesTex: Int
        private val stabProg: Int; private val finishProg: Int; private val copyProg: Int
        private val fbo = IntArray(1)
        private val texA: Int
        private val texOut = IntArray(2)
        private var lutTex = 0; private val lutSize = lut?.size ?: 0
        private var cur = 0; private var hasPrev = false
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 8).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        private val stMatrix = FloatArray(16)

        init {
            oesTex = genTex(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            texA = colorTex(); texOut[0] = colorTex(); texOut[1] = colorTex()
            GLES30.glGenFramebuffers(1, fbo, 0)
            stabProg = program(VS, if (hdr) FS_STAB_HDR else FS_STAB); finishProg = program(VS, FS_FINISH); copyProg = program(VS, FS_COPY)
            if (lut != null) {
                lutTex = genTex(GLES30.GL_TEXTURE_3D)
                val bytes = ByteBuffer.allocateDirect(lut.data.size / 3 * 4)
                for (i in 0 until lut.data.size / 3) {
                    bytes.put((lut.data[i * 3] * 255 + 0.5f).toInt().toByte()); bytes.put((lut.data[i * 3 + 1] * 255 + 0.5f).toInt().toByte())
                    bytes.put((lut.data[i * 3 + 2] * 255 + 0.5f).toInt().toByte()); bytes.put(-1)
                }
                bytes.position(0)
                GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, lut.size, lut.size, lut.size, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
            }
        }

        private fun genTex(target: Int): Int {
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0); GLES30.glBindTexture(target, t[0])
            for (p in listOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) GLES30.glTexParameteri(target, p, GLES30.GL_LINEAR)
            for (p in listOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) GLES30.glTexParameteri(target, p, GLES30.GL_CLAMP_TO_EDGE)
            return t[0]
        }

        private fun colorTex(): Int {
            val t = genTex(GLES30.GL_TEXTURE_2D)
            // 10 bits par canal en HDR, pour ne pas créer de bandes dans les dégradés.
            if (hdr) GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB10_A2, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, null)
            else GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            return t
        }

        private fun target(tex: Int?) {
            if (tex == null) GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            else {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
            }
            GLES30.glViewport(0, 0, w, h)
        }

        private fun drawQuad(prog: Int) {
            val loc = GLES30.glGetAttribLocation(prog, "aPos")
            GLES30.glEnableVertexAttribArray(loc)
            GLES30.glVertexAttribPointer(loc, 2, GLES30.GL_FLOAT, false, 8, quad)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun bind(prog: Int, unit: Int, target: Int, tex: Int, name: String) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit); GLES30.glBindTexture(target, tex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, name), unit)
        }

        /** Matrice ligne par ligne → colonne par colonne (convention GLSL). */
        private fun colMajor(m: DoubleArray) = FloatArray(9) { i -> m[(i % 3) * 3 + i / 3].toFloat() }

        fun draw(st: SurfaceTexture, hTop: DoubleArray?, hBot: DoubleArray?, denoise: Float) {
            st.getTransformMatrix(stMatrix)
            // 1. Stabilisation (lecture de l'image décodée)
            target(texA)
            GLES30.glUseProgram(stabProg)
            bind(stabProg, 0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, "uTex")
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(stabProg, "uSt"), 1, false, stMatrix, 0)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(stabProg, "uSize"), w.toFloat(), h.toFloat())
            GLES30.glUniform1i(GLES30.glGetUniformLocation(stabProg, "uStab"), if (hTop != null) 1 else 0)
            if (hTop != null && hBot != null) {
                GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(stabProg, "uHt"), 1, false, colMajor(hTop), 0)
                GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(stabProg, "uHb"), 1, false, colMajor(hBot), 0)
            }
            drawQuad(stabProg)
            // 2. Débruitage temporel + LUT
            val out = texOut[cur]; val prev = texOut[1 - cur]
            target(out)
            GLES30.glUseProgram(finishProg)
            bind(finishProg, 0, GLES30.GL_TEXTURE_2D, texA, "uCur")
            bind(finishProg, 1, GLES30.GL_TEXTURE_2D, prev, "uPrev")
            GLES30.glUniform1f(GLES30.glGetUniformLocation(finishProg, "uDn"), if (hasPrev) denoise else 0f)
            drawQuad(finishProg)
            // 3. LUT puis écriture vers l'encodeur. La LUT n'entre pas dans la boucle du débruitage,
            //    sinon elle s'appliquerait de nouveau à chaque image.
            target(null)
            GLES30.glUseProgram(copyProg)
            bind(copyProg, 0, GLES30.GL_TEXTURE_2D, out, "uTex")
            if (lutTex != 0) bind(copyProg, 1, GLES30.GL_TEXTURE_3D, lutTex, "uLut")
            GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProg, "uLutOn"), if (lutTex != 0) 1 else 0)
            val n = lutSize.coerceAtLeast(2).toFloat()
            GLES30.glUniform1f(GLES30.glGetUniformLocation(copyProg, "uLutScale"), (n - 1) / n)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(copyProg, "uLutOff"), 0.5f / n)
            drawQuad(copyProg)
            cur = 1 - cur; hasPrev = true
        }

        fun release() {
            GLES30.glDeleteTextures(4, intArrayOf(oesTex, texA, texOut[0], texOut[1]), 0)
            if (lutTex != 0) GLES30.glDeleteTextures(1, intArrayOf(lutTex), 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            listOf(stabProg, finishProg, copyProg).forEach { GLES30.glDeleteProgram(it) }
        }

        private fun program(vs: String, fs: String): Int {
            fun shader(type: Int, src: String) = GLES30.glCreateShader(type).also {
                GLES30.glShaderSource(it, src); GLES30.glCompileShader(it)
                val ok = IntArray(1); GLES30.glGetShaderiv(it, GLES30.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "Shader : " + GLES30.glGetShaderInfoLog(it) }
            }
            return GLES30.glCreateProgram().also {
                GLES30.glAttachShader(it, shader(GLES30.GL_VERTEX_SHADER, vs))
                GLES30.glAttachShader(it, shader(GLES30.GL_FRAGMENT_SHADER, fs))
                GLES30.glLinkProgram(it)
            }
        }

        companion object {
            const val VS = """#version 300 es
in vec2 aPos; out vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }"""

            /** Homographie interpolée entre le haut et le bas de l'image (obturateur roulant). */
            const val FS_STAB = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex; uniform mat4 uSt; uniform mat3 uHt; uniform mat3 uHb;
uniform vec2 uSize; uniform int uStab;
in vec2 vUv; out vec4 o;
void main() {
    vec2 uv = vUv;
    if (uStab == 1) {
        vec2 pix = vec2(vUv.x, 1.0 - vUv.y) * uSize;
        float f = pix.y / uSize.y;
        vec3 q = (uHt * (1.0 - f) + uHb * f) * vec3(pix, 1.0);
        vec2 p = q.xy / q.z;
        uv = vec2(p.x / uSize.x, 1.0 - p.y / uSize.y);
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) { o = vec4(0.0, 0.0, 0.0, 1.0); return; }
    }
    o = texture(uTex, (uSt * vec4(uv, 0.0, 1.0)).xy);
}"""

            /**
             * Variante HDR : lecture des valeurs YUV 10 bits brutes (GL_EXT_YUV_target), conversion BT.2020
             * plage limitée → R'G'B' HLG, sans passer par la conversion 8 bits du pilote.
             */
            const val FS_STAB_HDR = """#version 300 es
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uTex; uniform mat4 uSt; uniform mat3 uHt; uniform mat3 uHb;
uniform vec2 uSize; uniform int uStab;
in vec2 vUv; out vec4 o;
void main() {
    vec2 uv = vUv;
    if (uStab == 1) {
        vec2 pix = vec2(vUv.x, 1.0 - vUv.y) * uSize;
        float f = pix.y / uSize.y;
        vec3 q = (uHt * (1.0 - f) + uHb * f) * vec3(pix, 1.0);
        vec2 p = q.xy / q.z;
        uv = vec2(p.x / uSize.x, 1.0 - p.y / uSize.y);
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) { o = vec4(0.0, 0.0, 0.0, 1.0); return; }
    }
    vec3 yuv = texture(uTex, (uSt * vec4(uv, 0.0, 1.0)).xy).xyz;
    float y = (yuv.x * 1023.0 - 64.0) / 876.0;
    float cb = (yuv.y * 1023.0 - 512.0) / 896.0;
    float cr = (yuv.z * 1023.0 - 512.0) / 896.0;
    vec3 rgb = vec3(y + 1.4746 * cr, y - 0.16455 * cb - 0.57135 * cr, y + 1.8814 * cb);
    o = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}"""

            /** Filtre récursif adaptatif : moyenne avec l'image précédente, sauf là où ça bouge. */
            const val FS_FINISH = """#version 300 es
precision highp float;
uniform sampler2D uCur; uniform sampler2D uPrev; uniform float uDn;
in vec2 vUv; out vec4 o;
void main() {
    vec3 c = texture(uCur, vUv).rgb;
    if (uDn > 0.0) {
        vec3 p = texture(uPrev, vUv).rgb;
        vec3 d = c - p;
        c = mix(c, p, uDn * exp(-dot(d, d) / 0.0025));
    }
    o = vec4(c, 1.0);
}"""

            const val FS_COPY = """#version 300 es
precision highp float; precision highp sampler3D;
uniform sampler2D uTex; uniform sampler3D uLut; uniform int uLutOn; uniform float uLutScale; uniform float uLutOff;
in vec2 vUv; out vec4 o;
void main() {
    vec3 c = texture(uTex, vUv).rgb;
    if (uLutOn == 1) c = texture(uLut, c * uLutScale + uLutOff).rgb;
    o = vec4(c, 1.0);
}"""
        }
    }
}
