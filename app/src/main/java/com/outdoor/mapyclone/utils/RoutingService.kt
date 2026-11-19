package com.outdoor.mapyclone.utils

import com.outdoor.mapyclone.model.GpxTrack
import com.outdoor.mapyclone.model.SearchPlace
import com.outdoor.mapyclone.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object RoutingService {
    private val client = OkHttpClient()

    suspend fun searchPlaces(query: String): List<SearchPlace> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=6"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MapyOutdoorAndroid/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext emptyList()
                val array = JSONArray(body)
                val list = mutableListOf<SearchPlace>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val display = obj.getString("display_name")
                    val shortName = display.substringBefore(",")
                    list.add(
                        SearchPlace(
                            name = shortName,
                            displayName = display,
                            lat = obj.getString("lat").toDouble(),
                            lon = obj.getString("lon").toDouble()
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun calculateHikingRoute(start: TrackPoint, end: TrackPoint): GpxTrack? = withContext(Dispatchers.IO) {
        try {
            val url = "https://brouter.de/brouter?lonlats=${start.lon},${start.lat}|${end.lon},${end.lat}&profile=trekking&alternativeidx=0&format=geojson"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MapyOutdoorAndroid/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val features = json.getJSONArray("features")
                if (features.length() == 0) return@withContext null
                val geometry = features.getJSONObject(0).getJSONObject("geometry")
                val coords = geometry.getJSONArray("coordinates")
                val points = mutableListOf<TrackPoint>()
                for (i in 0 until coords.length()) {
                    val c = coords.getJSONArray(i)
                    val lon = c.getDouble(0)
                    val lat = c.getDouble(1)
                    val ele = if (c.length() > 2) c.getDouble(2) else 0.0
                    points.add(TrackPoint(lat, lon, ele))
                }
                GpxManager.calculateStats("Itinerario Pianificato", points)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
