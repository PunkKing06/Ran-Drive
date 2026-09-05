package com.example.randomdrive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
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
import android.view.Gravity
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
import com.google.android.gms.maps.model.MapStyleOptions
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
import kotlin.math.cos
import kotlin.math.ln

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val DEVIATION_METERS = 60.0
        private const val ARRIVAL_METERS = 30.0
        private const val WARNING_METERS = 150.0
        private const val LOW_STEPS_THRESHOLD = 3
        private const val REFETCH_FRACTION = 0.7
        private const val CAMERA_TICK_MS = 250L
    }

    private val carOptions: List<Pair<Int, String>> by lazy {
        listOf(
            Color.parseColor("#1A73E8") to "Sedan",
            Color.parseColor("#263238") to "SUV",
            Color.parseColor("#D32F2F") to "Sports Car",
            Color.parseColor("#FBC02D") to "Taxi",
            Color.parseColor("#37474F") to "Police Car",
            Color.parseColor("#607D8B") to "Pickup Truck"
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
    private val glowPolylines = mutableListOf<Polyline>()

    // Car avatar + camera
    private var carMarker: Marker? = null
    private var selectedCarColor = Color.parseColor("#1A73E8")
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
            currentLocation?.let { rawHere ->
                val path = currentPath
                // Snap to the actual road line so the car sits centered on
                // the street regardless of GPS noise (indoors, multipath, etc).
                val displayHere = if (path != null && path.polyline.size >= 2) {
                    snapToPolyline(rawHere, path.polyline)
                } else rawHere

                updateCarMarker(displayHere, deviceAzimuth)
                if (followingCamera) {
                    map.animateCamera(
                        CameraUpdateFactory.newCameraPosition(computeNavCameraPosition(displayHere, deviceAzimuth)),
                        CAMERA_TICK_MS.toInt(),
                        null
                    )
                }
            }
            if (isDriveActive) cameraUpdateHandler.postDelayed(this, CAMERA_TICK_MS)
        }
    }

    /**
     * Flat (no tilt), heading-rotated camera, zoomed and offset so the car
     * sits in the lower part of the screen with a look-ahead view toward
     * the upcoming turn — rather than centering exactly on the car, which
     * is how most nav apps actually achieve that look: the geographic
     * *target* is a point pushed forward along the heading, not the car's
     * own position.
     */
    private fun computeNavCameraPosition(carPosition: LatLng, bearing: Float): CameraPosition {
        val path = currentPath
        val lookaheadStep = path?.steps?.getOrNull(currentStepIndex + 1) ?: path?.steps?.getOrNull(currentStepIndex)
        val rawLookaheadMeters = lookaheadStep?.let { distanceMetersBetween(carPosition, it.location) } ?: 250.0
        val lookaheadMeters = rawLookaheadMeters.coerceIn(80.0, 600.0)

        // Aim for the lookahead distance spanning roughly half the screen height.
        val zoom = computeZoomForSpan(carPosition.latitude, lookaheadMeters / 0.5).coerceIn(15f, 20f)
        val forwardOffsetMeters = lookaheadMeters * 0.45
        val target = offsetPoint(carPosition, bearing, forwardOffsetMeters)

        return CameraPosition.Builder()
            .target(target)
            .zoom(zoom)
            .tilt(0f)
            .bearing(bearing)
            .build()
    }

    /** Zoom level at which [spanMeters] spans the full screen height at [latitude]. */
    private fun computeZoomForSpan(latitude: Double, spanMeters: Double): Float {
        val screenHeightPx = resources.displayMetrics.heightPixels.toDouble().coerceAtLeast(1.0)
        val metersPerPixelWanted = (spanMeters / screenHeightPx).coerceAtLeast(0.01)
        val zoom = ln(156543.03392 * cos(Math.toRadians(latitude)) / metersPerPixelWanted) / ln(2.0)
        return zoom.toFloat()
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
        for ((color, label) in carOptions) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4, 20, 4, 20)
                isClickable = true
                isFocusable = true
            }
            val swatch = View(this).apply {
                val size = dpToPx(28)
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            }
            val labelView = TextView(this).apply {
                text = label
                textSize = 16f
                setPadding(dpToPx(12), 0, 0, 0)
            }
            row.addView(swatch)
            row.addView(labelView)
            row.setOnClickListener {
                selectedCarColor = color
                carMarker?.setIcon(BitmapDescriptorFactory.fromBitmap(carBitmap(color)))
                drawerLayout.closeDrawer(GravityCompat.START)
            }
            carOptionsContainer.addView(row)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /**
     * Draws a shaded, correctly-oriented car icon (front = top of the
     * bitmap, matching marker.rotation = heading) rather than a rotating
     * emoji glyph — proper body shape, a light-to-dark gradient for a
     * glossy look, headlights/taillights so the front is unambiguous, and
     * a soft blurred drop shadow underneath. The shadow is what actually
     * sells the "sitting above the ground" look once the tilted camera
     * renders this as a flat ground-anchored marker — the same basic trick
     * real nav-app pucks use. It's a flat drawing, not a true 3D model —
     * the public Maps SDK for Android has no API for the latter (see README).
     */
    private fun carBitmap(bodyColor: Int, sizePx: Int = 230): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val carWidth = sizePx * 0.42f
        val carHeight = sizePx * 0.72f
        val corner = carWidth * 0.35f

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 0, 0)
            maskFilter = BlurMaskFilter(sizePx * 0.08f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawOval(
            RectF(cx - carWidth * 0.55f, cy - carHeight * 0.28f, cx + carWidth * 0.55f, cy + carHeight * 0.62f),
            shadowPaint
        )

        val bodyRect = RectF(cx - carWidth / 2f, cy - carHeight / 2f, cx + carWidth / 2f, cy + carHeight / 2f)

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                bodyRect.left, bodyRect.top, bodyRect.right, bodyRect.bottom,
                lightenColor(bodyColor, 0.35f), darkenColor(bodyColor, 0.25f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(bodyRect, corner, corner, bodyPaint)

        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.02f
            color = Color.argb(120, 0, 0, 0)
        }
        canvas.drawRoundRect(bodyRect, corner, corner, outlinePaint)

        // Windshield near the "front" (top of the bitmap) — matches the
        // rotation=bearing convention so it always faces the direction of travel.
        val windshieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 30, 40, 55) }
        val windshieldRect = RectF(
            bodyRect.left + carWidth * 0.14f, bodyRect.top + carHeight * 0.12f,
            bodyRect.right - carWidth * 0.14f, bodyRect.top + carHeight * 0.38f
        )
        canvas.drawRoundRect(windshieldRect, corner * 0.6f, corner * 0.6f, windshieldPaint)

        val lightRadius = carWidth * 0.09f
        val headlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF9C4") }
        canvas.drawCircle(bodyRect.left + lightRadius * 1.3f, bodyRect.top + lightRadius * 1.3f, lightRadius, headlightPaint)
        canvas.drawCircle(bodyRect.right - lightRadius * 1.3f, bodyRect.top + lightRadius * 1.3f, lightRadius, headlightPaint)

        val taillightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }
        canvas.drawCircle(bodyRect.left + lightRadius * 1.3f, bodyRect.bottom - lightRadius * 1.3f, lightRadius * 0.8f, taillightPaint)
        canvas.drawCircle(bodyRect.right - lightRadius * 1.3f, bodyRect.bottom - lightRadius * 1.3f, lightRadius * 0.8f, taillightPaint)

        return bitmap
    }

    private fun lightenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * (1 - factor)).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1 - factor)).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1 - factor)).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun updateCarMarker(position: LatLng, bearing: Float) {
        val marker = carMarker
        if (marker == null) {
            carMarker = map.addMarker(
                MarkerOptions()
                    .position(position)
                    .icon(BitmapDescriptorFactory.fromBitmap(carBitmap(selectedCarColor)))
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
        currentLocation?.let { rawHere ->
            val path = currentPath
            val displayHere = if (path != null && path.polyline.size >= 2) snapToPolyline(rawHere, path.polyline) else rawHere
            map.animateCamera(CameraUpdateFactory.newCameraPosition(computeNavCameraPosition(displayHere, deviceAzimuth)))
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
        map.setMapStyle(MapStyleOptions(DARK_MAP_STYLE))
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
        glowPolylines.forEach { it.remove() }
        glowPolylines.clear()
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
        if (::map.isInitialized) map.setMapStyle(null)
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
        mapPolyline = null
        glowPolylines.forEach { it.remove() }
        glowPolylines.clear()

        // Fake a neon glow: several wide, increasingly transparent lines
        // stacked under a bright, narrow core — Polyline has no real blur,
        // so this layering is what actually reads as "glowing" on screen.
        val coreColor = Color.parseColor("#40C4FF")
        val red = Color.red(coreColor)
        val green = Color.green(coreColor)
        val blue = Color.blue(coreColor)
        val haloLayers = listOf(56f to 35, 42f to 80, 30f to 150)

        for ((width, alpha) in haloLayers) {
            glowPolylines.add(
                map.addPolyline(
                    PolylineOptions()
                        .addAll(points)
                        .color(Color.argb(alpha, red, green, blue))
                        .width(width)
                        .jointType(JointType.ROUND)
                        .startCap(RoundCap())
                        .endCap(RoundCap())
                )
            )
        }

        mapPolyline = map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .color(coreColor)
                .width(18f)
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
