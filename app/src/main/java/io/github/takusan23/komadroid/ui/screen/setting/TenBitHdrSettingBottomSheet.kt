package io.github.takusan23.komadroid.ui.screen.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                text = "10 ビット HDR 動画撮影の設定",
                fontSize = 24.sp
            )

            Text(
                text = """
                従来の動画（SDR）と比べて、より多くの明るさと色で撮影することが出来ます。
                簡単に言うと「眩しい動画」が撮影できます。
                
                HDR で撮影された動画は、HDR に対応したディスプレイで見ると大体撮影時と同じ色と明るさで再生されます。
                ただし HDR に対応していない場合、全体的に色が白っぽくなってしまう傾向があります。
                ・HDR に対応していない端末で再生する場合
                ・動画共有サイトが対応していない場合
                ・動画編集で利用する場合（動画編集アプリが HDR に対応している場合は別です）
                上記の目的で使う場合はこの機能を OFF にすることをおすすめします。
                
                動画のコーデックは現状 HEVC（H.265）のみになります。
                
                有識者向け情報
                ・HDR の形式は HLG
                ・ガンマカーブは HLG
                ・色空間は BT.2020
            """.trimIndent()
            )

            HorizontalDivider(modifier = Modifier.padding(10.dp))

            SwitchSettingItem(
                title = "10 ビット HDR 動画撮影を有効にする",
                isCheck = settingData.isTenBitHdr,
                onSwitchChange = {
                    onUpdate(settingData.copy(isTenBitHdr = it))
                    onDismissRequest()
                }
            )
        }
    }
}