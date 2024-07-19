package io.github.takusan23.komadroid.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.takusan23.komadroid.KomaDroidCameraManager
import io.github.takusan23.komadroid.R

@Composable
fun ZoomControlOpenButton(
    modifier: Modifier = Modifier,
    zoomData: KomaDroidCameraManager.CameraZoomData,
    onFrontZoomClick: () -> Unit,
    onBackZoomClick: () -> Unit
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_zoom_in_24),
                contentDescription = null
            )

            ZoomIcon(
                iconResId = R.drawable.ic_photo_camera_front_24,
                text = zoomData.currentFrontCameraZoom.formatText(),
                onClick = onFrontZoomClick
            )

            ZoomIcon(
                iconResId = R.drawable.ic_photo_camera_24,
                text = zoomData.currentBackCameraZoom.formatText(),
                onClick = onBackZoomClick
            )
        }
    }
}

@Composable
private fun ZoomIcon(
    modifier: Modifier = Modifier,
    iconResId: Int,
    text: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, LocalContentColor.current),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painter = painterResource(id = iconResId), contentDescription = null)
            Text(text = text)
        }
    }
}

/** 少数第1桁まで */
private fun Float.formatText(): String {
    return "%.1f".format(this)
}