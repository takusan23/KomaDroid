package io.github.takusan23.komadroid.ui.components

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlinedDropDownMenu(
    modifier: Modifier = Modifier,
    label: String,
    currentSelectIndex: Int,
    menuList: List<String>,
    onSelect: (Int) -> Unit
) {
    val isExpanded = remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        modifier = modifier,
        expanded = isExpanded.value,
        onExpandedChange = { isExpanded.value = it }
    ) {
        OutlinedTextField(
            modifier = Modifier.menuAnchor(),
            readOnly = true,
            value = menuList[currentSelectIndex],
            onValueChange = { /* do nothing */ },
            label = { Text(text = label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = isExpanded.value
                )
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
        )
        ExposedDropdownMenu(
            expanded = isExpanded.value,
            onDismissRequest = { isExpanded.value = false }
        ) {
            menuList.forEachIndexed { index, menu ->
                DropdownMenuItem(
                    onClick = {
                        isExpanded.value = false
                        onSelect(index)
                    },
                    text = {
                        Text(text = menu)
                    }
                )
            }
        }
    }
}