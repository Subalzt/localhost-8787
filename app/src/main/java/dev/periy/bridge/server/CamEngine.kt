package dev.periy.bridge.server

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface

/**
 * One phone's camera as a security camera (server/Cameras.kt): the lens into the hardware H.264
 * encoder (each picture whole, the settings in front of every key picture, so a viewer can join at
 * any one), a small YUV copy beside it for the motion watch and snapshots, and the microphone into
 * AAC when sound is on. It runs only inside CameraService, which holds the camera and microphone
 * for it with the screen off and locked.
 */
class CamEngine(ctx: Context, private val out: Out) {
    interface Out {
        fun video(au: ByteArray, key: Boolean, ptsUs: Long)
        fun videoFormat(f: MediaFormat)
        fun audio(raw: ByteArray, ptsUs: Long)
        fun audioFormat(f: MediaFormat)
        /** A small picture (YUV), a few times a second: the motion watch, and snapshots. */
        fun frame(img: Image)
        fun failed(why: String)
        fun running(sensor: Int, maxZoom: Float)
    }

    private val app = ctx.applicationContext
    private val cm = app.getSystemService(CameraManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encSurface: Surface? = null
    private var reader: ImageReader? = null
    private var fps: Range<Int>? = null
    /** Night: the slowest frame rate the camera allows (a longer exposure for each picture). */
    private var nightFps: Range<Int>? = null
    private var evMax = 0
    private var nightScene = false
    private var zoomRange: Range<Float>? = null
    private var sensorArea: android.graphics.Rect? = null
    @Volatile private var night = false
    @Volatile private var zoom = 1f
    @Volatile private var torch = false
    @Volatile private var live = false
    @Volatile private var csd: ByteArray? = null
    private var audioThread: Thread? = null
    @Volatile private var realtime = true
    private var lastFrame = 0L

    /** Clock the camera stamps pictures with, which the sound is stamped with too, so clips line up. */
    private fun nowUs(): Long = if (realtime) SystemClock.elapsedRealtimeNanos() / 1000 else System.nanoTime() / 1000

    @SuppressLint("MissingPermission")
    fun start(lens: String, torchOn: Boolean, sound: Boolean, nightOn: Boolean = false, zoomTo: Float = 1f) {
        if (live) return
        live = true
        torch = torchOn
        night = nightOn
        zoom = zoomTo
        val t = HandlerThread("cam").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post {
            try {
                val id = pickCamera(lens) ?: error("This phone has no ${if (lens == "front") "front" else "back"} camera")
                val ch = cm.getCameraCharacteristics(id)
                val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                realtime = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("The camera gives no sizes")
                val sizes = map.getOutputSizes(MediaCodec::class.java).orEmpty().toList()
                val size = sizes.firstOrNull { it.width == W && it.height == H }
                    ?: sizes.filter { it.width <= W && it.width * 9 == it.height * 16 }.maxByOrNull { it.width } ?: Size(W, H)
                val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                fps = ranges.filter { it.upper == FPS }.minByOrNull { it.lower }
                    ?: ranges.filter { it.upper in FPS..30 }.minByOrNull { it.lower }
                // Night: as slow as it goes, down to about 5 pictures a second, for light.
                nightFps = ranges.filter { it.upper <= 15 }.minByOrNull { it.lower } ?: ranges.minByOrNull { it.lower }
                evMax = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.upper ?: 0
                nightScene = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.contains(CaptureRequest.CONTROL_SCENE_MODE_NIGHT) == true
                zoomRange = if (android.os.Build.VERSION.SDK_INT >= 30) ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
                sensorArea = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val maxDigital = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                maxZoom = minOf(8f, zoomRange?.upper ?: maxDigital)
                startEncoder(size)
                reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 3).apply {
                    setOnImageAvailableListener({ r ->
                        val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        try {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastFrame >= 200) { lastFrame = now; out.frame(img) }
                        } finally { img.close() }
                    }, h)
                }
                cm.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        if (!live) { d.close(); return }
                        device = d
                        @Suppress("DEPRECATION")
                        d.createCaptureSession(listOf(encSurface!!, reader!!.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (!live) { s.close(); return }
                                session = s
                                repeat()
                                out.running(sensor, maxZoom)
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { fail("The camera would not start") }
                        }, h)
                    }
                    override fun onDisconnected(d: CameraDevice) { d.close(); fail("Something else took the camera") }
                    override fun onError(d: CameraDevice, error: Int) {
                        d.close()
                        fail(when (error) {
                            ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> "The camera is in use"
                            ERROR_CAMERA_DISABLED -> "background"
                            else -> "The camera stopped ($error)"
                        })
                    }
                }, h)
                if (sound) startAudio()
            } catch (e: SecurityException) {
                fail("background")
            } catch (e: Exception) {
                Log.w(TAG, "Start", e)
                fail(if (e is android.hardware.camera2.CameraAccessException && e.reason == android.hardware.camera2.CameraAccessException.CAMERA_DISABLED) "background" else e.message ?: "The camera would not start")
            }
        }
    }

    private fun pickCamera(lens: String): String? {
        val want = if (lens == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: cm.cameraIdList.firstOrNull()
    }

    private fun startEncoder(size: Size) {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        e.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encSurface = e.createInputSurface()
        e.start()
        encoder = e
        Thread({ drainVideo(e) }, "cam-enc").apply { isDaemon = true; start() }
    }

    private fun drainVideo(e: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (live) {
                val i = e.dequeueOutputBuffer(info, 20_000)
                when {
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> out.videoFormat(e.outputFormat)
                    i >= 0 -> {
                        val buf = e.getOutputBuffer(i)
                        if (buf != null && info.size > 0) {
                            val b = ByteArray(info.size)
                            buf.position(info.offset); buf.get(b)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) csd = b
                            else {
                                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                // A key picture carries the settings, whatever the encoder does by itself.
                                val au = if (key && !hasSps(b)) (csd ?: ByteArray(0)) + b else b
                                out.video(au, key, info.presentationTimeUs)
                            }
                        }
                        e.releaseOutputBuffer(i, false)
                    }
                }
            }
        } catch (ex: Exception) {
            if (live) Log.w(TAG, "Encoder", ex)
        }
    }

    private fun hasSps(b: ByteArray): Boolean {
        var i = 0
        while (i + 4 < b.size && i < 64) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) return (b[i + 3].toInt() and 0x1F) == 7
            i++
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val t = Thread({
            var rec: AudioRecord? = null
            var enc: MediaCodec? = null
            try {
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
                if (rec.state != AudioRecord.STATE_INITIALIZED) error("no microphone")
                val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
                }
                enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
                rec.startRecording()
                val pcm = ByteArray(2048)
                val info = MediaCodec.BufferInfo()
                while (live) {
                    val n = rec.read(pcm, 0, pcm.size)
                    if (n <= 0) continue
                    val ii = enc.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = enc.getInputBuffer(ii)!!
                        ib.clear(); ib.put(pcm, 0, n)
                        // Stamped when it was heard: this read's length back from now.
                        enc.queueInputBuffer(ii, 0, n, nowUs() - n / 2 * 1_000_000L / RATE, 0)
                    }
                    while (true) {
                        val oi = enc.dequeueOutputBuffer(info, 0)
                        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { out.audioFormat(enc.outputFormat); continue }
                        if (oi < 0) break
                        val ob = enc.getOutputBuffer(oi)
                        if (ob != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val b = ByteArray(info.size)
                            ob.position(info.offset); ob.get(b)
                            out.audio(b, info.presentationTimeUs)
                        }
                        enc.releaseOutputBuffer(oi, false)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sound", e)
            } finally {
                runCatching { rec?.stop() }; runCatching { rec?.release() }
                runCatching { enc?.stop() }; runCatching { enc?.release() }
            }
        }, "cam-mic")
        t.isDaemon = true
        audioThread = t
        t.start()
    }

    private fun repeat() {
        val d = device ?: return
        val s = session ?: return
        runCatching {
            val r = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(encSurface!!)
                addTarget(reader!!.surface)
                (if (night) nightFps ?: fps else fps)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                if (night && nightScene) {
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                    set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
                } else set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                if (night) {
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evMax)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                }
                // Zoom in the camera itself: the sensor cropped, so a closer look keeps its detail.
                val z = zoom.coerceIn(1f, maxZoom)
                if (android.os.Build.VERSION.SDK_INT >= 30 && zoomRange != null) set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
                else sensorArea?.let { a ->
                    val w = (a.width() / z).toInt(); val h = (a.height() / z).toInt()
                    set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(a.centerX() - w / 2, a.centerY() - h / 2, a.centerX() + w / 2, a.centerY() + h / 2))
                }
                set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }.build()
            s.setRepeatingRequest(r, null, handler)
        }.onFailure { Log.w(TAG, "Repeat", it) }
    }

    fun setTorch(on: Boolean) { torch = on; handler?.post { repeat() } }
    fun setNight(on: Boolean) { night = on; handler?.post { repeat() } }
    fun setZoom(z: Float) { zoom = z; handler?.post { repeat() } }
    @Volatile var maxZoom = 1f
        private set

    /** The picture's bitrate, changed while it runs: to what the slowest viewer's way carries. */
    fun setBitrate(bps: Int) {
        runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) }
    }

    /** A key picture now, for a viewer that has just come. */
    fun keyNow() { runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } }

    private fun fail(why: String) {
        if (!live) return
        stop()
        out.failed(why)
    }

    fun stop() {
        if (!live) return
        live = false
        val h = handler
        val t = thread
        h?.post {
            runCatching { session?.close() }; session = null
            runCatching { device?.close() }; device = null
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }; encoder = null
            runCatching { encSurface?.release() }; encSurface = null
            runCatching { reader?.close() }; reader = null
            t?.quitSafely()
        }
        handler = null; thread = null
        audioThread = null
    }

    companion object {
        private const val TAG = "CamEngine"
        const val W = 1280
        const val H = 720
        const val FPS = 20
        const val BITRATE = 1_500_000
        const val RATE = 48_000
    }
}
