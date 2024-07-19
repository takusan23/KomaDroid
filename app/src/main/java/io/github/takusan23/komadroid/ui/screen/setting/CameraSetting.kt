package io.github.takusan23.komadroid.ui.screen.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.takusan23.komadroid.tool.CameraSettingData
import io.github.takusan23.komadroid.ui.components.ClickSettingItem
import io.github.takusan23.komadroid.ui.components.DropdownSettingItem

private val CameraSettingData.Resolution.menuLabel
    get() = when (this) {
        CameraSettingData.Resolution.RESOLUTION_720P -> "HD"
        CameraSettingData.Resolution.RESOLUTION_1080P -> "FHD"
        CameraSettingData.Resolution.RESOLUTION_2160P -> "4K"
    }

@Composable
fun CameraSetting(
    settingData: CameraSettingData,
    onUpdate: (CameraSettingData) -> Unit
) {
    val menuLabelList = remember { CameraSettingData.Resolution.entries.map { it.menuLabel } }

    Column {

        DropdownSettingItem(
            title = "フロントカメラの解像度",
            description = "自撮りカメラの方です",
            selectIndex = settingData.frontCameraResolution.ordinal,
            menu = menuLabelList,
            onSelect = { onUpdate(settingData.copy(frontCameraResolution = CameraSettingData.Resolution.entries[it])) }
        )

        DropdownSettingItem(
            title = "バックカメラの解像度",
            selectIndex = settingData.backCameraResolution.ordinal,
            menu = menuLabelList,
            onSelect = { onUpdate(settingData.copy(backCameraResolution = CameraSettingData.Resolution.entries[it])) }
        )

        ClickSettingItem(
            title = "カメラ設定をリセットする",
            description = "カメラの起動に失敗するようなら",
            onClick = { onUpdate(CameraSettingData.DEFAULT_SETTING) }
        )
    }
}