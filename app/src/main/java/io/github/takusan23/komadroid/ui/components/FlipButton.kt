package io.github.takusan23.komadroid.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import io.github.takusan23.komadroid.R

@Composable
fun FlipButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isAnimationFlag = remember { mutableStateOf(false) } // ただのアニメーショントリガー用
    val animateRotate = animateFloatAsState(
        targetValue = if (isAnimationFlag.value) 360f else 0f,
        label = "FlipButtonAnimation"
    )

    FilledIconButton(
        modifier = modifier,
        onClick = {
            isAnimationFlag.value = !isAnimationFlag.value
            onClick()
        }
    ) {
        Icon(
            modifier = Modifier.graphicsLayer { rotationZ = animateRotate.value },
            painter = painterResource(id = R.drawable.ic_cameraswitch_24),
            contentDescription = null
        )
    }
}