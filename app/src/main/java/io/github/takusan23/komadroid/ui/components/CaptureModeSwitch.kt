package io.github.takusan23.komadroid.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import io.github.takusan23.komadroid.KomaDroidCameraManager
import io.github.takusan23.komadroid.R

private val KomaDroidCameraManager.CaptureMode.iconResId: Int
    get() = when (this) {
        KomaDroidCameraManager.CaptureMode.PICTURE -> R.drawable.ic_photo_camera_24
        KomaDroidCameraManager.CaptureMode.VIDEO -> R.drawable.ic_videocam_24
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureModeSwitch(
    modifier: Modifier = Modifier,
    currentCaptureMode: KomaDroidCameraManager.CaptureMode,
    onChange: (KomaDroidCameraManager.CaptureMode) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        KomaDroidCameraManager.CaptureMode.entries.forEachIndexed { index, type ->
            SegmentedButton(
                selected = type == currentCaptureMode,
                onClick = { onChange(type) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = KomaDroidCameraManager.CaptureMode.entries.size
                )
            ) {
                Icon(
                    painter = painterResource(id = type.iconResId),
                    contentDescription = null
                )
            }
        }
    }
}