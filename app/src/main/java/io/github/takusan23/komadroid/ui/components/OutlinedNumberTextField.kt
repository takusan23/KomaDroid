package io.github.takusan23.komadroid.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

/** [OutlinedTextField]の[Int]版 */
@Composable
fun OutlinedIntTextField(
    modifier: Modifier = Modifier,
    value: Int,
    onValueChange: (Int) -> Unit,
    label: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null
) {
    val numberText = remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        modifier = modifier,
        value = numberText.value,
        onValueChange = {
            numberText.value = it
            it.toIntOrNull()?.also { int ->
                onValueChange(int)
            }
        },
        suffix = suffix,
        label = label,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}
