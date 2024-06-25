package io.github.takusan23.komadroid.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.takusan23.komadroid.KomaDroidCameraManager

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

    val currentMode = remember { mutableStateOf(KomaDroidCameraManager.CaptureMode.PICTURE) }

    Box(modifier = Modifier.fillMaxSize()) {

        // 静止画モード・動画撮影モード
        when (currentMode.value) {
            KomaDroidCameraManager.CaptureMode.PICTURE -> PictureModeScreen()
            KomaDroidCameraManager.CaptureMode.VIDEO -> VideoModeScreen()
        }

        // 切り替えボタン
        SwitchModeButton(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
            currentMode = currentMode.value,
            onSelect = { currentMode.value = it }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwitchModeButton(
    modifier: Modifier = Modifier,
    currentMode: KomaDroidCameraManager.CaptureMode,
    onSelect: (KomaDroidCameraManager.CaptureMode) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        KomaDroidCameraManager.CaptureMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == currentMode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = KomaDroidCameraManager.CaptureMode.entries.size
                )
            ) {
                Text(text = mode.name)
            }
        }
    }
}

@Composable
private fun PictureModeScreen() {
    val context = LocalContext.current
    val cameraManager = remember { KomaDroidCameraManager(context, KomaDroidCameraManager.CaptureMode.PICTURE) }

    // カメラを開く、Composable が破棄されたら破棄する
    DisposableEffect(key1 = Unit) {
        cameraManager.prepare()
        onDispose { cameraManager.destroy() }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // OpenGL ES を描画する SurfaceView
        // アスペクト比
        AndroidView(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(KomaDroidCameraManager.CAMERA_RESOLUTION_WIDTH / KomaDroidCameraManager.CAMERA_RESOLUTION_HEIGHT.toFloat()),
            factory = { cameraManager.surfaceView }
        )

        Button(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp),
            onClick = { cameraManager.takePicture() }
        ) { Text(text = "写真撮影") }
    }
}

@Composable
private fun VideoModeScreen() {
    val context = LocalContext.current
    val cameraManager = remember { KomaDroidCameraManager(context, KomaDroidCameraManager.CaptureMode.VIDEO) }

    // 仮でここに置かせて
    val isRecording = remember { mutableStateOf(false) }

    // カメラを開く、Composable が破棄されたら破棄する
    DisposableEffect(key1 = Unit) {
        cameraManager.prepare()
        onDispose { cameraManager.destroy() }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // OpenGL ES を描画する SurfaceView
        // アスペクト比
        AndroidView(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(KomaDroidCameraManager.CAMERA_RESOLUTION_WIDTH / KomaDroidCameraManager.CAMERA_RESOLUTION_HEIGHT.toFloat()),
            factory = { cameraManager.surfaceView }
        )

        Button(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp),
            onClick = {
                if (isRecording.value) {
                    cameraManager.stopRecordVideo()
                } else {
                    cameraManager.startRecordVideo()
                }
                isRecording.value = !isRecording.value
            }
        ) {
            Text(text = if (isRecording.value) "録画終了" else "録画開始")
        }
    }
}