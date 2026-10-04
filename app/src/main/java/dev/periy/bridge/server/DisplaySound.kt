package dev.periy.bridge.server

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * The laptop's sound with its screen on the phone (ui/SecondScreen.kt): the laptop helper posts
 * what its speakers play, as AAC in ADTS frames, and this plays it as it comes, with a short
 * buffer so it stays in step with the picture. Only while the screen view is open.
 */
object DisplaySound {
    private const val TAG = "DisplaySound"
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    @Volatile private var playing: InputStream? = null

    /** Plays [input] until it ends or the screen view closes. One at a time: a new one takes over. */
    fun play(input: InputStream) {
        runCatching { playing?.close() }
        playing = input
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            val head = ByteArray(7)
            val info = MediaCodec.BufferInfo()
            var pcm = ByteArray(0)
            while (DisplayFeed.open && playing === input) {
                if (!readFull(input, head, 0, 7)) break
                if (head[0].toInt() and 0xFF != 0xFF || head[1].toInt() and 0xF0 != 0xF0) continue
                val len = ((head[3].toInt() and 3) shl 11) or ((head[4].toInt() and 0xFF) shl 3) or ((head[5].toInt() and 0xFF) ushr 5)
                if (len <= 7) continue
                val body = ByteArray(len - 7)
                if (!readFull(input, body, 0, body.size)) break
                if (codec == null) {
                    val profile = ((head[2].toInt() and 0xFF) ushr 6) + 1
                    val sfi = ((head[2].toInt() and 0xFF) ushr 2) and 0xF
                    val ch = (((head[2].toInt() and 1) shl 2) or ((head[3].toInt() and 0xFF) ushr 6)).coerceAtLeast(1)
                    val rate = RATES.getOrElse(sfi) { 48000 }
                    val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch)
                    f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    f.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(((profile shl 3) or (sfi shr 1)).toByte(), (((sfi and 1) shl 7) or (ch shl 3)).toByte())))
                    codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(f, null, null, 0); start() }
                    val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                    track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(min * 2)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build().apply { play() }
                }
                val c = codec!!
                val idx = c.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    c.getInputBuffer(idx)?.apply { clear(); put(body) }
                    c.queueInputBuffer(idx, 0, body.size, System.nanoTime() / 1000, 0)
                }
                while (true) {
                    val out = c.dequeueOutputBuffer(info, 0)
                    if (out < 0) break
                    val ob = c.getOutputBuffer(out)
                    if (ob != null && info.size > 0) {
                        if (pcm.size < info.size) pcm = ByteArray(info.size)
                        ob.position(info.offset); ob.get(pcm, 0, info.size)
                        // Blocking: the track's own pace is the sound's pace.
                        track?.write(pcm, 0, info.size)
                    }
                    c.releaseOutputBuffer(out, false)
                }
            }
        } catch (e: Exception) {
            if (DisplayFeed.open) Log.w(TAG, "sound ended", e)
        } finally {
            runCatching { track?.stop(); track?.release() }
            runCatching { codec?.stop(); codec?.release() }
            if (playing === input) playing = null
        }
    }

    /** The screen view closed: the sound stops with it. */
    fun stop() { runCatching { playing?.close() }; playing = null }

    private fun readFull(s: InputStream, b: ByteArray, off: Int, n: Int): Boolean {
        var o = off; var left = n
        while (left > 0) {
            val r = s.read(b, o, left)
            if (r <= 0) return false
            o += r; left -= r
        }
        return true
    }
}
