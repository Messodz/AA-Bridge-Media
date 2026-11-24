package dev.caraalink.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Parked or moving, from the phone's GPS speed. The car's CarbitLink link
 * gives us no speed or parking-brake signal, so this is the only source.
 *
 * Safe by default: without a recent GPS fix the car counts as moving, so
 * video and web stay locked until the phone knows the car is standing still.
 */
object DrivingState : LocationListener {

    /** ~10 km/h: above this the car is moving. */
    private const val MOVING_MPS = 2.8f
    /** Must stay below the threshold this long before video unlocks again. */
    private const val SETTLE_MS = 8_000L
    /** A fix older than this doesn't count. */
    private const val FIX_MAX_AGE_MS = 15_000L

    @Volatile private var lastFixAt = 0L
    @Volatile private var lastSpeed = 0f
    @Volatile private var lastMovingAt = 0L
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        Handler(Looper.getMainLooper()).post {
            if (started) return@post
            val lm = app.getSystemService(LocationManager::class.java) ?: return@post
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
                started = true
            } catch (e: SecurityException) {
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    fun isParked(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastFixAt == 0L || now - lastFixAt > FIX_MAX_AGE_MS) return false
        return lastSpeed < MOVING_MPS && now - lastMovingAt > SETTLE_MS
    }

    /** Short reason for the lock screen. */
    fun reason(): String {
        val now = SystemClock.elapsedRealtime()
        return if (lastFixAt == 0L || now - lastFixAt > FIX_MAX_AGE_MS) {
            "Waiting for GPS to confirm the car is parked"
        } else {
            "Video and web are paused while the car is moving"
        }
    }

    override fun onLocationChanged(location: Location) {
        val now = SystemClock.elapsedRealtime()
        lastFixAt = now
        lastSpeed = if (location.hasSpeed()) location.speed else 0f
        if (lastSpeed >= MOVING_MPS) lastMovingAt = now
    }

    // Abstract before API 30; keep them so Android 10 doesn't crash.
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
