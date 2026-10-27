package com.proximity.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Application entry point for the Proximity Android app.
 * Manages the top-level [AppContainer] and initializes [com.proximity.core.AppCore].
 */
class ProximityApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applicationScope.launch {
            try {
                container.start()
            } catch (e: Exception) {
                android.util.Log.e("ProximityApp", "Failed to initialize AppCore", e)
            }
        }
    }

    override fun onTerminate() {
        applicationScope.launch {
            container.stop()
        }
        applicationScope.cancel()
        super.onTerminate()
    }
}
