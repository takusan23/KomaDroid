package io.github.takusan23.komadroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ZoomControlSlider(
    modifier: Modifier = Modifier,
    currentZoom: Float,
    zoomRange: ClosedFloatingPointRange<Float>,
    onZoomChange: (Float) -> Unit
) {
    val sliderColors = SliderDefaults.colors(
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White,
        thumbColor = Color.White
    )

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Slider(
                modifier = Modifier.weight(1f),
                value = currentZoom,
                onValueChange = onZoomChange,
                valueRange = zoomRange,
                colors = sliderColors,
            )

            Text(text = "x${currentZoom.formatText()}")
        }
    }
}

/** 少数第1桁まで */
private fun Float.formatText(): String {
    return "%.1f".format(this)
}