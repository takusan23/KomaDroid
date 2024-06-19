package io.github.takusan23.komadroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.takusan23.komadroid.ui.screen.CameraScreen
import io.github.takusan23.komadroid.ui.screen.PermissionScreen
import io.github.takusan23.komadroid.ui.theme.KomaDroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KomaDroidTheme {
                CameraOrPermissionScreen()
            }
        }
    }
}

// 権限画面かカメラ画面
@Composable
private fun CameraOrPermissionScreen() {
    val context = LocalContext.current

    // 権限ない場合は権限ください画面
    val isGrantedPermission = remember { mutableStateOf(PermissionTool.isGrantedPermission(context)) }
    if (!isGrantedPermission.value) {
        PermissionScreen(
            onGranted = { isGrantedPermission.value = true }
        )
    } else {
        CameraScreen()
    }
}
