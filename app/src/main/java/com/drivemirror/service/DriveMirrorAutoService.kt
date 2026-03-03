package com.drivemirror.service

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.drivemirror.ui.DriveMirrorCarScreen

/**
 * Android Auto entry point — replaces the old MediaBrowserServiceCompat.
 *
 * CarAppService gives us access to NavigationTemplate which provides
 * a Surface for rendering the mirrored phone screen.
 */
class DriveMirrorAutoService : CarAppService() {

    companion object {
        private const val TAG = "DriveMirrorAutoService"
    }

    override fun createHostValidator(): HostValidator {
        // Allow any host in debug builds; restrict in release if needed
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        }
    }

    override fun onCreateSession(): Session {
        Log.i(TAG, "Creating new CarApp session")
        return object : Session() {
            override fun onCreateScreen(intent: Intent): androidx.car.app.Screen {
                return DriveMirrorCarScreen(carContext)
            }
        }
    }
}
