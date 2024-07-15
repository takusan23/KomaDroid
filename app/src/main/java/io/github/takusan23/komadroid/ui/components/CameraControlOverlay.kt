package io.github.takusan23.komadroid.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.takusan23.komadroid.KomaDroidCameraManager

@Composable
fun CameraControlOverlay(
    modifier: Modifier = Modifier,
    currentCaptureMode: KomaDroidCameraManager.CaptureMode,
    onCaptureModeChange: (KomaDroidCameraManager.CaptureMode) -> Unit,
    onShutterClick: () -> Unit,
    onFlipClick: () -> Unit,
    onSettingButton: () -> Unit,
    isMoveEnable: Boolean,
    onMoveEnable: (Boolean) -> Unit
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
                onMoveEnable = onMoveEnable
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
                onMoveEnable = onMoveEnable
            )
        }
    }
}

@Composable
private fun Portrait(
    modifier: Modifier = Modifier,
    currentCaptureMode: KomaDroidCameraManager.CaptureMode,
    onCaptureModeChange: (KomaDroidCameraManager.CaptureMode) -> Unit,
    onShutterClick: () -> Unit,
    onFlipClick: () -> Unit,
    onSettingButton: () -> Unit,
    isMoveEnable: Boolean,
    onMoveEnable: (Boolean) -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {

            ZoomButton(onClick = { })

            MoveEnableButton(
                isEnable = isMoveEnable,
                onClick = onMoveEnable
            )
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
                FlipButton(onClick = onFlipClick)
                SettingButton(onClick = onSettingButton)
            }
        }
    }
}

@Composable
private fun Landscape(
    modifier: Modifier = Modifier,
    currentCaptureMode: KomaDroidCameraManager.CaptureMode,
    onCaptureModeChange: (KomaDroidCameraManager.CaptureMode) -> Unit,
    onShutterClick: () -> Unit,
    onFlipClick: () -> Unit,
    onSettingButton: () -> Unit,
    isMoveEnable: Boolean,
    onMoveEnable: (Boolean) -> Unit
) {
    Row(modifier = modifier) {

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

            ZoomButton(
                modifier = Modifier.graphicsLayer { rotationZ = 270f },
                onClick = { }
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.SpaceAround,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SettingButton(onClick = onSettingButton)

                FlipButton(onClick = onFlipClick)
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