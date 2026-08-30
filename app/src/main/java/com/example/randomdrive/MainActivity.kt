package com.example.randomdrive

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var radiusLabel: TextView
    private lateinit var randomizeButton: MaterialButton
    private lateinit var navigateButton: MaterialButton
    private lateinit var toiletButton: MaterialButton
    private lateinit var stopDriveButton: MaterialButton

    private var currentLocation: LatLng? = null
    private var randomDestination: LatLng? = null
    private var radiusKm = 5.0
    private var isDriveActive = false

    private val locationPermissionRequestCode = 1001

    // Fired by DriveMonitorService whenever it auto-picks a new random
    // destination (arrival, or drifting off the current route).
    private val newDestinationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val lat = intent.getDoubleExtra(DriveMonitorService.EXTRA_DEST_LAT, 0.0)
            val lng = intent.getDoubleExtra(DriveMonitorService.EXTRA_DEST_LNG, 0.0)
            val newDest = LatLng(lat, lng)
            randomDestination = newDest
            currentLocation?.let { showDestinationOnMap(it, newDest) }
            Toast.makeText(this@MainActivity, "New random direction picked!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        radiusLabel = findViewById(R.id.radiusLabel)
        val radiusSeekBar = findViewById<SeekBar>(R.id.radiusSeekBar)
        randomizeButton = findViewById(R.id.randomizeButton)
        navigateButton = findViewById(R.id.navigateButton)
        toiletButton = findViewById(R.id.toiletButton)
        stopDriveButton = findViewById(R.id.stopDriveButton)

        radiusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                radiusKm = (progress + 1).toDouble()
                radiusLabel.text = "Max distance: ${radiusKm.toInt()} km"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        randomizeButton.setOnClickListener {
            val origin = currentLocation
            if (origin != null) {
                val destination = generateRandomPoint(origin, radiusKm)
                randomDestination = destination
                showDestinationOnMap(origin, destination)
                navigateButton.isEnabled = true
            }
        }

        navigateButton.setOnClickListener {
            randomDestination?.let { dest ->
                launchGoogleMapsNavigation(dest)
                startDriveMonitoring(dest)
            }
        }

        toiletButton.setOnClickListener {
            findNearestToiletAndNavigate()
        }

        stopDriveButton.setOnClickListener {
            stopDriveMonitoring()
        }

        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(DriveMonitorService.BROADCAST_NEW_DESTINATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(newDestinationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(newDestinationReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(newDestinationReceiver)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        if (hasLocationPermission()) {
            map.isMyLocationEnabled = true
            fetchCurrentLocation()
        }
    }

    // ---------- Permissions ----------

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), locationPermissionRequestCode)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionRequestCode && hasLocationPermission()) {
            if (::map.isInitialized) {
                map.isMyLocationEnabled = true
            }
            fetchCurrentLocation()
        }
    }

    private fun fetchCurrentLocation() {
        if (!hasLocationPermission()) return
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val here = LatLng(location.latitude, location.longitude)
                currentLocation = here
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(here, 13f))
            }
        }
    }

    // ---------- Random destination picking ----------

    /**
     * Picks a random point within [radiusKm] of [origin]: a random bearing
     * (0-360°) and a random distance. Distance is drawn via sqrt() of a
     * uniform variable so points spread evenly across the disc's area
     * instead of clustering near the center.
     */
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

    private fun showDestinationOnMap(origin: LatLng, destination: LatLng) {
        map.clear()
        map.addMarker(MarkerOptions().position(origin).title("You"))
        map.addMarker(MarkerOptions().position(destination).title("Random Destination"))
        map.addPolyline(PolylineOptions().add(origin, destination).width(6f))

        val bounds = LatLngBounds.builder().include(origin).include(destination).build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))
    }

    private fun launchGoogleMapsNavigation(destination: LatLng) {
        val uri = Uri.parse("google.navigation:q=${destination.latitude},${destination.longitude}&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            val fallbackUri = Uri.parse(
                "https://www.google.com/maps/dir/?api=1&destination=" +
                    "${destination.latitude},${destination.longitude}&travelmode=driving"
            )
            startActivity(Intent(Intent.ACTION_VIEW, fallbackUri))
        }
    }

    // ---------- Drive monitoring (auto-reroute on missed/changed turn) ----------

    private fun startDriveMonitoring(destination: LatLng) {
        val intent = Intent(this, DriveMonitorService::class.java).apply {
            action = DriveMonitorService.ACTION_START
            putExtra(DriveMonitorService.EXTRA_LAT, destination.latitude)
            putExtra(DriveMonitorService.EXTRA_LNG, destination.longitude)
            putExtra(DriveMonitorService.EXTRA_RADIUS_KM, radiusKm)
        }
        ContextCompat.startForegroundService(this, intent)
        isDriveActive = true
        stopDriveButton.visibility = android.view.View.VISIBLE
    }

    private fun stopDriveMonitoring() {
        val intent = Intent(this, DriveMonitorService::class.java).apply {
            action = DriveMonitorService.ACTION_STOP
        }
        startService(intent)
        isDriveActive = false
        stopDriveButton.visibility = android.view.View.GONE
    }

    // ---------- Nearest toilet ----------

    /**
     * Google's own Places data has no filterable "public restroom" category
     * (restroom is just a yes/no attribute on other venues, not a place
     * type), so this uses OpenStreetMap's free Overpass API, which has
     * purpose-tagged public toilet locations. Picks the closest one and
     * launches turn-by-turn navigation straight to it. Falls back to a
     * plain Maps search if nothing turns up nearby (rural areas, or sparse
     * OSM coverage in some regions).
     */
    private fun findNearestToiletAndNavigate() {
        val origin = currentLocation
        if (origin == null) {
            Toast.makeText(this, "Still finding your location — try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Looking for the nearest restroom…", Toast.LENGTH_SHORT).show()

        Thread {
            val nearest = queryNearestToilet(origin, 3000) ?: queryNearestToilet(origin, 10000)
            runOnUiThread {
                if (nearest != null) {
                    launchGoogleMapsNavigation(nearest)
                } else {
                    val uri = Uri.parse("geo:${origin.latitude},${origin.longitude}?q=public+restroom")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.google.android.apps.maps")
                    }
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    } else {
                        Toast.makeText(
                            this,
                            "No mapped restroom found nearby. Try searching manually in Maps.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
    }

    /** Queries Overpass for amenity=toilets within [radiusMeters] of [origin]; returns the closest, or null. */
    private fun queryNearestToilet(origin: LatLng, radiusMeters: Int): LatLng? {
        return try {
            val query = "[out:json][timeout:10];" +
                "(node[\"amenity\"=\"toilets\"](around:$radiusMeters,${origin.latitude},${origin.longitude});" +
                "way[\"amenity\"=\"toilets\"](around:$radiusMeters,${origin.latitude},${origin.longitude}););" +
                "out center 20;"
            val url = URL("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8"))
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "GET"

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val elements = JSONObject(responseText).getJSONArray("elements")
            var nearest: LatLng? = null
            var nearestDistance = Double.MAX_VALUE

            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                val point = when {
                    element.has("lat") && element.has("lon") ->
                        LatLng(element.getDouble("lat"), element.getDouble("lon"))
                    element.has("center") -> {
                        val center = element.getJSONObject("center")
                        LatLng(center.getDouble("lat"), center.getDouble("lon"))
                    }
                    else -> null
                } ?: continue

                val distance = distanceMeters(origin, point)
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearest = point
                }
            }
            nearest
        } catch (e: Exception) {
            null
        }
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
        return results[0].toDouble()
    }
}
