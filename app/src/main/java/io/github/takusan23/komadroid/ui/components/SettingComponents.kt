package io.github.takusan23.komadroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingTabMenu(
    modifier: Modifier = Modifier,
    selectIndex: Int,
    menu: List<String>,
    onSelect: (Int) -> Unit
) {
    ScrollableTabRow(
        modifier = modifier,
        selectedTabIndex = selectIndex,
        divider = { /* do noting */ },
        indicator = { tabPositions ->
            Box(
                modifier = Modifier
                    .tabIndicatorOffset(tabPositions[selectIndex])
                    .height(3.dp)
                    .padding(start = 20.dp, end = 20.dp)
                    .background(LocalContentColor.current, RoundedCornerShape(100, 100, 0, 0))
            )
        }
    ) {
        menu.forEachIndexed { index, title ->
            Tab(
                selected = index == selectIndex,
                onClick = { onSelect(index) },
                text = { Text(text = title) }
            )
        }
    }
}

@Composable
fun DropdownSettingItem(
    modifier: Modifier = Modifier,
    title: String,
    description: String? = null,
    selectIndex: Int,
    menu: List<String>,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = modifier.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {

        Column(modifier = Modifier.weight(2f)) {
            Text(
                text = title,
                fontSize = 18.sp
            )

            if (description != null) {
                Text(text = description)
            }
        }

        OutlinedDropDownMenu(
            modifier = Modifier.weight(1f),
            label = "",
            currentSelectIndex = selectIndex,
            menuList = menu,
            onSelect = onSelect
        )
    }
}

@Composable
fun IntValueSettingItem(
    modifier: Modifier = Modifier,
    title: String,
    description: String? = null,
    value: Int,
    onChange: (Int) -> Unit
) {
    Row(
        modifier = modifier.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 18.sp
            )

            if (description != null) {
                Text(text = description)
            }
        }

        OutlinedIntTextField(
            modifier = Modifier.weight(1f),
            value = value,
            onValueChange = onChange
        )
    }
}

@Composable
fun ClickSettingItem(
    modifier: Modifier = Modifier,
    title: String,
    description: String? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {

            Column(modifier = Modifier.weight(3f)) {
                Text(
                    text = title,
                    fontSize = 18.sp
                )

                if (description != null) {
                    Text(text = description)
                }
            }
        }
    }
}