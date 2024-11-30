package io.github.takusan23.komadroid.ui.screen.setting

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.takusan23.komadroid.R
import io.github.takusan23.komadroid.tool.CameraSettingData
import io.github.takusan23.komadroid.tool.NumberFormat
import io.github.takusan23.komadroid.tool.isTenBitProfileSupported
import io.github.takusan23.komadroid.ui.components.DropdownSettingItem
import io.github.takusan23.komadroid.ui.components.IntValueSettingItem
import io.github.takusan23.komadroid.ui.components.SwitchSettingItem

private val CameraSettingData.Fps.menuLabelResId
    get() = when (this) {
        CameraSettingData.Fps.FPS_30 -> R.string.screen_camera_setting_bottomsheet_video_fps_30
        CameraSettingData.Fps.FPS_60 -> R.string.screen_camera_setting_bottomsheet_video_fps_60
    }

@Composable
fun VideoSetting(
    settingData: CameraSettingData,
    onUpdate: (CameraSettingData) -> Unit
) {
    val context = LocalContext.current

    Column {

        // TODO ボトムシートを追加で表示して説明する
        // 10Bit HDR 対応時のみ
        val isAvailable10BitHdr = remember { (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).isTenBitProfileSupported() }
        val isVisibleTenBitHdrBottomSheet = remember { mutableStateOf(false) }
        if (isAvailable10BitHdr) {
            SwitchSettingItem(
                title = stringResource(id = R.string.screen_camera_setting_bottomsheet_video_10_bit_hdr_title),
                description = stringResource(id = R.string.screen_camera_setting_bottomsheet_video_10_bit_hdr_description),
                isCheck = settingData.isTenBitHdr,
                onSwitchChange = { isVisibleTenBitHdrBottomSheet.value = true }
            )
        }
        // ボトムシートを出す場合
        if (isVisibleTenBitHdrBottomSheet.value) {
            TenBitHdrSettingBottomSheet(
                settingData = settingData,
                onUpdate = onUpdate,
                onDismissRequest = { isVisibleTenBitHdrBottomSheet.value = false }
            )
        }

        DropdownSettingItem(
            title = stringResource(id = R.string.screen_camera_setting_bottomsheet_video_codec_title),
            description = stringResource(id = R.string.screen_camera_setting_bottomsheet_video_codec_description),
            selectIndex = settingData.videoCodec.ordinal,
            menu = remember { CameraSettingData.VideoCodec.entries.map { it.name } },
            onSelect = { onUpdate(settingData.copy(videoCodec = CameraSettingData.VideoCodec.entries[it])) }
        )

        IntValueSettingItem(
            title = stringResource(id = R.string.screen_camera_setting_bottomsheet_video_bitrate_title),
            description = stringResource(id = R.string.screen_camera_setting_bottomsheet_video_bitrate_description),
            value = settingData.videoBitrate,
            onChange = { onUpdate(settingData.copy(videoBitrate = it)) },
            suffix = { Text(text = NumberFormat.formatByteUnit(settingData.videoBitrate)) }
        )

        DropdownSettingItem(
            title = stringResource(id = R.string.screen_camera_setting_bottomsheet_video_fps_title),
            description = stringResource(id = R.string.screen_camera_setting_bottomsheet_video_fps_description),
            selectIndex = settingData.cameraFps.ordinal,
            menu = remember { CameraSettingData.Fps.entries.map { context.getString(it.menuLabelResId) } },
            onSelect = { onUpdate(settingData.copy(cameraFps = CameraSettingData.Fps.entries[it])) }
        )

    }
}