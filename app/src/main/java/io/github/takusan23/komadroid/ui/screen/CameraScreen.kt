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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.takusan23.komadroid.KomaDroidCameraManager
import io.github.takusan23.komadroid.R
import io.github.takusan23.komadroid.tool.DataStoreTool
import io.github.takusan23.komadroid.ui.components.CameraControlOverlay
import io.github.takusan23.komadroid.ui.components.ComposeSurfaceView
import io.github.takusan23.komadroid.ui.components.ErrorDialog
import io.github.takusan23.komadroid.ui.components.ScreenRotateType
import io.github.takusan23.komadroid.ui.screen.setting.SettingSheet
import kotlinx.coroutines.launch

/** カメラ画面 */
@Composable
fun CameraScreen(onNavigation: (MainScreenNavigation) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val isLandScape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // カメラとプレビュー、撮影、録画を司るクラス
    val komaDroidCameraManager = remember { KomaDroidCameraManager(context, lifecycle.lifecycle) }
    DisposableEffect(key1 = Unit) {
        onDispose { komaDroidCameraManager.destroy() }
    }

    val zoomData = komaDroidCameraManager.cameraZoomDataFlow.collectAsState()
    val cameraSettingData = komaDroidCameraManager.cameraSettingFlow.collectAsState(initial = null)
    val isVideoRecording = komaDroidCameraManager.isVideoRecordingFlow.collectAsState()
    val errorOrNull = komaDroidCameraManager.errorFlow.collectAsState()
    val captureMode = komaDroidCameraManager.captureModeFlow.collectAsState()

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

    // エラー
    if (errorOrNull.value != null) {
        ErrorDialog(
            errorType = errorOrNull.value!!,
            onDismiss = { komaDroidCameraManager.closeError() }
        )
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        ComposeSurfaceView(
            modifier = Modifier
                .align(Alignment.Center)
                .then(
                    // たまに目一杯表示されないことがあり then() している
                    if (cameraSettingData.value != null) {
                        Modifier.aspectRatio(
                            cameraSettingData.value!!
                                .let { if (isLandScape) it.highestResolution.landscape else it.highestResolution.portrait }
                                .let { size -> size.width / size.height.toFloat() }
                        )
                    } else Modifier
                )
                .pointerInput(isMoveEnable.value) {
                    if (isMoveEnable.value) {
                        // ピンチイン、ピンチアウトで拡大縮小
                        detectTransformGestures { _, _, zoom, _ ->
                            komaDroidCameraManager.scale *= zoom
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
                                komaDroidCameraManager.xPos -= (dragAmount.y / 1000f)
                                komaDroidCameraManager.yPos -= (dragAmount.x / 1000f)
                            } else {
                                komaDroidCameraManager.xPos += (dragAmount.x / 1000f)
                                komaDroidCameraManager.yPos -= (dragAmount.y / 1000f)
                            }
                        }
                    }
                },
            onCreateSurface = { surfaceHolder -> komaDroidCameraManager.setPreviewSurfaceHolder(surfaceHolder) },
            onSizeChanged = { _, _ -> /* do nothing */ },
            onDestroySurface = { komaDroidCameraManager.setPreviewSurfaceHolder(null) }
        )

        // 撮影ボタンとかあるやつ
        CameraControlOverlay(
            currentCaptureMode = captureMode.value,
            onCaptureModeChange = { mode -> komaDroidCameraManager.switchCaptureMode(mode) },
            onShutterClick = {
                // TODO いい加減もうちょっと綺麗にしたい
                scope.launch {
                    when (captureMode.value) {

                        KomaDroidCameraManager.CaptureMode.PICTURE -> {
                            komaDroidCameraManager.awaitTakePicture()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }

                        KomaDroidCameraManager.CaptureMode.VIDEO -> {
                            if (isVideoRecording.value) {
                                komaDroidCameraManager.awaitStopRecordVideo()
                                currentScreenRotateType.value = ScreenRotateType.UnLockScreenRotation
                            } else {
                                komaDroidCameraManager.startRecordVideo()
                                currentScreenRotateType.value = ScreenRotateType.BlockRotationRequest // 録画中は回転しないように
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                }
            },
            onFlipClick = { komaDroidCameraManager.isFlip = !komaDroidCameraManager.isFlip },
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
                    context.getString(if (isMoveEnable.value) R.string.screen_camera_move_enable else R.string.screen_camera_move_disable),
                    Toast.LENGTH_SHORT
                ).show()
            },
            zoomData = zoomData.value,
            onZoomChange = { komaDroidCameraManager.updateZoomData(it) },
            isVideoRecording = isVideoRecording.value
        )
    }
}
