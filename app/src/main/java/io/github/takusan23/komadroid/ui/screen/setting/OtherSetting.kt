package io.github.takusan23.komadroid.ui.screen.setting

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import io.github.takusan23.komadroid.R
import io.github.takusan23.komadroid.ui.components.ClickSettingItem
import io.github.takusan23.komadroid.ui.screen.MainScreenNavigation

private const val GitHubUrl = "https://github.com/takusan23/KomaDroid"
private const val PrivacyPolicyUrl = "https://github.com/takusan23/KomaDroid/blob/master/PRIVACY_POLICY.md"

@Composable
fun OtherSetting(onNavigation: (MainScreenNavigation) -> Unit) {
    val context = LocalContext.current
    val appVersion = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }

    Column {
        ClickSettingItem(
            title = stringResource(id = R.string.screen_camera_setting_bottomsheet_other_privacy_policy_title),
            description = stringResource(id = R.string.screen_camera_setting_bottomsheet_other_privacy_policy_description),
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, PrivacyPolicyUrl.toUri())
                context.startActivity(intent)
            }
        )

        ClickSettingItem(
            title = stringResource(id = R.string.screen_camera_setting_bottomsheet_other_license_title),
            description = stringResource(id = R.string.screen_camera_setting_bottomsheet_other_license_description),
            onClick = { onNavigation(MainScreenNavigation.LicenseScreen) }
        )

        ClickSettingItem(
            title = stringResource(id = R.string.screen_camera_setting_bottomsheet_other_source_code_title),
            description = stringResource(id = R.string.screen_camera_setting_bottomsheet_other_source_code_description),
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, GitHubUrl.toUri())
                context.startActivity(intent)
            }
        )

        ClickSettingItem(
            title = stringResource(id = R.string.screen_camera_setting_bottomsheet_other_version_title),
            description = appVersion,
            onClick = { /* do nothing */ }
        )
    }
}