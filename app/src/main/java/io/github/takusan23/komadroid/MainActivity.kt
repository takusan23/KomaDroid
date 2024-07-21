package io.github.takusan23.komadroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.takusan23.komadroid.ui.screen.MainScreen
import io.github.takusan23.komadroid.ui.theme.KomaDroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KomaDroidTheme {
                MainScreen()
            }
        }
    }
}

