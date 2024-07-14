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
import android.media.MediaRecorder
import android.opengl.Matrix
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.core.content.contentValuesOf
import io.github.takusan23.komadroid.akaricore5.AkariGraphicsProcessor
import io.github.takusan23.komadroid.akaricore5.AkariGraphicsSurfaceTexture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** カメラを開いたり、プレビュー用の SurfaceView を作ったり、静止画撮影したりする */
@OptIn(ExperimentalCoroutinesApi::class)
class KomaDroidCameraManager(
    private val context: Context,
    private val mode: CaptureMode
) {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    /** 今のタスク（プレビュー、動画撮影）キャンセル用 */
    private var currentJob: Job? = null

    /** 静止画撮影用[ImageReader] */
    private var imageReader: ImageReader? = null

    /** 録画用の[MediaRecorder] */
    private var mediaRecorder: MediaRecorder? = null

    /** 録画保存先 */
    private var saveVideoFile: File? = null

    /** 出力先 Surface */
    val surfaceView = SurfaceView(context)

    /**
     * プレビューを表示する[SurfaceView]のコールバックを[Flow]にして、そのついでに[AkariGraphicsProcessor]のインスタンスを生成する。
     * [SurfaceView]のコールバックがいつ呼ばれても対応できるように、ホットフローに変換して常にコールバックを監視することにする。
     */
    private val previewAkariGraphicsProcessor = callbackFlow {
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                trySend(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // do nothing
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                trySend(null)
            }
        }
        surfaceView.holder.addCallback(callback)
        awaitClose { surfaceView.holder.removeCallback(callback) }
    }.map { holder ->
        if (holder != null) {
            // OpenGL ES の glViewport と合わせる
            holder.setFixedSize(CAMERA_RESOLUTION_WIDTH, CAMERA_RESOLUTION_HEIGHT)
            // Processor を作る
            AkariGraphicsProcessor(
                outputSurface = holder.surface,
                width = CAMERA_RESOLUTION_WIDTH,
                height = CAMERA_RESOLUTION_HEIGHT
            ).apply { prepare() }
        } else null
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    /** 静止画、録画撮影用 [AkariGraphicsProcessor] */
    private var recordAkariGraphicsProcessor: AkariGraphicsProcessor? = null

    // カメラを開く、Flow なのはコールバックで通知されるので。また、ホットフローに変換して、一度だけ openCamera されるようにします（collect のたびに動かさない）
    // TODO ライフサイクルを購読する

    /** 前面カメラ */
    private val frontCameraFlow = openCameraFlow(getFrontCameraId()).stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    /** 背面カメラ */
    private val backCameraFlow = openCameraFlow(getBackCameraId()).stateIn(
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

    /** 用意をする */
    fun prepare() {
        scope.launch {
            // モードに応じて初期化を分岐
            when (mode) {
                CaptureMode.PICTURE -> initPictureMode()
                CaptureMode.VIDEO -> initVideoMode()
            }

            // カメラ映像を OpenGL ES からテクスチャとして使えるように SurfaceTexture を作る
            // TODO もしかしたらプレビューと録画用で SurfaceTexture を作る必要がないかも、attach / detach 出来るっぽい
            generatePreviewSurfaceTexture()

            // プレビュー用 OpenGL ES の用意
            // SurfaceView の生成と破棄に合わせて作り直す
            // collectLatest で Flow から新しい値が流れてきたらキャンセルするように
            launch {
                previewAkariGraphicsProcessor.collectLatest { processor ->
                    processor ?: return@collectLatest
                    try {
                        processor.drawLoop {
                            previewFrontCameraAkariSurfaceTexture ?: return@drawLoop
                            previewBackCameraAkariSurfaceTexture ?: return@drawLoop

                            // カメラ映像を描画する
                            drawSurfaceTexture(previewFrontCameraAkariSurfaceTexture!!) { mvpMatrix ->
                                Matrix.scaleM(mvpMatrix, 0, 1.7f, 1f, 1f)
                            }
                            drawSurfaceTexture(previewBackCameraAkariSurfaceTexture!!) { mvpMatrix ->
                                Matrix.scaleM(mvpMatrix, 0, 1.7f, 1f, 1f)
                                Matrix.scaleM(mvpMatrix, 0, 0.3f, 0.3f, 0.3f)
                            }
                        }
                    } finally {
                        processor.destroy()
                    }
                }
            }

            // プレビューを開始する
            startPreview()
        }
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
                    frontCameraFlow,
                    backCameraFlow,
                    previewAkariGraphicsProcessor
                ) { a, b, c -> Triple(a, b, c) }.collect { (frontCamera, backCamera, processor) ->

                    // フロントカメラ、バックカメラ、プレビューの OpenGL ES がすべて準備完了になるまで待つ
                    frontCamera ?: return@collect
                    backCamera ?: return@collect
                    processor ?: return@collect

                    // フロントカメラの設定
                    // 出力先
                    val frontCameraCaptureRequest = frontCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        frontCameraOutputSurfaceList.forEach { surface -> addTarget(surface) }
                    }.build()
                    val frontCameraCaptureSession = frontCamera.awaitCameraSessionConfiguration(frontCameraOutputSurfaceList)
                    frontCameraCaptureSession?.setRepeatingRequest(frontCameraCaptureRequest, null, null)

                    // バックカメラの設定
                    val backCameraCaptureRequest = backCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        backCameraOutputSurfaceList.forEach { surface -> addTarget(surface) }
                    }.build()
                    val backCameraCaptureSession = backCamera.awaitCameraSessionConfiguration(backCameraOutputSurfaceList)
                    backCameraCaptureSession?.setRepeatingRequest(backCameraCaptureRequest, null, null)
                }
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

                // 用意が揃うまで待つ
                val frontCamera = frontCameraFlow.filterNotNull().first()
                val backCamera = backCameraFlow.filterNotNull().first()

                // フロントカメラの設定
                // 出力先
                val frontCameraCaptureRequest = frontCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    frontCameraOutputSurfaceList.forEach { surface -> addTarget(surface) }
                }.build()
                val frontCameraCaptureSession = frontCamera.awaitCameraSessionConfiguration(frontCameraOutputSurfaceList)
                frontCameraCaptureSession?.capture(frontCameraCaptureRequest, null, null)

                // バックカメラの設定
                val backCameraCaptureRequest = backCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    backCameraOutputSurfaceList.forEach { surface -> addTarget(surface) }
                }.build()
                val backCameraCaptureSession = backCamera.awaitCameraSessionConfiguration(backCameraOutputSurfaceList)
                backCameraCaptureSession?.capture(backCameraCaptureRequest, null, null)

                // ImageReader に OpenGL ES で描画する
                // プレビューと違ってテクスチャが来るのを待つ
                recordAkariGraphicsProcessor?.drawOneshot {
                    // カメラ映像を描画する
                    drawSurfaceTexture(recordFrontCameraAkariSurfaceTexture!!, isAwaitTextureUpdate = true) { mvpMatrix ->
                        Matrix.scaleM(mvpMatrix, 0, 1.7f, 1f, 1f)
                    }
                    drawSurfaceTexture(recordBackCameraAkariSurfaceTexture!!, isAwaitTextureUpdate = true) { mvpMatrix ->
                        Matrix.scaleM(mvpMatrix, 0, 1.7f, 1f, 1f)
                        Matrix.scaleM(mvpMatrix, 0, 0.3f, 0.3f, 0.3f)
                    }
                }
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
     * 動画撮影をする
     * 静止画撮影用に[CameraDevice.TEMPLATE_RECORD]と[CameraCaptureSession.setRepeatingRequest]が使われます。
     */
    fun startRecordVideo() {
        scope.launch {
            // キャンセルして、コルーチンが終わるのを待つ
            currentJob?.cancelAndJoin()
            currentJob = launch {

                // カメラを開けるか
                // 全部非同期なのでコールバックを待つ
                combine(
                    frontCameraFlow,
                    backCameraFlow,
                    previewAkariGraphicsProcessor
                ) { a, b, c -> Triple(a, b, c) }.collectLatest { (frontCamera, backCamera, processor) ->

                    // フロントカメラ、バックカメラ、プレビューの OpenGL ES がすべて準備完了になるまで待つ
                    frontCamera ?: return@collectLatest
                    backCamera ?: return@collectLatest
                    processor ?: return@collectLatest

                    // フロントカメラの設定
                    // 出力先
                    val frontCameraCaptureRequest = frontCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        frontCameraOutputSurfaceList.forEach { surface -> addTarget(surface) }
                    }.build()
                    val frontCameraCaptureSession = frontCamera.awaitCameraSessionConfiguration(frontCameraOutputSurfaceList)
                    frontCameraCaptureSession?.setRepeatingRequest(frontCameraCaptureRequest, null, null)

                    // バックカメラの設定
                    val backCameraCaptureRequest = backCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        backCameraOutputSurfaceList.forEach { surface -> addTarget(surface) }
                    }.build()
                    val backCameraCaptureSession = backCamera.awaitCameraSessionConfiguration(backCameraOutputSurfaceList)
                    backCameraCaptureSession?.setRepeatingRequest(backCameraCaptureRequest, null, null)

                    // 録画開始
                    mediaRecorder?.start()
                    try {
                        // MediaRecorder に OpenGL ES で描画
                        // 録画中はループするのでこれ以降の処理には進まない
                        recordAkariGraphicsProcessor?.drawLoop {
                            // カメラ映像を描画する
                            drawSurfaceTexture(recordFrontCameraAkariSurfaceTexture!!) { mvpMatrix ->
                                Matrix.scaleM(mvpMatrix, 0, 1.7f, 1f, 1f)
                            }
                            drawSurfaceTexture(recordBackCameraAkariSurfaceTexture!!) { mvpMatrix ->
                                Matrix.scaleM(mvpMatrix, 0, 1.7f, 1f, 1f)
                                Matrix.scaleM(mvpMatrix, 0, 0.3f, 0.3f, 0.3f)
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
                            initVideoMode()
                            startPreview()
                        }
                    }
                }
            }
        }
    }

    /** [startRecordVideo]を終了する */
    fun stopRecordVideo() {
        // startRecordVideo の finally に進みます
        currentJob?.cancel()
    }


    /** 静止画モードの初期化 */
    private suspend fun initPictureMode() {
        imageReader = ImageReader.newInstance(
            CAMERA_RESOLUTION_WIDTH,
            CAMERA_RESOLUTION_HEIGHT,
            PixelFormat.RGBA_8888,
            2
        )
        // 描画を OpenGL に、プレビューと同じ
        recordAkariGraphicsProcessor = AkariGraphicsProcessor(
            outputSurface = imageReader!!.surface,
            width = CAMERA_RESOLUTION_WIDTH,
            height = CAMERA_RESOLUTION_HEIGHT,
        ).apply { prepare() }
        generateRecordSurfaceTexture()
    }

    /** 録画モードの初期化 */
    private suspend fun initVideoMode() {
        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(2)
            setVideoEncodingBitRate(3_000_000) // H.264 なので高めに
            setVideoFrameRate(30)
            setVideoSize(CAMERA_RESOLUTION_WIDTH, CAMERA_RESOLUTION_HEIGHT)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            // 一時的に getExternalFilesDir に保存する
            saveVideoFile = context.getExternalFilesDir(null)!!.resolve("${System.currentTimeMillis()}.mp4")
            setOutputFile(saveVideoFile!!)
            prepare()
        }
        // 描画を OpenGL に、プレビューと同じ
        recordAkariGraphicsProcessor = AkariGraphicsProcessor(
            outputSurface = mediaRecorder!!.surface,
            width = CAMERA_RESOLUTION_WIDTH,
            height = CAMERA_RESOLUTION_HEIGHT,
        ).apply { prepare() }
        generateRecordSurfaceTexture()
    }

    /** 録画用の[AkariGraphicsSurfaceTexture]を作る */
    private suspend fun generateRecordSurfaceTexture() {
        recordFrontCameraAkariSurfaceTexture = recordAkariGraphicsProcessor?.genTextureId { texId -> AkariGraphicsSurfaceTexture(texId) }
        recordBackCameraAkariSurfaceTexture = recordAkariGraphicsProcessor?.genTextureId { texId -> AkariGraphicsSurfaceTexture(texId) }
        recordFrontCameraAkariSurfaceTexture?.setResolution()
        recordBackCameraAkariSurfaceTexture?.setResolution()
    }

    /** プレビュー用の[AkariGraphicsSurfaceTexture]を作る */
    private suspend fun generatePreviewSurfaceTexture() {
        val previewProcessor = previewAkariGraphicsProcessor.filterNotNull().first()
        previewFrontCameraAkariSurfaceTexture = previewProcessor.genTextureId { texId -> AkariGraphicsSurfaceTexture(texId) }
        previewBackCameraAkariSurfaceTexture = previewProcessor.genTextureId { texId -> AkariGraphicsSurfaceTexture(texId) }
        previewFrontCameraAkariSurfaceTexture?.setResolution()
        previewBackCameraAkariSurfaceTexture?.setResolution()
    }

    /** [AkariGraphicsSurfaceTexture.setTextureSize]を呼び出す */
    private fun AkariGraphicsSurfaceTexture.setResolution() {
        setTextureSize(CAMERA_RESOLUTION_WIDTH, CAMERA_RESOLUTION_HEIGHT)
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
        scope.launch {
            recordAkariGraphicsProcessor?.destroy()
            previewAkariGraphicsProcessor.value?.destroy()
            previewFrontCameraAkariSurfaceTexture?.destroy()
            previewBackCameraAkariSurfaceTexture?.destroy()
            recordFrontCameraAkariSurfaceTexture?.destroy()
            recordBackCameraAkariSurfaceTexture?.destroy()
            frontCameraFlow.value?.close()
            backCameraFlow.value?.close()
            cancel()
        }
    }

    /**
     * カメラを開く
     * 開くのに成功したら[CameraDevice]を流します。失敗したら null を流します。
     *
     * @param cameraId 起動したいカメラ
     */
    @SuppressLint("MissingPermission")
    private fun openCameraFlow(cameraId: String) = callbackFlow {
        var _cameraDevice: CameraDevice? = null
        cameraManager.openCamera(cameraId, cameraExecutor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                _cameraDevice = camera
                trySend(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                _cameraDevice = camera
                camera.close()
                trySend(null)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                _cameraDevice = camera
                camera.close()
                trySend(null)
            }
        })
        awaitClose { _cameraDevice?.close() }
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

    companion object {
        const val CAMERA_RESOLUTION_WIDTH = 720
        const val CAMERA_RESOLUTION_HEIGHT = 1280
    }
}