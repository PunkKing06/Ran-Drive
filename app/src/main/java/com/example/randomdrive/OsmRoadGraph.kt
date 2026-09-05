package com.example.randomdrive

import com.google.android.gms.maps.model.LatLng
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class PlannedStep(
    val instruction: String,
    val location: LatLng,
    val legDistanceMeters: Double
)

data class RandomPath(
    val steps: List<PlannedStep>,
    val polyline: List<LatLng>
)

class RoadGraph(
    val nodePositions: Map<Long, LatLng>,
    val adjacency: Map<Long, List<Long>>,
    private val edgeStreetName: Map<Long, String>,
    val fetchCenter: LatLng,
    val fetchRadiusMeters: Int
) {
    /** Straight-line nearest node to [point]. Fine at this scale — graphs are a few thousand nodes at most. */
    fun nearestNode(point: LatLng): Long? {
        var bestId: Long? = null
        var bestDist = Double.MAX_VALUE
        for ((id, pos) in nodePositions) {
            val d = distanceMetersBetween(point, pos)
            if (d < bestDist) {
                bestDist = d
                bestId = id
            }
        }
        return bestId
    }

    fun streetNameFor(from: Long, to: Long): String = edgeStreetName[edgeKey(from, to)] ?: "the road"
}

private fun edgeKey(a: Long, b: Long): Long = a * 1_000_003L + b

fun distanceMetersBetween(a: LatLng, b: LatLng): Double {
    val results = FloatArray(1)
    android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
    return results[0].toDouble()
}

