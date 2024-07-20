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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
fun CameraScreen(onNavigation: (MainScreenNavigation) -> Unit) {
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
    val haptic = LocalHapticFeedback.current

    val isLandScape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val captureMode = rememberSaveable { mutableStateOf(KomaDroidCameraManager.CaptureMode.PICTURE) }

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
            val cameraSettingData = cameraManager.settingDataFlow.collectAsState(initial = null)
            val isVideoRecording = cameraManager.isVideoRecordingFlow.collectAsState()

            val isMoveEnable = remember { mutableStateOf(false) }
            val currentScreenRotateType = remember { mutableStateOf(ScreenRotateType.UnLockScreenRotation) }
            val isSettingOpen = remember { mutableStateOf(false) }

            // 画面回転、ロックするとか
            LaunchedEffect(key1 = currentScreenRotateType.value) {
                (context as? Activity)?.requestedOrientation = when (currentScreenRotateType.value) {
                    ScreenRotateType.BlockRotationRequest -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    ScreenRotateType.UnLockScreenRotation -> ActivityInfo.SCREEN_ORIENTATION_SENSOR // 端末設定よりもセンサーを優先する
                    ScreenRotateType.LockScreenRotation -> if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }

            // 設定を開くか
            if (cameraSettingData.value != null && isSettingOpen.value) {
                // TODO 設定内容を KomaDroidCameraManager でも監視していて、高頻度で設定内容を書き換えると startPreview() がエラーになる。
                // TODO 仕方ないので閉じたときだけ設定を保存するようにした。
                val currentSettingData = remember { mutableStateOf(cameraSettingData.value!!) }
                SettingSheet(
                    onDismiss = {
                        isSettingOpen.value = false
                        scope.launch { DataStoreTool.writeData(context, currentSettingData.value) }
                    },
                    settingData = currentSettingData.value,
                    onSettingUpdate = { currentSettingData.value = it },
                    onNavigation = onNavigation
                )
            }

            // OpenGL ES を描画する SurfaceView
            // アスペクト比
            key(cameraManager) { // TODO key で強制再コンポジションさせているのでガチアンチパターン
                AndroidView(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .aspectRatio(
                            ratio = cameraSettingData.value
                                ?.let { if (isLandScape) it.highestResolution.landscape else it.highestResolution.portrait }
                                ?.let { size -> size.width / size.height.toFloat() } ?: 1f
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
                    scope.launch {
                        // TODO いい加減もうちょっと綺麗にしたい
                        if (captureMode.value == KomaDroidCameraManager.CaptureMode.PICTURE) {
                            cameraManager.awaitTakePicture()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else {
                            if (isVideoRecording.value) {
                                cameraManager.awaitStopRecordVideo()
                                currentScreenRotateType.value = ScreenRotateType.UnLockScreenRotation
                            } else {
                                cameraManager.startRecordVideo()
                                currentScreenRotateType.value = ScreenRotateType.BlockRotationRequest // 録画中は回転しないように
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
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
                onZoomChange = { cameraManager.updateZoomData(it) },
                isVideoRecording = isVideoRecording.value
            )
        }
    }
}
