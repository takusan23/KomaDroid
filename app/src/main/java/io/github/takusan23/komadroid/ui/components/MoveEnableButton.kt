package io.github.takusan23.komadroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.takusan23.komadroid.R

@Composable
fun MoveEnableButton(
    modifier: Modifier = Modifier,
    isEnable: Boolean,
    onClick: (Boolean) -> Unit
) {
    Surface(
        modifier = modifier,
        onClick = { onClick(!isEnable) },
        color = MaterialTheme.colorScheme.primary,
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Max)
                .padding(10.dp)
        ) {
            Icon(
                modifier = Modifier.fillMaxHeight(),
                painter = painterResource(id = R.drawable.ic_drag_pan_24),
                contentDescription = null
            )
            Icon(
                modifier = Modifier.fillMaxHeight(),
                painter = painterResource(id = R.drawable.ic_pinch_zoom_out_24),
                contentDescription = null
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(color = LocalContentColor.current)
            )
            Icon(
                modifier = Modifier.fillMaxHeight(),
                painter = painterResource(id = if (isEnable) R.drawable.ic_check_24 else R.drawable.ic_block_24),
                tint = if (isEnable) LocalContentColor.current else MaterialTheme.colorScheme.onError,
                contentDescription = null
            )
        }
    }
}