fun bearingBetween(a: LatLng, b: LatLng): Double {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/**
 * Projects [p] onto the closest point that actually lies ON the given
 * [polyline] (checking every segment, not just the nearest vertex), using a
 * flat planar approximation local to the segment — accurate enough at
 * street scale. This is what keeps the car marker centered on the road
 * itself rather than showing raw, sometimes-noisy GPS position — useful
 * indoors or anywhere GPS drifts off the actual street.
 */
fun snapToPolyline(p: LatLng, polyline: List<LatLng>): LatLng {
    if (polyline.isEmpty()) return p
    if (polyline.size == 1) return polyline[0]

    var best = polyline[0]
    var bestDist = Double.MAX_VALUE
    for (i in 0 until polyline.size - 1) {
        val projected = projectOntoSegment(p, polyline[i], polyline[i + 1])
        val d = distanceMetersBetween(p, projected)
        if (d < bestDist) {
            bestDist = d
            best = projected
        }
    }
    return best
}

private fun projectOntoSegment(p: LatLng, a: LatLng, b: LatLng): LatLng {
    // Local planar approximation: scale longitude by cos(latitude) so x/y
    // are in comparable units near this segment, project, then unscale.
    val latRef = Math.toRadians(a.latitude)
    val k = cos(latRef).let { if (it == 0.0) 1.0 else it }

    val ax = a.longitude * k; val ay = a.latitude
    val bx = b.longitude * k; val by = b.latitude
    val px = p.longitude * k; val py = p.latitude

    val dx = bx - ax
    val dy = by - ay
    val lengthSq = dx * dx + dy * dy
    if (lengthSq == 0.0) return a

    var t = ((px - ax) * dx + (py - ay) * dy) / lengthSq
    t = t.coerceIn(0.0, 1.0)

    val projX = ax + t * dx
    val projY = ay + t * dy
    return LatLng(projY, projX / k)
}


/**
 * Fetches the real local street network from OpenStreetMap (via the free
 * Overpass API — same data source already used for the toilet finder) and
 * does a genuine random walk across it: at every intersection, it picks a
 * real connected road at random (never a paid routing engine computing
 * "the best route to point X"). That's what makes this actually random
 * turn by turn instead of one deterministic path to a destination.
 */
object OsmRoadGraph {

    fun fetchGraph(center: LatLng, radiusMeters: Int): RoadGraph? {
        return try {
            val query = "[out:json][timeout:20];" +
                "way[\"highway\"~\"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|" +
                "secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|service)$\"]" +
                "(around:$radiusMeters,${center.latitude},${center.longitude});(._;>;);out body;"
            val url = URL("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8"))
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 12000
            connection.readTimeout = 20000
            connection.requestMethod = "GET"

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val elements = JSONObject(responseText).getJSONArray("elements")
            val nodePositions = HashMap<Long, LatLng>()

            data class WayInfo(val nodeIds: List<Long>, val name: String, val oneway: Int)
            val ways = ArrayList<WayInfo>()

            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                when (el.optString("type")) {
                    "node" -> {
                        nodePositions[el.getLong("id")] = LatLng(el.getDouble("lat"), el.getDouble("lon"))
                    }
                    "way" -> {
                        val nodesArray = el.optJSONArray("nodes") ?: continue
                        val ids = ArrayList<Long>(nodesArray.length())
                        for (j in 0 until nodesArray.length()) ids.add(nodesArray.getLong(j))
                        val tags = el.optJSONObject("tags")
                        val name = tags?.optString("name", "") ?: ""
                        val onewayTag = tags?.optString("oneway", "") ?: ""
                        val oneway = when (onewayTag) {
                            "yes", "true", "1" -> 1
                            "-1" -> -1
                            else -> 0
                        }
                        ways.add(WayInfo(ids, name, oneway))
                    }
                }
            }

            val adjacency = HashMap<Long, MutableList<Long>>()
            val edgeStreetName = HashMap<Long, String>()

            fun addDirectedEdge(from: Long, to: Long, name: String) {
                if (from == to) return
                if (!nodePositions.containsKey(from) || !nodePositions.containsKey(to)) return
                adjacency.getOrPut(from) { mutableListOf() }.add(to)
                if (name.isNotBlank()) edgeStreetName[edgeKey(from, to)] = name
            }

            for (way in ways) {
                for (k in 0 until way.nodeIds.size - 1) {
                    val n1 = way.nodeIds[k]
                    val n2 = way.nodeIds[k + 1]
                    when (way.oneway) {
                        1 -> addDirectedEdge(n1, n2, way.name)
                        -1 -> addDirectedEdge(n2, n1, way.name)
                        else -> {
                            addDirectedEdge(n1, n2, way.name)
                            addDirectedEdge(n2, n1, way.name)
                        }
                    }
                }
            }

            if (nodePositions.isEmpty() || adjacency.isEmpty()) null
            else RoadGraph(nodePositions, adjacency, edgeStreetName, center, radiusMeters)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Walks forward from [startNode], picking a random connected road at
     * every intersection (avoiding an immediate U-turn back the way we
     * came whenever another option exists), for [desiredHops] intersections.
     */
    fun buildRandomPath(graph: RoadGraph, startNode: Long, cameFrom: Long?, desiredHops: Int = 12): RandomPath {
        val nodeSequence = mutableListOf(startNode)
        var prev = cameFrom
        var current = startNode

        repeat(desiredHops) {
            val neighbors = graph.adjacency[current].orEmpty()
            if (neighbors.isEmpty()) return@repeat
            val nonBacktrack = neighbors.filter { it != prev }
            val choices = if (nonBacktrack.isNotEmpty()) nonBacktrack else neighbors
            val next = choices[Random.nextInt(choices.size)]
            nodeSequence.add(next)
            prev = current
            current = next
        }

        val polyline = nodeSequence.mapNotNull { graph.nodePositions[it] }
        val steps = mutableListOf<PlannedStep>()
        for (i in 1 until nodeSequence.size - 1) {
            val a = graph.nodePositions[nodeSequence[i - 1]] ?: continue
            val b = graph.nodePositions[nodeSequence[i]] ?: continue
            val c = graph.nodePositions[nodeSequence[i + 1]] ?: continue
            val inBearing = bearingBetween(a, b)
            val outBearing = bearingBetween(b, c)
            val streetName = graph.streetNameFor(nodeSequence[i], nodeSequence[i + 1])
            steps.add(PlannedStep(turnInstruction(inBearing, outBearing, streetName), b, distanceMetersBetween(a, b)))
        }

        return RandomPath(steps, polyline)
    }

    private fun turnInstruction(inBearing: Double, outBearing: Double, streetName: String): String {
        var diff = outBearing - inBearing
        diff = ((diff + 540) % 360) - 180 // normalize to [-180, 180]; positive = turning right
        val absDiff = abs(diff)
        return when {
            absDiff < 20 -> "Continue straight onto $streetName"
            absDiff < 70 -> if (diff > 0) "Veer right onto $streetName" else "Veer left onto $streetName"
            absDiff < 150 -> if (diff > 0) "Turn right onto $streetName" else "Turn left onto $streetName"
            else -> if (diff > 0) "Make a sharp right onto $streetName" else "Make a sharp left onto $streetName"
        }
    }
}
