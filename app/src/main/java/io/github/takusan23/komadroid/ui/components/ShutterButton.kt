package io.github.takusan23.komadroid.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val ShutterButtonSize = 100.dp

@Composable
fun ShutterButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    isVideoRecording: Boolean
) {
    Surface(
        modifier = modifier.size(ShutterButtonSize),
        onClick = onClick,
        shape = CircleShape,
        color = if (isVideoRecording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(
            width = 10.dp,
            color = if (isVideoRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    ) { /* do nothing */ }
}