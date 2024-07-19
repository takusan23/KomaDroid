package io.github.takusan23.komadroid.ui.screen.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.takusan23.komadroid.tool.CameraSettingData
import io.github.takusan23.komadroid.tool.NumberFormat
import io.github.takusan23.komadroid.ui.components.DropdownSettingItem
import io.github.takusan23.komadroid.ui.components.IntValueSettingItem

private val CameraSettingData.Fps.menuLabel
    get() = when (this) {
        CameraSettingData.Fps.FPS_30 -> "30 FPS"
        CameraSettingData.Fps.FPS_60 -> "60 FPS"
    }

@Composable
fun VideoSetting(
    settingData: CameraSettingData,
    onUpdate: (CameraSettingData) -> Unit
) {
    Column {

        IntValueSettingItem(
            title = "ビットレート",
            description = "1秒間に利用するデータ量です",
            value = settingData.videoBitrate,
            onChange = { onUpdate(settingData.copy(videoBitrate = it)) },
            suffix = { Text(text = NumberFormat.formatByteUnit(settingData.videoBitrate)) }
        )

        DropdownSettingItem(
            title = "フレームレート",
            description = "動画の滑らかさです",
            selectIndex = settingData.cameraFps.ordinal,
            menu = remember { CameraSettingData.Fps.entries.map { it.menuLabel } },
            onSelect = { onUpdate(settingData.copy(cameraFps = CameraSettingData.Fps.entries[it])) }
        )

    }
}