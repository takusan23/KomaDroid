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
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.core.content.contentValuesOf
import androidx.core.graphics.scale
import io.github.takusan23.komadroid.gl.InputSurface
import io.github.takusan23.komadroid.gl.KomaDroidCameraTextureRenderer
import io.github.takusan23.komadroid.gl.TextureCopyTextureRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
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

    /** プレビュー用 OpenGL ES のスレッド */
    private val previewGlThreadDispatcher = newSingleThreadContext("PreviewGlThread")

    /** 録画用 OpenGL ES のスレッド */
    private val recordGlThreadDispatcher = newSingleThreadContext("RecordGlThread")

    /** 静止画撮影用[ImageReader] */
    private var imageReader: ImageReader? = null

    /** 録画用の[MediaRecorder] */
    private var mediaRecorder: MediaRecorder? = null

    /** 録画保存先 */
    private var saveVideoFile: File? = null

    /**
     * 録画用[OpenGlDrawPair]。
     * SurfaceView と違って ImageReader / MediaRecorder にはコールバックとかは無いので Flow にはしていない。
     */
    private var recordOpenGlDrawPair: OpenGlDrawPair? = null

    /** 出力先 Surface */
    val surfaceView = SurfaceView(context)

    /**
     * [SurfaceView]へ OpenGL で描画できるやつ。
     * ただ、[SurfaceView]は生成と破棄の非同期コールバックを待つ必要があるため、このような[Flow]を使う羽目になっている。
     * これは[OpenGlDrawPair]の生成までしかやっていないので、破棄は使う側で頼みました。
     *
     * また、[stateIn]でホットフローに変換し、[SurfaceView]のコールバックがいつ呼ばれても大丈夫にする。
     * [callbackFlow]はコールドフローで、collect するまで動かない、いつコールバックが呼ばれるかわからないため、今回はホットフローに変換している。
     */
    private val previewOpenGlDrawPairFlow = callbackFlow {
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
        // OpenGL ES のセットアップ
        val surface = holder?.surface
        if (surface != null) {
            withContext(previewGlThreadDispatcher) {
                createOpenGlDrawPair(surface)
            }
        } else null
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    // カメラを開く、Flow なのはコールバックで通知されるので。また、ホットフローに変換して、一度だけ openCamera されるようにします（collect のたびに動かさない）

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

    /**
     * イメージセグメンテーション用に Bitmap を提供しなくてはいけない、そのための[ImageReader]。
     * maxImages が多いが、多めに取らないと、プレビューが遅くなる。https://stackoverflow.com/questions/34555545
     *
     * width / height を半分以下にしているのはメモリ使用量を減らす目的と、
     * どうせ解析にクソデカい画像を入れても遅くなるだけなので、この段階で小さくしてしまう。
     */
    private val analyzeImageReader = ImageReader.newInstance(
        CAMERA_RESOLUTION_WIDTH / ANALYZE_DIV_SCALE,
        CAMERA_RESOLUTION_HEIGHT / ANALYZE_DIV_SCALE,
        PixelFormat.RGBA_8888,
        2
    )

    /** [analyzeImageReader]用 GL スレッド */
    private val analyzeGlThreadDispatcher = newSingleThreadContext("AnalyzeGlThread")

    /** [analyzeImageReader]用[OpenGlDrawPair] */
    private var analyzeOpenGlDrawPair: AnalyzeOpenGlDrawPair? = null

    /** 用意をする */
    fun prepare() {
        scope.launch {
            // モードに応じて初期化を分岐
            when (mode) {
                CaptureMode.PICTURE -> initPictureMode()
                CaptureMode.VIDEO -> initVideoMode()
            }

            // プレビュー Surface で OpenGL ES の描画と破棄を行う。OpenGL ES の用意は map { } でやっている。
            // 新しい値が来たら、既存の OpenGlDrawPair は破棄するので、collectLatest でキャンセルを投げてもらうようにする。
            // また、録画用（静止画撮影、動画撮影）も別のところで描画
            launch {
                previewOpenGlDrawPairFlow.collectLatest { previewOpenGlDrawPair ->
                    previewOpenGlDrawPair ?: return@collectLatest

                    try {
                        // OpenGL ES の描画のためのメインループ
                        withContext(previewGlThreadDispatcher) {
                            while (isActive) {
                                renderOpenGl(previewOpenGlDrawPair)
                            }
                        }
                    } finally {
                        // 終了時は OpenGL ES の破棄
                        withContext(NonCancellable + previewGlThreadDispatcher) {
                            previewOpenGlDrawPair.textureRenderer.destroy()
                            previewOpenGlDrawPair.inputSurface.destroy()
                        }
                    }
                }
            }

            // MediaPipe のイメージセグメンテーションを行う
            // まず前面カメラのカメラ映像を ImageReader で受け取り、Bitmap にする
            // そのあと、イメージセグメンテーションの推論をして、結果を OpenGL ES へ渡す
            // OpenGL ES 側で描画する処理は、他のプレビューとかの描画ループ中に入れてもらうことにする。
            analyzeOpenGlDrawPair = withContext(analyzeGlThreadDispatcher) {
                // また、本当は ImageReader を Camera2 API に渡せばいいはずだが、プレビューが重たくなってしまった。
                // OpenGL ES を経由すると改善したのでとりあえずそれで（謎）
                val inputSurface = InputSurface(analyzeImageReader.surface)
                val textureRenderer = TextureCopyTextureRenderer()
                inputSurface.makeCurrent()
                textureRenderer.createShader()
                textureRenderer.setSurfaceTextureSize(CAMERA_RESOLUTION_WIDTH / ANALYZE_DIV_SCALE, CAMERA_RESOLUTION_HEIGHT / ANALYZE_DIV_SCALE)
                AnalyzeOpenGlDrawPair(inputSurface, textureRenderer)
            }
            launch { prepareAndStartImageSegmentation() }

            // プレビューを開始する
            startPreview()
        }
    }

    /** イメージセグメンテーションの用意と開始 */
    private suspend fun prepareAndStartImageSegmentation() = coroutineScope {
        // MediaPipe
        val mediaPipeImageSegmentation = MediaPipeImageSegmentation(context)

        try {
            listOf(
                launch {
                    // カメラ映像を受け取って解析に投げる部分
                    withContext(analyzeGlThreadDispatcher) {

                        while (isActive) {
                            if (analyzeOpenGlDrawPair?.textureRenderer?.isAvailableFrame() == true) {
                                // カメラ映像テクスチャを更新して、描画
                                analyzeOpenGlDrawPair?.textureRenderer?.updateCameraTexture()
                                analyzeOpenGlDrawPair?.textureRenderer?.draw()
                                analyzeOpenGlDrawPair?.inputSurface?.swapBuffers()
                                // ImageReader で取りだして、MediaPipe のイメージセグメンテーションに投げる
                                val bitmap = analyzeImageReader.acquireLatestImage()?.toRgbaBitmap(
                                    imageReaderWidth = CAMERA_RESOLUTION_WIDTH / ANALYZE_DIV_SCALE,
                                    imageReaderHeight = CAMERA_RESOLUTION_HEIGHT / ANALYZE_DIV_SCALE,
                                )
                                if (bitmap != null) {
                                    mediaPipeImageSegmentation.segmentation(bitmap)
                                }
                            }
                        }
                    }
                },
                launch {
                    // 解析結果を受け取って、OpenGL ES へテクスチャとして提供する
                    mediaPipeImageSegmentation
                        .segmentedBitmapFlow
                        .filterNotNull()
                        // 多分サイズを合わせないと、1px くらいの細い線がでてしまう（サイズが違うから？）
                        // 元のサイズに戻したほうがきれい。ただ、元の解像度のままイメージセグメンテーションに突っ込むと遅いので
                        .map { smallBitmap -> smallBitmap.scale(CAMERA_RESOLUTION_WIDTH, CAMERA_RESOLUTION_HEIGHT) }
                        .collect { segmentedBitmap ->
                            withContext(previewGlThreadDispatcher) {
                                previewOpenGlDrawPairFlow.value?.textureRenderer?.updateSegmentedBitmap(segmentedBitmap)
                            }
                            withContext(recordGlThreadDispatcher) {
                                recordOpenGlDrawPair?.textureRenderer?.updateSegmentedBitmap(segmentedBitmap)
                            }
                        }
                }
            ).joinAll()
        } finally {
            mediaPipeImageSegmentation.destroy()
        }
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
        recordOpenGlDrawPair = withContext(recordGlThreadDispatcher) {
            createOpenGlDrawPair(surface = imageReader!!.surface)
        }
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
        recordOpenGlDrawPair = withContext(recordGlThreadDispatcher) {
            createOpenGlDrawPair(surface = mediaRecorder!!.surface)
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
                    previewOpenGlDrawPairFlow
                ) { a, b, c -> Triple(a, b, c) }.collect { (frontCamera, backCamera, previewOpenGlDrawPair) ->

                    // フロントカメラ、バックカメラ、プレビューの OpenGL ES がすべて準備完了になるまで待つ
                    frontCamera ?: return@collect
                    backCamera ?: return@collect
                    previewOpenGlDrawPair ?: return@collect
                    val recordOpenGlDrawPair = recordOpenGlDrawPair ?: return@collect

                    // フロントカメラの設定
                    // 出力先
                    val frontCameraOutputList = listOfNotNull(
                        previewOpenGlDrawPair.textureRenderer.frontCameraInputSurface,
                        recordOpenGlDrawPair.textureRenderer.frontCameraInputSurface,
                        analyzeOpenGlDrawPair?.textureRenderer?.inputSurface
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
                val previewOpenGlDrawPair = previewOpenGlDrawPairFlow.filterNotNull().first()
                val recordOpenGlDrawPair = recordOpenGlDrawPair!!

                // フロントカメラの設定
                // 出力先
                val frontCameraOutputList = listOfNotNull(
                    previewOpenGlDrawPair.textureRenderer.frontCameraInputSurface,
                    recordOpenGlDrawPair.textureRenderer.frontCameraInputSurface,
                    analyzeOpenGlDrawPair?.textureRenderer?.inputSurface
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
                backCameraCaptureSession?.close()

                // ImageReader に OpenGL ES で描画する
                withContext(recordGlThreadDispatcher) {
                    renderOpenGl(recordOpenGlDrawPair)
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
                    previewOpenGlDrawPairFlow
                ) { a, b, c -> Triple(a, b, c) }.collectLatest { (frontCamera, backCamera, previewOpenGlDrawPair) ->

                    // フロントカメラ、バックカメラ、プレビューの OpenGL ES がすべて準備完了になるまで待つ
                    frontCamera ?: return@collectLatest
                    backCamera ?: return@collectLatest
                    previewOpenGlDrawPair ?: return@collectLatest
                    val recordOpenGlDrawPair = recordOpenGlDrawPair ?: return@collectLatest

                    // フロントカメラの設定
                    // 出力先
                    val frontCameraOutputList = listOfNotNull(
                        previewOpenGlDrawPair.textureRenderer.frontCameraInputSurface,
                        recordOpenGlDrawPair.textureRenderer.frontCameraInputSurface,
                        analyzeOpenGlDrawPair?.textureRenderer?.inputSurface
                    )
                    val frontCameraCaptureRequest = frontCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        frontCameraOutputList.forEach { surface -> addTarget(surface) }
                    }.build()
                    val frontCameraCaptureSession = frontCamera.awaitCameraSessionConfiguration(frontCameraOutputList)
                    frontCameraCaptureSession?.setRepeatingRequest(frontCameraCaptureRequest, null, null)

                    // バックカメラの設定
                    val backCameraOutputList = listOfNotNull(
                        previewOpenGlDrawPair.textureRenderer.backCameraInputSurface,
                        recordOpenGlDrawPair.textureRenderer.backCameraInputSurface
                    )
                    val backCameraCaptureRequest = backCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        backCameraOutputList.forEach { surface -> addTarget(surface) }
                    }.build()
                    val backCameraCaptureSession = backCamera.awaitCameraSessionConfiguration(backCameraOutputList)
                    backCameraCaptureSession?.setRepeatingRequest(backCameraCaptureRequest, null, null)

                    // 録画開始
                    mediaRecorder?.start()
                    try {
                        // MediaRecorder に OpenGL ES で描画
                        // 録画中はループするのでこれ以降の処理には進まない
                        withContext(recordGlThreadDispatcher) {
                            while (isActive) {
                                renderOpenGl(recordOpenGlDrawPair)
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

    /**
     * [surface]を受け取って、[OpenGlDrawPair]を作る
     * この関数は[previewGlThreadDispatcher]や[recordGlThreadDispatcher]等、OpenGL 用スレッドの中で呼び出す必要があります。
     *
     * @param surface 描画先
     * @return [OpenGlDrawPair]
     */
    private fun createOpenGlDrawPair(surface: Surface): OpenGlDrawPair {
        val inputSurface = InputSurface(surface)
        val textureRenderer = KomaDroidCameraTextureRenderer()
        // スレッド切り替え済みなはずなので
        inputSurface.makeCurrent()
        textureRenderer.createShader()
        // カメラ映像の解像度
        // https://developer.android.com/reference/android/hardware/camera2/CameraDevice#createCaptureSession(android.hardware.camera2.params.SessionConfiguration)
        textureRenderer.setSurfaceTextureSize(CAMERA_RESOLUTION_WIDTH, CAMERA_RESOLUTION_HEIGHT)
        return OpenGlDrawPair(inputSurface, textureRenderer)
    }

    /**
     * [OpenGlDrawPair]を使って描画する。
     * スレッド注意です！！！。[previewGlThreadDispatcher]や[recordGlThreadDispatcher]から呼び出す必要があります。
     *
     * @param drawPair プレビューとか
     */
    private suspend fun renderOpenGl(drawPair: OpenGlDrawPair) {
        if (drawPair.textureRenderer.isAvailableFrontCameraFrame() || drawPair.textureRenderer.isAvailableBackCameraFrame()) {
            // カメラ映像テクスチャを更新して、描画
            drawPair.textureRenderer.updateFrontCameraTexture()
            drawPair.textureRenderer.updateBackCameraTexture()
            drawPair.textureRenderer.draw()
            drawPair.inputSurface.swapBuffers()
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
        val takeBitmap = acquireLatestImage()?.toRgbaBitmap(CAMERA_RESOLUTION_WIDTH, CAMERA_RESOLUTION_HEIGHT) ?: return@withContext
        // ギャラリーに登録する
        val contentResolver = context.contentResolver
        val contentValues = contentValuesOf(
            MediaStore.Images.Media.DISPLAY_NAME to "${System.currentTimeMillis()}.jpg",
            MediaStore.Images.Media.RELATIVE_PATH to "${Environment.DIRECTORY_PICTURES}/KomaDroid"
        )
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return@withContext
        contentResolver.openOutputStream(uri)?.use { outputStream ->
            takeBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        }
        // 開放
        takeBitmap.recycle()
    }

    /** [Image]から[Bitmap]を作る */
    private suspend fun Image.toRgbaBitmap(imageReaderWidth: Int, imageReaderHeight: Int) = withContext(Dispatchers.IO) {
        val image = this@toRgbaBitmap
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
        val editBitmap = Bitmap.createBitmap(readBitmap, 0, 0, imageReaderWidth, imageReaderHeight)
        readBitmap.recycle()
        image.close()
        return@withContext editBitmap
    }

    /** 破棄時に呼び出す。Activity の onDestroy とかで呼んでください。 */
    fun destroy() {
        scope.cancel()
        recordOpenGlDrawPair?.textureRenderer?.destroy()
        recordOpenGlDrawPair?.inputSurface?.destroy()
        analyzeOpenGlDrawPair?.textureRenderer?.destroy()
        analyzeOpenGlDrawPair?.inputSurface?.destroy()
        previewGlThreadDispatcher.close()
        recordGlThreadDispatcher.close()
        analyzeGlThreadDispatcher.close()
        frontCameraFlow.value?.close()
        backCameraFlow.value?.close()
        imageReader?.close()
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

    /**
     * OpenGL 描画のための2点セット。
     * [InputSurface]、[KomaDroidCameraTextureRenderer]を持っているだけ。
     */
    private data class OpenGlDrawPair(
        val inputSurface: InputSurface,
        val textureRenderer: KomaDroidCameraTextureRenderer
    )

    /** 解析用 [OpenGlDrawPair] */
    private data class AnalyzeOpenGlDrawPair(
        val inputSurface: InputSurface,
        val textureRenderer: TextureCopyTextureRenderer
    )

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
        private const val ANALYZE_DIV_SCALE = 4 // イメージセグメンテーションに元の解像度はいらないので 1/4 する
    }
}