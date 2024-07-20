package io.github.takusan23.komadroid.ui.screen.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.takusan23.komadroid.ui.components.ClickSettingItem

// TODO この辺のリンクを完成させていく

@Composable
fun OtherSetting() {
    val context = LocalContext.current
    val appVersion = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }

    Column {

        ClickSettingItem(
            title = "ライセンス",
            description = "何の見返りも求めず作ってくれたキミにありがとう",
            onClick = { }
        )

        ClickSettingItem(
            title = "ソースコード",
            description = "GitHub にあります",
            onClick = { }
        )

        ClickSettingItem(
            title = "バージョン",
            description = appVersion,
            onClick = { }
        )
    }
}