package io.github.takusan23.komadroid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.core.content.contentValuesOf
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

    /** 今のタスク（プレビュー、動画撮影）キャンセル用 */
    private var currentJob: Job? = null

    /** フロントカメラ（前面） */
    private var frontCamera: CameraDevice? = null

    /** バックカメラ（背面） */
    private var backCamera: CameraDevice? = null

    /** プレビュー用[OpenGlDrawPair] */
    private var previewOpenGlDrawPair: OpenGlDrawPair? = null

    /** 録画用[OpenGlDrawPair] */
    private var recordOpenGlDrawPair: OpenGlDrawPair? = null

    /** プレビュー用 OpenGL ES のスレッド */
    private val previewGlThreadDispatcher = newSingleThreadContext("PreviewGlThread")

    /** 録画用 OpenGL ES のスレッド */
    private val recordGlThreadDispatcher = newSingleThreadContext("RecordGlThread")

    /** 静止画撮影用 ImageReader */
    private var imageReader: ImageReader? = null

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
            // カメラを開く
            backCamera = awaitOpenCamera(getBackCameraId())
            frontCamera = awaitOpenCamera(getFrontCameraId())

            // OpenGL の用意をする
            // SurfaceView が利用可能になるまで待つ
            // また、SurfaceView が使えなくなった場合に、↓のブロック内のコルーチンをキャンセルしてほしいので collectLatest
            // TODO この Flow を transform して OpenGlDrawPair を作れば良いかもしれない
            isSurfaceAvailableFlow.collectLatest { isAvailable ->

                // 利用できないなら return
                if (!isAvailable) return@collectLatest

                // 静止画撮影のやつ
                imageReader = ImageReader.newInstance(CAMERA_RESOLUTION_WIDTH, CAMERA_RESOLUTION_HEIGHT, PixelFormat.RGBA_8888, 2)

                val drawPairPair = createOpenGlDrawPair(
                    previewSurface = surfaceView.holder.surface,
                    recordSurface = imageReader!!.surface
                )
                // TODO リソース開放
                previewOpenGlDrawPair = drawPairPair.first
                recordOpenGlDrawPair = drawPairPair.second

                // プレビューを開始する
                startPreview()
            }
        }
    }

    /** プレビューを始める */
    fun startPreview() {
        scope.launch {
            // キャンセルして、コルーチンが終わるのを待つ
            currentJob?.cancelAndJoin()
            currentJob = launch {

                val frontCamera = frontCamera!!
                val backCamera = backCamera!!
                val previewOpenGlDrawPair = previewOpenGlDrawPair!!
                val recordOpenGlDrawPair = recordOpenGlDrawPair!!

                // フロントカメラの設定
                // 出力先
                val frontCameraOutputList = listOfNotNull(
                    previewOpenGlDrawPair.textureRenderer.frontCameraInputSurface,
                    recordOpenGlDrawPair.textureRenderer.frontCameraInputSurface
                )
                val frontCameraCaptureRequest = frontCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    frontCameraOutputList.forEach { surface -> addTarget(surface) }
                }.build()
                val frontCameraCaptureSession = frontCamera.awaitCameraSessionConfiguration(frontCameraOutputList)
                frontCameraCaptureSession?.setRepeatingRequest(frontCameraCaptureRequest, null, null)

                // バックカメラの設定
                val backCameraOutputList = listOfNotNull(
                    previewOpenGlDrawPair.textureRenderer.backCameraInputSurface,
                    recordOpenGlDrawPair.textureRenderer.backCameraInputSurface
                )
                val backCameraCaptureRequest = backCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    backCameraOutputList.forEach { surface -> addTarget(surface) }
                }.build()
                val backCameraCaptureSession = backCamera.awaitCameraSessionConfiguration(backCameraOutputList)
                backCameraCaptureSession?.setRepeatingRequest(backCameraCaptureRequest, null, null)

                // 描画ループを開始する
                // loop なのでこれ以降の処理には進まない
                loopRenderOpenGl(previewOpenGlDrawPair, recordOpenGlDrawPair)
            }
        }
    }

    /**
     * 静止画撮影する
     * 静止画撮影用に[CameraDevice.TEMPLATE_STILL_CAPTURE]と[CameraCaptureSession.capture]が使われます。
     */
    fun takePicture() {
        scope.launch {
            // キャンセルして、コルーチンが終わるのを待つ
            currentJob?.cancelAndJoin()
            currentJob = launch {

                val frontCamera = frontCamera!!
                val backCamera = backCamera!!
                val previewOpenGlDrawPair = previewOpenGlDrawPair!!
                val recordOpenGlDrawPair = recordOpenGlDrawPair!!

                // フロントカメラの設定
                // 出力先
                val frontCameraOutputList = listOfNotNull(
                    previewOpenGlDrawPair.textureRenderer.frontCameraInputSurface,
                    recordOpenGlDrawPair.textureRenderer.frontCameraInputSurface
                )
                val frontCameraCaptureRequest = frontCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    frontCameraOutputList.forEach { surface -> addTarget(surface) }
                }.build()
                val frontCameraCaptureSession = frontCamera.awaitCameraSessionConfiguration(frontCameraOutputList)
                frontCameraCaptureSession?.capture(frontCameraCaptureRequest, null, null)

                // バックカメラの設定
                val backCameraOutputList = listOfNotNull(
                    previewOpenGlDrawPair.textureRenderer.backCameraInputSurface,
                    recordOpenGlDrawPair.textureRenderer.backCameraInputSurface
                )
                val backCameraCaptureRequest = backCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    backCameraOutputList.forEach { surface -> addTarget(surface) }
                }.build()
                val backCameraCaptureSession = backCamera.awaitCameraSessionConfiguration(backCameraOutputList)
                backCameraCaptureSession?.capture(backCameraCaptureRequest, null, null)

                // 一回だけ描画する
                renderOpenGl(previewOpenGlDrawPair, recordOpenGlDrawPair)

                // ImageReader で取り出す
                imageReader?.saveJpegImage()

                // 撮影したらプレビューに戻す
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "撮影しました", Toast.LENGTH_SHORT).show()
                }
                startPreview()
            }
        }
    }

    /**
     * プレビュー用、録画用 OpenGL のセットアップを行う
     *
     * @param previewSurface プレビュー Surface
     * @param recordSurface 録画用 Surface
     * @return first がプレビュー用[OpenGlDrawPair]、second が録画用[OpenGlDrawPair]
     */
    private suspend fun createOpenGlDrawPair(
        previewSurface: Surface,
        recordSurface: Surface
    ): Pair<OpenGlDrawPair, OpenGlDrawPair> {
        // EGL とテクスチャとを描画するやつ、それぞれつくる
        val previewInputSurface = InputSurface(previewSurface)
        val previewTextureRenderer = KomaDroidCameraTextureRenderer()
        val recordInputSurface = InputSurface(recordSurface)
        val recordTextureRenderer = KomaDroidCameraTextureRenderer()

        // それぞれ用意したスレッドで呼び出す
        withContext(previewGlThreadDispatcher) {
            previewInputSurface.makeCurrent()
            previewTextureRenderer.createShader()
        }
        withContext(recordGlThreadDispatcher) {
            recordInputSurface.makeCurrent()
            recordTextureRenderer.createShader()
        }

        // カメラ映像の解像度
        // https://developer.android.com/reference/android/hardware/camera2/CameraDevice#createCaptureSession(android.hardware.camera2.params.SessionConfiguration)
        previewTextureRenderer.setSurfaceTextureSize(CAMERA_RESOLUTION_WIDTH, CAMERA_RESOLUTION_HEIGHT)
        recordTextureRenderer.setSurfaceTextureSize(CAMERA_RESOLUTION_WIDTH, CAMERA_RESOLUTION_HEIGHT)

        // 返す
        return OpenGlDrawPair(previewInputSurface, previewTextureRenderer) to OpenGlDrawPair(recordInputSurface, recordTextureRenderer)
    }

    /**
     * OpenGL で描画を行う。
     * コルーチンがキャンセルされるまでずっと描画するため、一時停止し続けます。
     *
     * @param previewPair プレビューを描画する[OpenGlDrawPair]
     * @param recordPair 録画用[OpenGlDrawPair]
     */
    private suspend fun loopRenderOpenGl(
        previewPair: OpenGlDrawPair,
        recordPair: OpenGlDrawPair
    ) = coroutineScope {
        while (isActive) {
            renderOpenGl(previewPair, recordPair)
        }
    }

    /**
     * OpenGL の描画を行う
     * 無限ループ版もあります。[loopRenderOpenGl]
     *
     * @param previewPair プレビューを描画する[OpenGlDrawPair]
     * @param recordPair 録画用[OpenGlDrawPair]
     */
    private suspend fun renderOpenGl(
        previewPair: OpenGlDrawPair,
        recordPair: OpenGlDrawPair
    ) {
        val (previewInputSurface, previewRenderer) = previewPair
        val (recordInputSurface, recordRenderer) = recordPair

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
        // 録画用も描画
        if (recordRenderer.isAvailableFrontCameraFrame() || recordRenderer.isAvailableBackCameraFrame()) {
            withContext(recordGlThreadDispatcher) {
                recordRenderer.updateFrontCameraTexture()
                recordRenderer.updateBackCameraTexture()
                recordRenderer.draw()
                recordInputSurface.swapBuffers()
            }
        }
    }

    /**
     * [SessionConfiguration]が非同期なので、コルーチンで出来るように
     *
     * @param outputSurfaceList 出力先[Surface]
     */
    private suspend fun CameraDevice.awaitCameraSessionConfiguration(
        outputSurfaceList: List<Surface>
    ) = suspendCancellableCoroutine { continuation ->
        // OutputConfiguration を作る
        val outputConfigurationList = outputSurfaceList.map { surface -> OutputConfiguration(surface) }
        val backCameraSessionConfiguration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputConfigurationList, cameraExecutor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                continuation.resume(captureSession)
            }

            override fun onConfigureFailed(p0: CameraCaptureSession) {
                continuation.resume(null)
            }
        })
        createCaptureSession(backCameraSessionConfiguration)
    }

    /** [ImageReader]から写真を取り出して、端末のギャラリーに登録する拡張関数。 */
    private suspend fun ImageReader.saveJpegImage() = withContext(Dispatchers.IO) {
        val image = acquireLatestImage()
        val width = image.width
        val height = image.height
        val planes = image.planes
        val buffer = planes[0].buffer
        // なぜか ImageReader のサイズに加えて、何故か Padding が入っていることを考慮する必要がある
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width
        // Bitmap 作成
        val readBitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        readBitmap.copyPixelsFromBuffer(buffer)
        // 余分な Padding を消す
        val editBitmap = Bitmap.createBitmap(readBitmap, 0, 0, CAMERA_RESOLUTION_WIDTH, CAMERA_RESOLUTION_HEIGHT)
        readBitmap.recycle()
        // ギャラリーに登録する
        val contentResolver = context.contentResolver
        val contentValues = contentValuesOf(
            MediaStore.Images.Media.DISPLAY_NAME to "${System.currentTimeMillis()}.jpg",
            MediaStore.Images.Media.RELATIVE_PATH to "${Environment.DIRECTORY_PICTURES}/KomaDroid"
        )
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return@withContext
        contentResolver.openOutputStream(uri)?.use { outputStream ->
            editBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        }
        // 開放
        editBitmap.recycle()
        image.close()
    }

    /** 破棄時に呼び出す。Activity の onDestroy とかで呼んでください。 */
    fun destroy() {
        scope.cancel()
        previewGlThreadDispatcher.close()
        recordGlThreadDispatcher.close()
        frontCamera?.close()
        backCamera?.close()
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

    /**
     * OpenGL 描画のための2点セット。
     * [InputSurface]、[KomaDroidCameraTextureRenderer]を持っているだけ。
     */
    private data class OpenGlDrawPair(
        val inputSurface: InputSurface,
        val textureRenderer: KomaDroidCameraTextureRenderer
    )

    companion object {
        const val CAMERA_RESOLUTION_WIDTH = 720
        const val CAMERA_RESOLUTION_HEIGHT = 1280
    }
}