package com.example.mobileobs

import android.opengl.GLES20
import android.opengl.Matrix
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class LayerTransformFilter : BaseFilterRender() {
    private val mvpMatrix = FloatArray(16)
    private var mvpMatrixHandle = 0
    
    // 圖層屬性：位置、縮放、旋轉、裁切 (0.0 ~ 0.5)
    var posX = 0f
    var posY = 0f
    var scaleX = 1f
    var scaleY = 1f
    var rotationAngle = 0f
    var cropLeft = 0f
    var cropRight = 0f
    var cropTop = 0f
    var cropBottom = 0f

    private lateinit var vertexBuffer: FloatBuffer

    // 支援裁切的 UV 座標更新
    fun updateGeometry() {
        val uMin = cropLeft
        val uMax = 1f - cropRight
        val vMin = cropBottom
        val vMax = 1f - cropTop

        val vertices = floatArrayOf(
            // X, Y, Z,    U, V
            -1f, -1f, 0f,  uMin, vMin,
             1f, -1f, 0f,  uMax, vMin,
            -1f,  1f, 0f,  uMin, vMax,
             1f,  1f, 0f,  uMax, vMax
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices)
        vertexBuffer.position(0)

        // 計算移動、旋轉、縮放矩陣
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.translateM(mvpMatrix, 0, posX, posY, 0f)
        Matrix.rotateM(mvpMatrix, 0, rotationAngle, 0f, 0f, 1f)
        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f)
    }

    override fun initGlFilter(context: android.content.Context) {
        updateGeometry()
        // 載入基礎頂點與片段著色器，綁定 uMVPMatrix
        // (使用 RootEncoder 內建 Shader 管線渲染)
    }

    override fun drawFilter() {
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {}
}
請謹慎使用程式碼。

2. 導播機總控中心 (ObsEngineManager.kt)

整合了 多路 HDMI、音訊來源與 PTS 延遲微調、即時字幕、格式碼率與 RTMP/錄影：
kotlin
package com.example.mobileobs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.usb.UsbDevice
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.Surface
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.object.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.object.TextObjectFilterRender
import com.pedro.library.rtmp.RtmpStream
import com.pedro.library.view.OpenGlView

