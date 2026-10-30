package com.proximity.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.proximity.app.ui.navigation.MainAppScreen
import com.proximity.app.ui.theme.ProximityTheme
import com.proximity.transport.android.NearbyPermissions
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasNearbyPermissions()) startCore()
        else Toast.makeText(this, "Nearby permissions are needed to find peers.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ProximityApp).container

        setContent {
            val preferences = remember { getSharedPreferences("proximity_app_settings", MODE_PRIVATE) }
            var darkMode by rememberSaveable {
                mutableStateOf(preferences.getBoolean("dark_mode", false))
            }
            ProximityTheme(darkTheme = darkMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainAppScreen(
                        container = container,
                        darkMode = darkMode,
                        onDarkModeChange = { enabled ->
                            darkMode = enabled
                            preferences.edit().putBoolean("dark_mode", enabled).apply()
                        },
                    )
                }
            }
        }

        if (container.transportMode.value != TransportMode.REAL_NEARBY || hasNearbyPermissions()) {
            startCore()
        } else {
            window.decorView.post {
                if (!isFinishing && !hasNearbyPermissions()) {
                    permissionRequest.launch(requiredNearbyPermissions())
                } else if (!isFinishing) {
                    startCore()
                }
            }
        }
    }

    private fun requiredNearbyPermissions(): Array<String> =
        NearbyPermissions.requiredRuntimePermissions(android.os.Build.VERSION.SDK_INT).toTypedArray()

    private fun hasNearbyPermissions(): Boolean = requiredNearbyPermissions().all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun startCore() {
        val container = (application as ProximityApp).container
        if (container.core.value != null) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                container.start()
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Could not start Proximity: ${failure.message ?: failure.javaClass.simpleName}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
}
