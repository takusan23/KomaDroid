package io.github.takusan23.komadroid.ui.screen.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.takusan23.komadroid.R
import io.github.takusan23.komadroid.tool.CameraSettingData
import io.github.takusan23.komadroid.ui.components.ClickSettingItem
import io.github.takusan23.komadroid.ui.components.DropdownSettingItem

private val CameraSettingData.Resolution.menuLabelResId
    get() = when (this) {
        CameraSettingData.Resolution.RESOLUTION_720P -> R.string.screen_camera_setting_bottomsheet_camera_resolution_720p
        CameraSettingData.Resolution.RESOLUTION_1080P -> R.string.screen_camera_setting_bottomsheet_camera_resolution_1080p
        CameraSettingData.Resolution.RESOLUTION_2160P -> R.string.screen_camera_setting_bottomsheet_camera_resolution_2160p
    }

@Composable
fun CameraSetting(
    settingData: CameraSettingData,
    onUpdate: (CameraSettingData) -> Unit
) {
    val context = LocalContext.current
    val menuLabelList = remember {
        CameraSettingData.Resolution.entries.map { context.getString(it.menuLabelResId) }
    }

    Column {

        DropdownSettingItem(
            title = stringResource(id = R.string.screen_camera_setting_bottomsheet_camera_front_camera_resolution_title),
            description = stringResource(id = R.string.screen_camera_setting_bottomsheet_camera_front_camera_resolution_description),
            selectIndex = settingData.frontCameraResolution.ordinal,
            menu = menuLabelList,
            onSelect = { onUpdate(settingData.copy(frontCameraResolution = CameraSettingData.Resolution.entries[it])) }
        )

        DropdownSettingItem(
            title = stringResource(id = R.string.screen_camera_setting_bottomsheet_camera_back_camera_resolution_title),
            selectIndex = settingData.backCameraResolution.ordinal,
            menu = menuLabelList,
            onSelect = { onUpdate(settingData.copy(backCameraResolution = CameraSettingData.Resolution.entries[it])) }
        )

        ClickSettingItem(
            title = stringResource(id = R.string.screen_camera_setting_bottomsheet_camera_reset_title),
            description = stringResource(id = R.string.screen_camera_setting_bottomsheet_camera_reset_description),
            onClick = { onUpdate(CameraSettingData.DEFAULT_SETTING) }
        )
    }
}