package com.roboduck.image

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roboduck.image.enhance.Enhancer
import com.roboduck.image.enhance.RgbImage
import com.roboduck.image.modem.ChirpDecoder
import com.roboduck.image.modem.ChirpEncoder
import com.roboduck.image.modem.Payload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs on a real Android system (the CI emulator): checks the parts that
 * depend on Android itself (its WebP encoder and decoder, audio, the screen)
 * and takes screenshots of every state, in light and dark mode.
 * Everything it finds goes to files/ci/report.txt plus PNGs.
 */
@RunWith(AndroidJUnit4::class)
class DeviceCheck {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val out = File(ctx.filesDir, "ci").apply { mkdirs() }
    private val dark: Boolean
        get() = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun log(line: String) {
        File(out, "report.txt").appendText(line + "\n")
        Log.i("RoboDuckCheck", line)
    }

    private fun photo(name: String): Bitmap =
        inst.context.assets.open("photos/$name").use { BitmapFactory.decodeStream(it) }

    private fun savePng(name: String, bmp: Bitmap) {
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun lum(b: Bitmap): FloatArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return FloatArray(px.size) {
            val p = px[it]
            0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
    }

    private fun fitTo(b: Bitmap, side: Int): Bitmap {
        val s = side.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * s).toInt()), maxOf(1, (b.height * s).toInt()), true)
    }

    @Test
    fun codec() {
        log("== Picture compression (Android's own WebP encoder), budget ${ImageCodec.BUDGET} bytes")
        for (name in listOf("fruits.jpg", "building.jpg", "home.jpg", "orange.jpg")) {
            val src = photo(name)
            val t0 = SystemClock.elapsedRealtime()
            val p = ImageCodec.compress(src)
            val ms = SystemClock.elapsedRealtime() - t0
            assertTrue("payload too big", p.payload.size <= ImageCodec.BUDGET)
            // similarity to the original, both viewed at 256 px
            val ref = fitTo(src, 256)
            val got = Bitmap.createScaledBitmap(p.preview, ref.width, ref.height, true)
            val score = ImageCodec.ssim(lum(got), lum(ref), ref.width, ref.height)
            // the receiver's rebuilt file must decode to exactly the sender's preview
            val full = ImageCodec.decodeQuietly(Payload.unpack(p.payload)!!)
            val same = full != null && full.sameAs(p.preview)
            // live preview from the first 60% of the bytes: rows it claims vs rows really right
            val part = p.payload.copyOf(p.payload.size * 6 / 10)
            val pv = ImageCodec.previewSoFar(part, p.payload.size)
            var exactRows = 0
            if (pv != null && full != null) {
                val ra = IntArray(full.width); val rb = IntArray(full.width)
                for (y in 0 until full.height) {
                    pv.first.getPixels(ra, 0, full.width, 0, y, full.width, 1)
                    full.getPixels(rb, 0, full.width, 0, y, full.width, 1)
                    if (!ra.contentEquals(rb)) break
                    exactRows = y + 1
                }
            }
            log(
                "%-13s -> %dx%d px, WebP q%d, sent %d bytes (Android's file %d bytes: %s), similarity %.3f, %d ms | rebuilt = sender's picture: %s | 60%% of bytes: preview %s, claims %.0f%% rows, really exact %.0f%%".format(
                    name, p.preview.width, p.preview.height, p.quality, p.payload.size, p.fileSize, p.fileChunks, score, ms, same,
                    if (pv == null) "NONE" else "ok", 100 * (pv?.second ?: 0f), if (full == null) 0f else 100f * exactRows / full.height
                )
            )
            assertTrue("rebuilt picture differs", same)
            if (pv != null) assertTrue("preview claims more rows than are right", pv.second * (full?.height ?: 1) <= exactRows + 0.5f)
            savePng("preview-" + name.substringBefore('.'), Bitmap.createScaledBitmap(p.preview, p.preview.width * 3, p.preview.height * 3, true))
        }
    }

    @Test
    fun modem() {
        log("== Sound round trip on the device (encode -> noise -> decode)")
        val p = ImageCodec.compress(photo("fruits.jpg"))
        val t0 = SystemClock.elapsedRealtime()
        val wave = ChirpEncoder.encode(p.payload)
        val encMs = SystemClock.elapsedRealtime() - t0
        val rnd = Random(7)
        var power = 0.0
        for (v in wave) power += v * v
        val noise = Math.sqrt(power / wave.size / Math.pow(10.0, 5.0 / 10)) // 5 dB SNR
        val rx = FloatArray(wave.size + 44100) { i ->
            val s = if (i in 22050 until 22050 + wave.size) wave[i - 22050] else 0f
            (s + rnd.nextGaussian() * noise).toFloat()
        }
        var result: ByteArray? = null
        var ok = false
        val dec = ChirpDecoder(object : ChirpDecoder.Listener {
            override fun onStart(bytes: Int) {}
            override fun onProgress(fraction: Float) {}
            override fun onPartial(data: ByteArray) {}
            override fun onDone(data: ByteArray, ok2: Boolean) { result = data; ok = ok2 }
            override fun onLost() {}
        })
        val t1 = SystemClock.elapsedRealtime()
        var i = 0
        while (i < rx.size) {
            val n = minOf(2048, rx.size - i)
            dec.feed(rx.copyOfRange(i, i + n), n)
            i += n
        }
        val decMs = SystemClock.elapsedRealtime() - t1
        val seconds = wave.size / 44100.0
        log(
            "payload %d bytes -> %.1f s of sound (encode %d ms); decode took %d ms = %.0f%% of real time; exact copy: %s".format(
                p.payload.size, seconds, encMs, decMs, 100.0 * decMs / (seconds * 1000), ok && result!!.contentEquals(p.payload)
            )
        )
        assertTrue(ok)
        assertArrayEquals(p.payload, result)
    }

    @Test
    fun tools() {
        log("== ImageJ picture tools")
        val p = ImageCodec.compress(photo("building.jpg"))
        val px = IntArray(p.preview.width * p.preview.height)
        p.preview.getPixels(px, 0, p.preview.width, 0, 0, p.preview.width, p.preview.height)
        val orig = RgbImage.fromArgb(px, p.preview.width, p.preview.height)
        val up = Enhancer.upscale(orig)
        val steps = listOf<Pair<String, () -> RgbImage>>(
            "Auto" to { Enhancer.auto(orig) },
            "Sharpen" to { Enhancer.sharpen(up) },
            "Smooth" to { Enhancer.smooth(up) },
            "Contrast" to { Enhancer.contrast(up) },
            "Denoise" to { Enhancer.denoise(up) }
        )
        val line = StringBuilder()
        for ((name, f) in steps) {
            val t0 = SystemClock.elapsedRealtime()
            val r = f()
            line.append("$name ${SystemClock.elapsedRealtime() - t0} ms (${r.width}x${r.height})  ")
        }
        log(line.toString())
    }

    @Test
    fun audio() {
        log("== Speaker and microphone")
        val tone = FloatArray(44100) { (0.3 * Math.sin(2 * Math.PI * 2000 * it / 44100.0)).toFloat() }
        val done = CountDownLatch(1)
        var finished = false
        Player(tone, object : Player.Callback {
            override fun onLevel(rms: Float) {}
            override fun onProgress(fraction: Float) {}
            override fun onFinished(completed: Boolean) { finished = completed; done.countDown() }
        }).start()
        done.await(10, TimeUnit.SECONDS)
        log("speaker: played 1 s, finished=$finished")

        val levels = AtomicInteger()
        val errors = AtomicInteger()
        val rec = Recorder(
            ctx.getSystemService(AudioManager::class.java),
            ChirpDecoder(object : ChirpDecoder.Listener {
                override fun onStart(bytes: Int) {}
                override fun onProgress(fraction: Float) {}
                override fun onPartial(data: ByteArray) {}
                override fun onDone(data: ByteArray, ok: Boolean) {}
                override fun onLost() {}
            }),
            object : Recorder.Callback {
                override fun onLevel(rms: Float) { levels.incrementAndGet() }
                override fun onSpectrum(columns: List<FloatArray>) {}
                override fun onError() { errors.incrementAndGet() }
            }
        )
        rec.start()
        Thread.sleep(2000)
        rec.cancel()
        rec.join(3000)
        log("microphone: ${levels.get()} chunks in 2 s, errors=${errors.get()} (about 43 expected)")
    }

    @Test
    fun screens() {
        val suffix = if (dark) "-dark" else "-light"
        log("== Screenshots$suffix")
        val p = ImageCodec.compress(photo("fruits.jpg"))
        val (partial, shown) = ImageCodec.previewSoFar(p.payload.copyOf(p.payload.size * 70 / 100), p.payload.size)!!
        val whistles = IntArray(12) { (it * 37 + 11) % 128 }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            fun shot(name: String) {
                Thread.sleep(1500)
                val b = inst.uiAutomation.takeScreenshot()
                savePng("screen-$name$suffix", b)
            }
            shot("1-ready")
            sc.onActivity { it.demoSending(p.preview, 0.42f) }
            shot("2-sending")
            sc.onActivity { it.demoReceiving(partial, shown, whistles) }
            shot("3-receiving")
            sc.onActivity { it.demoReceived(p.preview) }
            shot("4-received")
            sc.onActivity { it.findViewById<View>(R.id.tool_auto).performClick() }
            Thread.sleep(2500)
            shot("5-auto")
        }
        log("saved 5 screenshots$suffix")
    }
}
