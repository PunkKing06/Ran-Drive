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
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        // How far off the planned path counts as "left the suggestion" —
        // expected/normal here, not an error, since the path is just one
        // random suggestion, not a route you're required to follow.
        private const val DEVIATION_METERS = 60.0
        // How close to a turn's location counts as having reached it.
        private const val ARRIVAL_METERS = 30.0
        // Distance out at which we speak an early warning for the upcoming turn.
        private const val WARNING_METERS = 150.0
        // Once fewer than this many steps remain, queue a continuation.
        private const val LOW_STEPS_THRESHOLD = 3
        // Refetch the local road graph once we've wandered this fraction of
        // the way to the edge of what was originally fetched.
        private const val REFETCH_FRACTION = 0.7
    }

    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var radiusLabel: TextView
    private lateinit var controlsPanel: View
    private lateinit var navigateButton: MaterialButton
    private lateinit var toiletButton: MaterialButton
    private lateinit var stopDriveButton: MaterialButton
    private lateinit var turnListPanel: View
    private lateinit var turn1Text: TextView
    private lateinit var turn2Text: TextView
    private lateinit var turn3Text: TextView

    private var currentLocation: LatLng? = null
    private var radiusKm = 3.0

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    // In-app random-walk drive state
    private var isDriveActive = false
    private var roadGraph: RoadGraph? = null
    private var currentPath: RandomPath? = null
    private var currentStepIndex = 0
    private var routeFetchInProgress = false
    private var hasWarnedForCurrentStep = false
    private var mapPolyline: Polyline? = null
    private var lastKnownBearing = 0f

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
        controlsPanel = findViewById(R.id.controls)
        val radiusSeekBar = findViewById<SeekBar>(R.id.radiusSeekBar)
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
                radiusLabel.text = "Explore radius: ${radiusKm.toInt()} km"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

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

    // ---------- Starting / stopping a random drive ----------

    private fun startDrive() {
        val origin = currentLocation
        if (origin == null) {
            Toast.makeText(this, "Still finding your location — try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }

        isDriveActive = true
        controlsPanel.visibility = View.GONE
        stopDriveButton.visibility = View.VISIBLE
        turnListPanel.visibility = View.VISIBLE
        turn1Text.text = "Scouting nearby roads…"
        turn2Text.visibility = View.GONE
        turn3Text.visibility = View.GONE

        val radiusMeters = (radiusKm * 1000).toInt().coerceAtLeast(500)
        routeFetchInProgress = true
        Thread {
            val graph = OsmRoadGraph.fetchGraph(origin, radiusMeters)
            runOnUiThread {
                routeFetchInProgress = false
                if (graph == null) {
                    Toast.makeText(
                        this, "Couldn't load nearby roads — check your connection and try again.", Toast.LENGTH_LONG
                    ).show()
                    stopDrive()
                    return@runOnUiThread
                }
                roadGraph = graph
                val nearest = graph.nearestNode(origin)
                if (nearest == null) {
                    Toast.makeText(this, "No mapped roads found nearby.", Toast.LENGTH_LONG).show()
                    stopDrive()
                    return@runOnUiThread
                }
                val path = OsmRoadGraph.buildRandomPath(graph, nearest, cameFrom = null)
                if (path.steps.isEmpty()) {
                    Toast.makeText(this, "Couldn't find a path from here — try a bigger explore radius.", Toast.LENGTH_LONG).show()
                    stopDrive()
                    return@runOnUiThread
                }
                applyNewPath(path, announce = true)
                startDriveLocationUpdates()
            }
        }.start()
    }

    private fun stopDrive() {
        isDriveActive = false
        stopDriveLocationUpdates()
        mapPolyline?.remove()
        mapPolyline = null
        roadGraph = null
        currentPath = null
        currentStepIndex = 0
        turnListPanel.visibility = View.GONE
        stopDriveButton.visibility = View.GONE
        controlsPanel.visibility = View.VISIBLE
    }

    private fun startDriveLocationUpdates() {
        if (!hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1500L)
            .build()
        fusedLocationClient.requestLocationUpdates(request, driveLocationCallback, mainLooper)
    }

    private fun stopDriveLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(driveLocationCallback)
    }

    // ---------- Live driving loop ----------

    private fun onDriveLocationUpdate(location: Location) {
        val here = LatLng(location.latitude, location.longitude)
        currentLocation = here
        if (::map.isInitialized) updateNavCamera(location, here)

        val graph = roadGraph ?: return
        val path = currentPath ?: return
        if (routeFetchInProgress) return

        // Wandered near the edge of the fetched area -> pull a fresh graph around here
        if (distanceMetersBetween(here, graph.fetchCenter) > graph.fetchRadiusMeters * REFETCH_FRACTION) {
            refetchGraphAndContinue(here)
            return
        }

        if (path.steps.isEmpty()) {
            continueRandomPath(here)
            return
        }

        // Off the suggested path is normal here (it's a suggestion, not a
        // required route) -> just quietly continue randomly from here.
        if (minDistanceToPolyline(here, path.polyline) > DEVIATION_METERS) {
            continueRandomPath(here)
            return
        }

        val step = path.steps.getOrNull(currentStepIndex)
        if (step == null) {
            continueRandomPath(here)
            return
        }
        val distanceToStep = distanceMetersBetween(here, step.location)

        if (distanceToStep <= ARRIVAL_METERS) {
            currentStepIndex++
            hasWarnedForCurrentStep = false
            val nextStep = path.steps.getOrNull(currentStepIndex)
            if (nextStep != null) {
                speak(nextStep.instruction)
            }
            updateTurnListUi()
        } else if (!hasWarnedForCurrentStep && distanceToStep <= WARNING_METERS) {
            hasWarnedForCurrentStep = true
            speak("In ${distanceToStep.toInt()} meters, ${step.instruction}")
        }

        if (path.steps.size - currentStepIndex <= LOW_STEPS_THRESHOLD) {
            continueRandomPath(here)
        }
    }

    private fun continueRandomPath(here: LatLng) {
        val graph = roadGraph ?: return
        if (routeFetchInProgress) return
        routeFetchInProgress = true
        Thread {
            val nearest = graph.nearestNode(here)
            val path = if (nearest != null) OsmRoadGraph.buildRandomPath(graph, nearest, cameFrom = null) else null
            runOnUiThread {
                routeFetchInProgress = false
                if (path != null && path.steps.isNotEmpty()) {
                    applyNewPath(path, announce = false)
                }
            }
        }.start()
    }

    private fun refetchGraphAndContinue(here: LatLng) {
        if (routeFetchInProgress) return
        routeFetchInProgress = true
        val radiusMeters = (radiusKm * 1000).toInt().coerceAtLeast(500)
        Thread {
            val graph = OsmRoadGraph.fetchGraph(here, radiusMeters)
            runOnUiThread {
                routeFetchInProgress = false
                if (graph != null) {
                    roadGraph = graph
                    val nearest = graph.nearestNode(here)
                    if (nearest != null) {
                        val path = OsmRoadGraph.buildRandomPath(graph, nearest, cameFrom = null)
                        if (path.steps.isNotEmpty()) {
                            applyNewPath(path, announce = false)
                        }
                    }
                }
            }
        }.start()
    }

    private fun applyNewPath(path: RandomPath, announce: Boolean) {
        currentPath = path
        currentStepIndex = 0
        hasWarnedForCurrentStep = false
        drawRoutePolyline(path.polyline)
        updateTurnListUi()
        if (announce) {
            speak(path.steps.firstOrNull()?.instruction ?: "Let's go")
        }
    }

    /** Close, tilted, direction-following camera — like a real nav app's driving view. */
    private fun updateNavCamera(location: Location, here: LatLng) {
        val bearing: Float = if (location.hasBearing() && location.speed > 1.5f) {
            location.bearing
        } else {
            val nextPoint = currentPath?.polyline?.getOrNull(1)
            if (nextPoint != null) bearingBetween(here, nextPoint).toFloat() else lastKnownBearing
        }
        lastKnownBearing = bearing

        val cameraPosition = CameraPosition.Builder()
            .target(here)
            .zoom(18f)
            .tilt(65f)
            .bearing(bearing)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 600, null)
    }

    private fun drawRoutePolyline(points: List<LatLng>) {
        mapPolyline?.remove()
        mapPolyline = map.addPolyline(PolylineOptions().addAll(points).width(8f))
    }

    private fun updateTurnListUi() {
        val path = currentPath ?: return
        val upcoming = path.steps.drop(currentStepIndex).take(3)
        turn1Text.text = upcoming.getOrNull(0)?.instruction ?: ""
        turn2Text.text = upcoming.getOrNull(1)?.instruction ?: ""
        turn3Text.text = upcoming.getOrNull(2)?.instruction ?: ""
        turn2Text.visibility = if (upcoming.size > 1) View.VISIBLE else View.GONE
        turn3Text.visibility = if (upcoming.size > 2) View.VISIBLE else View.GONE
    }

    private fun minDistanceToPolyline(point: LatLng, polyline: List<LatLng>): Double {
        var min = Double.MAX_VALUE
        for (p in polyline) {
            val d = distanceMetersBetween(point, p)
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

                val distance = distanceMetersBetween(origin, point)
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
}
