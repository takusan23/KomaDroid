package io.github.takusan23.komadroid.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.takusan23.komadroid.PermissionTool

enum class MainScreenNavigation(val path: String) {
    PermissionScreen("permission"),
    CameraScreen("camera"),
    LicenseScreen("license")
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        // 権限ない場合は権限ください画面
        startDestination = if (PermissionTool.isGrantedPermission(context)) MainScreenNavigation.CameraScreen.path else MainScreenNavigation.PermissionScreen.path
    ) {
        composable(MainScreenNavigation.PermissionScreen.path) {
            PermissionScreen(
                onGranted = {
                    navController.navigate(MainScreenNavigation.CameraScreen.path) {
                        // 権限画面に戻ってこれないように
                        popUpTo(MainScreenNavigation.PermissionScreen.path) {
                            inclusive = true
                        }
                    }
                }
            )
        }

        composable(MainScreenNavigation.CameraScreen.path) {
            CameraScreen(onNavigation = { navController.navigate(it.path) })
        }

        composable(MainScreenNavigation.LicenseScreen.path) {
            LicenseScreen()
        }
    }
}