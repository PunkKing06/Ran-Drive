package com.example.randomdrive

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Keeps the "just drive randomly" idea alive even after you hand off to
 * Google Maps for turn-by-turn directions. Google Maps itself will happily
 * recalculate a route back to the SAME destination if you miss a turn —
 * which defeats the point. This service instead watches your real position:
 * if you arrive, or if you drift meaningfully away from the current random
 * destination (missed turn, or you just felt like going somewhere else),
 * it rolls a brand-new random destination from wherever you are now and
 * re-launches navigation to that instead.
 *
 * It's a heuristic, not a true "missed turn" detector: with no in-app
 * navigation SDK, we don't have the actual road-following route to compare
 * against, only straight-line distance to the target. On a winding road
 * that can occasionally cause a false trigger. Tune DEVIATION_THRESHOLD_METERS
 * and the consecutive-reading check below if it reroutes too eagerly or not
 * eagerly enough for your driving area.
 */
class DriveMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.example.randomdrive.action.START"
        const val ACTION_STOP = "com.example.randomdrive.action.STOP"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_RADIUS_KM = "extra_radius_km"

        const val BROADCAST_NEW_DESTINATION = "com.example.randomdrive.NEW_DESTINATION"
        const val EXTRA_DEST_LAT = "dest_lat"
        const val EXTRA_DEST_LNG = "dest_lng"

        private const val CHANNEL_ID = "drive_monitor_channel"
        private const val NOTIFICATION_ID = 42

        // How far past your closest-approach-so-far counts as "drifting away".
        private const val DEVIATION_THRESHOLD_METERS = 400.0
        // How close counts as "arrived" and ready for a new random leg.
        private const val ARRIVAL_THRESHOLD_METERS = 60.0
        // Require this many consecutive drifting readings before rerouting,
        // to smooth out ordinary GPS jitter.
        private const val REQUIRED_CONSECUTIVE_DRIFT_READINGS = 2
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private var destLat = 0.0
    private var destLng = 0.0
    private var radiusKm = 5.0
    private var closestDistanceSoFarMeters = Double.MAX_VALUE
    private var consecutiveDriftReadings = 0

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                destLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                destLng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                radiusKm = intent.getDoubleExtra(EXTRA_RADIUS_KM, 5.0)
                closestDistanceSoFarMeters = Double.MAX_VALUE
                consecutiveDriftReadings = 0
                startForeground(NOTIFICATION_ID, buildNotification("Free-driving — go wherever!"))
                startLocationUpdates()
            }
        }
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15000L)
            .setMinUpdateIntervalMillis(8000L)
            .setMinUpdateDistanceMeters(20f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onNewLocation(it) }
            }
        }
        fusedLocationClient.requestLocationUpdates(request, locationCallback as LocationCallback, mainLooper)
    }

    private fun onNewLocation(location: Location) {
        val results = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, destLat, destLng, results)
        val distanceToDest = results[0].toDouble()

        if (distanceToDest < closestDistanceSoFarMeters) {
            closestDistanceSoFarMeters = distanceToDest
            consecutiveDriftReadings = 0
        }

        if (distanceToDest <= ARRIVAL_THRESHOLD_METERS) {
            rerouteRandomly(location, "Arrived! Picking a new random direction…")
            return
        }

        if (distanceToDest > closestDistanceSoFarMeters + DEVIATION_THRESHOLD_METERS) {
            consecutiveDriftReadings++
            if (consecutiveDriftReadings >= REQUIRED_CONSECUTIVE_DRIFT_READINGS) {
                rerouteRandomly(location, "Took a different turn — new random direction!")
            }
        } else {
            consecutiveDriftReadings = 0
        }
    }

    private fun rerouteRandomly(location: Location, reason: String) {
        val origin = LatLng(location.latitude, location.longitude)
        val newDest = generateRandomPoint(origin, radiusKm)

        destLat = newDest.latitude
        destLng = newDest.longitude
        closestDistanceSoFarMeters = Double.MAX_VALUE
        consecutiveDriftReadings = 0

        updateNotification(reason)
        launchNavigation(newDest)
        broadcastNewDestination(newDest)
    }

    private fun generateRandomPoint(origin: LatLng, radiusKm: Double): LatLng {
        val earthRadiusKm = 6371.0
        val bearingRad = Math.toRadians(Random.nextDouble(0.0, 360.0))
        val distanceKm = radiusKm * sqrt(Random.nextDouble(0.1, 1.0))
        val angularDistance = distanceKm / earthRadiusKm

        val lat1 = Math.toRadians(origin.latitude)
        val lon1 = Math.toRadians(origin.longitude)

        val lat2 = asin(
            sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearingRad)
        )
        val lon2 = lon1 + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )
        return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun launchNavigation(dest: LatLng) {
        val uri = Uri.parse("google.navigation:q=${dest.latitude},${dest.longitude}&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    private fun broadcastNewDestination(dest: LatLng) {
        val intent = Intent(BROADCAST_NEW_DESTINATION).apply {
            setPackage(packageName)
            putExtra(EXTRA_DEST_LAT, dest.latitude)
            putExtra(EXTRA_DEST_LNG, dest.longitude)
        }
        sendBroadcast(intent)
    }

    private fun stopMonitoring() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Drive Monitor", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracks your free-drive and picks new random directions"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Random Drive is active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
