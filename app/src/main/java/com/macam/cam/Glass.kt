package com.macam.cam

import android.content.Context
import android.graphics.*
import android.view.View
import android.widget.FrameLayout

/**
 * Arrière-plan partagé des panneaux de verre : une copie très basse définition de l'aperçu,
 * floutée et légèrement saturée. Chaque [GlassView] en peint la portion qui se trouve derrière elle.
 */
object Backdrop {
    @Volatile var bitmap: Bitmap? = null; private set
    /** Vue dont le contenu est flouté (l'aperçu caméra). */
    var source: View? = null
    private val views = mutableSetOf<GlassView>()

    fun register(v: GlassView) { views += v }
    fun unregister(v: GlassView) { views -= v }

    /** Floute [small] (déjà réduite) et la publie. */
    fun update(small: Bitmap) {
        bitmap = blur(small, radius = (small.width / 28).coerceAtLeast(3), passes = 3, saturation = 1.35f)
        views.forEach { it.postInvalidate() }
    }

    fun clear() { bitmap = null; views.forEach { it.postInvalidate() } }

    /** Flou boîte séparable répété (≈ gaussien) + saturation, en entiers : rapide sur une petite image. */
    fun blur(src: Bitmap, radius: Int, passes: Int, saturation: Float): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val r = IntArray(w * h); val g = IntArray(w * h); val b = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
            val l = (cr * 77 + cg * 150 + cb * 29) shr 8
            r[i] = (l + (cr - l) * saturation).toInt().coerceIn(0, 255)
            g[i] = (l + (cg - l) * saturation).toInt().coerceIn(0, 255)
            b[i] = (l + (cb - l) * saturation).toInt().coerceIn(0, 255)
        }
        val tmp = IntArray(w * h)
        repeat(passes) { for (ch in arrayOf(r, g, b)) { boxH(ch, tmp, w, h, radius); boxV(tmp, ch, w, h, radius) } }
        for (i in px.indices) px[i] = (0xFF shl 24) or (r[i] shl 16) or (g[i] shl 8) or b[i]
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxH(s: IntArray, d: IntArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        for (y in 0 until h) {
            val o = y * w; var acc = 0
            for (i in -r..r) acc += s[o + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                d[o + x] = acc / n
                acc += s[o + (x + r + 1).coerceAtMost(w - 1)] - s[o + (x - r).coerceAtLeast(0)]
            }
        }
    }

    private fun boxV(s: IntArray, d: IntArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        for (x in 0 until w) {
            var acc = 0
            for (i in -r..r) acc += s[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                d[y * w + x] = acc / n
                acc += s[(y + r + 1).coerceAtMost(h - 1) * w + x] - s[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }
}

/**
 * Panneau de verre dépoli : flou réel de l'aperçu situé derrière, voile teinté, reflet diagonal
 * et liseré lumineux plus marqué sur le bord supérieur.
 */
class GlassView(ctx: Context, private val radiusPx: Float, private val tint: Int = 0x52101418) : FrameLayout(ctx) {

    private val path = Path()
    private val rect = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val me = IntArray(2); private val src = IntArray(2)
    private val density = ctx.resources.displayMetrics.density

    init {
        setWillNotDraw(false)
        clipToOutline = false
        // Ombre douce : le panneau « flotte » au-dessus de l'image.
        elevation = 10 * density
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, radiusPx) }
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Backdrop.register(this) }
    override fun onDetachedFromWindow() { Backdrop.unregister(this); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        path.reset(); path.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        tintPaint.color = tint
        // Reflet : lumière venant du haut à gauche, qui s'éteint vers le centre.
        sheenPaint.shader = LinearGradient(0f, 0f, w * 0.6f, h.toFloat(),
            intArrayOf(0x2EFFFFFF, 0x0AFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        // Liseré : plus lumineux en haut (lumière zénithale), presque invisible en bas.
        strokePaint.strokeWidth = 1.2f * density
        strokePaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x8CFFFFFF.toInt(), 0x26FFFFFF, 0x14FFFFFF), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(path)
        val bmp = Backdrop.bitmap
        val s = Backdrop.source
        if (bmp != null && s != null && s.width > 0) {
            getLocationInWindow(me); s.getLocationInWindow(src)
            val l = (src[0] - me[0]).toFloat(); val t = (src[1] - me[1]).toFloat()
            canvas.drawBitmap(bmp, null, RectF(l, t, l + s.width, t + s.height), bmpPaint)
        }
        canvas.drawRect(rect, tintPaint)
        canvas.drawRect(rect, sheenPaint)
        canvas.restore()
        val inset = strokePaint.strokeWidth / 2
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, radiusPx, radiusPx, strokePaint)
    }
}
