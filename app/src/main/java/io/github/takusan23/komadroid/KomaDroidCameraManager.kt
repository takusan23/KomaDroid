package io.github.takusan23.komadroid

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.opengl.Matrix
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Range
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.core.content.contentValuesOf
import androidx.lifecycle.Lifecycle
import io.github.takusan23.akaricore.graphics.AkariGraphicsProcessor
import io.github.takusan23.akaricore.graphics.AkariGraphicsSurfaceTexture
import io.github.takusan23.akaricore.graphics.AkariGraphicsTextureRenderer
import io.github.takusan23.komadroid.tool.CameraDeviceState
import io.github.takusan23.komadroid.tool.CameraSettingData
import io.github.takusan23.komadroid.tool.DataStoreTool
import io.github.takusan23.komadroid.tool.SessionConfigureFailedException
import io.github.takusan23.komadroid.tool.awaitCameraSessionConfiguration
import io.github.takusan23.komadroid.tool.dataStore
import io.github.takusan23.komadroid.tool.openCameraFlow
import io.github.takusan23.komadroid.ui.components.ErrorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** カメラを開いたり、プレビュー用の SurfaceView を作ったり、静止画撮影したりする */
@OptIn(ExperimentalCoroutinesApi::class)
class KomaDroidCameraManager(
    private val context: Context,
    lifecycle: Lifecycle
) {

    var scale = 0.5f // TODO 拡大縮小
    var xPos = 0f // TODO X座標
    var yPos = 0f // TODO Y座標
    var isFlip = false // TODO 前面背面カメラを入れ替える

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val isLandScape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val _cameraZoomDataFlow = MutableStateFlow(getCameraZoomSpecification())
    private val _isVideoRecordingFlow = MutableStateFlow(false)
    private val _errorFlow = MutableStateFlow<ErrorType?>(null)
    private val _surfaceHolderStateFlow = MutableStateFlow<SurfaceHolder?>(null)
    private val _captureModeFlow = MutableStateFlow(CaptureMode.PICTURE)

    /** 今のタスク（動画撮影）キャンセル用 */
    private var currentJob: Job? = null

    /** 静止画撮影用[ImageReader] */
    private var imageReader: ImageReader? = null

    /** 録画用の[MediaRecorder] */
    private var mediaRecorder: MediaRecorder? = null

    /** 録画保存先 */
    private var saveVideoFile: File? = null

    /** 静止画、録画撮影用 [AkariGraphicsProcessor] */
    private var recordAkariGraphicsProcessor: AkariGraphicsProcessor? = null

    // カメラを開く、Flow なのはコールバックで通知されるので。また、ホットフローに変換して、一度だけ openCamera されるようにします（collect のたびに動かさない）

    /** 前面カメラ */
    private val frontCameraStateFlow = lifecycle.currentStateFlow.transformLatest { current ->
        // onResume のときだけ
        if (current == Lifecycle.State.RESUMED) {
            cameraManager.openCameraFlow(getFrontCameraId(), cameraExecutor).collect { camera -> emit(camera) }
        } else {
            emit(null)
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    /** 背面カメラ */
    private val backCameraStateFlow = lifecycle.currentStateFlow.transformLatest { current ->
        if (current == Lifecycle.State.RESUMED) {
            cameraManager.openCameraFlow(getBackCameraId(), cameraExecutor).collect { camera -> emit(camera) }
        } else {
            emit(null)
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    // プレビュー用、録画用の AkariGraphicsSurfaceTexture
    private var previewFrontCameraAkariSurfaceTexture: AkariGraphicsSurfaceTexture? = null
    private var previewBackCameraAkariSurfaceTexture: AkariGraphicsSurfaceTexture? = null
    private var recordFrontCameraAkariSurfaceTexture: AkariGraphicsSurfaceTexture? = null
    private var recordBackCameraAkariSurfaceTexture: AkariGraphicsSurfaceTexture? = null

    /** 前面カメラの出力先[Surface]の配列 */
    private val frontCameraOutputSurfaceList: List<Surface>
        get() = listOfNotNull(previewFrontCameraAkariSurfaceTexture?.surface, recordFrontCameraAkariSurfaceTexture?.surface)

    /** 背面カメラの出力先[Surface]の配列 */
    private val backCameraOutputSurfaceList: List<Surface>
        get() = listOfNotNull(previewBackCameraAkariSurfaceTexture?.surface, recordBackCameraAkariSurfaceTexture?.surface)

    /** カメラのズーム状態 */
    val cameraZoomDataFlow = _cameraZoomDataFlow.asStateFlow()

    /** 動画撮影中かどうか */
    val isVideoRecordingFlow = _isVideoRecordingFlow.asStateFlow()

    /** エラー Flow */
    val errorFlow = _errorFlow.asStateFlow()

    /** 撮影モード */
    val captureModeFlow = _captureModeFlow.asStateFlow()

    /** DataStore から設定を読み出して [CameraSettingData] を作る */
    val cameraSettingFlow = context.dataStore.data.map {
        DataStoreTool.readData(context)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    init {
        scope.launch {
            // SurfaceView が再生成された
            // 設定内容が変化した
            // 撮影モード（静止画、動画撮影）が変化した
            // 撮影モード切り替えででプレビューまで作り直している理由は 10Bit HDR のプレビューのため
            combine(
                cameraSettingFlow,
                _surfaceHolderStateFlow,
                _captureModeFlow,
                ::Triple
            ).collectLatest { (cameraSetting, surfaceHolder, captureMode) ->
                cameraSetting ?: return@collectLatest
                surfaceHolder ?: return@collectLatest

                // モードに応じて初期化を分岐
                when (captureMode) {
                    CaptureMode.PICTURE -> initPictureMode(cameraSetting)
                    CaptureMode.VIDEO -> initVideoMode(cameraSetting)
                }

                // glViewport に合わせる
                val (width, height) = cameraSetting.orientatedResolution
                surfaceHolder.setFixedSize(width, height)

                // 新しい SurfaceHolder で作り直す
                // 10Bit HDR は動画撮影でかつ 10Bit HDR 有効時のみ
                val previewAkariGraphicsProcessor = AkariGraphicsProcessor(
                    outputSurface = surfaceHolder.surface,
                    width = width,
                    height = height,
                    isEnableTenBitHdr = captureMode == CaptureMode.VIDEO && cameraSetting.isTenBitHdr
                ).apply { prepare() }

                // まだプレビュー用の AkariGraphicsSurfaceTexture がない場合は作る
                if (previewFrontCameraAkariSurfaceTexture == null) {
                    previewFrontCameraAkariSurfaceTexture = previewAkariGraphicsProcessor.genTextureId { texId -> AkariGraphicsSurfaceTexture(texId) }
                }
                if (previewBackCameraAkariSurfaceTexture == null) {
                    previewBackCameraAkariSurfaceTexture = previewAkariGraphicsProcessor.genTextureId { texId -> AkariGraphicsSurfaceTexture(texId) }
                }

                // 解像度
                previewFrontCameraAkariSurfaceTexture?.setResolution(cameraSetting)
                previewBackCameraAkariSurfaceTexture?.setResolution(cameraSetting)

                // プレビューを開始する
                // TODO 三連続ぐらい startPreview() を呼び出すと落ちる
                startPreview()

                try {
                    val previewLoopContinueData = AkariGraphicsProcessor.LoopContinueData(isRequestNextFrame = true, currentFrameNanoSeconds = 0)
                    previewAkariGraphicsProcessor.drawLoop {
                        drawFrame(
                            frontTexture = previewFrontCameraAkariSurfaceTexture!!,
                            backTexture = previewBackCameraAkariSurfaceTexture!!
                        )
                        previewLoopContinueData
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    // java.lang.RuntimeException: glBindFramebuffer: glError 1285
                    // Google Tensor だけ静止画撮影・動画撮影切り替え時に頻繁に落ちる
                    // Snapdragon だと落ちないのでガチで謎
                    // もうどうしようもないので checkGlError() の例外をここは無視する
                    // Google Tensor 、、、許すまじ
                    e.printStackTrace()
                } finally {
                    withContext(NonCancellable) {
                        // プレビュー Processor が作り直しになる、それつまり OpenGL のコンテキストも作り直しになる
                        // ので、detachGl で切り離しておく
                        // OpenGL ES くん、かなりシビア
                        previewAkariGraphicsProcessor.destroy {
                            previewFrontCameraAkariSurfaceTexture?.detachGl()
                            previewBackCameraAkariSurfaceTexture?.detachGl()
                        }
                    }
                }
            }
        }
    }

    /**
     * 撮影モードを切り替える
     *
     * @param mode 静止画か動画
     */
    fun switchCaptureMode(mode: CaptureMode) {
        _captureModeFlow.value = mode
    }

    /**
     * プレビュー[android.view.SurfaceView]の[SurfaceHolder]をセットする
     *
     * @param surfaceHolder [android.view.SurfaceView]のコールバック参照。破棄された場合は null
     */
    fun setPreviewSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        _surfaceHolderStateFlow.value = surfaceHolder
    }

    /** プレビューを始める */
    private fun startPreview() {
        scope.launch {
            // キャンセルして、コルーチンが終わるのを待つ
            currentJob?.cancelAndJoin()
            currentJob = launch {

                // カメラを開けるか
                // 全部非同期なので、Flow にした後、複数の Flow を一つにしてすべての準備ができるのを待つ。
                combine(
                    frontCameraStateFlow,
                    backCameraStateFlow,
                    ::Pair
                ).collectLatest { (frontCameraState, backCameraState) ->

                    // null は初期値
                    frontCameraState ?: return@collectLatest
                    backCameraState ?: return@collectLatest

                    // フロントカメラ、バックカメラがすべて準備完了になるまで待つ
                    // カメラが Open 以外は return
                    val frontCameraDevice = when (frontCameraState) {
                        // CameraDevice があれば OK
                        is CameraDeviceState.Open -> frontCameraState.cameraDevice
                        // エラーは多分戻ってこないので return
                        // TODO なんかいい感じにエラーはまとめたい
                        CameraDeviceState.Error -> {
                            _errorFlow.value = ErrorType.CameraOpenError
                            return@collectLatest
                        }

                        CameraDeviceState.Disconnect -> {
                            _errorFlow.value = ErrorType.UnknownError
                            return@collectLatest
                        }
                    }
                    val backCameraDevice = when (backCameraState) {
                        is CameraDeviceState.Open -> backCameraState.cameraDevice
                        CameraDeviceState.Error -> {
                            _errorFlow.value = ErrorType.CameraOpenError
                            return@collectLatest
                        }

                        CameraDeviceState.Disconnect -> {
                            _errorFlow.value = ErrorType.UnknownError
                            return@collectLatest
                        }
                    }

                    val cameraSetting = cameraSettingFlow.filterNotNull().first()
                    val captureMode = captureModeFlow.value

                    // 10Bit HDR は動画撮影でかつ 10Bit HDR 有効時のみ
                    val isEnableTenBitHdr = captureMode == CaptureMode.VIDEO && cameraSetting.isTenBitHdr

                    // フロントカメラ
                    val frontCameraCaptureRequest: CaptureRequest.Builder
                    val frontCameraCaptureSession: CameraCaptureSession
                    try {
                        frontCameraCaptureRequest = frontCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            frontCameraOutputSurfaceList.forEach { surface -> addTarget(surface) }
                            set(CaptureRequest.CONTROL_ZOOM_RATIO, cameraZoomDataFlow.value.currentFrontCameraZoom)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cameraSetting.cameraFps.fps, cameraSetting.cameraFps.fps))
                        }

                        frontCameraCaptureSession = frontCameraDevice.awaitCameraSessionConfiguration(
                            frontCameraOutputSurfaceList,
                            cameraExecutor,
                            isEnableTenBitHdr = isEnableTenBitHdr
                        )
                        frontCameraCaptureSession.setRepeatingRequest(frontCameraCaptureRequest.build(), null, null)
                    } catch (e: CameraAccessException) {
                        // エラー return
                        _errorFlow.value = ErrorType.UnknownError
                        return@collectLatest
                    } catch (e: SessionConfigureFailedException) {
                        // セッション構成に失敗した return
                        _errorFlow.value = ErrorType.UnknownError
                        return@collectLatest
                    }

                    // バックカメラ
                    val backCameraCaptureRequest: CaptureRequest.Builder
                    val backCameraCaptureSession: CameraCaptureSession
                    try {
                        backCameraCaptureRequest = backCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            backCameraOutputSurfaceList.forEach { surface -> addTarget(surface) }
                            set(CaptureRequest.CONTROL_ZOOM_RATIO, cameraZoomDataFlow.value.currentBackCameraZoom)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cameraSetting.cameraFps.fps, cameraSetting.cameraFps.fps))
                        }

                        backCameraCaptureSession = backCameraDevice.awaitCameraSessionConfiguration(
                            backCameraOutputSurfaceList,
                            cameraExecutor,
                            isEnableTenBitHdr = isEnableTenBitHdr
                        )
                        backCameraCaptureSession.setRepeatingRequest(backCameraCaptureRequest.build(), null, null)
                    } catch (e: CameraAccessException) {
                        _errorFlow.value = ErrorType.UnknownError
                        return@collectLatest
                    } catch (e: SessionConfigureFailedException) {
                        _errorFlow.value = ErrorType.UnknownError
                        return@collectLatest
                    }

                    launch {
                        collectZoomFlowAndSetZoomLevel(
                            backCameraCaptureSession = backCameraCaptureSession,
                            backCameraCaptureRequest = backCameraCaptureRequest,
                            frontCameraCaptureSession = frontCameraCaptureSession,
                            frontCameraCaptureRequest = frontCameraCaptureRequest
                        )
                    }
                }
            }
        }
    }

    /**
     * 静止画撮影する。撮影が終わるまで一時停止します。
     * 静止画撮影用に[CameraDevice.TEMPLATE_STILL_CAPTURE]と[CameraCaptureSession.capture]が使われます。
     */
    suspend fun awaitTakePicture() {
        // キャンセルして、コルーチンが終わるのを待つ
        currentJob?.cancelAndJoin()
        currentJob = scope.launch {

            // 用意が揃うまで待つ
            val frontCameraState = frontCameraStateFlow.filterIsInstance<CameraDeviceState.Open>().first()
            val backCameraState = backCameraStateFlow.filterIsInstance<CameraDeviceState.Open>().first()
            val cameraSetting = cameraSettingFlow.filterNotNull().first()

            // セッション構成に失敗したら return
            // フロントカメラ
            try {
                val frontCameraCaptureRequest = frontCameraState.cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    frontCameraOutputSurfaceList.forEach { surface -> addTarget(surface) }
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, cameraZoomDataFlow.value.currentFrontCameraZoom)
                }.build()

                val frontCameraCaptureSession = frontCameraState.cameraDevice.awaitCameraSessionConfiguration(
                    frontCameraOutputSurfaceList,
                    cameraExecutor,
                    isEnableTenBitHdr = false // 静止画撮影では HDR 使わないので
                )
                frontCameraCaptureSession.capture(frontCameraCaptureRequest, null, null)
            } catch (e: CameraAccessException) {
                _errorFlow.value = ErrorType.UnknownError
                return@launch
            } catch (e: SessionConfigureFailedException) {
                _errorFlow.value = ErrorType.UnknownError
                return@launch
            }

            // バックカメラ
            try {
                val backCameraCaptureRequest = backCameraState.cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    backCameraOutputSurfaceList.forEach { surface -> addTarget(surface) }
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, cameraZoomDataFlow.value.currentBackCameraZoom)
                }.build()

                val backCameraCaptureSession = backCameraState.cameraDevice.awaitCameraSessionConfiguration(
                    backCameraOutputSurfaceList,
                    cameraExecutor,
                    isEnableTenBitHdr = false
                )
                backCameraCaptureSession.capture(backCameraCaptureRequest, null, null)
            } catch (e: CameraAccessException) {
                _errorFlow.value = ErrorType.UnknownError
                return@launch
            } catch (e: SessionConfigureFailedException) {
                _errorFlow.value = ErrorType.UnknownError
                return@launch
            }

            // ImageReader に OpenGL ES で描画する
            // プレビューと違ってテクスチャが来るのを待つ
            recordAkariGraphicsProcessor?.drawOneshot {
                drawFrame(
                    frontTexture = recordFrontCameraAkariSurfaceTexture!!,
                    backTexture = recordBackCameraAkariSurfaceTexture!!,
                    isAwaitTextureUpdate = true
                )
            }
            // ImageReader で取り出す
            imageReader?.saveJpegImage(cameraSetting)

            // 撮影したらプレビューに戻す
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "保存しました", Toast.LENGTH_SHORT).show()
            }
            startPreview()
        }
        // 終わるのを待つ
        currentJob?.join()
    }

    /**
     * 動画撮影をする
     * 動画撮影用に[CameraDevice.TEMPLATE_RECORD]と[CameraCaptureSession.setRepeatingRequest]が使われます。
     */
    fun startRecordVideo() {
        scope.launch {
            // キャンセルして、コルーチンが終わるのを待つ
            currentJob?.cancelAndJoin()
            currentJob = launch {

                // カメラを開けるか
                // 全部非同期なのでコールバックを待つ
                combine(
                    frontCameraStateFlow,
                    backCameraStateFlow,
                    ::Pair
                ).collectLatest { (frontCameraState, backCameraState) ->

                    // null は初期値
                    frontCameraState ?: return@collectLatest
                    backCameraState ?: return@collectLatest

                    // フロントカメラ、バックカメラがすべて準備完了になるまで待つ
                    // カメラが Open 以外は return
                    val frontCameraDevice = when (frontCameraState) {
                        is CameraDeviceState.Open -> frontCameraState.cameraDevice
                        CameraDeviceState.Error -> {
                            _errorFlow.value = ErrorType.CameraOpenError
                            return@collectLatest
                        }

                        CameraDeviceState.Disconnect -> {
                            _errorFlow.value = ErrorType.UnknownError
                            return@collectLatest
                        }
                    }
                    val backCameraDevice = when (backCameraState) {
                        is CameraDeviceState.Open -> backCameraState.cameraDevice
                        CameraDeviceState.Error -> {
                            _errorFlow.value = ErrorType.CameraOpenError
                            return@collectLatest
                        }

                        CameraDeviceState.Disconnect -> {
                            _errorFlow.value = ErrorType.UnknownError
                            return@collectLatest
                        }
                    }

                    val cameraSetting = cameraSettingFlow.filterNotNull().first()

                    // フロントカメラ
                    val frontCameraCaptureSession: CameraCaptureSession
                    val frontCameraCaptureRequest: CaptureRequest.Builder
                    try {
                        frontCameraCaptureRequest = frontCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            frontCameraOutputSurfaceList.forEach { surface -> addTarget(surface) }
                            set(CaptureRequest.CONTROL_ZOOM_RATIO, cameraZoomDataFlow.value.currentFrontCameraZoom)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cameraSetting.cameraFps.fps, cameraSetting.cameraFps.fps))
                        }

                        frontCameraCaptureSession = frontCameraDevice.awaitCameraSessionConfiguration(
                            frontCameraOutputSurfaceList,
                            cameraExecutor,
                            isEnableTenBitHdr = cameraSetting.isTenBitHdr
                        )
                        frontCameraCaptureSession.setRepeatingRequest(frontCameraCaptureRequest.build(), null, null)
                    } catch (e: CameraAccessException) {
                        _errorFlow.value = ErrorType.UnknownError
                        return@collectLatest
                    } catch (e: SessionConfigureFailedException) {
                        _errorFlow.value = ErrorType.UnknownError
                        return@collectLatest
                    }

                    // バックカメラ
                    val backCameraCaptureSession: CameraCaptureSession
                    val backCameraCaptureRequest: CaptureRequest.Builder
                    try {
                        backCameraCaptureRequest = backCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            backCameraOutputSurfaceList.forEach { surface -> addTarget(surface) }
                            set(CaptureRequest.CONTROL_ZOOM_RATIO, cameraZoomDataFlow.value.currentBackCameraZoom)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cameraSetting.cameraFps.fps, cameraSetting.cameraFps.fps))
                        }

                        backCameraCaptureSession = backCameraDevice.awaitCameraSessionConfiguration(
                            backCameraOutputSurfaceList,
                            cameraExecutor,
                            isEnableTenBitHdr = cameraSetting.isTenBitHdr
                        )
                        backCameraCaptureSession.setRepeatingRequest(backCameraCaptureRequest.build(), null, null)
                    } catch (e: CameraAccessException) {
                        _errorFlow.value = ErrorType.UnknownError
                        return@collectLatest
                    } catch (e: SessionConfigureFailedException) {
                        _errorFlow.value = ErrorType.UnknownError
                        return@collectLatest
                    }

                    _isVideoRecordingFlow.value = true
                    // 両方の映像が来るまで録画を開始しない
                    // 録画開始
                    mediaRecorder?.start()
                    try {
                        coroutineScope {
                            launch {
                                // ズームを適用する
                                collectZoomFlowAndSetZoomLevel(
                                    backCameraCaptureSession = backCameraCaptureSession,
                                    backCameraCaptureRequest = backCameraCaptureRequest,
                                    frontCameraCaptureSession = frontCameraCaptureSession,
                                    frontCameraCaptureRequest = frontCameraCaptureRequest
                                )
                            }
                            launch {
                                // MediaRecorder に OpenGL ES で描画
                                // 録画中はループするのでこれ以降の処理には進まない
                                val recordLoopContinueData = AkariGraphicsProcessor.LoopContinueData(isRequestNextFrame = true, currentFrameNanoSeconds = 0)
                                recordAkariGraphicsProcessor?.drawLoop {
                                    drawFrame(
                                        frontTexture = recordFrontCameraAkariSurfaceTexture!!,
                                        backTexture = recordBackCameraAkariSurfaceTexture!!
                                    )
                                    // System.nanoTime() が eglPresentationTimeANDROID のデフォルト値になるらしい
                                    recordLoopContinueData.currentFrameNanoSeconds = System.nanoTime()
                                    recordLoopContinueData
                                }
                            }
                        }
                    } finally {
                        // 録画終了処理
                        // stopRecordVideo を呼び出したときか、collectLatest から新しい値が来た時
                        // キャンセルされた後、普通ならコルーチンが起動できない。
                        // NonCancellable を付けることで起動できるが、今回のように終了処理のみで使いましょうね
                        withContext(NonCancellable) {
                            mediaRecorder?.stop()
                            mediaRecorder?.release()

                            // 動画ファイルを動画フォルダへコピーさせ、ファイルを消す
                            withContext(Dispatchers.IO) {
                                val contentResolver = context.contentResolver
                                val contentValues = contentValuesOf(
                                    MediaStore.Images.Media.DISPLAY_NAME to saveVideoFile!!.name,
                                    MediaStore.Images.Media.RELATIVE_PATH to "${Environment.DIRECTORY_MOVIES}/KomaDroid"
                                )
                                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)!!
                                saveVideoFile!!.inputStream().use { inputStream ->
                                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }
                                saveVideoFile!!.delete()
                            }

                            // MediaRecorder は stop したら使えないので、MediaRecorder を作り直してからプレビューに戻す
                            initVideoMode(cameraSettingData = cameraSettingFlow.filterNotNull().first())
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "保存しました", Toast.LENGTH_SHORT).show()
                            }
                            startPreview()

                            // 全部終わってからフラグを落とす
                            _isVideoRecordingFlow.value = false
                        }
                    }
                }
            }
        }
    }

    /** [startRecordVideo]を終了する。終了できるまで一時停止します。 */
    suspend fun awaitStopRecordVideo() {
        // startRecordVideo の finally に進みます
        currentJob?.cancelAndJoin()
    }

    /** エラーを閉じる */
    fun closeError() {
        _errorFlow.value = null
    }

    /** 破棄時に呼び出す。Activity の onDestroy とかで呼んでください。 */
    fun destroy() {
        scope.launch {
            currentJob?.cancel()
            recordAkariGraphicsProcessor?.destroy()
            recordFrontCameraAkariSurfaceTexture?.destroy()
            recordBackCameraAkariSurfaceTexture?.destroy()
            recordFrontCameraAkariSurfaceTexture?.destroy()
            recordBackCameraAkariSurfaceTexture?.destroy()
            cancel()
        }
    }

    /** 静止画モードの初期化 */
    private suspend fun initPictureMode(cameraSettingData: CameraSettingData) {
        val (width, height) = cameraSettingData.orientatedResolution

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )
        // 描画を OpenGL に、プレビューと同じ
        beforeRecreateRecordAkariGraphicsProcessor()
        recordAkariGraphicsProcessor = AkariGraphicsProcessor(
            outputSurface = imageReader!!.surface,
            width = width,
            height = height,
            isEnableTenBitHdr = false
        ).apply { prepare() }
        afterRecreateRecordAkariGraphicsProcessor(cameraSettingData)
    }

    /** 録画モードの初期化 */
    private suspend fun initVideoMode(cameraSettingData: CameraSettingData) {
        val (width, height) = cameraSettingData.orientatedResolution
        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(
                when (cameraSettingData.videoCodec) {
                    CameraSettingData.VideoCodec.AVC -> MediaRecorder.VideoEncoder.H264
                    CameraSettingData.VideoCodec.HEVC -> MediaRecorder.VideoEncoder.HEVC
                }
            )
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(2)
            setVideoEncodingBitRate(cameraSettingData.videoBitrate)
            setVideoFrameRate(cameraSettingData.cameraFps.fps)
            setVideoSize(width, height)
            setAudioEncodingBitRate(192_000)
            setAudioSamplingRate(48_000)
            // 一時的に getExternalFilesDir に保存する
            saveVideoFile = context.getExternalFilesDir(null)!!.resolve("${System.currentTimeMillis()}.mp4")
            setOutputFile(saveVideoFile!!)
            prepare()
        }
        // 描画を OpenGL に、プレビューと同じ
        beforeRecreateRecordAkariGraphicsProcessor()
        recordAkariGraphicsProcessor = AkariGraphicsProcessor(
            outputSurface = mediaRecorder!!.surface,
            width = width,
            height = height,
            isEnableTenBitHdr = cameraSettingData.isTenBitHdr
        ).apply { prepare() }
        afterRecreateRecordAkariGraphicsProcessor(cameraSettingData)
    }

    /**
     * [AkariGraphicsProcessor.drawOneshot]や[AkariGraphicsProcessor.drawLoop]の共通している描画処理
     * 行列とかなんとか。
     *
     * TODO 左右反転欲しいかも。自撮りのときに文字が反転しそう
     *
     * @param frontTexture プレビュー用か録画用か
     * @param backTexture プレビュー用か録画用か
     * @param isAwaitTextureUpdate [AkariGraphicsTextureRenderer.drawSurfaceTexture]の引数
     */
    private suspend fun AkariGraphicsTextureRenderer.drawFrame(
        frontTexture: AkariGraphicsSurfaceTexture,
        backTexture: AkariGraphicsSurfaceTexture,
        isAwaitTextureUpdate: Boolean = false
    ) {
        // 映像を入れ替えるなら
        val backgroundTexture = if (isFlip) frontTexture else backTexture
        val popupTexture = if (isFlip) backTexture else frontTexture

        // カメラ映像を描画する
        drawSurfaceTexture(backgroundTexture, isAwaitTextureUpdate, onTransform = { mvpMatrix ->
            if (isLandScape) {
                // 回転する
                Matrix.rotateM(mvpMatrix, 0, 90f, 0f, 0f, 1f)
            }
        })
        drawSurfaceTexture(popupTexture, isAwaitTextureUpdate, onTransform = { mvpMatrix ->
            if (isLandScape) {
                // 回転する
                Matrix.rotateM(mvpMatrix, 0, 90f, 0f, 0f, 1f)
            }
            Matrix.scaleM(mvpMatrix, 0, scale, scale, 1f)
            Matrix.translateM(mvpMatrix, 0, xPos, yPos, 1f)
        })
    }

    /** 録画用の[AkariGraphicsProcessor]を破棄する前に呼び出す */
    private suspend fun beforeRecreateRecordAkariGraphicsProcessor() {
        // recordAkariGraphicsProcessor を作り直すため、detach して切り離しておく
        // 作り直したら attach する（TODO が、今のところ描画前に attach しており不要）
        recordAkariGraphicsProcessor?.destroy {
            recordFrontCameraAkariSurfaceTexture?.detachGl()
            recordBackCameraAkariSurfaceTexture?.detachGl()
        }
    }

    /** 録画用の[AkariGraphicsProcessor]を生成したときに呼び出す */
    private suspend fun afterRecreateRecordAkariGraphicsProcessor(cameraSettingData: CameraSettingData) {
        // そもそもない場合
        if (recordBackCameraAkariSurfaceTexture == null) {
            recordFrontCameraAkariSurfaceTexture = recordAkariGraphicsProcessor?.genTextureId { texId -> AkariGraphicsSurfaceTexture(texId) }
        }
        if (recordBackCameraAkariSurfaceTexture == null) {
            recordBackCameraAkariSurfaceTexture = recordAkariGraphicsProcessor?.genTextureId { texId -> AkariGraphicsSurfaceTexture(texId) }
        }
        // 解像度
        recordFrontCameraAkariSurfaceTexture?.setResolution(cameraSettingData)
        recordBackCameraAkariSurfaceTexture?.setResolution(cameraSettingData)
    }

    /** [AkariGraphicsSurfaceTexture.setTextureSize]を呼び出す */
    private fun AkariGraphicsSurfaceTexture.setResolution(cameraSettingData: CameraSettingData) {
        // 多分横の状態のアスペクト比を入れる。
        // カメラ側で、縦持ちの場合は、幅と高さを入れ替えてくれる（可能性）
        val (width, height) = cameraSettingData.highestResolution.landscape
        setTextureSize(width, height)
    }

    /** [ImageReader]から写真を取り出して、端末のギャラリーに登録する拡張関数。 */
    private suspend fun ImageReader.saveJpegImage(cameraSettingData: CameraSettingData) = withContext(Dispatchers.IO) {
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
        val (settingWidth, settingHeight) = cameraSettingData.orientatedResolution
        val editBitmap = Bitmap.createBitmap(readBitmap, 0, 0, settingWidth, settingHeight)
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

    /** ズーム状態を更新する */
    fun updateZoomData(zoomData: CameraZoomData) {
        // TODO ZoomData.backCameraZoomRange とかは別に分ける、copy で書き換えできないようにしたい
        _cameraZoomDataFlow.value = zoomData
    }

    /** カメラのズームをセットして、[CameraCaptureSession.setRepeatingRequest]を再度呼び出す。Flow を購読するので、一時停止し続けます。TODO 多分これも[CameraAccessException]の例外を見る必要がある、が、セッションが確立しないとこの関数が呼ばれないので今のところは動いている。 */
    private suspend fun collectZoomFlowAndSetZoomLevel(
        backCameraCaptureSession: CameraCaptureSession,
        backCameraCaptureRequest: CaptureRequest.Builder,
        frontCameraCaptureSession: CameraCaptureSession,
        frontCameraCaptureRequest: CaptureRequest.Builder
    ) = coroutineScope {
        // ズーム状態が変化したら適用
        launch {
            cameraZoomDataFlow
                .mapNotNull { it.currentBackCameraZoom }
                .distinctUntilChanged()
                .collect { newZoom ->
                    backCameraCaptureRequest.set(CaptureRequest.CONTROL_ZOOM_RATIO, newZoom)
                    backCameraCaptureSession.setRepeatingRequest(backCameraCaptureRequest.build(), null, null)
                }
        }
        launch {
            cameraZoomDataFlow
                .mapNotNull { it.currentFrontCameraZoom }
                .distinctUntilChanged()
                .collect { newZoom ->
                    frontCameraCaptureRequest.set(CaptureRequest.CONTROL_ZOOM_RATIO, newZoom)
                    frontCameraCaptureSession.setRepeatingRequest(frontCameraCaptureRequest.build(), null, null)
                }
        }
    }

    /** 画面回転に合わせて width / height を入れ替える */
    private val CameraSettingData.orientatedResolution
        get() = CameraSettingData.Size(
            if (isLandScape) highestResolution.landscape.width else highestResolution.portrait.width,
            if (isLandScape) highestResolution.landscape.height else highestResolution.portrait.height
        )

    /** ズームの情報を返す */
    private fun getCameraZoomSpecification(): CameraZoomData {
        // Pixel 8 Pro の場合は 0.4944783..30.0 のような値になる
        val backCameraZoomSpecification = cameraManager.getCameraCharacteristics(getBackCameraId()).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.lower..it.upper }
        val frontCameraZoomSpecification = cameraManager.getCameraCharacteristics(getFrontCameraId()).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.lower..it.upper }
        return CameraZoomData(
            backCameraZoomRange = backCameraZoomSpecification ?: 1f..1f,
            frontCameraZoomRange = frontCameraZoomSpecification ?: 1f..1f,
            currentBackCameraZoom = 1f,
            currentFrontCameraZoom = 1f
        )
    }

    /** フロントカメラの ID を返す */
    private fun getFrontCameraId(): String = cameraManager
        .cameraIdList
        .first { cameraId -> cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }

    /** バックカメラの ID を返す */
    private fun getBackCameraId(): String = cameraManager
        .cameraIdList
        .first { cameraId -> cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }

    /** 静止画撮影 or 録画 */
    enum class CaptureMode {
        /** 静止画撮影 */
        PICTURE,

        /** 録画 */
        VIDEO
    }

    /** カメラのズームのデータ */
    data class CameraZoomData(
        val backCameraZoomRange: ClosedFloatingPointRange<Float> = 1f..1f,
        val frontCameraZoomRange: ClosedFloatingPointRange<Float> = 1f..1f,
        val currentBackCameraZoom: Float = 1f,
        val currentFrontCameraZoom: Float = 1f
    )
}