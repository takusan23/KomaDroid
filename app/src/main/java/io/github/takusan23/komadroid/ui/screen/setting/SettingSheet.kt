package io.github.takusan23.komadroid.ui.screen.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.takusan23.komadroid.tool.CameraSettingData
import io.github.takusan23.komadroid.ui.components.SettingTabMenu
import io.github.takusan23.komadroid.ui.screen.MainScreenNavigation

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
    onSettingUpdate: (CameraSettingData) -> Unit,
    onNavigation: (MainScreenNavigation) -> Unit
) {
    ModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss
    ) {
        SettingScreen(
            settingData = settingData,
            onSettingUpdate = onSettingUpdate,
            onNavigation = onNavigation
        )
    }
}

@Composable
private fun SettingScreen(
    settingData: CameraSettingData,
    onSettingUpdate: (CameraSettingData) -> Unit,
    onNavigation: (MainScreenNavigation) -> Unit
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

            Menu.Other -> OtherSetting(
                onNavigation = onNavigation
            )
        }
    }
}