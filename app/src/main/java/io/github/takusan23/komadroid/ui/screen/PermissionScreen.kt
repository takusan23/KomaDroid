package io.github.takusan23.komadroid.ui.screen

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.takusan23.komadroid.PermissionTool
import io.github.takusan23.komadroid.R

/** 権限くださいダイアログ */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(onGranted: () -> Unit) {
    val permissionRequest = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = {
            // 権限付与された
            if (it.all { it.value }) {
                onGranted()
            }
        }
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text(text = "権限ください") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Text(text = "権限を付与してください。カメラは撮影目的に、マイクは動画撮影の録音のために使います。")

            Button(onClick = {
                permissionRequest.launch(PermissionTool.REQUIRED_PERMISSION_LIST)
            }) { Text(text = "権限を付与") }

            MultiCameraOpenSupportInfo(
                modifier = Modifier
                    .padding(top = 50.dp)
                    .padding(10.dp)
            )
        }
    }
}

@Composable
private fun MultiCameraOpenSupportInfo(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val concurrentCameraIdList = remember { mutableStateOf<List<Set<String>>>(emptyList()) }

    LaunchedEffect(key1 = Unit) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // concurrentCameraIds 、Xiaomi Mi 11 Lite 5G だと同時に利用できるのにかかわらず空の配列を返してて信用できない
        concurrentCameraIdList.value = cameraManager.concurrentCameraIds.toList()
    }

    OutlinedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {

            Text(
                text = stringResource(id = if (concurrentCameraIdList.value.isNotEmpty()) R.string.screen_permission_support_ok else R.string.screen_permission_support_maybe),
                fontSize = 20.sp
            )

            Text(
                text = stringResource(id = if (concurrentCameraIdList.value.isNotEmpty()) R.string.screen_permission_support_description_ok else R.string.screen_permission_support_description_maybe)
            )

            Text(text = "CameraManager#getConcurrentCameraIds() = ${concurrentCameraIdList.value}")
        }
    }
}