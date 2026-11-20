package com.roboduck.image

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import com.roboduck.image.modem.Chirp
import com.roboduck.image.modem.ChirpDecoder
import kotlin.math.sqrt

/** Records from the microphone and feeds the decoder on its own thread. */
class Recorder(
    private val audioManager: AudioManager,
    private val decoder: ChirpDecoder,
    private val cb: Callback
) : Thread("recorder") {

    interface Callback {
        fun onLevel(rms: Float)
        fun onError()
    }

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    @SuppressLint("MissingPermission")
    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = Chirp.SAMPLE_RATE
        // Raw mic if the phone offers it; otherwise the speech-recognition path,
        // which also skips most noise suppression and auto gain.
        val unprocessed = audioManager.getProperty(
            AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
        ) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.VOICE_RECOGNITION

        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                source, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192) * 4
            )
        } catch (e: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            cb.onError()
            return
        }

        // Phones' voice filters treat steady whistles as noise and fade them out: turn them off.
        val session = rec.audioSessionId
        val effects = listOfNotNull(
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session) else null,
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session) else null,
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session) else null
        )
        effects.forEach { try { it.enabled = false } catch (_: Exception) {} }

        val shorts = ShortArray(2048)
        val floats = FloatArray(2048)
        try {
            rec.startRecording()
            while (!cancelled) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n < 0) { cb.onError(); break }
                if (n == 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val v = shorts[i] / 32768f
                    floats[i] = v
                    sum += v * v
                }
                cb.onLevel(sqrt(sum / n).toFloat())
                decoder.feed(floats, n)
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            effects.forEach { try { it.release() } catch (_: Exception) {} }
            rec.release()
        }
    }
}
