package com.macam.cam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Rendu réel de l'interface (moteur graphique natif d'Android via Robolectric), sans téléphone.
 * Les PNG sont écrits dans app/build/design/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-port-xxhdpi")
class DesignScreenshotTest {

    private fun scene(name: String): Bitmap =
        javaClass.classLoader!!.getResourceAsStream(name).use { BitmapFactory.decodeStream(it) }

    private fun shoot(tab: Tab, sceneName: String, status: String, out: String) {
        val act = Robolectric.buildActivity(CameraActivity::class.java).setup().get()
        act.showDemo(tab, scene(sceneName), status)
        val root = act.window.decorView
        val w = 393 * 3; val h = 852 * 3
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File("build/design").apply { mkdirs() }
        File(dir, out).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun photo() = shoot(Tab.PHOTO, "scene_day.jpg", "HDR+ · 8 images · HIGH · 12 Go · RAW", "1_photo.png")
    @Test fun night() = shoot(Tab.NIGHT, "scene_city.jpg", "Nuit · 13 images · ne bougez pas (3 s)", "2_nuit.png")
    @Test fun video() = shoot(Tab.VIDEO, "scene_day.jpg", "4K30 HDR  ISO 100  1/60  MAP ∞", "3_video.png")
    @Test fun portrait() = shoot(Tab.PORTRAIT, "scene_city.jpg", "Portrait (traitement du fabricant)", "4_portrait.png")
}
