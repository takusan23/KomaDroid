package io.github.takusan23.komadroid.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.takusan23.komadroid.PermissionTool

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

            Text(text = "権限ください")

            Button(onClick = {
                permissionRequest.launch(PermissionTool.REQUIRED_PERMISSION_LIST)
            }) { Text(text = "権限を付与") }
        }
    }
}