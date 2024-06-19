package io.github.takusan23.komadroid

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.github.takusan23.komadroid.gl.InputSurface
import io.github.takusan23.komadroid.gl.KomaDroidCameraTextureRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** カメラを開いたり、プレビュー用の SurfaceView を作ったり、静止画撮影したりする */
@OptIn(ExperimentalCoroutinesApi::class)
class KomaDroidCameraManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    /** フロントカメラ（前面） */
    private var frontCamera: CameraDevice? = null

    /** バックカメラ（背面） */
    private var backCamera: CameraDevice? = null

    /** プレビュー用 OpenGL ES のスレッド */
    private val previewGlThreadDispatcher = newSingleThreadContext("PreviewGlThread")

    /** 録画用 OpenGL ES のスレッド */
    private val recordGlThreadDispatcher = newSingleThreadContext("RecordGlThread")

    /** 静止画撮影用 ImageReader */
    private var recordImageReader: ImageReader? = null

    /** 今のタスク（プレビュー、動画撮影）キャンセル用 */
    private var currentJob: Job? = null

    /** 出力先 Surface */
    val surfaceView = SurfaceView(context)

    /** SurfaceView が利用可能かどうかの Flow */
    private val isSurfaceAvailableFlow = callbackFlow {
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                trySend(true)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // do nothing
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                trySend(false)
            }
        }
        surfaceView.holder.addCallback(callback)
        awaitClose { surfaceView.holder.removeCallback(callback) }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    /** カメラを開く */
    fun openCamera() {
        scope.launch {
            backCamera = awaitOpenCamera(getBackCameraId())
            frontCamera = awaitOpenCamera(getFrontCameraId())

            // プレビューを開始する
            startPreview()
        }
    }

    /** プレビューを始める */
    fun startPreview() {
        scope.launch {
            // キャンセルして、コルーチンが終わるのを待つ
            currentJob?.cancelAndJoin()
            currentJob = launch {
                // SurfaceView が利用可能になるまで待つ
                // また、SurfaceView が使えなくなった場合に、↓のブロック内のコルーチンをキャンセルしてほしいので collectLatest
                isSurfaceAvailableFlow.collectLatest { isAvailable ->

                    // 利用できないなら return
                    if (!isAvailable) return@collectLatest

                    // プレビューを開始
                    renderOpenGl(
                        previewSurface = surfaceView.holder.surface,
                        recordSurface = null,
                        templateMode = CameraDevice.TEMPLATE_PREVIEW
                    )
                }
            }
        }
    }

    /**
     * OpenGL ES で描画する。
     * 描画中はループに入って、一時停止するので、この関数以降の処理には進みません。
     *
     * @param previewSurface プレビュー用 Surface
     * @param recordSurface 静止画撮影、録画するならそれ用の Surface
     * @param templateMode [CameraDevice.TEMPLATE_PREVIEW]など
     */
    private suspend fun renderOpenGl(
        previewSurface: Surface,
        recordSurface: Surface? = null,
        templateMode: Int = CameraDevice.TEMPLATE_PREVIEW
    ) = coroutineScope {
        val frontCamera = frontCamera!!
        val backCamera = backCamera!!

        // プレビュー Surface 用の OpenGL ES の用意
        val previewInputSurface = InputSurface(previewSurface)
        val previewRenderer = KomaDroidCameraTextureRenderer()
        withContext(previewGlThreadDispatcher) {
            // プレビュー専用のスレッドで呼び出す
            previewInputSurface.makeCurrent()
            previewRenderer.createShader()
        }

        // 録画用 Surface があれば、録画用の OpenGL ES 周りも用意。
        var recordInputSurface: InputSurface? = null
        var recordRenderer: KomaDroidCameraTextureRenderer? = null
        if (recordSurface != null) {
            recordInputSurface = InputSurface(recordSurface)
            recordRenderer = KomaDroidCameraTextureRenderer()
            // 録画専用のスレッドで呼び出す
            withContext(recordGlThreadDispatcher) {
                recordInputSurface.makeCurrent()
                recordRenderer.createShader()
            }
        }

        // カメラ映像の解像度
        // https://developer.android.com/reference/android/hardware/camera2/CameraDevice#createCaptureSession(android.hardware.camera2.params.SessionConfiguration)
        previewRenderer.setSurfaceTextureSize(CAMERA_RESOLUTION_WIDTH, CAMERA_RESOLUTION_HEIGHT)
        recordRenderer?.setSurfaceTextureSize(CAMERA_RESOLUTION_WIDTH, CAMERA_RESOLUTION_HEIGHT)

        // フロントカメラの設定
        // 出力先
        val frontCameraOutputList = listOfNotNull(
            previewRenderer.frontCameraInputSurface,
            recordRenderer?.frontCameraInputSurface
        )
        val frontCameraCaptureRequest = frontCamera.createCaptureRequest(templateMode).apply {
            frontCameraOutputList.forEach { surface -> addTarget(surface) }
        }.build()
        val frontCameraOutputConfigurationList = frontCameraOutputList.map { surface -> OutputConfiguration(surface) }
        val frontCameraSessionConfiguration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, frontCameraOutputConfigurationList, cameraExecutor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                captureSession.setRepeatingRequest(frontCameraCaptureRequest, null, null)
            }

            override fun onConfigureFailed(p0: CameraCaptureSession) {
                // do nothing
            }
        })
        frontCamera.createCaptureSession(frontCameraSessionConfiguration)

        // バックカメラの設定
        val backCameraOutputList = listOfNotNull(
            previewRenderer.backCameraInputSurface,
            recordRenderer?.backCameraInputSurface
        )
        val backCameraCaptureRequest = backCamera.createCaptureRequest(templateMode).apply {
            backCameraOutputList.forEach { surface -> addTarget(surface) }
        }.build()
        val backCameraOutputConfigurationList = backCameraOutputList.map { surface -> OutputConfiguration(surface) }
        val backCameraSessionConfiguration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, backCameraOutputConfigurationList, cameraExecutor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                captureSession.setRepeatingRequest(backCameraCaptureRequest, null, null)
            }

            override fun onConfigureFailed(p0: CameraCaptureSession) {
                // do nothing
            }
        })
        backCamera.createCaptureSession(backCameraSessionConfiguration)

        try {
            // OpenGL ES の描画用メインループを開始。スレッド注意
            while (isActive) {
                // プレビューを描画
                if (previewRenderer.isAvailableFrontCameraFrame() || previewRenderer.isAvailableBackCameraFrame()) {
                    withContext(previewGlThreadDispatcher) {
                        // カメラ映像テクスチャを更新して、描画
                        previewRenderer.updateFrontCameraTexture()
                        previewRenderer.updateBackCameraTexture()
                        previewRenderer.draw()
                        previewInputSurface.swapBuffers()
                    }
                }
                // 録画用も nonnull なら描画
                if (recordRenderer != null && recordInputSurface != null) {
                    if (recordRenderer.isAvailableFrontCameraFrame() || recordRenderer.isAvailableBackCameraFrame()) {
                        withContext(recordGlThreadDispatcher) {
                            recordRenderer.updateFrontCameraTexture()
                            recordRenderer.updateBackCameraTexture()
                            recordRenderer.draw()
                            recordInputSurface.swapBuffers()
                        }
                    }
                }
            }
        } finally {
            // コルーチンがキャンセルされた時
            previewRenderer.destroy()
            previewInputSurface.destroy()
            recordRenderer?.destroy()
            recordInputSurface?.destroy()
        }
    }

    /** 破棄時に呼び出す。Activity の onDestroy とかで呼んでください。 */
    fun destroy() {
        scope.cancel()
        previewGlThreadDispatcher.close()
        recordGlThreadDispatcher.close()
        frontCamera?.close()
        backCamera?.close()
        recordImageReader?.close()
    }

    /**
     * カメラを開く
     *
     * @param cameraId 起動したいカメラ
     * @return カメラ
     */
    @SuppressLint("MissingPermission")
    private suspend fun awaitOpenCamera(cameraId: String): CameraDevice = suspendCancellableCoroutine { continuation ->
        cameraManager.openCamera(cameraId, cameraExecutor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                continuation.resume(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                // do nothing
            }

            override fun onError(camera: CameraDevice, error: Int) {
                // do nothing
            }
        })
    }


    /** フロントカメラの ID を返す */
    private fun getFrontCameraId(): String = cameraManager
        .cameraIdList
        .first { cameraId -> cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }

    /** バックカメラの ID を返す */
    private fun getBackCameraId(): String = cameraManager
        .cameraIdList
        .first { cameraId -> cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }

    companion object {
        const val CAMERA_RESOLUTION_WIDTH = 720
        const val CAMERA_RESOLUTION_HEIGHT = 1280
    }
}