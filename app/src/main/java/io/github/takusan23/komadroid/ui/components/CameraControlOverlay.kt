package io.github.takusan23.komadroid.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.takusan23.komadroid.KomaDroidCameraManager
import kotlinx.coroutines.delay

/** TODO null で非表示はイマイチなので直すべき */
private enum class OpenZoomSliderState {
    FrontCameraZoom,
    BackCameraZoom
}

private const val ZoomSliderTimeoutMs = 3_000L

@Composable
fun CameraControlOverlay(
    modifier: Modifier = Modifier,
    currentCaptureMode: KomaDroidCameraManager.CaptureMode,
    screenRotateType: ScreenRotateType,
    zoomData: KomaDroidCameraManager.CameraZoomData,
    onCaptureModeChange: (KomaDroidCameraManager.CaptureMode) -> Unit,
    onShutterClick: () -> Unit,
    onFlipClick: () -> Unit,
    onSettingButton: () -> Unit,
    isMoveEnable: Boolean,
    onMoveEnable: (Boolean) -> Unit,
    onScreenRotationClick: () -> Unit,
    onZoomChange: (KomaDroidCameraManager.CameraZoomData) -> Unit
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = modifier
            .systemBarsPadding()
            .fillMaxSize()
    ) {

        if (isLandscape) {
            Landscape(
                modifier = Modifier.align(Alignment.CenterEnd),
                currentCaptureMode = currentCaptureMode,
                onCaptureModeChange = onCaptureModeChange,
                onShutterClick = onShutterClick,
                onFlipClick = onFlipClick,
                onSettingButton = onSettingButton,
                isMoveEnable = isMoveEnable,
                onMoveEnable = onMoveEnable,
                screenRotateType = screenRotateType,
                onScreenRotationClick = onScreenRotationClick,
                zoomData = zoomData,
                onZoomChange = onZoomChange,
            )
        } else {
            Portrait(
                modifier = Modifier.align(Alignment.BottomCenter),
                currentCaptureMode = currentCaptureMode,
                onCaptureModeChange = onCaptureModeChange,
                onShutterClick = onShutterClick,
                onFlipClick = onFlipClick,
                onSettingButton = onSettingButton,
                isMoveEnable = isMoveEnable,
                onMoveEnable = onMoveEnable,
                screenRotateType = screenRotateType,
                onScreenRotationClick = onScreenRotationClick,
                zoomData = zoomData,
                onZoomChange = onZoomChange,
            )
        }
    }
}

@Composable
private fun Portrait(
    modifier: Modifier = Modifier,
    currentCaptureMode: KomaDroidCameraManager.CaptureMode,
    screenRotateType: ScreenRotateType,
    zoomData: KomaDroidCameraManager.CameraZoomData,
    onCaptureModeChange: (KomaDroidCameraManager.CaptureMode) -> Unit,
    onShutterClick: () -> Unit,
    onFlipClick: () -> Unit,
    onSettingButton: () -> Unit,
    isMoveEnable: Boolean,
    onMoveEnable: (Boolean) -> Unit,
    onScreenRotationClick: () -> Unit,
    onZoomChange: (KomaDroidCameraManager.CameraZoomData) -> Unit
) {
    // ズームを開くか。null で開かない
    val openZoomSliderOrNull = remember { mutableStateOf<OpenZoomSliderState?>(null) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        // ズームのスライダーを表示する場合、それ以外のボタンを消す
        if (openZoomSliderOrNull.value != null) {
            val openZoomSliderState = openZoomSliderOrNull.value!!

            // 最後のズームの値変更呼び出しからしばらく経ったら消す
            LaunchedEffect(key1 = zoomData) {
                delay(ZoomSliderTimeoutMs)
                openZoomSliderOrNull.value = null
            }

            ZoomControlSlider(
                modifier = Modifier.padding(horizontal = 10.dp),
                currentZoom = when (openZoomSliderState) {
                    OpenZoomSliderState.FrontCameraZoom -> zoomData.currentFrontCameraZoom
                    OpenZoomSliderState.BackCameraZoom -> zoomData.currentBackCameraZoom
                },
                zoomRange = when (openZoomSliderState) {
                    OpenZoomSliderState.FrontCameraZoom -> zoomData.frontCameraZoomRange
                    OpenZoomSliderState.BackCameraZoom -> zoomData.backCameraZoomRange
                },
                onZoomChange = { newZoom ->
                    onZoomChange(
                        when (openZoomSliderState) {
                            OpenZoomSliderState.FrontCameraZoom -> zoomData.copy(currentFrontCameraZoom = newZoom)
                            OpenZoomSliderState.BackCameraZoom -> zoomData.copy(currentBackCameraZoom = newZoom)
                        }
                    )
                }
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                FlipButton(onClick = onFlipClick)

                ZoomControlOpenButton(
                    modifier = Modifier,
                    zoomData = zoomData,
                    onFrontZoomClick = { openZoomSliderOrNull.value = OpenZoomSliderState.FrontCameraZoom },
                    onBackZoomClick = { openZoomSliderOrNull.value = OpenZoomSliderState.BackCameraZoom }
                )

                MoveEnableButton(
                    isEnable = isMoveEnable,
                    onClick = onMoveEnable
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CaptureModeSwitch(
                    currentCaptureMode = currentCaptureMode,
                    onChange = onCaptureModeChange
                )
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                ShutterButton(onClick = onShutterClick)
            }

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                ScreenRotationLockButton(
                    screenRotateType = screenRotateType,
                    onClick = onScreenRotationClick
                )

                SettingButton(onClick = onSettingButton)
            }
        }
    }
}

