package io.github.takusan23.komadroid.ui.components

import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import io.github.takusan23.komadroid.R

enum class ScreenRotateType {
    /** 画面回転の切り替え不可、録画中に使われる */
    BlockRotationRequest,

    /** 回転センサーの画面回転を有効 */
    UnLockScreenRotation,

    /** 回転センサーの画面回転を無効 */
    LockScreenRotation
}

@Composable
fun ScreenRotationLockButton(
    modifier: Modifier = Modifier,
    screenRotateType: ScreenRotateType,
    onClick: () -> Unit
) {
    FilledIconButton(
        modifier = modifier,
        onClick = onClick,
        enabled = screenRotateType != ScreenRotateType.BlockRotationRequest // ブロック中以外は押せる
    ) {
        Icon(
            painter = painterResource(
                id = when (screenRotateType) {
                    ScreenRotateType.BlockRotationRequest -> R.drawable.ic_block_24
                    ScreenRotateType.UnLockScreenRotation -> R.drawable.ic_screen_rotation_24
                    ScreenRotateType.LockScreenRotation -> R.drawable.ic_screen_lock_rotation_24
                }
            ),
            contentDescription = null
        )
    }
}