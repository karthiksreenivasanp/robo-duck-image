package com.roboduck.image

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.roboduck.image.enhance.Enhancer
import com.roboduck.image.enhance.RgbImage
import com.roboduck.image.modem.ChirpDecoder
import com.roboduck.image.modem.ChirpEncoder
import com.roboduck.image.modem.GapMemory
import com.roboduck.image.modem.Payload
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private enum class Mode { IDLE, SENDING, RECEIVING }

    private lateinit var image: ImageView
    private lateinit var placeholder: View
    private lateinit var live: LiveCanvas
    private lateinit var meter: View
    private lateinit var level: LevelView
    private lateinit var progress: NeoProgress
    private lateinit var status: TextView
    private lateinit var tools: View
    private lateinit var sendButton: NeoButton
    private lateinit var receiveButton: NeoButton
    private lateinit var toolButtons: List<NeoButton>
    private lateinit var undoButton: NeoButton

    private val ui = Handler(Looper.getMainLooper())
    private var mode = Mode.IDLE
    private var player: Player? = null
    private var recorder: Recorder? = null
    private var lowVolume = false

    // Parts of a picture that arrived, kept while the app is open so a resend fills the gaps.
    private val gaps = GapMemory()
    @Volatile private var decoder: ChirpDecoder? = null
    private var totalSeconds = 0.0
    private var expectedBytes = 0

    // Picture tools: the received picture, the edited version (null = showing the original),
    // and earlier versions for Undo.
    private var original: RgbImage? = null
    private var originalBitmap: Bitmap? = null
    private var edited: RgbImage? = null
    private val history = ArrayDeque<RgbImage?>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        image = findViewById(R.id.image)
        placeholder = findViewById(R.id.placeholder)
        live = findViewById(R.id.live)
        meter = findViewById(R.id.meter)
        level = findViewById(R.id.level)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        tools = findViewById(R.id.tools)
        sendButton = findViewById(R.id.send)
        receiveButton = findViewById(R.id.receive)
        undoButton = findViewById(R.id.tool_undo)

        val root = findViewById<View>(R.id.root)
        val pl = root.paddingLeft
        val pt = root.paddingTop
        val pr = root.paddingRight
        val pb = root.paddingBottom
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(pl + bars.left, pt + bars.top, pr + bars.right, pb + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    pl + insets.systemWindowInsetLeft, pt + insets.systemWindowInsetTop,
                    pr + insets.systemWindowInsetRight, pb + insets.systemWindowInsetBottom
                )
            }
            insets
        }

        sendButton.setOnClickListener {
            when (mode) {
                Mode.IDLE -> pickImage()
                Mode.SENDING -> stopAll(getString(R.string.stopped))
                else -> {}
            }
        }
        receiveButton.setOnClickListener {
            when (mode) {
                Mode.IDLE -> startReceive()
                Mode.RECEIVING -> stopAll(getString(R.string.stopped))
                else -> {}
            }
        }

        toolButtons = listOf(
            tool(R.id.tool_auto, R.string.done_auto) { _ -> Enhancer.auto(original!!) },
            tool(R.id.tool_sharpen, R.string.done_sharpen) { Enhancer.sharpen(it) },
            tool(R.id.tool_smooth, R.string.done_smooth) { Enhancer.smooth(it) },
            tool(R.id.tool_contrast, R.string.done_contrast) { Enhancer.contrast(it) },
            tool(R.id.tool_denoise, R.string.done_denoise) { Enhancer.denoise(it) },
            undoButton,
            findViewById(R.id.tool_original),
            findViewById(R.id.tool_save)
        )
        undoButton.setOnClickListener { undo() }
        findViewById<View>(R.id.tool_original).setOnClickListener { showOriginal() }
        findViewById<View>(R.id.tool_save).setOnClickListener { saveEdited() }

        setPicture(null)
        render()
        setStatus(getString(R.string.ready))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Follow a dark/light switch right away when nothing would be lost.
        if (mode == Mode.IDLE && original == null && !busy) recreate()
    }

    override fun onDestroy() {
        player?.cancel()
        recorder?.cancel()
        super.onDestroy()
    }

    // ---------- Send ----------

    private fun pickImage() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            data?.data?.let { startSend(it) }
        }
    }

    private fun startSend(uri: Uri) {
        forgetPicture()
        mode = Mode.SENDING
        render()
        progress.indeterminate = true
        setStatus(getString(R.string.preparing))
        val am = getSystemService(AudioManager::class.java)
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        lowVolume = vol < am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.4

        Thread {
            val packed = try {
                ImageCodec.compress(ImageCodec.load(contentResolver, uri))
            } catch (e: Exception) {
                null
            }
            if (packed == null) {
                ui.post { stopAll(getString(R.string.open_failed)) }
                return@Thread
            }
            val preview = packed.preview
            val wave = ChirpEncoder.encode(packed.payload)
            totalSeconds = wave.size.toDouble() / com.roboduck.image.modem.Chirp.SAMPLE_RATE
            ui.post {
                if (mode != Mode.SENDING) return@post
                setPicture(preview)
                showLive(LiveCanvas.Mode.SEND)
                live.setImage(preview, 1f)
                live.setProgress(0f)
                progress.indeterminate = false
                progress.progress = 0
                level.active = true
                haptic()
                val p = Player(wave, object : Player.Callback {
                    override fun onLevel(rms: Float) {
                        ui.post { level.push(rms) }
                    }

                    override fun onProgress(fraction: Float) {
                        ui.post { showSendProgress(fraction) }
                    }

                    override fun onFinished(completed: Boolean) {
                        ui.post {
                            if (completed && mode == Mode.SENDING) {
                                finishMode(getString(R.string.sent))
                                haptic()
                            }
                        }
                    }
                })
                player = p
                p.start()
            }
        }.start()
    }

    private fun showSendProgress(fraction: Float) {
        if (mode != Mode.SENDING) return
        val pct = (fraction * 100).roundToInt().coerceIn(0, 100)
        progress.progress = pct
        live.setProgress(fraction)
        // Mention a low volume for the first few seconds only, then show the countdown.
        if (lowVolume && fraction < 0.06f) setStatus(getString(R.string.volume_low))
        else setStatus(getString(R.string.sending, pct, timeLeft(fraction)))
    }

    // ---------- Receive ----------

    private fun startReceive() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        forgetPicture()
        mode = Mode.RECEIVING
        render()
        setPicture(null)
        showLive(LiveCanvas.Mode.RECEIVE)
        progress.indeterminate = true
        level.active = true
        setStatus(getString(R.string.listening))

        val listener = object : ChirpDecoder.Listener {
            override fun onStart(bytes: Int) {
                totalSeconds = com.roboduck.image.modem.Chirp.dataSeconds(bytes)
                expectedBytes = bytes
                ui.post {
                    if (mode != Mode.RECEIVING) return@post
                    live.setImage(null, 0f)
                    live.setProgress(0f)
                    progress.indeterminate = false
                    progress.progress = 0
                    setStatus(getString(R.string.receiving, 0, timeLeft(0f)))
                    haptic()
                }
            }

            override fun onProgress(fraction: Float) {
                ui.post {
                    if (mode != Mode.RECEIVING) return@post
                    val pct = (fraction * 100).roundToInt().coerceIn(0, 100)
                    progress.progress = pct
                    live.setProgress(fraction)
                    setStatus(getString(R.string.receiving, pct, timeLeft(fraction)))
                }
            }

            override fun onPartial(data: ByteArray) {
                val (bmp, shown) = ImageCodec.previewSoFar(data, expectedBytes) ?: return
                ui.post { if (mode == Mode.RECEIVING) live.setImage(bmp, shown) }
            }

            override fun onDone(data: ByteArray, ok: Boolean) {
                val img = Payload.unpack(data)
                if (!ok) {
                    // Keep listening: the same picture sent again fills the gaps.
                    val partial = img?.let { ImageCodec.decodeQuietly(it) }
                    ui.post {
                        if (mode != Mode.RECEIVING) return@post
                        if (partial != null) live.setImage(partial, 1f)
                        progress.indeterminate = true
                        setStatus(getString(R.string.missed))
                        haptic()
                    }
                    return
                }
                recorder?.cancel()
                val filled = (decoder?.filledFromEarlier ?: 0) > 0
                val bmp = img?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                val jpg = img?.let { ImageCodec.toJpeg(it) }
                val saved = jpg != null && ImageCodec.save(this@MainActivity, jpg)
                ui.post {
                    if (mode != Mode.RECEIVING) return@post
                    if (bmp != null) {
                        setPicture(bmp)
                        originalBitmap = bmp
                        original = toRgb(bmp)
                    }
                    progress.progress = 100
                    finishMode(
                        getString(
                            when {
                                saved && filled -> R.string.saved_filled
                                saved -> R.string.saved
                                else -> R.string.save_failed
                            }
                        )
                    )
                    haptic()
                }
            }

            override fun onWhistle(k: Int) {
                ui.post { if (mode == Mode.RECEIVING) live.pushWhistle(k) }
            }

            override fun onLost() {
                ui.post {
                    if (mode != Mode.RECEIVING) return@post
                    progress.indeterminate = true
                    setStatus(getString(R.string.lost))
                }
            }
        }

        val dec = ChirpDecoder(listener, gaps)
        decoder = dec
        val r = Recorder(
            getSystemService(AudioManager::class.java),
            dec,
            object : Recorder.Callback {
                override fun onLevel(rms: Float) {
                    ui.post { level.push(rms) }
                }

                override fun onSpectrum(columns: List<FloatArray>) {
                    if (columns.isNotEmpty()) ui.post { if (mode == Mode.RECEIVING) live.pushColumns(columns) }
                }

                override fun onError() {
                    ui.post { stopAll(getString(R.string.mic_failed)) }
                }
            }
        )
        recorder = r
        r.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startReceive()
        else setStatus(getString(R.string.mic_needed))
    }

    // ---------- Picture tools (ImageJ) ----------

    /** Wires a tool button: runs [op] on the current picture (a 4x copy of the original if unedited). */
    private fun tool(id: Int, doneText: Int, op: (RgbImage) -> RgbImage): NeoButton {
        val button = findViewById<NeoButton>(id)
        button.setOnClickListener {
            val src = original ?: return@setOnClickListener
            if (busy || mode != Mode.IDLE) return@setOnClickListener
            val current = edited
            runTool(doneText) {
                val base = current ?: Enhancer.upscale(src)
                op(base)
            }
        }
        return button
    }

    private fun runTool(doneText: Int, work: () -> RgbImage) {
        busy = true
        render()
        setStatus(getString(R.string.working))
        val before = edited
        val forPicture = original
        Thread {
            val result = try { work() } catch (e: Exception) { null }
            val bmp = result?.let { Bitmap.createBitmap(it.toArgb(), it.width, it.height, Bitmap.Config.ARGB_8888) }
            ui.post {
                busy = false
                if (original !== forPicture || result == null || bmp == null) {
                    render()
                    return@post
                }
                pushHistory(before)
                edited = result
                setPicture(bmp)
                setStatus(getString(doneText))
                render()
                haptic()
            }
        }.start()
    }

    private fun pushHistory(state: RgbImage?) {
        history.addLast(state)
        while (history.size > 8) history.removeFirst()
    }

    private fun undo() {
        if (busy || history.isEmpty()) return
        edited = history.removeLast()
        showCurrent()
        setStatus(getString(R.string.done_undo))
        render()
    }

    private fun showOriginal() {
        if (busy || edited == null) {
            setStatus(getString(R.string.done_original))
            return
        }
        pushHistory(edited)
        edited = null
        showCurrent()
        setStatus(getString(R.string.done_original))
        render()
    }

    private fun showCurrent() {
        val e = edited
        if (e == null) setPicture(originalBitmap)
        else setPicture(Bitmap.createBitmap(e.toArgb(), e.width, e.height, Bitmap.Config.ARGB_8888))
    }

    private fun saveEdited() {
        if (busy) return
        val e = edited
        val bmp = if (e != null) {
            Bitmap.createBitmap(e.toArgb(), e.width, e.height, Bitmap.Config.ARGB_8888)
        } else originalBitmap ?: return
        busy = true
        render()
        Thread {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            val ok = ImageCodec.save(this, out.toByteArray(), "_edit")
            ui.post {
                busy = false
                setStatus(getString(if (ok) R.string.done_saved else R.string.done_save_failed))
                render()
                if (ok) haptic()
            }
        }.start()
    }

    private fun forgetPicture() {
        original = null
        originalBitmap = null
        edited = null
        history.clear()
    }

    private fun toRgb(bmp: Bitmap): RgbImage {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return RgbImage.fromArgb(px, bmp.width, bmp.height)
    }

    // ---------- UI helpers ----------

    private fun stopAll(message: String) {
        player?.cancel()
        recorder?.cancel()
        finishMode(message)
    }

    private fun finishMode(message: String) {
        player = null
        recorder = null
        mode = Mode.IDLE
        progress.indeterminate = false
        level.active = false
        hideLive()
        render()
        setStatus(message)
    }

    private fun render() {
        sendButton.text = getString(if (mode == Mode.SENDING) R.string.stop else R.string.send)
        receiveButton.text = getString(if (mode == Mode.RECEIVING) R.string.stop else R.string.receive)
        sendButton.isEnabled = mode != Mode.RECEIVING && !busy
        receiveButton.isEnabled = mode != Mode.SENDING && !busy

        val showTools = mode == Mode.IDLE && original != null
        tools.visibility = if (showTools) View.VISIBLE else View.GONE
        meter.visibility = if (showTools) View.GONE else View.VISIBLE
        for (b in toolButtons) b.isEnabled = !busy
        undoButton.isEnabled = !busy && history.isNotEmpty()

        if (mode == Mode.IDLE) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun timeLeft(fraction: Float): String {
        val left = ((1f - fraction) * totalSeconds).roundToInt().coerceAtLeast(0)
        return String.format(java.util.Locale.US, "%d:%02d", left / 60, left % 60)
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    private fun showLive(m: LiveCanvas.Mode) {
        live.clear()
        live.mode = m
        live.visibility = View.VISIBLE
        placeholder.visibility = View.GONE
        image.visibility = View.GONE
    }

    private fun hideLive() {
        if (live.visibility != View.VISIBLE) return
        live.visibility = View.GONE
        if (image.drawable != null) image.visibility = View.VISIBLE
        else placeholder.visibility = View.VISIBLE
    }

    private fun setPicture(bmp: Bitmap?) {
        if (bmp == null) {
            image.setImageDrawable(null)
            image.visibility = View.GONE
            placeholder.visibility = View.VISIBLE
            return
        }
        if (live.visibility == View.VISIBLE) {
            image.setImageBitmap(bmp)   // shown when the live view closes
            return
        }
        val first = image.visibility != View.VISIBLE
        image.setImageBitmap(bmp)
        image.visibility = View.VISIBLE
        placeholder.visibility = View.GONE
        if (first) {
            image.alpha = 0f
            image.animate().alpha(1f).setDuration(250).start()
        }
    }

    private fun haptic() {
        val c = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY
        status.performHapticFeedback(c)
    }

    // ---------- Hooks for the on-device check (puts the screen in each state for screenshots) ----------

    internal fun demoSending(preview: Bitmap, fraction: Float) {
        mode = Mode.SENDING
        render()
        setPicture(preview)
        showLive(LiveCanvas.Mode.SEND)
        live.setImage(preview, 1f)
        totalSeconds = 64.0
        level.active = true
        repeat(32) { level.push(0.02f + 0.03f * (it % 7)) }
        progress.indeterminate = false
        showSendProgress(fraction)
    }

    internal fun demoReceiving(partial: Bitmap, fraction: Float, whistles: IntArray) {
        mode = Mode.RECEIVING
        render()
        setPicture(null)
        showLive(LiveCanvas.Mode.RECEIVE)
        totalSeconds = 64.0
        level.active = true
        repeat(32) { level.push(0.01f + 0.02f * ((it * 3) % 7)) }
        whistles.forEach { live.pushWhistle(it) }
        live.setImage(partial, fraction)
        live.setProgress(fraction)
        progress.indeterminate = false
        val pct = (fraction * 100).roundToInt()
        progress.progress = pct
        setStatus(getString(R.string.receiving, pct, timeLeft(fraction)))
    }

    internal fun demoReceived(bmp: Bitmap) {
        mode = Mode.RECEIVING
        setPicture(bmp)
        originalBitmap = bmp
        original = toRgb(bmp)
        progress.progress = 100
        finishMode(getString(R.string.saved))
    }

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_MIC = 2
    }
}
