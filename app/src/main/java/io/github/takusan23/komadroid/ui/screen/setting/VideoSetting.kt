package io.github.takusan23.komadroid.ui.screen.setting

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.takusan23.komadroid.tool.CameraSettingData
import io.github.takusan23.komadroid.tool.NumberFormat
import io.github.takusan23.komadroid.tool.isTenBitProfileSupported
import io.github.takusan23.komadroid.ui.components.DropdownSettingItem
import io.github.takusan23.komadroid.ui.components.IntValueSettingItem
import io.github.takusan23.komadroid.ui.components.SwitchSettingItem

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
    val context = LocalContext.current

    Column {

        // TODO ボトムシートを追加で表示して説明する
        // 10Bit HDR 対応時のみ
        val isAvailable10BitHdr = remember { (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).isTenBitProfileSupported() }
        val isVisibleTenBitHdrBottomSheet = remember { mutableStateOf(false) }
        if (isAvailable10BitHdr) {
            SwitchSettingItem(
                title = "10 ビット HDR 動画撮影を有効にする",
                description = "従来の動画（SDR）と比べて、より多くの明るさと色で撮影することが出来ます。\n簡単に言うと「眩しい動画」が撮影できます。",
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
            title = "動画コーデック",
            description = "HEVC を利用すると、互換性を犠牲に容量が半分になると言われています。",
            selectIndex = settingData.videoCodec.ordinal,
            menu = remember { CameraSettingData.VideoCodec.entries.map { it.name } },
            onSelect = { onUpdate(settingData.copy(videoCodec = CameraSettingData.VideoCodec.entries[it])) }
        )

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