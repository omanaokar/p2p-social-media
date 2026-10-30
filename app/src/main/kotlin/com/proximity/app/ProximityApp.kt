package com.proximity.app

import android.app.Application

/**
 * Application entry point for the Proximity Android app.
 * Manages the top-level [AppContainer] and initializes [com.proximity.core.AppCore].
 */
class ProximityApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
