package io.github.takusan23.komadroid.ui.screen.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.takusan23.komadroid.R
import io.github.takusan23.komadroid.tool.CameraSettingData
import io.github.takusan23.komadroid.ui.components.SwitchSettingItem

/** 10 ビット HDR を有効にするボトムシート。注意点を書いてあるあれ。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TenBitHdrSettingBottomSheet(
    modifier: Modifier = Modifier,
    settingData: CameraSettingData,
    onUpdate: (CameraSettingData) -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .padding(10.dp)
                .verticalScroll(rememberScrollState())
        ) {

            Text(
                text = stringResource(id = R.string.screen_camera_ten_bit_hdr_bottomsheet_title),
                fontSize = 24.sp
            )

            SwitchSettingItem(
                title = stringResource(id = R.string.screen_camera_ten_bit_hdr_bottomsheet_enable_title),
                isCheck = settingData.isTenBitHdr,
                onSwitchChange = {
                    onUpdate(settingData.copy(isTenBitHdr = it))
                    onDismissRequest()
                }
            )

            HorizontalDivider(modifier = Modifier.padding(10.dp))

            Icon(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                painter = painterResource(R.drawable.android_hdr_description),
                contentDescription = null
            )

            Text(text = stringResource(id = R.string.screen_camera_ten_bit_hdr_bottomsheet_enable_description))
        }
    }
}