package com.roboduck.image

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.roboduck.image.modem.Chirp
import kotlin.math.sqrt

/** Plays a waveform through the speaker on its own thread. */
class Player(private val wave: FloatArray, private val cb: Callback) : Thread("player") {

    interface Callback {
        fun onLevel(rms: Float)
        fun onProgress(fraction: Float)
        fun onFinished(completed: Boolean)
    }

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    override fun run() {
        val rate = Chirp.SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            cb.onFinished(false)
            return
        }

        val chunk = 2205 // 50 ms
        val buf = ShortArray(chunk)
        var written = 0L
        try {
            track.play()
            // A little silence first so the speaker path is awake.
            val lead = ShortArray(rate / 4)
            track.write(lead, 0, lead.size)
            written += lead.size

            var i = 0
            while (i < wave.size && !cancelled) {
                val n = minOf(chunk, wave.size - i)
                var sum = 0.0
                for (j in 0 until n) {
                    val v = wave[i + j]
                    buf[j] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    sum += v * v
                }
                var off = 0
                while (off < n && !cancelled) {
                    val w = track.write(buf, off, n - off)
                    if (w < 0) { cancelled = true; break }
                    off += w
                }
                written += off
                i += n
                cb.onLevel(sqrt(sum / n).toFloat())
                cb.onProgress(i.toFloat() / wave.size)
            }
            if (!cancelled) {
                val deadline = SystemClock.elapsedRealtime() + 4000
                while (!cancelled &&
                    (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < written &&
                    SystemClock.elapsedRealtime() < deadline
                ) sleep(20)
            }
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
        cb.onFinished(!cancelled)
    }
}
