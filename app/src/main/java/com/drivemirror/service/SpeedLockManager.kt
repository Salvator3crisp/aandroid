package com.drivemirror.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Speed Lock Manager — Fase 2 safety feature.
 *
 * Monitors device speed via GPS.
 * Emits [SpeedState] updates:
 *   - SAFE    → speed < SPEED_THRESHOLD_KMH (mirroring allowed)
 *   - MOVING  → speed ≥ SPEED_THRESHOLD_KMH (mirroring blocked)
 *   - NO_GPS  → GPS unavailable / permission denied
 *
 * The [ScreenMirrorService] observes [speedState] and calls stopMirroring()
 * when the device is detected as moving.
 *
 * Design notes:
 * - Uses fused location (LocationManager) with 1-second updates
 * - Hysteresis: requires 3 consecutive MOVING readings before locking
 *   (avoids false stops from GPS jitter)
 * - Speed is derived from GPS velocity, not distance delta
 */
class SpeedLockManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeedLock"

        const val SPEED_THRESHOLD_KMH = 5.0f   // Lock above this speed
        private const val GPS_UPDATE_INTERVAL_MS = 1_000L  // 1 sec
        private const val GPS_MIN_DISTANCE_M = 0f

        // Hysteresis: N consecutive moving readings → lock
        private const val MOVING_LOCK_COUNT = 3
    }

    enum class SpeedState {
        SAFE,      // Parked or very slow — mirroring allowed
        MOVING,    // Speed above threshold — mirroring blocked
        NO_GPS     // Cannot determine speed
    }

    private val _speedState = MutableStateFlow(SpeedState.NO_GPS)
    val speedState: StateFlow<SpeedState> = _speedState

    private var currentSpeedKmh = 0f
    private var movingCount = 0     // consecutive moving readings
    private var locationManager: LocationManager? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        // Legacy callbacks (required pre-API 30)
        override fun onProviderEnabled(provider: String) {
            Log.i(TAG, "GPS provider enabled: $provider")
            if (_speedState.value == SpeedState.NO_GPS) {
                _speedState.value = SpeedState.SAFE
            }
        }

        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "GPS provider disabled: $provider")
            _speedState.value = SpeedState.NO_GPS
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /**
     * Start GPS monitoring.
     * Returns false if permission is missing (caller should request it).
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!hasLocationPermission()) {
            Log.e(TAG, "Missing ACCESS_FINE_LOCATION permission")
            _speedState.value = SpeedState.NO_GPS
            return false
        }

        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var registered = false

        for (provider in providers) {
            if (locationManager?.isProviderEnabled(provider) == true) {
                locationManager?.requestLocationUpdates(
                    provider,
                    GPS_UPDATE_INTERVAL_MS,
                    GPS_MIN_DISTANCE_M,
                    locationListener,
                    Looper.getMainLooper()
                )
                registered = true
                Log.i(TAG, "SpeedLock: listening on $provider")
            }
        }

        if (!registered) {
            Log.w(TAG, "No GPS/Network provider available")
            _speedState.value = SpeedState.NO_GPS
        } else {
            _speedState.value = SpeedState.SAFE
        }

        return registered
    }

    private fun handleLocation(location: Location) {
        // GPS provides speed directly (m/s) when hasSpeed() is true
        currentSpeedKmh = if (location.hasSpeed()) {
            location.speed * 3.6f
        } else {
            0f // Fall back to safe — don't lock without reliable speed
        }

        val isMoving = currentSpeedKmh >= SPEED_THRESHOLD_KMH

        if (isMoving) {
            movingCount++
            if (movingCount >= MOVING_LOCK_COUNT && _speedState.value != SpeedState.MOVING) {
                Log.w(TAG, "🚗 MOVING at ${currentSpeedKmh}km/h — locking mirroring")
                _speedState.value = SpeedState.MOVING
            }
        } else {
            movingCount = 0
            if (_speedState.value == SpeedState.MOVING) {
                Log.i(TAG, "🅿 PARKED (${currentSpeedKmh}km/h) — unlocking mirroring")
                _speedState.value = SpeedState.SAFE
            }
        }

        Log.v(TAG, "Speed: ${currentSpeedKmh}km/h | state: ${_speedState.value} | movingCount: $movingCount")
    }

    fun stop() {
        locationManager?.removeUpdates(locationListener)
        locationManager = null
        _speedState.value = SpeedState.NO_GPS
        Log.i(TAG, "SpeedLock stopped")
    }

    fun getCurrentSpeedKmh(): Float = currentSpeedKmh

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
