package com.example.randomdrive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        // How far off the planned route counts as "missed the turn" (or
        // deliberately went a different way) and triggers a fresh route.
        private const val DEVIATION_METERS = 60.0
        // How close to a turn's location counts as having reached it.
        private const val ARRIVAL_METERS = 30.0
        // Distance out at which we speak an early warning for the upcoming turn.
        private const val WARNING_METERS = 150.0
        // Once fewer than this many steps remain, proactively fetch a
        // continuation so there's always something upcoming to show.
        private const val LOW_STEPS_THRESHOLD = 3
    }

    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var radiusLabel: TextView
    private lateinit var randomizeButton: MaterialButton
    private lateinit var navigateButton: MaterialButton
    private lateinit var toiletButton: MaterialButton
    private lateinit var stopDriveButton: MaterialButton
    private lateinit var turnListPanel: View
    private lateinit var turn1Text: TextView
    private lateinit var turn2Text: TextView
    private lateinit var turn3Text: TextView

    private var currentLocation: LatLng? = null
    private var randomDestination: LatLng? = null
    private var radiusKm = 5.0

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    // In-app "random drive" state
    private var isDriveActive = false
    private var drivingRoute: DrivingRoute? = null
    private var currentStepIndex = 0
    private var routeFetchInProgress = false
    private var hasWarnedForCurrentStep = false
    private var mapPolyline: Polyline? = null

    private val locationPermissionRequestCode = 1001

    private val driveLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onDriveLocationUpdate(it) }
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
        turnListPanel = findViewById(R.id.turnListPanel)
        turn1Text = findViewById(R.id.turn1Text)
        turn2Text = findViewById(R.id.turn2Text)
        turn3Text = findViewById(R.id.turn3Text)

        textToSpeech = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) textToSpeech?.language = Locale.getDefault()
        }

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
                showPreviewOnMap(origin, destination)
                navigateButton.isEnabled = true
            }
        }

        navigateButton.setOnClickListener { startDrive() }
        stopDriveButton.setOnClickListener { stopDrive() }

        toiletButton.setOnClickListener { findNearestToiletAndNavigate() }

        requestNeededPermissions()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        if (hasLocationPermission()) {
            map.isMyLocationEnabled = true
            fetchCurrentLocation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isDriveActive) stopDriveLocationUpdates()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }

    // ---------- Permissions ----------

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNeededPermissions() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), locationPermissionRequestCode
            )
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

    // ---------- Random destination picking (preview, before a drive starts) ----------

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

    private fun showPreviewOnMap(origin: LatLng, destination: LatLng) {
        map.clear()
        map.addMarker(MarkerOptions().position(origin).title("You"))
        map.addMarker(MarkerOptions().position(destination).title("Random Destination"))

        val bounds = LatLngBounds.builder().include(origin).include(destination).build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))
    }

    // ---------- In-app random drive ----------

    private fun startDrive() {
        val origin = currentLocation ?: return
        val destination = randomDestination ?: generateRandomPoint(origin, radiusKm)

        isDriveActive = true
        navigateButton.isEnabled = false
        randomizeButton.isEnabled = false
        stopDriveButton.visibility = View.VISIBLE
        turnListPanel.visibility = View.VISIBLE
        turn1Text.text = "Finding a route…"
        turn2Text.visibility = View.GONE
        turn3Text.visibility = View.GONE

        routeFetchInProgress = true
        Thread {
            val result = OsrmClient.fetchRoute(origin, destination)
            runOnUiThread {
                routeFetchInProgress = false
                when (result) {
                    is RouteResult.Success -> {
                        applyNewRoute(result.route, announce = true)
                        startDriveLocationUpdates()
                    }
                    is RouteResult.Failure -> {
                        Toast.makeText(this, "Couldn't fetch a route: ${result.reason}", Toast.LENGTH_LONG).show()
                        stopDrive()
                    }
                }
            }
        }.start()
    }

    private fun stopDrive() {
        isDriveActive = false
        stopDriveLocationUpdates()
        mapPolyline?.remove()
        mapPolyline = null
        drivingRoute = null
        currentStepIndex = 0
        turnListPanel.visibility = View.GONE
        stopDriveButton.visibility = View.GONE
        navigateButton.isEnabled = randomDestination != null
        randomizeButton.isEnabled = true
    }

    private fun startDriveLocationUpdates() {
        if (!hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()
        fusedLocationClient.requestLocationUpdates(request, driveLocationCallback, mainLooper)
    }

    private fun stopDriveLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(driveLocationCallback)
    }

    private fun onDriveLocationUpdate(location: Location) {
        val here = LatLng(location.latitude, location.longitude)
        currentLocation = here
        if (::map.isInitialized) {
            map.animateCamera(CameraUpdateFactory.newLatLng(here))
        }

        val route = drivingRoute ?: return
        if (routeFetchInProgress) return

        // Off-route (missed the turn, or turned off on purpose) -> fresh random route
        if (minDistanceToPolyline(here, route.polyline) > DEVIATION_METERS) {
            fetchNewRandomRoute(here, toastMessage = "Off route — new random direction!")
            return
        }

        val step = route.steps.getOrNull(currentStepIndex) ?: return
        val distanceToStep = distanceMeters(here, step.location)

        if (distanceToStep <= ARRIVAL_METERS) {
            if (step.maneuverType == "arrive" || currentStepIndex >= route.steps.size - 1) {
                fetchNewRandomRoute(here, toastMessage = "Arrived! Picking a new direction…")
                return
            }
            currentStepIndex++
            hasWarnedForCurrentStep = false
            speak(route.steps[currentStepIndex].instruction)
            updateTurnListUi()
        } else if (!hasWarnedForCurrentStep && distanceToStep <= WARNING_METERS) {
            hasWarnedForCurrentStep = true
            speak("In ${distanceToStep.toInt()} meters, ${step.instruction}")
        }

        // Running low on upcoming turns -> quietly queue up a continuation
        if (route.steps.size - currentStepIndex <= LOW_STEPS_THRESHOLD) {
            fetchNewRandomRoute(here, toastMessage = null)
        }
    }

    private fun fetchNewRandomRoute(origin: LatLng, toastMessage: String?) {
        if (routeFetchInProgress) return
        routeFetchInProgress = true
        val destination = generateRandomPoint(origin, radiusKm)
        Thread {
            val result = OsrmClient.fetchRoute(origin, destination)
            runOnUiThread {
                routeFetchInProgress = false
                when (result) {
                    is RouteResult.Success -> {
                        applyNewRoute(result.route, announce = toastMessage != null)
                        if (toastMessage != null) {
                            Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
                        }
                    }
                    is RouteResult.Failure -> {
                        // Surface it once (arrival/deviation case) but stay quiet on
                        // routine proactive re-fetches — we'll just retry on the next tick.
                        if (toastMessage != null) {
                            Toast.makeText(this, "Reroute failed: ${result.reason}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }.start()
    }

    private fun applyNewRoute(route: DrivingRoute, announce: Boolean) {
        drivingRoute = route
        currentStepIndex = 0
        hasWarnedForCurrentStep = false
        drawRoutePolyline(route.polyline)
        updateTurnListUi()
        if (announce) {
            speak(route.steps.firstOrNull()?.instruction ?: "Let's go")
        }
    }

    private fun drawRoutePolyline(points: List<LatLng>) {
        mapPolyline?.remove()
        mapPolyline = map.addPolyline(PolylineOptions().addAll(points).width(8f))
        if (points.isNotEmpty()) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 16f))
        }
    }

    private fun updateTurnListUi() {
        val route = drivingRoute ?: return
        val upcoming = route.steps.drop(currentStepIndex).take(3)
        turn1Text.text = upcoming.getOrNull(0)?.instruction ?: ""
        turn2Text.text = upcoming.getOrNull(1)?.instruction ?: ""
        turn3Text.text = upcoming.getOrNull(2)?.instruction ?: ""
        turn2Text.visibility = if (upcoming.size > 1) View.VISIBLE else View.GONE
        turn3Text.visibility = if (upcoming.size > 2) View.VISIBLE else View.GONE
    }

    private fun minDistanceToPolyline(point: LatLng, polyline: List<LatLng>): Double {
        var min = Double.MAX_VALUE
        for (p in polyline) {
            val d = distanceMeters(point, p)
            if (d < min) min = d
        }
        return min
    }

    private fun speak(text: String) {
        if (ttsReady && text.isNotBlank()) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "randomdrive_tts")
        }
    }

    // ---------- Nearest toilet (still hands off to Google Maps — a single
    // real destination is better served by full turn-by-turn + voice) ----------

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
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
        return results[0].toDouble()
    }
}
