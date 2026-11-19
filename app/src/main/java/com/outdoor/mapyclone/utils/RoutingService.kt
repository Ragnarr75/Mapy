package com.outdoor.mapyclone.utils

import android.content.Context
import com.outdoor.mapyclone.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*

object RoutingService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun searchPlaces(query: String): List<SearchPlace> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://photon.komoot.io/api/?q=$encoded&limit=10"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MapyOutdoorApp/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val features = json.getJSONArray("features")
                val list = mutableListOf<SearchPlace>()

                for (i in 0 until features.length()) {
                    val feat = features.getJSONObject(i)
                    val props = feat.getJSONObject("properties")
                    val geom = feat.getJSONObject("geometry")
                    val coords = geom.getJSONArray("coordinates")

                    val lon = coords.getDouble(0)
                    val lat = coords.getDouble(1)
                    val name = props.optString("name", "").ifBlank {
                        props.optString("street", "Località")
                    }
                    val ele = props.optString("ele", "")
                    val titleWithEle = if (ele.isNotBlank()) "$name (${ele}m)" else name

                    val details = listOfNotNull(
                        props.optString("osm_value").takeIf { it.isNotBlank() },
                        props.optString("city").takeIf { it.isNotBlank() },
                        props.optString("county").takeIf { it.isNotBlank() },
                        props.optString("state").takeIf { it.isNotBlank() },
                        props.optString("country").takeIf { it.isNotBlank() }
                    ).joinToString(", ")

                    list.add(
                        SearchPlace(
                            name = titleWithEle,
                            displayName = details.ifBlank { String.format(Locale.US, "%.4f, %.4f", lat, lon) },
                            lat = lat,
                            lon = lon
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

    suspend fun fetchPoisInBounds(
        context: Context,
        south: Double,
        west: Double,
        north: Double,
        east: Double
    ): List<PoiItem> = withContext(Dispatchers.IO) {
        val bbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", south, west, north, east)
        val overpassQuery = """
            [out:json][timeout:15];
            (
              node["tourism"~"alpine_hut|wilderness_hut"]($bbox);
              way["tourism"~"alpine_hut|wilderness_hut"]($bbox);
              node["amenity"="shelter"]["shelter_type"~"basic_hut|rock_shelter|weather_shelter"]($bbox);
              node["amenity"="drinking_water"]($bbox);
              node["natural"="spring"]($bbox);
              node["sport"="climbing"]($bbox);
              way["sport"="climbing"]($bbox);
              way["highway"="via_ferrata"]($bbox);
              node["via_ferrata_scale"]($bbox);
            );
            out center;
        """.trimIndent()

        try {
            val encoded = URLEncoder.encode(overpassQuery, "UTF-8")
            val url = "https://overpass-api.de/api/interpreter?data=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MapyOutdoorApp/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext loadCachedPois(context)
                val body = response.body?.string() ?: return@withContext loadCachedPois(context)
                val json = JSONObject(body)
                val elements = json.getJSONArray("elements")
                val result = mutableListOf<PoiItem>()

                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val id = el.getLong("id")
                    val tags = el.optJSONObject("tags") ?: continue
                    val center = el.optJSONObject("center")
                    val lat = center?.optDouble("lat") ?: el.optDouble("lat", Double.NaN)
                    val lon = center?.optDouble("lon") ?: el.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue

                    val tourism = tags.optString("tourism")
                    val amenity = tags.optString("amenity")
                    val natural = tags.optString("natural")
                    val sport = tags.optString("sport")
                    val highway = tags.optString("highway")

                    val category = when {
                        tourism == "alpine_hut" || tourism == "wilderness_hut" || amenity == "shelter" -> PoiCategory.HUT
                        amenity == "drinking_water" || natural == "spring" -> PoiCategory.WATER
                        sport == "climbing" -> PoiCategory.CLIMBING
                        highway == "via_ferrata" || tags.has("via_ferrata_scale") -> PoiCategory.FERRATA
                        else -> continue
                    }

                    val defaultTitle = when (category) {
                        PoiCategory.HUT -> "Rifugio / Bivacco"
                        PoiCategory.WATER -> "Acqua / Sorgente"
                        PoiCategory.CLIMBING -> "Falesia"
                        PoiCategory.FERRATA -> "Via Ferrata"
                    }
                    val name = tags.optString("name", "").ifBlank { defaultTitle }
                    val ele = tags.optString("ele", "")

                    result.add(PoiItem(id, name, category, lat, lon, ele))
                }

                if (result.isNotEmpty()) {
                    saveCachedPois(context, result)
                }
                mergeWithCache(context, result)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            loadCachedPois(context)
        }
    }

    private fun saveCachedPois(context: Context, newItems: List<PoiItem>) {
        val existing = loadCachedPois(context).associateBy { it.id }.toMutableMap()
        newItems.forEach { existing[it.id] = it }
        val arr = JSONArray()
        existing.values.takeLast(1500).forEach { poi ->
            val obj = JSONObject()
            obj.put("id", poi.id)
            obj.put("name", poi.name)
            obj.put("cat", poi.category.name)
            obj.put("lat", poi.lat)
            obj.put("lon", poi.lon)
            obj.put("ele", poi.elevation)
            arr.put(obj)
        }
        context.getSharedPreferences("mapy_poi_cache", Context.MODE_PRIVATE)
            .edit().putString("pois_json", arr.toString()).apply()
    }

    fun loadCachedPois(context: Context): List<PoiItem> {
        val raw = context.getSharedPreferences("mapy_poi_cache", Context.MODE_PRIVATE)
            .getString("pois_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<PoiItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val cat = PoiCategory.valueOf(obj.getString("cat"))
                list.add(
                    PoiItem(
                        id = obj.getLong("id"),
                        name = obj.getString("name"),
                        category = cat,
                        lat = obj.getDouble("lat"),
                        lon = obj.getDouble("lon"),
                        elevation = obj.optString("ele", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun mergeWithCache(context: Context, fresh: List<PoiItem>): List<PoiItem> {
        val map = loadCachedPois(context).associateBy { it.id }.toMutableMap()
        fresh.forEach { map[it.id] = it }
        return map.values.toList()
    }

    suspend fun fetchAlpineWeather(lat: Double, lon: Double): AlpineWeatherReport? = withContext(Dispatchers.IO) {
        try {
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,wind_speed_10m,wind_gusts_10m,cloud_cover_low,cloud_cover_mid,cloud_cover_high&hourly=freezing_level_height,precipitation_probability,wind_gusts_10m&forecast_days=2&timezone=auto",
                lat, lon
            )
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val elevation = json.optDouble("elevation", 0.0).toInt()
                val current = json.getJSONObject("current")
                val hourly = json.getJSONObject("hourly")

                val temp = current.getDouble("temperature_2m")
                val wind = current.getDouble("wind_speed_10m")
                val gusts = current.getDouble("wind_gusts_10m")
                val cLow = current.getInt("cloud_cover_low")
                val cMid = current.getInt("cloud_cover_mid")
                val cHigh = current.getInt("cloud_cover_high")

                val flArray = hourly.getJSONArray("freezing_level_height")
                val precipArray = hourly.getJSONArray("precipitation_probability")
                val gustsArray = hourly.getJSONArray("wind_gusts_10m")

                var flMin = 9999
                var flMax = 0
                var maxPrecip = 0
                var maxGust24 = gusts

                val count = minOf(24, flArray.length())
                for (i in 0 until count) {
                    val fl = flArray.optDouble(i, 2000.0).toInt()
                    if (fl < flMin) flMin = fl
                    if (fl > flMax) flMax = fl
                    val pr = precipArray.optInt(i, 0)
                    if (pr > maxPrecip) maxPrecip = pr
                    val g = gustsArray.optDouble(i, 0.0)
                    if (g > maxGust24) maxGust24 = g
                }
                val flCurrent = flArray.optDouble(0, 2000.0).toInt()

                val warning = when {
                    maxGust24 >= 65.0 -> "ATTENZIONE: Raffiche forti in quota (${maxGust24.toInt()} km/h)"
                    cLow >= 75 -> "VISIBILITÀ RIDOTTA: Rischio nebbia/whiteout in quota"
                    maxPrecip >= 60 -> "INSTABILITÀ: Alta probabilità precipitazioni/temporali"
                    flCurrent > elevation + 400 -> "RIGELO SCARSO: Zero termico oltre la quota del punto"
                    else -> "CONDIZIONI STABILI: Buona finestra operativa in quota"
                }

                AlpineWeatherReport(
                    elevationMeters = elevation,
                    freezingLevelCurrent = flCurrent,
                    freezingLevelMin24h = flMin,
                    freezingLevelMax24h = flMax,
                    tempAtElevation = temp,
                    windSpeedKmh = wind,
                    windGustsKmh = gusts,
                    cloudCoverLow = cLow,
                    cloudCoverMid = cMid,
                    cloudCoverHigh = cHigh,
                    precipitationProbMax = maxPrecip,
                    summaryWarning = warning
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun calculateHikingRoute(start: TrackPoint, end: TrackPoint): GpxTrack? = withContext(Dispatchers.IO) {
        try {
            val url = "https://brouter.de/brouter?lonlats=${start.lon},${start.lat}|${end.lon},${end.lat}&profile=trekking&alternativeidx=0&format=geojson"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MapyOutdoorApp/1.0")
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
                GpxManager.calculateStats("Itinerario Sentiero", points)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback offline: rotta diretta punto-punto
            val direct = listOf(start, end)
            GpxManager.calculateStats("Rotta Diretta Offline", direct)
        }
    }

    fun toUtmString(lat: Double, lon: Double): String {
        val zone = floor((lon + 180.0) / 6.0).toInt() + 1
        val letter = if (lat >= 0) "N" else "S"
        val a = 6378137.0
        val eccSquared = 0.00669438
        val k0 = 0.9996
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val lonOrigin = (zone - 1) * 6 - 180 + 3
        val lonOriginRad = Math.toRadians(lonOrigin.toDouble())
        val eccPrimeSquared = eccSquared / (1 - eccSquared)
        val n = a / sqrt(1 - eccSquared * sin(latRad) * sin(latRad))
        val t = tan(latRad) * tan(latRad)
        val c = eccPrimeSquared * cos(latRad) * cos(latRad)
        val aVal = cos(latRad) * (lonRad - lonOriginRad)
        val m = a * ((1 - eccSquared / 4 - 3 * eccSquared * eccSquared / 64) * latRad
                - (3 * eccSquared / 8 + 3 * eccSquared * eccSquared / 32) * sin(2 * latRad)
                + (15 * eccSquared * eccSquared / 256) * sin(4 * latRad))
        val easting = k0 * n * (aVal + (1 - t + c) * aVal.pow(3) / 6) + 500000.0
        var northing = k0 * (m + n * tan(latRad) * (aVal * aVal / 2 + (5 - t + 9 * c + 4 * c * c) * aVal.pow(4) / 24))
        if (lat < 0) northing += 10000000.0
        return String.format(Locale.US, "%d%s E:%.0f N:%.0f", zone, letter, easting, northing)
    }
}
