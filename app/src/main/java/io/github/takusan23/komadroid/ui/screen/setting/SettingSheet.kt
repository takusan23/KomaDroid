package io.github.takusan23.komadroid.ui.screen.setting

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import io.github.takusan23.komadroid.tool.CameraSettingData
import io.github.takusan23.komadroid.ui.components.SettingTabMenu

private enum class Menu(val title: String) {
    CameraSetting("カメラ設定"),
    VideoSetting("動画設定"),
    Other("そのほか")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSheet(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    settingData: CameraSettingData,
    onSettingUpdate: (CameraSettingData) -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandScape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    ModalBottomSheet(
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.then(
            if (isLandScape) {
                Modifier
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight()
            } else {
                Modifier.fillMaxHeight(0.5f)
            }
        ),
        onDismissRequest = onDismiss
    ) {
        SettingScreen(
            settingData = settingData,
            onSettingUpdate = onSettingUpdate
        )
    }
}

@Composable
private fun SettingScreen(
    settingData: CameraSettingData,
    onSettingUpdate: (CameraSettingData) -> Unit
) {
    val currentPage = remember { mutableStateOf(Menu.CameraSetting) }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

        SettingTabMenu(
            selectIndex = currentPage.value.ordinal,
            menu = remember { Menu.entries.map { it.title } },
            onSelect = { currentPage.value = Menu.entries.get(it) }
        )

        when (currentPage.value) {
            Menu.CameraSetting -> CameraSetting(
                settingData = settingData,
                onUpdate = onSettingUpdate
            )

            Menu.VideoSetting -> VideoSetting(
                settingData = settingData,
                onUpdate = onSettingUpdate
            )

            Menu.Other -> OtherSetting()
        }
    }
}