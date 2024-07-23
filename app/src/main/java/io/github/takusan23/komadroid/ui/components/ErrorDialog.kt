package io.github.takusan23.komadroid.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.takusan23.komadroid.R

enum class ErrorType(val messageResId: Int) {
    /** カメラを開くのに失敗した */
    CameraOpenError(R.string.dialog_error_camera_open),

    /** 不明なエラー */
    UnknownError(R.string.dialog_error_unknown)
}

@Composable
fun ErrorDialog(
    modifier: Modifier = Modifier,
    errorType: ErrorType,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(text = "閉じる")
            }
        },
        title = { Text(text = stringResource(id = R.string.dialog_error_title)) },
        text = { Text(text = stringResource(id = errorType.messageResId)) }
    )
}