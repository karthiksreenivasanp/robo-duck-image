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
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.roboduck.image.modem.ChirpDecoder
import com.roboduck.image.modem.ChirpEncoder
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private enum class Mode { IDLE, SENDING, RECEIVING }

    private lateinit var image: ImageView
    private lateinit var level: LevelView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var sendButton: Button
    private lateinit var receiveButton: Button

    private val ui = Handler(Looper.getMainLooper())
    private var mode = Mode.IDLE
    private var player: Player? = null
    private var recorder: Recorder? = null
    private var lowVolume = false

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
        findViewById<View>(R.id.frame).clipToOutline = true
        image = findViewById(R.id.image)
        level = findViewById(R.id.level)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        sendButton = findViewById(R.id.send)
        receiveButton = findViewById(R.id.receive)

        val root = findViewById<View>(R.id.root)
        val pad = root.paddingLeft
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    pad + insets.systemWindowInsetLeft, pad + insets.systemWindowInsetTop,
                    pad + insets.systemWindowInsetRight, pad + insets.systemWindowInsetBottom
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
        render()
        setStatus(getString(R.string.ready))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Follow a dark/light switch right away unless a transfer is running.
        if (mode == Mode.IDLE) recreate()
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
        mode = Mode.SENDING
        render()
        progress.isIndeterminate = true
        setStatus(getString(R.string.preparing))
        val am = getSystemService(AudioManager::class.java)
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        lowVolume = vol < am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.6

        Thread {
            val jpg = try {
                ImageCodec.compress(ImageCodec.load(contentResolver, uri))
            } catch (e: Exception) {
                null
            }
            if (jpg == null) {
                ui.post { stopAll(getString(R.string.open_failed)) }
                return@Thread
            }
            val preview = BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
            val wave = ChirpEncoder.encode(jpg)
            ui.post {
                if (mode != Mode.SENDING) return@post
                showImage(preview)
                progress.isIndeterminate = false
                progress.progress = 0
                level.active = true
                haptic()
                val p = Player(wave, object : Player.Callback {
                    override fun onLevel(rms: Float) = ui.post { level.push(rms) }.let { }
                    override fun onProgress(fraction: Float) = ui.post { showSendProgress(fraction) }.let { }
                    override fun onFinished(completed: Boolean) = ui.post {
                        if (completed && mode == Mode.SENDING) {
                            finishMode(getString(R.string.sent))
                            haptic()
                        }
                    }.let { }
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
        val text = getString(R.string.sending, pct)
        setStatus(if (lowVolume) text + getString(R.string.volume_low) else text)
    }

    // ---------- Receive ----------

    private fun startReceive() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        mode = Mode.RECEIVING
        render()
        image.setImageDrawable(null)
        progress.isIndeterminate = true
        level.active = true
        setStatus(getString(R.string.listening))

        val listener = object : ChirpDecoder.Listener {
            override fun onStart(bytes: Int) {
                ui.post {
                    if (mode != Mode.RECEIVING) return@post
                    image.setImageDrawable(null)
                    progress.isIndeterminate = false
                    progress.progress = 0
                    setStatus(getString(R.string.receiving, 0))
                    haptic()
                }
            }

            override fun onProgress(fraction: Float) {
                ui.post {
                    if (mode != Mode.RECEIVING) return@post
                    val pct = (fraction * 100).roundToInt().coerceIn(0, 100)
                    progress.progress = pct
                    setStatus(getString(R.string.receiving, pct))
                }
            }

            override fun onPartial(data: ByteArray) {
                val bmp = decodeQuietly(data) ?: return
                ui.post { if (mode == Mode.RECEIVING) showImage(bmp) }
            }

            override fun onDone(data: ByteArray, ok: Boolean) {
                recorder?.cancel()
                val bmp = decodeQuietly(data)
                val saved = ok && ImageCodec.save(this@MainActivity, data)
                ui.post {
                    if (mode != Mode.RECEIVING) return@post
                    bmp?.let { showImage(it) }
                    progress.progress = 100
                    finishMode(
                        getString(
                            when {
                                saved -> R.string.saved
                                ok -> R.string.save_failed
                                else -> R.string.errors
                            }
                        )
                    )
                    haptic()
                }
            }

            override fun onLost() {
                ui.post {
                    if (mode != Mode.RECEIVING) return@post
                    progress.isIndeterminate = true
                    setStatus(getString(R.string.lost))
                }
            }
        }

        val r = Recorder(
            getSystemService(AudioManager::class.java),
            ChirpDecoder(listener),
            object : Recorder.Callback {
                override fun onLevel(rms: Float) = ui.post { level.push(rms) }.let { }
                override fun onError() = ui.post { stopAll(getString(R.string.mic_failed)) }.let { }
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
        progress.isIndeterminate = false
        level.active = false
        render()
        setStatus(message)
    }

    private fun render() {
        sendButton.text = getString(if (mode == Mode.SENDING) R.string.stop else R.string.send)
        receiveButton.text = getString(if (mode == Mode.RECEIVING) R.string.stop else R.string.receive)
        sendButton.isEnabled = mode != Mode.RECEIVING
        receiveButton.isEnabled = mode != Mode.SENDING
        sendButton.alpha = if (sendButton.isEnabled) 1f else 0.35f
        receiveButton.alpha = if (receiveButton.isEnabled) 1f else 0.35f
        if (mode == Mode.IDLE) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    private fun showImage(bmp: Bitmap) {
        val first = image.drawable == null
        image.setImageBitmap(bmp)
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

    private fun decodeQuietly(data: ByteArray): Bitmap? = try {
        BitmapFactory.decodeByteArray(data, 0, data.size)
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_MIC = 2
    }
}
