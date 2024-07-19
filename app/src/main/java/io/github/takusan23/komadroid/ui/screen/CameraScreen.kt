package io.github.takusan23.komadroid.ui.screen

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import io.github.takusan23.komadroid.KomaDroidCameraManager
import io.github.takusan23.komadroid.tool.DataStoreTool
import io.github.takusan23.komadroid.ui.components.CameraControlOverlay
import io.github.takusan23.komadroid.ui.components.ScreenRotateType
import io.github.takusan23.komadroid.ui.screen.setting.SettingSheet
import kotlinx.coroutines.launch

/** カメラ画面 */
@Composable
fun CameraScreen() {
    // TODO これ静止画撮影と動画撮影でインスタンス分ける
    // どうやら、Google Tensor は一度でも GLES にバインドしたことのある Surface は他の GLES にはバインドできない
    // Surface そのままで、GLES だけ作り直すのができない。
    // 同じく、スレッドも、一度でも makeCurrent したことある場合は、GLES を破棄しても、他の GLES のスレッドとしては使えない。
    // Surface とスレッドが使い回せない以上、録画と静止画撮影ではインスタンスを分けて、別々の SurfaceView を作るしかなさそう。
    // これは Google Tensor が悪いかも。Google Tensor の OpenGL ドライバーいまいち説ある
    // 長々書いたけど InputSurface / Renderer を作り直すのは Google Tensor 都合でできない（すでに一回 Surface + GLES 作ると破棄しても何故か作れない。ANGLE 実装に切り替えると使えなくはないけどスレッド作り直しが必要）
    // TODO 上記の怪文書は私のミスで、GLES の破棄が GL スレッドじゃなかったから。

    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()

    val isLandScape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val captureMode = remember { mutableStateOf(KomaDroidCameraManager.CaptureMode.PICTURE) }

    // カメラと描画、録画を司るクラス
    // remember { } だと suspend fun 呼べないので produceState
    val cameraManagerOrNull = produceState<KomaDroidCameraManager?>(
        initialValue = null,
        key1 = captureMode.value
    ) {
        // インスタンスを生成
        val _cameraManager = KomaDroidCameraManager(
            context = context,
            lifecycle = lifecycle.lifecycle,
            mode = captureMode.value
        )
        // カメラや OpenGL ES の初期化する
        _cameraManager.prepare()
        value = _cameraManager
        // 破棄時
        awaitDispose { _cameraManager.destroy() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (cameraManagerOrNull.value != null) {
            val cameraManager = cameraManagerOrNull.value!!

            val zoomData = cameraManager.cameraZoomDataFlow.collectAsState()
            val settingData = cameraManager.settingDataFlow.collectAsState(initial = null)

            val isMoveEnable = remember { mutableStateOf(false) }
            val isVideoRecording = remember(captureMode.value) { mutableStateOf(false) }
            val currentScreenRotateType = remember { mutableStateOf(ScreenRotateType.UnLockScreenRotation) }
            val isSettingOpen = remember { mutableStateOf(false) }

            // 画面回転、ロックするとか
            LaunchedEffect(key1 = currentScreenRotateType.value) {
                (context as? Activity)?.requestedOrientation = when (currentScreenRotateType.value) {
                    ScreenRotateType.BlockRotationRequest -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    ScreenRotateType.UnLockScreenRotation -> ActivityInfo.SCREEN_ORIENTATION_USER
                    ScreenRotateType.LockScreenRotation -> if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }

            // 設定を開くか
            if (settingData.value != null && isSettingOpen.value) {
                SettingSheet(
                    onDismiss = { isSettingOpen.value = false },
                    settingData = settingData.value!!,
                    onSettingUpdate = { scope.launch { DataStoreTool.writeData(context, it) } }
                )
            }

            // OpenGL ES を描画する SurfaceView
            // アスペクト比
            // TODO 横画面のアスペクト比
            key(cameraManager) { // TODO key で強制再コンポジションさせているのでガチアンチパターン
                AndroidView(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .aspectRatio(
                            ratio = if (isLandScape) {
                                KomaDroidCameraManager.CAMERA_RESOLUTION_HEIGHT / KomaDroidCameraManager.CAMERA_RESOLUTION_WIDTH.toFloat()
                            } else {
                                KomaDroidCameraManager.CAMERA_RESOLUTION_WIDTH / KomaDroidCameraManager.CAMERA_RESOLUTION_HEIGHT.toFloat()
                            }
                        )
                        .pointerInput(isMoveEnable.value) {
                            if (isMoveEnable.value) {
                                // ピンチイン、ピンチアウトで拡大縮小
                                detectTransformGestures { _, _, zoom, _ ->
                                    cameraManager.scale *= zoom
                                }
                            }
                        }
                        .pointerInput(isMoveEnable.value) {
                            if (isMoveEnable.value) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    // テクスチャ座標が Y 座標においては反転してるので多分 -= であってる
                                    // イキすぎイクイクすぎるので 1000 で割っている
                                    if (isLandScape) {
                                        cameraManager.xPos -= (dragAmount.y / 1000f)
                                        cameraManager.yPos -= (dragAmount.x / 1000f)
                                    } else {
                                        cameraManager.xPos += (dragAmount.x / 1000f)
                                        cameraManager.yPos -= (dragAmount.y / 1000f)
                                    }
                                }
                            }
                        },
                    factory = { cameraManager.surfaceView }
                )
            }

            // 撮影ボタンとかあるやつ
            CameraControlOverlay(
                currentCaptureMode = captureMode.value,
                onCaptureModeChange = { captureMode.value = it },
                onShutterClick = {
                    // TODO いい加減もうちょっと綺麗にしたい
                    if (captureMode.value == KomaDroidCameraManager.CaptureMode.PICTURE) {
                        cameraManager.takePicture()
                    } else {
                        if (isVideoRecording.value) {
                            cameraManager.stopRecordVideo()
                        } else {
                            cameraManager.startRecordVideo()
                        }
                        // TODO CameraManager 側にフラグを移動
                        isVideoRecording.value = !isVideoRecording.value
                        // 録画中は回転しないように
                        currentScreenRotateType.value = if (isVideoRecording.value) ScreenRotateType.BlockRotationRequest else ScreenRotateType.UnLockScreenRotation
                    }
                },
                onFlipClick = { cameraManager.isFlip = !cameraManager.isFlip },
                onSettingButton = { isSettingOpen.value = !isSettingOpen.value },
                isMoveEnable = isMoveEnable.value,
                screenRotateType = currentScreenRotateType.value,
                onScreenRotationClick = {
                    currentScreenRotateType.value = when (currentScreenRotateType.value) {
                        ScreenRotateType.BlockRotationRequest -> ScreenRotateType.BlockRotationRequest
                        ScreenRotateType.UnLockScreenRotation -> ScreenRotateType.LockScreenRotation
                        ScreenRotateType.LockScreenRotation -> ScreenRotateType.UnLockScreenRotation
                    }
                },
                onMoveEnable = {
                    isMoveEnable.value = !isMoveEnable.value
                    Toast.makeText(
                        context,
                        if (isMoveEnable.value) "ドラッグで移動、ピンチイン / ピンチアウトで拡大縮小できます。" else "移動、拡大縮小を無効にしました。",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                zoomData = zoomData.value,
                onZoomChange = { cameraManager.updateZoomData(it) }
            )
        }
    }
}
