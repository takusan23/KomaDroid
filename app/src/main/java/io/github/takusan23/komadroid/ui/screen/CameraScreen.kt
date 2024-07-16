package io.github.takusan23.komadroid.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import io.github.takusan23.komadroid.KomaDroidCameraManager
import io.github.takusan23.komadroid.ui.components.CameraControlOverlay

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

            // OpenGL ES を描画する SurfaceView
            // アスペクト比
            // TODO 横画面のアスペクト比
            key(cameraManager) { // TODO key で強制再コンポジションさせているのでガチアンチパターン
                AndroidView(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .aspectRatio(KomaDroidCameraManager.CAMERA_RESOLUTION_WIDTH / KomaDroidCameraManager.CAMERA_RESOLUTION_HEIGHT.toFloat())
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                // テクスチャ座標が Y 座標においては反転してるので多分 -= であってる
                                // イキすぎイクイクすぎるので 1000 で割っている
                                cameraManager.xPos += (dragAmount.x / 1000f)
                                cameraManager.yPos -= (dragAmount.y / 1000f)
                            }
                        },
                    factory = { cameraManager.surfaceView }
                )
            }

            // 撮影ボタンとかあるやつ
            val isMoveEnable = remember { mutableStateOf(false) }
            CameraControlOverlay(
                currentCaptureMode = captureMode.value,
                onCaptureModeChange = { captureMode.value = it },
                onShutterClick = { cameraManager.takePicture() },
                onFlipClick = { },
                onSettingButton = { },
                isMoveEnable = isMoveEnable.value,
                onMoveEnable = { isMoveEnable.value = !isMoveEnable.value }
            )
        }
    }
}
