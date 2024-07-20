package io.github.takusan23.komadroid.ui.screen.setting

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import io.github.takusan23.komadroid.ui.components.ClickSettingItem
import io.github.takusan23.komadroid.ui.screen.MainScreenNavigation

private const val GitHubUrl = "https://github.com/takusan23/KomaDroid"

@Composable
fun OtherSetting(onNavigation: (MainScreenNavigation) -> Unit) {
    val context = LocalContext.current
    val appVersion = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }

    Column {

        ClickSettingItem(
            title = "ライセンス",
            description = "何の見返りも求めず作ってくれたキミにありがとう",
            onClick = { onNavigation(MainScreenNavigation.LicenseScreen) }
        )

        ClickSettingItem(
            title = "ソースコード",
            description = "GitHub にあります",
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, GitHubUrl.toUri())
                context.startActivity(intent)
            }
        )

        ClickSettingItem(
            title = "バージョン",
            description = appVersion,
            onClick = { }
        )
    }
}