package com.proximity.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.proximity.app.ui.navigation.MainAppScreen
import com.proximity.app.ui.theme.ProximityTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ProximityApp).container

        setContent {
            ProximityTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainAppScreen(container = container)
                }
            }
        }
    }
}