@Composable
private fun Landscape(
    modifier: Modifier = Modifier,
    currentCaptureMode: KomaDroidCameraManager.CaptureMode,
    screenRotateType: ScreenRotateType,
    zoomData: KomaDroidCameraManager.CameraZoomData,
    onCaptureModeChange: (KomaDroidCameraManager.CaptureMode) -> Unit,
    onShutterClick: () -> Unit,
    onFlipClick: () -> Unit,
    onSettingButton: () -> Unit,
    isMoveEnable: Boolean,
    onMoveEnable: (Boolean) -> Unit,
    onScreenRotationClick: () -> Unit,
    onZoomChange: (KomaDroidCameraManager.CameraZoomData) -> Unit
) {
    // ズームを開くか。null で開かない
    val openZoomSliderOrNull = remember { mutableStateOf<OpenZoomSliderState?>(null) }

    Row(modifier = modifier) {

        // TODO 横画面でもズームを縦に並べたい、けど自前で縦用のコンポーネントを作らないとダメそうで一旦保留
        if (openZoomSliderOrNull.value != null) {
            val openZoomSliderState = openZoomSliderOrNull.value!!

            // 最後のズームの値変更呼び出しからしばらく経ったら消す
            LaunchedEffect(key1 = zoomData) {
                delay(ZoomSliderTimeoutMs)
                openZoomSliderOrNull.value = null
            }

            ZoomControlSlider(
                modifier = Modifier
                    .weight(1f)
                    .padding(10.dp)
                    .align(Alignment.Bottom),
                currentZoom = when (openZoomSliderState) {
                    OpenZoomSliderState.FrontCameraZoom -> zoomData.currentFrontCameraZoom
                    OpenZoomSliderState.BackCameraZoom -> zoomData.currentBackCameraZoom
                },
                zoomRange = when (openZoomSliderState) {
                    OpenZoomSliderState.FrontCameraZoom -> zoomData.frontCameraZoomRange
                    OpenZoomSliderState.BackCameraZoom -> zoomData.backCameraZoomRange
                },
                onZoomChange = { newZoom ->
                    onZoomChange(
                        when (openZoomSliderState) {
                            OpenZoomSliderState.FrontCameraZoom -> zoomData.copy(currentFrontCameraZoom = newZoom)
                            OpenZoomSliderState.BackCameraZoom -> zoomData.copy(currentBackCameraZoom = newZoom)
                        }
                    )
                }
            )
        } else {
            ZoomControlOpenButton(
                modifier = Modifier.align(Alignment.Bottom),
                zoomData = zoomData,
                onFrontZoomClick = { openZoomSliderOrNull.value = OpenZoomSliderState.FrontCameraZoom },
                onBackZoomClick = { openZoomSliderOrNull.value = OpenZoomSliderState.BackCameraZoom }
            )
        }

        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround
        ) {
            MoveEnableButton(
                modifier = Modifier.graphicsLayer { rotationZ = 270f },
                isEnable = isMoveEnable,
                onClick = onMoveEnable
            )

            FlipButton(onClick = onFlipClick)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.SpaceAround,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SettingButton(onClick = onSettingButton)

                ScreenRotationLockButton(
                    screenRotateType = screenRotateType,
                    onClick = onScreenRotationClick
                )
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                ShutterButton(onClick = onShutterClick)
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // TODO 横のやつを回転させて暫定対応、切り替えボタン自前で作ったほうがいいかも
                CaptureModeSwitch(
                    modifier = Modifier.graphicsLayer { rotationZ = 270f },
                    currentCaptureMode = currentCaptureMode,
                    onChange = onCaptureModeChange
                )
            }
        }
    }
}