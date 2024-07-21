package io.github.takusan23.komadroid.ui.screen.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ライセンス情報データクラス
 * @param name 名前
 * @param license ライセンス
 * */
private data class LicenseData(
    val name: String,
    val license: String,
)

private val aosp = LicenseData(
    name = "Android Open Source Project",
    license = """
        Copyright (C) 2013 The Android Open Source Project
        Licensed under the Apache License, Version 2.0 (the "License");
        you may not use this file except in compliance with the License.
        You may obtain a copy of the License at

             http://www.apache.org/licenses/LICENSE-2.0

        Unless required by applicable law or agreed to in writing, software
        distributed under the License is distributed on an "AS IS" BASIS,
        WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
        See the License for the specific language governing permissions and
        limitations under the License.
    """.trimIndent()
)

private val kotlinCoroutine = LicenseData(
    name = "Kotlin/kotlinx.coroutines",
    license = """
           Copyright 2000-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.

           Licensed under the Apache License, Version 2.0 (the "License");
           you may not use this file except in compliance with the License.
           You may obtain a copy of the License at

               http://www.apache.org/licenses/LICENSE-2.0

           Unless required by applicable law or agreed to in writing, software
           distributed under the License is distributed on an "AS IS" BASIS,
           WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
           See the License for the specific language governing permissions and
           limitations under the License.
    """.trimIndent()
)

private val materialIcons = LicenseData(
    name = "google/material-design-icons",
    license = """
           Licensed under the Apache License, Version 2.0 (the "License");
           you may not use this file except in compliance with the License.
           You may obtain a copy of the License at

               http://www.apache.org/licenses/LICENSE-2.0

           Unless required by applicable law or agreed to in writing, software
           distributed under the License is distributed on an "AS IS" BASIS,
           WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
           See the License for the specific language governing permissions and
           limitations under the License.
    """.trimIndent()
)

/** ライセンス一覧 */
private val LicenseList = listOf(
    aosp,
    kotlinCoroutine,
    materialIcons
)

@Composable
fun LicenseScreen() {
    Scaffold { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding)) {
            items(LicenseList) { licenseData ->
                LicenseItem(licenseData = licenseData)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LicenseItem(licenseData: LicenseData) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            modifier = Modifier.padding(5.dp),
            text = licenseData.name,
            fontSize = 25.sp
        )
        Text(
            text = licenseData.license,
            modifier = Modifier.padding(start = 5.dp, end = 5.dp)
        )
    }
}