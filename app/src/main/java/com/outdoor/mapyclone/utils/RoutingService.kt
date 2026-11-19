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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*

object RoutingService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
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

    suspend fun fetchPoisAndPeaksInBounds(
        context: Context,
        south: Double,
        west: Double,
        north: Double,
        east: Double
    ): Pair<List<PoiItem>, List<PeakItem>> = withContext(Dispatchers.IO) {
        val bbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", south, west, north, east)
        val overpassQuery = """
            [out:json][timeout:18];
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
              node["natural"="peak"]["name"]($bbox);
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
                if (!response.isSuccessful) {
                    return@withContext (loadCachedPois(context) to loadCachedPeaks(context))
                }
                val body = response.body?.string()
                    ?: return@withContext (loadCachedPois(context) to loadCachedPeaks(context))
                val json = JSONObject(body)
                val elements = json.getJSONArray("elements")
                val poiResult = mutableListOf<PoiItem>()
                val peakResult = mutableListOf<PeakItem>()

                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val id = el.getLong("id")
                    val tags = el.optJSONObject("tags") ?: continue
                    val center = el.optJSONObject("center")
                    val lat = center?.optDouble("lat") ?: el.optDouble("lat", Double.NaN)
                    val lon = center?.optDouble("lon") ?: el.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue

                    val natural = tags.optString("natural")
                    if (natural == "peak") {
                        val pName = tags.optString("name", "")
                        val eleVal = tags.optString("ele", "").replace("m", "").trim().toDoubleOrNull()?.toInt() ?: 0
                        if (pName.isNotBlank()) {
                            peakResult.add(PeakItem(pName, lat, lon, eleVal))
                        }
                        continue
                    }

                    val tourism = tags.optString("tourism")
                    val amenity = tags.optString("amenity")
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

                    poiResult.add(PoiItem(id, name, category, lat, lon, ele))
                }

                if (poiResult.isNotEmpty()) saveCachedPois(context, poiResult)
                if (peakResult.isNotEmpty()) saveCachedPeaks(context, peakResult)
                mergePoisWithCache(context, poiResult) to mergePeaksWithCache(context, peakResult)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            loadCachedPois(context) to loadCachedPeaks(context)
        }
    }

    private fun saveCachedPois(context: Context, newItems: List<PoiItem>) {
        val existing = loadCachedPois(context).associateBy { it.id }.toMutableMap()
        newItems.forEach { existing[it.id] = it }
        val arr = JSONArray()
        existing.values.toList().takeLast(2000).forEach { poi ->
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

    private fun saveCachedPeaks(context: Context, newPeaks: List<PeakItem>) {
        val existing = loadCachedPeaks(context).associateBy { it.name + it.elevationMeters }.toMutableMap()
        newPeaks.forEach { existing[it.name + it.elevationMeters] = it }
        val arr = JSONArray()
        existing.values.toList().takeLast(1500).forEach { pk ->
            val obj = JSONObject()
            obj.put("name", pk.name)
            obj.put("lat", pk.lat)
            obj.put("lon", pk.lon)
            obj.put("ele", pk.elevationMeters)
            arr.put(obj)
        }
        context.getSharedPreferences("mapy_poi_cache", Context.MODE_PRIVATE)
            .edit().putString("peaks_json", arr.toString()).apply()
    }

    fun loadCachedPeaks(context: Context): List<PeakItem> {
        val raw = context.getSharedPreferences("mapy_poi_cache", Context.MODE_PRIVATE)
            .getString("peaks_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<PeakItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    PeakItem(
                        name = obj.getString("name"),
                        lat = obj.getDouble("lat"),
                        lon = obj.getDouble("lon"),
                        elevationMeters = obj.optInt("ele", 0)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun mergePoisWithCache(context: Context, fresh: List<PoiItem>): List<PoiItem> {
        val map = loadCachedPois(context).associateBy { it.id }.toMutableMap()
        fresh.forEach { map[it.id] = it }
        return map.values.toList()
    }

    private fun mergePeaksWithCache(context: Context, fresh: List<PeakItem>): List<PeakItem> {
        val map = loadCachedPeaks(context).associateBy { it.name + it.elevationMeters }.toMutableMap()
        fresh.forEach { map[it.name + it.elevationMeters] = it }
        return map.values.toList()
    }

    suspend fun fetchFullAlpineWeather(
        context: Context,
        lat: Double,
        lon: Double,
        overrideElevationMeters: Int?
    ): AlpineFullWeather? = withContext(Dispatchers.IO) {
        try {
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&hourly=temperature_2m,apparent_temperature,freezing_level_height,wind_speed_10m,wind_gusts_10m,precipitation,cloud_cover_low,cloud_cover_mid,cloud_cover_high,temperature_700hPa,wind_speed_700hPa,temperature_850hPa,wind_speed_850hPa&forecast_days=5&timezone=auto",
                lat, lon
            )
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext loadCachedWeather(context)
                val body = response.body?.string() ?: return@withContext loadCachedWeather(context)
                val json = JSONObject(body)
                val modelElev = json.optDouble("elevation", 1500.0).toInt()
                val targetElev = overrideElevationMeters ?: modelElev
                val hourly = json.getJSONObject("hourly")

                val times = hourly.getJSONArray("time")
                val temp2m = hourly.getJSONArray("temperature_2m")
                val flArr = hourly.getJSONArray("freezing_level_height")
                val wind10 = hourly.getJSONArray("wind_speed_10m")
                val gusts10 = hourly.getJSONArray("wind_gusts_10m")
                val precipArr = hourly.getJSONArray("precipitation")
                val cLowArr = hourly.getJSONArray("cloud_cover_low")
                val cMidArr = hourly.getJSONArray("cloud_cover_mid")
                val cHighArr = hourly.getJSONArray("cloud_cover_high")
                val temp700 = hourly.optJSONArray("temperature_700hPa")
                val wind700 = hourly.optJSONArray("wind_speed_700hPa")

                val daysMap = linkedMapOf<String, MutableList<HourlyAlpineSlot>>()
                val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
                val dayFmt = SimpleDateFormat("EEE dd/MM", Locale.ITALIAN)

                for (i in 0 until times.length()) {
                    val tStr = times.getString(i)
                    val hourPart = tStr.substringAfter("T")
                    val hourInt = hourPart.substringBefore(":").toIntOrNull() ?: 0
                    if (hourInt % 3 != 0) continue

                    val dateObj = inFmt.parse(tStr) ?: Date()
                    val dayKey = dayFmt.format(dateObj).replaceFirstChar { it.uppercase() }

                    val baseT = temp2m.optDouble(i, 0.0)
                    val t700 = temp700?.optDouble(i, Double.NaN) ?: Double.NaN
                    // Gradiente termico reale in quota (interpolazione con libera atmosfera 700hPa ~3000m)
                    val adjustedTemp = if (!t700.isNaN() && targetElev >= 2400) {
                        val diff3000 = (targetElev - 3000) / 100.0 * 0.65
                        ((t700 - diff3000) * 10.0).roundToInt() / 10.0
                    } else {
                        val altDiff = (targetElev - modelElev) / 100.0 * 0.65
                        ((baseT - altDiff) * 10.0).roundToInt() / 10.0
                    }

                    val wBase = wind10.optDouble(i, 0.0)
                    val w700 = wind700?.optDouble(i, Double.NaN) ?: Double.NaN
                    val adjustedWind = if (!w700.isNaN() && targetElev >= 2500) {
                        w700.toInt()
                    } else {
                        (wBase * (1.0 + maxOf(0, targetElev - modelElev) / 2500.0)).toInt()
                    }
                    val adjustedGusts = maxOf(gusts10.optDouble(i, 0.0).toInt(), (adjustedWind * 1.45).toInt())

                    // Wind chill semplificato
                    val feels = if (adjustedTemp <= 10.0 && adjustedWind >= 5) {
                        val wc = 13.12 + 0.6215 * adjustedTemp - 11.37 * adjustedWind.toDouble().pow(0.16) + 0.3965 * adjustedTemp * adjustedWind.toDouble().pow(0.16)
                        (wc * 10.0).roundToInt() / 10.0
                    } else adjustedTemp

                    val fl = flArr.optDouble(i, 2000.0).toInt()
                    val snowLimit = maxOf(0, fl - 250)

                    val slot = HourlyAlpineSlot(
                        timeLabel = hourPart,
                        tempAtTarget = adjustedTemp,
                        feelsLike = feels,
                        freezingLevel = fl,
                        windKmh = adjustedWind,
                        gustsKmh = adjustedGusts,
                        snowLimitMeters = snowLimit,
                        precipMm = precipArr.optDouble(i, 0.0),
                        cloudLow = cLowArr.optInt(i, 0),
                        cloudMid = cMidArr.optInt(i, 0),
                        cloudHigh = cHighArr.optInt(i, 0)
                    )
                    daysMap.getOrPut(dayKey) { mutableListOf() }.add(slot)
                }

                val dailyList = daysMap.map { (k, v) -> DailyAlpineForecast(k, v) }
                val avalanche = fetchAvalancheInfo(lat, lon)
                val alert = fetchOfficialAlert(lat, lon)
                val report = AlpineFullWeather(modelElev, dailyList, avalanche, alert)
                saveCachedWeather(context, report)
                report
            }
        } catch (e: Exception) {
            e.printStackTrace()
            loadCachedWeather(context)
        }
    }

    private fun fetchAvalancheInfo(lat: Double, lon: Double): AvalancheBulletin? {
        // Verifica se le coordinate ricadono nell'arco alpino italiano/Euregio
        val isAlps = lat in 44.0..47.1 && lon in 6.6..13.9
        if (!isAlps) {
            return AvalancheBulletin(
                regionName = "Appennino / Centro-Sud (Meteomont)",
                dangerRating = 0,
                dangerLabel = "Consulta Bollettino Meteomont",
                problemTitle = "Settore non coperto da feed CAAML AINEVA",
                elevationThreshold = "Oltre il limite del bosco",
                summaryText = "Per l'Appennino verifica il bollettino neve e valanghe ufficiale Meteomont (Carabinieri / Truppe Alpine) prima di impegnare canali."
            )
        }
        return try {
            // Feed pubblico Euregio / AINEVA CAAML v6 JSON
            val req = Request.Builder()
                .url("https://static.avalanche.report/bulletins/latest/EUREGIO_it_CAAMLv6.json")
                .header("User-Agent", "MapyOutdoorApp/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return AvalancheBulletin(
                        regionName = "Arco Alpino (AINEVA / Euregio)",
                        dangerRating = 0,
                        dangerLabel = "Bollettino stagionale",
                        problemTitle = "Verifica bollettino regionale AINEVA",
                        elevationThreshold = "Pendii ripidi >30°",
                        summaryText = "Feed CAAML momentaneamente non attivo per la data odierna. Controlla bollettini.aineva.it."
                    )
                }
                val body = resp.body?.string() ?: return null
                val root = JSONObject(body)
                val bulletins = root.optJSONArray("bulletins") ?: return null
                if (bulletins.length() == 0) return null

                val first = bulletins.getJSONObject(0)
                val ratings = first.optJSONArray("dangerRatings")
                val mainValue = ratings?.optJSONObject(0)?.optString("mainValue", "moderate") ?: "moderate"
                val (num, label) = when (mainValue.lowercase()) {
                    "low" -> 1 to "1 - Debole"
                    "moderate" -> 2 to "2 - Moderato"
                    "considerable" -> 3 to "3 - Marcato"
                    "high" -> 4 to "4 - Forte"
                    "very_high" -> 5 to "5 - Molto Forte"
                    else -> 2 to "2 - Moderato"
                }
                val problems = first.optJSONArray("avalancheProblems")
                val probType = problems?.optJSONObject(0)?.optString("problemType", "wind_slab") ?: "wind_slab"
                val probLabel = when (probType) {
                    "new_snow" -> "Neve fresca"
                    "wind_slab" -> "Lastroni da vento"
                    "persistent_weak_layers" -> "Strati deboli persistenti"
                    "wet_snow" -> "Neve bagnata"
                    "gliding_snow" -> "Valanghe di slittamento"
                    else -> "Lastroni da vento / Rischio locale"
                }
                val highlights = first.optString("highlights", "Prestare attenzione ai pendii ripidi oltre i 30° e ai canali sottovento.")
                AvalancheBulletin(
                    regionName = "Bollettino Ufficiale Alpi (AINEVA / Euregio)",
                    dangerRating = num,
                    dangerLabel = label,
                    problemTitle = probLabel,
                    elevationThreshold = "Esposizioni critiche in quota",
                    summaryText = highlights
                )
            }
        } catch (_: Exception) {
            AvalancheBulletin(
                regionName = "Arco Alpino (AINEVA)",
                dangerRating = 0,
                dangerLabel = "Dati offline non aggiornati",
                problemTitle = "Verifica esposizione canali >30°",
                elevationThreshold = "Quota critica in base allo zero termico",
                summaryText = "Impossibile scaricare il bollettino valanghe aggiornato. Valuta il rigelo e i lastroni da vento sul terreno."
            )
        }
    }

    private fun fetchOfficialAlert(lat: Double, lon: Double): String? {
        return try {
            val req = Request.Builder()
                .url("https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-italy")
                .header("User-Agent", "MapyOutdoorApp/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val xml = resp.body?.string() ?: return null
                if (xml.contains("Red", ignoreCase = true)) {
                    "ALLERTA ROSSA Protezione Civile attiva su settori italiani: verifica criticità vento/temporali."
                } else if (xml.contains("Orange", ignoreCase = true)) {
                    "ALLERTA ARANCIONE Protezione Civile attiva su alcuni settori: attenzione a vento forte o temporali in quota."
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveCachedWeather(context: Context, weather: AlpineFullWeather) {
        val obj = JSONObject()
        obj.put("elev", weather.baseElevation)
        obj.put("alert", weather.officialAlert ?: "")
        weather.avalanche?.let { av ->
            val avObj = JSONObject()
            avObj.put("reg", av.regionName)
            avObj.put("rat", av.dangerRating)
            avObj.put("lbl", av.dangerLabel)
            avObj.put("prob", av.problemTitle)
            avObj.put("ele", av.elevationThreshold)
            avObj.put("sum", av.summaryText)
            obj.put("avalanche", avObj)
        }
        val dArr = JSONArray()
        weather.days.forEach { d ->
            val dObj = JSONObject()
            dObj.put("date", d.dateLabel)
            val sArr = JSONArray()
            d.slots.forEach { s ->
                val sObj = JSONObject()
                sObj.put("t", s.timeLabel)
                sObj.put("temp", s.tempAtTarget)
                sObj.put("feel", s.feelsLike)
                sObj.put("fl", s.freezingLevel)
                sObj.put("w", s.windKmh)
                sObj.put("g", s.gustsKmh)
                sObj.put("sn", s.snowLimitMeters)
                sObj.put("pr", s.precipMm)
                sObj.put("cl", s.cloudLow)
                sObj.put("cm", s.cloudMid)
                sObj.put("ch", s.cloudHigh)
                sArr.put(sObj)
            }
            dObj.put("slots", sArr)
            dArr.put(dObj)
        }
        obj.put("days", dArr)
        context.getSharedPreferences("mapy_poi_cache", Context.MODE_PRIVATE)
            .edit().putString("weather_json", obj.toString()).apply()
    }

    fun loadCachedWeather(context: Context): AlpineFullWeather? {
        val raw = context.getSharedPreferences("mapy_poi_cache", Context.MODE_PRIVATE)
            .getString("weather_json", null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val elev = obj.getInt("elev")
            val alert = obj.optString("alert").takeIf { it.isNotBlank() }
            val avObj = obj.optJSONObject("avalanche")
            val av = avObj?.let {
                AvalancheBulletin(
                    it.getString("reg"),
                    it.getInt("rat"),
                    it.getString("lbl"),
                    it.getString("prob"),
                    it.getString("ele"),
                    it.getString("sum")
                )
            }
            val dArr = obj.getJSONArray("days")
            val days = mutableListOf<DailyAlpineForecast>()
            for (i in 0 until dArr.length()) {
                val dObj = dArr.getJSONObject(i)
                val sArr = dObj.getJSONArray("slots")
                val slots = mutableListOf<HourlyAlpineSlot>()
                for (j in 0 until sArr.length()) {
                    val s = sArr.getJSONObject(j)
                    slots.add(
                        HourlyAlpineSlot(
                            s.getString("t"), s.getDouble("temp"), s.getDouble("feel"),
                            s.getInt("fl"), s.getInt("w"), s.getInt("g"), s.getInt("sn"),
                            s.getDouble("pr"), s.getInt("cl"), s.getInt("cm"), s.getInt("ch")
                        )
                    )
                }
                days.add(DailyAlpineForecast(dObj.getString("date"), slots))
            }
            AlpineFullWeather(elev, days, av, alert)
        } catch (_: Exception) {
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
            GpxManager.calculateStats("Rotta Diretta Offline", listOf(start, end))
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

    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return ((brng + 360.0) % 360.0).toInt()
    }


    suspend fun fetchHorizonProfile360(
        centerLat: Double,
        centerLon: Double,
        observerEleMeters: Double
    ): FloatArray = withContext(Dispatchers.IO) {
        val resultAngles = FloatArray(360) { 0f }
        try {
            // Campiona 72 direzioni (ogni 5°) su 4 anelli di distanza (5km, 15km, 35km, 65km) = 288 punti
            val distancesKm = doubleArrayOf(5.0, 15.0, 35.0, 65.0)
            val lats = mutableListOf<Double>()
            val lons = mutableListOf<Double>()
            val meta = mutableListOf<Pair<Int, Double>>() // azimuthIdx (0..71) to distMeters

            val earthRadius = 6371000.0
            for (azIdx in 0 until 72) {
                val brng = Math.toRadians(azIdx * 5.0)
                for (dKm in distancesKm) {
                    val dM = dKm * 1000.0
                    val angDist = dM / earthRadius
                    val lat1 = Math.toRadians(centerLat)
                    val lon1 = Math.toRadians(centerLon)
                    val lat2 = asin(sin(lat1) * cos(angDist) + cos(lat1) * sin(angDist) * cos(brng))
                    val lon2 = lon1 + atan2(sin(brng) * sin(angDist) * cos(lat1), cos(angDist) - sin(lat1) * sin(lat2))
                    lats.add(Math.toDegrees(lat2))
                    lons.add(Math.toDegrees(lon2))
                    meta.add(azIdx to dM)
                }
            }

            // Chiamata batch Open-Meteo Elevation API (gratuita, senza chiave)
            val latsStr = lats.joinToString(",") { String.format(Locale.US, "%.4f", it) }
            val lonsStr = lons.joinToString(",") { String.format(Locale.US, "%.4f", it) }
            val url = "https://api.open-meteo.com/v1/elevation?latitude=$latsStr&longitude=$lonsStr"
            val req = Request.Builder().url(url).build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return@withContext resultAngles
                val json = JSONObject(body)
                val elevArr = json.getJSONArray("elevation")
                val coarseMaxAngles = FloatArray(72) { -15f }

                for (i in 0 until minOf(elevArr.length(), meta.size)) {
                    val terrainEle = elevArr.getDouble(i)
                    val (azIdx, distM) = meta[i]
                    // Correzione curvatura terrestre
                    val curvatureDrop = (distM * distM) / (2.0 * earthRadius)
                    val effectiveDeltaH = terrainEle - observerEleMeters - curvatureDrop
                    val angleDeg = Math.toDegrees(atan2(effectiveDeltaH, distM)).toFloat()
                    if (angleDeg > coarseMaxAngles[azIdx]) {
                        coarseMaxAngles[azIdx] = angleDeg
                    }
                }

                // Interpolazione lineare sui 360 gradi
                for (deg in 0 until 360) {
                    val idx0 = (deg / 5) % 72
                    val idx1 = (idx0 + 1) % 72
                    val frac = (deg % 5) / 5.0f
                    resultAngles[deg] = coarseMaxAngles[idx0] * (1f - frac) + coarseMaxAngles[idx1] * frac
                }
            }
        } catch (_: Exception) {
        }
        resultAngles
    }

}
