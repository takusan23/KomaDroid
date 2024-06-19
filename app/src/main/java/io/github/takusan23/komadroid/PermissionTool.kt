package io.github.takusan23.komadroid

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionTool {

    /** 必要な権限 */
    val REQUIRED_PERMISSION_LIST = arrayOf(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO
    )

    /** 権限があるか */
    fun isGrantedPermission(context: Context): Boolean = REQUIRED_PERMISSION_LIST
        .map { permission -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }
        .all { it }
}
