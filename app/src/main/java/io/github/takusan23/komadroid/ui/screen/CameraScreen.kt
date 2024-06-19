package io.github.takusan23.komadroid.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
    val context = LocalContext.current
    val cameraManager = remember { KomaDroidCameraManager(context) }

    // カメラを開く、Composable が破棄されたら破棄する
    DisposableEffect(key1 = Unit) {
        cameraManager.openCamera()
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
                // TODO この後すぐ
            }
        ) { Text(text = "撮影") }
    }
}