package com.example.randomdrive

import com.google.android.gms.maps.model.LatLng
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RouteStep(
    val instruction: String,
    val location: LatLng,
    val maneuverType: String
)

data class DrivingRoute(
    val steps: List<RouteStep>,
    val polyline: List<LatLng>
)

/**
 * Fetches real, road-following driving routes with turn-by-turn steps from
 * OSRM's public demo server — free, no API key, built on OpenStreetMap data.
 *
 * Note: router.project-osrm.org is meant for light/personal use, not heavy
 * production traffic. Fine for one person's random-drive app; if it ever
 * feels slow or unreliable, the fix is self-hosting OSRM or pointing this
 * at a different OSRM-compatible instance (see README).
 */
object OsrmClient {

    fun fetchRoute(origin: LatLng, destination: LatLng): DrivingRoute? {
        return try {
            val url = URL(
                "https://router.project-osrm.org/route/v1/driving/" +
                    "${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}" +
                    "?steps=true&geometries=geojson&overview=full"
            )
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "GET"

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val json = JSONObject(responseText)
            if (json.optString("code") != "Ok") return null

            val route = json.getJSONArray("routes").getJSONObject(0)

            val coordsArray = route.getJSONObject("geometry").getJSONArray("coordinates")
            val polyline = ArrayList<LatLng>(coordsArray.length())
            for (i in 0 until coordsArray.length()) {
                val pair = coordsArray.getJSONArray(i)
                // GeoJSON order is [lon, lat]
                polyline.add(LatLng(pair.getDouble(1), pair.getDouble(0)))
            }

            val steps = ArrayList<RouteStep>()
            val legs = route.getJSONArray("legs")
            for (legIndex in 0 until legs.length()) {
                val stepsArray = legs.getJSONObject(legIndex).getJSONArray("steps")
                for (i in 0 until stepsArray.length()) {
                    val stepJson = stepsArray.getJSONObject(i)
                    val maneuver = stepJson.getJSONObject("maneuver")
                    val loc = maneuver.getJSONArray("location")
                    val point = LatLng(loc.getDouble(1), loc.getDouble(0))
                    val type = maneuver.optString("type", "continue")
                    val modifier = maneuver.optString("modifier", "")
                    val streetNameRaw = stepJson.optString("name", "")
                    val streetName = if (streetNameRaw.isBlank()) "the road" else streetNameRaw
                    steps.add(RouteStep(buildInstruction(type, modifier, streetName), point, type))
                }
            }

            if (steps.isEmpty() || polyline.isEmpty()) null else DrivingRoute(steps, polyline)
        } catch (e: Exception) {
            null
        }
    }

    private fun buildInstruction(type: String, modifier: String, streetName: String): String {
        return when (type) {
            "depart" -> "Head out toward $streetName"
            "arrive" -> "Arrive at your random destination"
            "roundabout", "rotary" -> "Enter the roundabout, then exit onto $streetName"
            "turn" -> when {
                modifier.contains("left") -> "Turn left onto $streetName"
                modifier.contains("right") -> "Turn right onto $streetName"
                modifier == "straight" -> "Continue straight onto $streetName"
                else -> "Turn onto $streetName"
            }
            "fork" -> when {
                modifier.contains("left") -> "Keep left toward $streetName"
                modifier.contains("right") -> "Keep right toward $streetName"
                else -> "Continue onto $streetName"
            }
            "merge" -> "Merge onto $streetName"
            else -> "Continue onto $streetName"
        }
    }
}
