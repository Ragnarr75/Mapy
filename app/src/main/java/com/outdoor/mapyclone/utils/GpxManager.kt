package com.outdoor.mapyclone.utils

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.outdoor.mapyclone.model.GpxTrack
import com.outdoor.mapyclone.model.TrackPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

object GpxManager {

    fun parseGpx(context: Context, uri: Uri, defaultName: String = "Traccia GPX"): GpxTrack? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parseStream(stream, defaultName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun parseStream(inputStream: InputStream, defaultName: String): GpxTrack {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        val points = mutableListOf<TrackPoint>()
        var trackName = defaultName
        var currentLat = 0.0
        var currentLon = 0.0
        var currentEle = 0.0
        var inTrkPt = false
        var inName = false
        var inEle = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (tagName) {
                        "trkpt", "rtept", "wpt" -> {
                            inTrkPt = true
                            currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            currentEle = 0.0
                        }
                        "ele" -> if (inTrkPt) inEle = true
                        "name" -> if (!inTrkPt) inName = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inEle) {
                        currentEle = parser.text?.toDoubleOrNull() ?: 0.0
                    } else if (inName && !parser.text.isNullOrBlank()) {
                        trackName = parser.text.trim()
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (tagName) {
                        "trkpt", "rtept", "wpt" -> {
                            if (currentLat != 0.0 || currentLon != 0.0) {
                                points.add(TrackPoint(currentLat, currentLon, currentEle))
                            }
                            inTrkPt = false
                        }
                        "ele" -> inEle = false
                        "name" -> inName = false
                    }
                }
            }
            eventType = parser.next()
        }
        return calculateStats(trackName, points)
    }

    fun calculateStats(name: String, points: List<TrackPoint>): GpxTrack {
        if (points.isEmpty()) {
            return GpxTrack(name, emptyList(), 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        var totalDist = 0.0
        var gain = 0.0
        var loss = 0.0
        var maxEle = points.first().ele
        var minEle = points.first().ele
        var lastValidEle = points.first().ele

        for (i in 1 until points.size) {
            val p1 = points[i - 1]
            val p2 = points[i]
            totalDist += haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)

            if (p2.ele > maxEle) maxEle = p2.ele
            if (p2.ele < minEle) minEle = p2.ele

            val diff = p2.ele - lastValidEle
            if (diff > 2.0) {
                gain += diff
                lastValidEle = p2.ele
            } else if (diff < -2.0) {
                loss += abs(diff)
                lastValidEle = p2.ele
            }
        }
        return GpxTrack(name, points, totalDist, gain, loss, maxEle, minEle)
    }

    fun exportToGpxString(track: GpxTrack): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder()
        sb.append("<?xml version="1.0" encoding="UTF-8"?>
")
        sb.append("<gpx version="1.1" creator="MapyOutdoor" xmlns="http://www.topografix.com/GPX/1/1">
")
        sb.append("  <trk>
")
        sb.append("    <name>${track.name}</name>
")
        sb.append("    <trkseg>
")
        for (pt in track.points) {
            sb.append("      <trkpt lat="${pt.lat}" lon="${pt.lon}">
")
            sb.append("        <ele>${pt.ele}</ele>
")
            sb.append("        <time>${sdf.format(Date(pt.time))}</time>
")
            sb.append("      </trkpt>
")
        }
        sb.append("    </trkseg>
")
        sb.append("  </trk>
")
        sb.append("</gpx>")
        return sb.toString()
    }

    fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