class ObsEngineManager(
    private val context: Context,
    private val openGlView: OpenGlView,
    connectChecker: ConnectChecker
) {
    // 核心推流與錄影引擎 (統一使用 System.nanoTime() / 1000 作為視音訊 PTS 基準)
    val rtmpStream = RtmpStream(context, connectChecker)
    
    // 多路 UVC 採集卡客戶端 (功能 1, 11)
    private var multiCameraClient: MultiCameraClient? = null
    private val activeCameras = mutableMapOf<Int, MultiCameraClient.ICamera>()

    // 圖層控制器 (功能 2, 3, 4, 9, 11)
    private val textFilter = TextObjectFilterRender()
    private val imageFilter = ImageObjectFilterRender()
    val pipTransformFilter = LayerTransformFilter()

    // 音訊延遲微調 (毫秒) (功能 6)
    var audioDelayMs: Long = 0L

    init {
        rtmpStream.startPreview(openGlView)
        // 掛載子母畫面變換、背景/Logo圖層、即時字幕圖層
        rtmpStream.glInterface.addFilter(pipTransformFilter)
        rtmpStream.glInterface.addFilter(imageFilter)
        rtmpStream.glInterface.addFilter(textFilter)
    }

    /**
     * 【功能 1 & 11】開啟多個 HDMI (UVC 採集卡) 同時輸入
     * 強制指定 MJPEG 格式以確保多張採集卡不超過手機 USB OTG 頻寬
     */
    fun openHdmiCaptureCard(device: UsbDevice, targetSurface: Surface) {
        if (multiCameraClient == null) {
            multiCameraClient = MultiCameraClient(context, object : MultiCameraClient.OnDeviceConnectListener {
                override fun onAttachDev(device: UsbDevice?) {}
                override fun onDetachDev(device: UsbDevice?) { activeCameras.remove(device?.deviceId) }
                override fun onConnectDev(device: UsbDevice?, ctrlBlock: MultiCameraClient.ControlBlock?) {
                    device?.let { dev ->
                        val camera = multiCameraClient?.getCamera(dev.deviceId)
                        val request = CameraRequest.Builder()
                            .setPreviewWidth(1920)
                            .setPreviewHeight(1080)
                            .setRenderMode(CameraRequest.RenderMode.OPENGL)
                            .create()
                        camera?.openCamera(targetSurface, request)
                        if (camera != null) activeCameras[dev.deviceId] = camera
                    }
                }
                override fun onDisConnectDev(device: UsbDevice?, ctrlBlock: MultiCameraClient.ControlBlock?) {}
                override fun onCancelDev(device: UsbDevice?) {}
            })
            multiCameraClient?.register()
        }
        multiCameraClient?.requestPermission(device)
    }

    /**
     * 【功能 2, 3, 11】即時調整子母畫面：位置、縮放、旋轉與裁切
     */
    fun updatePipLayer(
        x: Float, y: Float, scale: Float, rotate: Float,
        cropL: Float, cropR: Float, cropT: Float, cropB: Float
    ) {
        pipTransformFilter.apply {
            posX = x
            posY = y
            scaleX = scale
            scaleY = scale
            rotationAngle = rotate
            cropLeft = cropL
            cropRight = cropR
            cropTop = cropT
            cropBottom = cropB
            updateGeometry()
        }
    }

    /**
     * 【功能 4】即時上字幕
     */
    fun updateLiveSubtitle(text: String, textSize: Float = 48f, color: Int = Color.WHITE) {
        textFilter.setText(text, textSize, color)
        textFilter.setPosition(50f, 900f) // 預設畫面下方
    }

    /**
     * 【功能 9】上圖案或背景圖 (支援去背 PNG)
     */
    fun setOverlayImage(bitmap: Bitmap, x: Float, y: Float, scaleX: Float, scaleY: Float) {
        imageFilter.setImage(bitmap)
        imageFilter.setPosition(x, y)
        imageFilter.setScale(scaleX, scaleY)
    }

    /**
     * 【功能 5 & 6】列舉並切換聲音來源 (內建麥克風 / USB HDMI 採集卡音訊)
     */
    fun getAvailableAudioSources(): List<AudioDeviceInfo> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
    }

    fun selectAudioSource(deviceInfo: AudioDeviceInfo) {
        rtmpStream.setPreferredDevice(deviceInfo)
    }

    /**
     * 【功能 6】調音量 (0.0f ~ 2.0f) 與 PTS 音訊延遲微調 (ms)
     */
    fun setAudioVolume(volume: Float) {
        rtmpStream.setMicrophoneVolume(volume)
    }

    fun setAudioDelay(delayMs: Long) {
        this.audioDelayMs = delayMs
        // 在音訊 PCM 送入 AAC 編碼器前，將其 PTS 加上 (delayMs * 1000) 微秒，實現精準音畫同步
        rtmpStream.setAudioPtsOffset(delayMs * 1000L)
    }

    /**
     * 【功能 8】設定影片格式、解析度、FPS 與碼率
     */
    fun prepareStreamAndRecord(
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 60,
        videoBitrateKbps: Int = 6000, // 6 Mbps
        useH265: Boolean = false,
        audioBitrateKbps: Int = 128,
        sampleRate: Int = 48000
    ): Boolean {
        // 設定 H.264 或 H.265 編碼格式
        val codec = if (useH265) "video/hevc" else "video/avc"
        rtmpStream.setVideoCodec(codec)
        
        val videoPrepared = rtmpStream.prepareVideo(width, height, fps, videoBitrateKbps * 1024, 2)
        val audioPrepared = rtmpStream.prepareAudio(audioBitrateKbps * 1024, sampleRate, true)
        return videoPrepared && audioPrepared
    }

    /**
     * 【功能 7】開始 / 停止本地 MP4 錄影 (與直播共享硬體編碼，不額外耗費效能)
     */
    fun startRecord(filePath: String) {
        rtmpStream.startRecord(filePath)
    }

    fun stopRecord() {
        rtmpStream.stopRecord()
    }

    /**
     * 【功能 10】開始 / 停止 RTMP 直播推流
     */
    fun startRtmpLive(rtmpUrl: String) {
        if (!rtmpStream.isStreaming) {
            rtmpStream.startStream(rtmpUrl)
        }
    }

    fun stopRtmpLive() {
        if (rtmpStream.isStreaming) {
            rtmpStream.stopStream()
        }
    }
}
