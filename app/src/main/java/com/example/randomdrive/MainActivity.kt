package com.example.randomdrive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Surface
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
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
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val DEVIATION_METERS = 60.0
        private const val ARRIVAL_METERS = 30.0
        private const val WARNING_METERS = 150.0
        private const val LOW_STEPS_THRESHOLD = 3
        private const val REFETCH_FRACTION = 0.7
        private const val CAMERA_TICK_MS = 250L

        private val CAR_OPTIONS = listOf(
            "🚗" to "Sedan",
            "🚙" to "SUV",
            "🏎️" to "Sports Car",
            "🚕" to "Taxi",
            "🚓" to "Police Car",
            "🛻" to "Pickup Truck"
        )
    }

    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var radiusLabel: TextView
    private lateinit var controlsPanel: View
    private lateinit var navigateButton: MaterialButton
    private lateinit var stopDriveButton: MaterialButton
    private lateinit var hamburgerButton: MaterialButton
    private lateinit var recenterButton: MaterialButton
    private lateinit var muteButton: MaterialButton
    private lateinit var carOptionsContainer: LinearLayout
    private lateinit var toiletMenuItem: TextView
    private lateinit var turnListPanel: View
    private lateinit var turn1Text: TextView
    private lateinit var turn2Text: TextView
    private lateinit var turn3Text: TextView

    private var currentLocation: LatLng? = null
    private var radiusKm = 3.0

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var voiceEnabled = true

    // In-app random-walk drive state
    private var isDriveActive = false
    private var roadGraph: RoadGraph? = null
    private var currentPath: RandomPath? = null
    private var currentStepIndex = 0
    private var routeFetchInProgress = false
    private var hasWarnedForCurrentStep = false
    private var mapPolyline: Polyline? = null
    private var mapPolylineOutline: Polyline? = null

    // Car avatar + camera
    private var carMarker: Marker? = null
    private var selectedCarEmoji = "🚗"
    private var followingCamera = true
    private var deviceAzimuth = 0f

    private val locationPermissionRequestCode = 1001

    private val driveLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onDriveLocationUpdate(it) }
        }
    }

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

            @Suppress("DEPRECATION")
            val rotation = windowManager.defaultDisplay.rotation
            val (worldAxisX, worldAxisZ) = when (rotation) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            val adjustedMatrix = FloatArray(9)
            SensorManager.remapCoordinateSystem(rotationMatrix, worldAxisX, worldAxisZ, adjustedMatrix)

            val orientation = FloatArray(3)
            SensorManager.getOrientation(adjustedMatrix, orientation)
            deviceAzimuth = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // Drives the car marker + camera at a smooth, steady rate — decoupled
    // from GPS location ticks (which only arrive every second or two) so
    // rotation tracks the phone's facing direction responsively.
    private val cameraUpdateHandler = Handler(Looper.getMainLooper())
    private val cameraUpdateRunnable = object : Runnable {
        override fun run() {
            currentLocation?.let { here ->
                updateCarMarker(here, deviceAzimuth)
                if (followingCamera) {
                    val cameraPosition = CameraPosition.Builder()
                        .target(here)
                        .zoom(18f)
                        .tilt(65f)
                        .bearing(deviceAzimuth)
                        .build()
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), CAMERA_TICK_MS.toInt(), null)
                }
            }
            if (isDriveActive) cameraUpdateHandler.postDelayed(this, CAMERA_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        drawerLayout = findViewById(R.id.drawerLayout)
        radiusLabel = findViewById(R.id.radiusLabel)
        controlsPanel = findViewById(R.id.controls)
        val radiusSeekBar = findViewById<SeekBar>(R.id.radiusSeekBar)
        navigateButton = findViewById(R.id.navigateButton)
        stopDriveButton = findViewById(R.id.stopDriveButton)
        hamburgerButton = findViewById(R.id.hamburgerButton)
        recenterButton = findViewById(R.id.recenterButton)
        muteButton = findViewById(R.id.muteButton)
        carOptionsContainer = findViewById(R.id.carOptionsContainer)
        toiletMenuItem = findViewById(R.id.toiletMenuItem)
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
        hamburgerButton.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        recenterButton.setOnClickListener { recenterCamera() }
        muteButton.setOnClickListener { toggleVoice() }
        toiletMenuItem.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            findNearestToiletAndNavigate()
        }

        setupCarOptionsMenu()
        requestNeededPermissions()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                followingCamera = false
                if (isDriveActive) recenterButton.visibility = View.VISIBLE
            }
        }
        if (hasLocationPermission()) {
            map.isMyLocationEnabled = true
            fetchCurrentLocation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isDriveActive) {
            stopDriveLocationUpdates()
            sensorManager.unregisterListener(sensorEventListener)
            cameraUpdateHandler.removeCallbacks(cameraUpdateRunnable)
        }
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

    // ---------- Hamburger drawer: car avatar picker ----------

    private fun setupCarOptionsMenu() {
        carOptionsContainer.removeAllViews()
        for ((emoji, label) in CAR_OPTIONS) {
            val row = TextView(this).apply {
                text = "$emoji  $label"
                textSize = 16f
                setPadding(4, 20, 4, 20)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedCarEmoji = emoji
                    carMarker?.setIcon(BitmapDescriptorFactory.fromBitmap(emojiToBitmap(emoji)))
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
            }
            carOptionsContainer.addView(row)
        }
    }

    private fun emojiToBitmap(emoji: String, sizePx: Int = 140): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx * 0.75f
            textAlign = Paint.Align.CENTER
        }
        val metrics = paint.fontMetrics
        val yPos = sizePx / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(emoji, sizePx / 2f, yPos, paint)
        return bitmap
    }

    private fun updateCarMarker(position: LatLng, bearing: Float) {
        val marker = carMarker
        if (marker == null) {
            carMarker = map.addMarker(
                MarkerOptions()
                    .position(position)
                    .icon(BitmapDescriptorFactory.fromBitmap(emojiToBitmap(selectedCarEmoji)))
                    .anchor(0.5f, 0.5f)
                    .flat(true)
                    .rotation(bearing)
            )
        } else {
            marker.position = position
            marker.rotation = bearing
        }
    }

    // ---------- Recenter / mute ----------

    private fun recenterCamera() {
        followingCamera = true
        recenterButton.visibility = View.GONE
        currentLocation?.let { here ->
            val cameraPosition = CameraPosition.Builder()
                .target(here).zoom(18f).tilt(65f).bearing(deviceAzimuth).build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
        }
    }

    private fun toggleVoice() {
        voiceEnabled = !voiceEnabled
        muteButton.text = if (voiceEnabled) "🔊" else "🔇"
        if (!voiceEnabled) textToSpeech?.stop()
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
        muteButton.visibility = View.VISIBLE
        turnListPanel.visibility = View.VISIBLE
        turn1Text.text = "Scouting nearby roads…"
        turn2Text.visibility = View.GONE
        turn3Text.visibility = View.GONE
        map.isMyLocationEnabled = false
        followingCamera = true

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
                startCompassUpdates()
                cameraUpdateHandler.post(cameraUpdateRunnable)
            }
        }.start()
    }

    private fun stopDrive() {
        isDriveActive = false
        stopDriveLocationUpdates()
        sensorManager.unregisterListener(sensorEventListener)
        cameraUpdateHandler.removeCallbacks(cameraUpdateRunnable)
        mapPolyline?.remove()
        mapPolyline = null
        mapPolylineOutline?.remove()
        mapPolylineOutline = null
        carMarker?.remove()
        carMarker = null
        roadGraph = null
        currentPath = null
        currentStepIndex = 0
        turnListPanel.visibility = View.GONE
        stopDriveButton.visibility = View.GONE
        recenterButton.visibility = View.GONE
        muteButton.visibility = View.GONE
        controlsPanel.visibility = View.VISIBLE
        if (hasLocationPermission()) map.isMyLocationEnabled = true
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

    private fun startCompassUpdates() {
        val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(sensorEventListener, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    // ---------- Live driving loop ----------

    private fun onDriveLocationUpdate(location: Location) {
        val here = LatLng(location.latitude, location.longitude)
        currentLocation = here
        // Camera + car marker are driven by cameraUpdateRunnable, not here.

        val graph = roadGraph ?: return
        val path = currentPath ?: return
        if (routeFetchInProgress) return

        if (distanceMetersBetween(here, graph.fetchCenter) > graph.fetchRadiusMeters * REFETCH_FRACTION) {
            refetchGraphAndContinue(here)
            return
        }

        if (path.steps.isEmpty()) {
            continueRandomPath(here)
            return
        }

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

    private fun drawRoutePolyline(points: List<LatLng>) {
        mapPolyline?.remove()
        mapPolylineOutline?.remove()

        // Layered like Google Maps' route line: a soft light-blue outline
        // under a brighter blue core, both wide enough to sit over the road.
        mapPolylineOutline = map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .color(Color.parseColor("#B3C9F4"))
                .width(26f)
                .jointType(JointType.ROUND)
                .startCap(RoundCap())
                .endCap(RoundCap())
        )
        mapPolyline = map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .color(Color.parseColor("#4285F4"))
                .width(16f)
                .jointType(JointType.ROUND)
                .startCap(RoundCap())
                .endCap(RoundCap())
        )
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
        if (voiceEnabled && ttsReady && text.isNotBlank()) {
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
