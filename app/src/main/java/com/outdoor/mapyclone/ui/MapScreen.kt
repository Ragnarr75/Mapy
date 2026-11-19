package com.outdoor.mapyclone.ui

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.BatteryManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.outdoor.mapyclone.model.*
import com.outdoor.mapyclone.service.TrackingService
import com.outdoor.mapyclone.utils.GpxManager
import com.outdoor.mapyclone.utils.RoutingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var currentLayer by remember { mutableStateOf(MapLayerType.ALPINE) }
    var showLayerMenu by remember { mutableStateOf(false) }
    var is3DEnabled by remember { mutableStateOf(false) }

    // Ricerca luoghi
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchPlace>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    // POI selezionabili + Vette 3D
    var allLoadedPois by remember { mutableStateOf<List<PoiItem>>(emptyList()) }
    var allLoadedPeaks by remember { mutableStateOf<List<PeakItem>>(emptyList()) }
    var activePoiFilters by remember { mutableStateOf<Set<PoiCategory>>(emptySet()) }
    var isLoadingPois by remember { mutableStateOf(false) }

    // Tracce GPX e Navigazione Offline
    var activeTrack by remember { mutableStateOf<GpxTrack?>(null) }
    var routeStart by remember { mutableStateOf<TrackPoint?>(null) }
    var isPlanningMode by remember { mutableStateOf(false) }
    var targetWaypoint by remember { mutableStateOf<Pair<String, LatLng>?>(null) }
    var userCurrentLatLng by remember { mutableStateOf<LatLng?>(null) }

    // Sensori hardware (Bussola, Barometro, Inclinometro)
    var baroAltitude by remember { mutableStateOf<Float?>(null) }
    var compassAzimuth by remember { mutableStateOf(0f) }
    var slopePitchDegrees by remember { mutableStateOf(0) }

    // Meteo Alpinistico 5 Giorni & Bollettini Valanghe
    var showWeatherSheet by remember { mutableStateOf(false) }
    var weatherData by remember { mutableStateOf<AlpineFullWeather?>(null) }
    var isLoadingWeather by remember { mutableStateOf(false) }
    var selectedDayIndex by remember { mutableStateOf(0) }
    var selectedAltitudeOverride by remember { mutableStateOf<Int?>(null) }
    var weatherTargetLatLng by remember { mutableStateOf<LatLng?>(null) }

    // Menu Strumenti Unificato ("magro")
    var showToolsMenu by remember { mutableStateOf(false) }
    var showSunSimulator by remember { mutableStateOf(false) }
    var sunHour by remember { mutableStateOf(12f) }
    var showInclinometer by remember { mutableStateOf(false) }
    var isNorthLocked by remember { mutableStateOf(false) }
    var showPeakFinderDialog by remember { mutableStateOf(false) }
    var showArCamera by remember { mutableStateOf(false) }
    var ultraBatteryEnabled by remember { mutableStateOf(false) }
    var showLiveShareDialog by remember { mutableStateOf(false) }
    var contactEmail by remember { mutableStateOf("") }
    var showSosConfirmDialog by remember { mutableStateOf(false) }

    // Offline & Aggiornamento dati
    var showOfflineDialog by remember { mutableStateOf(false) }
    var offlineProgress by remember { mutableStateOf<Float?>(null) }
    var savedRegions by remember { mutableStateOf<List<Pair<String, OfflineRegion>>>(emptyList()) }

    val isRecording by TrackingService.isRecording.collectAsState()
    val recordedPoints by TrackingService.recordedPoints.collectAsState()

    fun refreshSavedRegions() {
        OfflineManager.getInstance(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                savedRegions = offlineRegions?.map { reg ->
                    val title = try {
                        String(reg.metadata, Charset.forName("UTF-8"))
                    } catch (_: Exception) {
                        "Area Offline"
                    }
                    title to reg
                } ?: emptyList()
            }
            override fun onError(error: String) {}
        })
    }

    // Sensori: Barometro + Bussola + Inclinometro
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                if (event.sensor.type == Sensor.TYPE_PRESSURE) {
                    val hPa = event.values?.firstOrNull() ?: return
                    baroAltitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hPa)
                } else if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val deg = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360.0) % 360.0).toFloat()
                    compassAzimuth = deg
                    val pitchDeg = abs(Math.toDegrees(orientationAngles[1].toDouble())).toInt()
                    slopePitchDegrees = minOf(90, pitchDeg)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        pressureSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        rotationSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    LaunchedEffect(Unit) {
        OfflineManager.getInstance(context).setOfflineMapboxTileCountLimit(500_000L)
        allLoadedPois = RoutingService.loadCachedPois(context)
        allLoadedPeaks = RoutingService.loadCachedPeaks(context)
        weatherData = RoutingService.loadCachedWeather(context)
        refreshSavedRegions()
    }

    LaunchedEffect(activePoiFilters, allLoadedPois, mapLibreMap) {
        val filtered = allLoadedPois.filter { it.category in activePoiFilters }
        drawPoisOnMap(mapLibreMap, filtered)
    }

    LaunchedEffect(allLoadedPeaks, is3DEnabled, mapLibreMap) {
        drawPeaksOnMap(mapLibreMap, if (is3DEnabled) allLoadedPeaks else emptyList())
    }

    val gpxImporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val parsed = GpxManager.parseGpx(context, it)
            if (parsed != null && parsed.points.isNotEmpty()) {
                activeTrack = parsed
                drawTrackOnMap(mapLibreMap, parsed.points)
                zoomToTrack(mapLibreMap, parsed.points)
            } else {
                Toast.makeText(context, "File GPX non valido", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val gpxExporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri: Uri? ->
        uri?.let {
            activeTrack?.let { track ->
                context.contentResolver.openOutputStream(it)?.use { out ->
                    out.write(GpxManager.exportToGpxString(track).toByteArray())
                }
                Toast.makeText(context, "GPX esportato", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.trim().length < 2) {
            searchResults = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        isSearching = true
        delay(400)
        searchResults = RoutingService.searchPlaces(searchQuery)
        isSearching = false
    }

    LaunchedEffect(recordedPoints) {
        if (isRecording && recordedPoints.size >= 2) {
            val liveTrack = GpxManager.calculateStats("Registrazione in corso", recordedPoints)
            activeTrack = liveTrack
            drawTrackOnMap(mapLibreMap, recordedPoints)
        }
    }

    fun triggerWeatherAnalysis(target: LatLng, altOverride: Int? = selectedAltitudeOverride) {
        weatherTargetLatLng = target
        showWeatherSheet = true
        isLoadingWeather = true
        scope.launch {
            weatherData = RoutingService.fetchFullAlpineWeather(context, target.latitude, target.longitude, altOverride)
            isLoadingWeather = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    getMapAsync { map ->
                        mapLibreMap = map
                        applyMapStyle(map, currentLayer, is3DEnabled, sunHour) {
                            enableUserLocation(ctx, map)
                        }
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(44.85, 9.85))
                            .zoom(11.0)
                            .build()

                        map.addOnMapClickListener { latLng ->
                            val screenPoint = map.projection.toScreenLocation(latLng)
                            val features = map.queryRenderedFeatures(screenPoint, "poi-circles")
                            if (features.isNotEmpty()) {
                                val feat = features.first()
                                val title = feat.getStringProperty("title") ?: "POI"
                                val cat = feat.getStringProperty("cat") ?: ""
                                val ele = feat.getStringProperty("ele") ?: ""
                                val eleTxt = if (ele.isNotBlank()) " (${ele}m)" else ""
                                Toast.makeText(ctx, "$cat: $title$eleTxt", Toast.LENGTH_LONG).show()
                                targetWaypoint = "$title$eleTxt" to latLng
                                drawTargetLine(map, userCurrentLatLng, latLng)
                                true
                            } else {
                                false
                            }
                        }

                        map.addOnMapLongClickListener { latLng ->
                            if (isPlanningMode) {
                                val clicked = TrackPoint(latLng.latitude, latLng.longitude)
                                if (routeStart == null) {
                                    routeStart = clicked
                                    Toast.makeText(ctx, "Partenza fissata. Tieni premuto sulla meta.", Toast.LENGTH_LONG).show()
                                } else {
                                    val start = routeStart!!
                                    routeStart = null
                                    isPlanningMode = false
                                    scope.launch {
                                        val route = RoutingService.calculateHikingRoute(start, clicked)
                                        if (route != null) {
                                            activeTrack = route
                                            drawTrackOnMap(map, route.points)
                                            zoomToTrack(map, route.points)
                                        }
                                    }
                                }
                            } else {
                                targetWaypoint = String.format(Locale.US, "Punto %.4f, %.4f", latLng.latitude, latLng.longitude) to latLng
                                drawTargetLine(map, userCurrentLatLng, latLng)
                                triggerWeatherAnalysis(latLng)
                            }
                            true
                        }
                    }
                }
            }
        )

        // BARRA SUPERIORE PULITA: RICERCA + CHIP POI + PILLOLA BUSSOLA/QUOTA
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(6.dp, RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
                color = Color.White
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF1B4332))
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Cerca vette, rifugi, canali...", color = Color.Gray, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true
                    )
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF1B4332))
                    } else if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            searchResults = emptyList()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancella", tint = Color.Gray)
                        }
                    }
                }
            }

            if (searchResults.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .shadow(4.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White
                ) {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(searchResults) { place ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        searchResults = emptyList()
                                        searchQuery = place.name
                                        val dest = LatLng(place.lat, place.lon)
                                        targetWaypoint = place.name to dest
                                        mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(dest, 14.5))
                                        drawTargetLine(mapLibreMap, userCurrentLatLng, dest)
                                    }
                                    .padding(12.dp)
                            ) {
                                Text(place.name, fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 14.sp)
                                Text(place.displayName, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                            }
                            HorizontalDivider(color = Color(0xFFEEEEEE))
                        }
                    }
                }
            }

            // Chip POI selezionabili singolarmente
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PoiCategory.entries.forEach { cat ->
                    val selected = cat in activePoiFilters
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val newSet = if (selected) activePoiFilters - cat else activePoiFilters + cat
                            activePoiFilters = newSet
                            if (!selected) {
                                mapLibreMap?.projection?.visibleRegion?.latLngBounds?.let { b ->
                                    isLoadingPois = true
                                    scope.launch {
                                        val (pois, peaks) = RoutingService.fetchPoisAndPeaksInBounds(
                                            context, b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast
                                        )
                                        allLoadedPois = pois
                                        allLoadedPeaks = peaks
                                        isLoadingPois = false
                                    }
                                }
                            }
                        },
                        label = {
                            Text(
                                text = if (isLoadingPois && selected) "${cat.label}..." else cat.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.White.copy(alpha = 0.94f),
                            labelColor = Color.Black,
                            selectedContainerColor = Color(android.graphics.Color.parseColor(cat.colorHex)),
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            // Pillola compatta Bussola + Quota sempre visibile in alto
            val loc = mapLibreMap?.locationComponent?.lastKnownLocation
            if (loc != null) userCurrentLatLng = LatLng(loc.latitude, loc.longitude)
            val altStr = baroAltitude?.let { "${it.toInt()}m" } ?: loc?.altitude?.let { "${it.toInt()}m" } ?: "--"
            val cardinal = when (((compassAzimuth + 22.5f) % 360f).toInt() / 45) {
                0 -> "N"; 1 -> "NE"; 2 -> "E"; 3 -> "SE"; 4 -> "S"; 5 -> "SO"; 6 -> "O"; else -> "NO"
            }

            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1B4332).copy(alpha = 0.90f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Navigation,
                            contentDescription = "Bussola",
                            tint = Color(0xFFE63946),
                            modifier = Modifier.size(14.dp).rotate(-compassAzimuth)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${compassAzimuth.toInt()}° $cardinal", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("  •  Alt: $altStr", color = Color.White, fontSize = 12.sp)
                    }
                }

                targetWaypoint?.let { (wpName, wpLatLng) ->
                    val distTxt = userCurrentLatLng?.let { u ->
                        val d = GpxManager.haversineDistance(u.latitude, u.longitude, wpLatLng.latitude, wpLatLng.longitude)
                        if (d >= 1000) String.format(Locale.US, "%.1fkm", d / 1000.0) else "${d.toInt()}m"
                    } ?: ""
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFE65100).copy(alpha = 0.92f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("$wpName $distTxt", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Chiudi",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp).clickable {
                                    targetWaypoint = null
                                    drawTargetLine(mapLibreMap, null, null)
                                }
                            )
                        }
                    }
                }
            }
        }

        // SOLO 4 PULSANTI ESSENZIALI A DESTRA (Schermata pulita!)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Vista 3D volumetrica con vette
            SmallFloatingActionButton(
                onClick = {
                    is3DEnabled = !is3DEnabled
                    if (is3DEnabled && currentLayer == MapLayerType.ALPINE) {
                        currentLayer = MapLayerType.SATELLITE
                    } else if (!is3DEnabled && currentLayer == MapLayerType.SATELLITE) {
                        currentLayer = MapLayerType.ALPINE
                    }
                    mapLibreMap?.let { map ->
                        if (is3DEnabled) {
                            val b = map.projection.visibleRegion.latLngBounds
                            scope.launch {
                                val (pois, peaks) = RoutingService.fetchPoisAndPeaksInBounds(
                                    context, b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast
                                )
                                allLoadedPois = pois
                                allLoadedPeaks = peaks
                            }
                        }
                        applyMapStyle(map, currentLayer, is3DEnabled, sunHour) {
                            activeTrack?.let { drawTrackOnMap(map, it.points) }
                            drawPoisOnMap(map, allLoadedPois.filter { it.category in activePoiFilters })
                            drawPeaksOnMap(map, if (is3DEnabled) allLoadedPeaks else emptyList())
                        }
                        val targetPitch = if (is3DEnabled) 78.0 else 0.0
                        val targetZoom = if (is3DEnabled && map.cameraPosition.zoom < 12.5) 13.2 else map.cameraPosition.zoom
                        map.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(map.cameraPosition.target)
                                    .zoom(targetZoom)
                                    .tilt(targetPitch)
                                    .build()
                            )
                        )
                    }
                },
                containerColor = if (is3DEnabled) Color(0xFF1B4332) else Color.White,
                contentColor = if (is3DEnabled) Color.White else Color(0xFF1B4332)
            ) {
                Text("3D", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            // 2. Stili Mappa (Alpina, Swisstopo, Pendenze Canali, Satellitare)
            SmallFloatingActionButton(
                onClick = { showLayerMenu = !showLayerMenu },
                containerColor = Color.White,
                contentColor = Color(0xFF1B4332)
            ) {
                Icon(Icons.Default.Layers, contentDescription = "Mappe")
            }

            // 3. Meteo Alpinistico 5 Giorni & Bollettini Valanghe
            SmallFloatingActionButton(
                onClick = {
                    val target = targetWaypoint?.second ?: mapLibreMap?.cameraPosition?.target
                    if (target != null) {
                        triggerWeatherAnalysis(target)
                    }
                },
                containerColor = if (showWeatherSheet) Color(0xFF0288D1) else Color.White,
                contentColor = if (showWeatherSheet) Color.White else Color(0xFF0288D1)
            ) {
                Icon(Icons.Default.Cloud, contentDescription = "Meteo & Bollettini")
            }

            // 4. Menu Strumenti Unificato (Sole 3D, Inclinometro, PeakFinder, GPX, Offline/Aggiorna, SOS)
            SmallFloatingActionButton(
                onClick = { showToolsMenu = true },
                containerColor = Color(0xFF1B4332),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Tune, contentDescription = "Strumenti Alpinismo")
            }
        }

        // MENU SCELTA LAYER MAPPA
        if (showLayerMenu) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 68.dp)
                    .shadow(8.dp, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                color = Color.White
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    MapLayerType.entries.forEach { layer ->
                        Row(
                            modifier = Modifier
                                .clickable {
                                    currentLayer = layer
                                    showLayerMenu = false
                                    mapLibreMap?.let { map ->
                                        applyMapStyle(map, layer, is3DEnabled, sunHour) {
                                            activeTrack?.let { drawTrackOnMap(map, it.points) }
                                            drawPoisOnMap(map, allLoadedPois.filter { it.category in activePoiFilters })
                                            drawPeaksOnMap(map, if (is3DEnabled) allLoadedPeaks else emptyList())
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentLayer == layer,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1B4332))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(layer.label, color = Color.Black, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // OVERLAY SIMULATORE SOLE / OMBRA 3D (se attivato dal menu)
        if (showSunSimulator) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 135.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
                    .shadow(6.dp, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                color = Color.White
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Esposizione Sole/Ombra: Ore ${sunHour.toInt()}:00", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.Black)
                        Icon(Icons.Default.Close, contentDescription = "Chiudi", tint = Color.Gray, modifier = Modifier.size(18.dp).clickable { showSunSimulator = false })
                    }
                    Slider(
                        value = sunHour,
                        onValueChange = {
                            sunHour = it
                            mapLibreMap?.let { map ->
                                applyMapStyle(map, currentLayer, true, sunHour) {
                                    activeTrack?.let { t -> drawTrackOnMap(map, t.points) }
                                }
                            }
                        },
                        valueRange = 5f..21f,
                        steps = 15
                    )
                }
            }
        }

        // OVERLAY INCLINOMETRO PENDII (se attivato dal menu)
        if (showInclinometer) {
            val riskColor = when {
                slopePitchDegrees in 30..45 -> Color(0xFFD32F2F)
                slopePitchDegrees > 45 -> Color(0xFFE65100)
                else -> Color(0xFF2E7D32)
            }
            val riskText = when {
                slopePitchDegrees in 30..45 -> "FASCIA CRITICA VALANGHE (30°-45°)"
                slopePitchDegrees > 45 -> "CANALE RIPIDO / ALPINISTICO (>45°)"
                else -> "PENDENZA MODERATA (<30°)"
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 135.dp, start = 24.dp, end = 24.dp)
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                color = riskColor
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Inclinazione: $slopePitchDegrees°", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(riskText, color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp)
                    }
                    IconButton(onClick = { showInclinometer = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Chiudi", tint = Color.White)
                    }
                }
            }
        }

        // INDICATORE PROGRESSO DOWNLOAD OFFLINE
        offlineProgress?.let { prog ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 135.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF1B4332)
            ) {
                Text(
                    text = "Aggiornamento / Download offline: ${(prog * 100).toInt()}%",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    fontSize = 13.sp
                )
            }
        }

        // PANNELLO MENU STRUMENTI UNIFICATO ("MAGRO")
        if (showToolsMenu) {
            AlertDialog(
                onDismissRequest = { showToolsMenu = false },
                containerColor = Color.White,
                title = { Text("Strumenti Alpinismo", fontWeight = FontWeight.Bold, color = Color(0xFF1B4332)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToolMenuRow(Icons.Default.WbSunny, "Simulatore Sole / Ombra 3D", "Verifica orario sole su canali e falesie") {
                            showToolsMenu = false
                            is3DEnabled = true
                            showSunSimulator = true
                        }
                        ToolMenuRow(Icons.Default.Straighten, "Inclinometro Pendii", "Misura pendenza in gradi (30°-45° valanghe)") {
                            showToolsMenu = false
                            showInclinometer = true
                        }
                        ToolMenuRow(Icons.Default.PhotoCamera, "Riconoscimento Cime AR (Fotocamera)", "Inquadra l'orizzonte con nome e quota delle vette") {
                            showToolsMenu = false
                            mapLibreMap?.projection?.visibleRegion?.latLngBounds?.let { b ->
                                scope.launch {
                                    val (pois, peaks) = RoutingService.fetchPoisAndPeaksInBounds(
                                        context, b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast
                                    )
                                    allLoadedPois = pois
                                    allLoadedPeaks = peaks
                                }
                            }
                            showArCamera = true
                        }
                        ToolMenuRow(Icons.Default.ShareLocation, "Live Tracking con Email", "Condividi link posizione live (con coda offline)") {
                            showToolsMenu = false
                            showLiveShareDialog = true
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Ultra Risparmio Batteria", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Black)
                                Text("GPS ogni 12s/15m per uscite lunghe 8-9h", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = ultraBatteryEnabled,
                                onCheckedChange = {
                                    ultraBatteryEnabled = it
                                    TrackingService.ultraBatteryMode = it
                                }
                            )
                        }
                        ToolMenuRow(Icons.Default.Route, "Pianifica Percorso / Importa GPX", "Traccia itinerario o apri file GPX") {
                            showToolsMenu = false
                            gpxImporter.launch(arrayOf("*/*"))
                        }
                        ToolMenuRow(Icons.Default.DownloadForOffline, "Mappe Offline & Aggiorna Dati", "Scarica o aggiorna mappe, POI e meteo locale") {
                            showToolsMenu = false
                            refreshSavedRegions()
                            showOfflineDialog = true
                        }
                        HorizontalDivider()
                        ToolMenuRow(Icons.Default.Sos, "SOS Soccorso Alpino (112 / GeoResQ)", "Prepara SMS/WhatsApp con WGS84 + UTM + Quota", tint = Color(0xFFD32F2F)) {
                            showToolsMenu = false
                            showSosConfirmDialog = true
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showToolsMenu = false }) {
                        Text("Chiudi", color = Color(0xFF1B4332))
                    }
                }
            )
        }

        // IDENTIFICATORE CIME / ORIZZONTE
        if (showPeakFinderDialog) {
            val refPoint = userCurrentLatLng ?: mapLibreMap?.cameraPosition?.target ?: LatLng(45.5, 7.6)
            val sortedPeaks = allLoadedPeaks.map { pk ->
                val d = GpxManager.haversineDistance(refPoint.latitude, refPoint.longitude, pk.lat, pk.lon)
                val brg = RoutingService.calculateBearing(refPoint.latitude, refPoint.longitude, pk.lat, pk.lon)
                Triple(pk, d, brg)
            }.sortedBy { it.second }.take(20)

            AlertDialog(
                onDismissRequest = { showPeakFinderDialog = false },
                containerColor = Color.White,
                title = { Text("Cime nell'Orizzonte", fontWeight = FontWeight.Bold, color = Color.Black) },
                text = {
                    if (sortedPeaks.isEmpty()) {
                        Text("Nessuna cima caricata in questa area. Avvicina o sposta la mappa su un gruppo montuoso.", color = Color.Gray)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                            items(sortedPeaks) { (pk, dist, brg) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showPeakFinderDialog = false
                                            val dest = LatLng(pk.lat, pk.lon)
                                            targetWaypoint = "${pk.name} (${pk.elevationMeters}m)" to dest
                                            drawTargetLine(mapLibreMap, userCurrentLatLng, dest)
                                            mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(dest, 14.0))
                                        }
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${pk.name} (${pk.elevationMeters} m)", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 14.sp)
                                        Text("Rilevamento bussola: $brg°", fontSize = 12.sp, color = Color.Gray)
                                    }
                                    Text(String.format(Locale.US, "%.1f km", dist / 1000.0), fontWeight = FontWeight.Bold, color = Color(0xFF1B4332), fontSize = 13.sp)
                                }
                                HorizontalDivider(color = Color(0xFFEEEEEE))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPeakFinderDialog = false }) { Text("Chiudi", color = Color(0xFF1B4332)) }
                }
            )
        }

        // FINESTRA SOS SOCCORSO ALPINO (PROTETTA)
        if (showSosConfirmDialog) {
            val loc = mapLibreMap?.locationComponent?.lastKnownLocation
            val lat = loc?.latitude ?: mapLibreMap?.cameraPosition?.target?.latitude ?: 0.0
            val lon = loc?.longitude ?: mapLibreMap?.cameraPosition?.target?.longitude ?: 0.0
            val utm = RoutingService.toUtmString(lat, lon)
            val alt = baroAltitude?.toInt() ?: loc?.altitude?.toInt() ?: 0
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val bat = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val sosMsg = "EMERGENZA SOCCORSO ALPINO\nPosizione WGS84: ${String.format(Locale.US, "%.5f, %.5f", lat, lon)}\nCoordinate UTM: $utm\nQuota: ${alt}m slm\nBatteria telefono: $bat%"

            AlertDialog(
                onDismissRequest = { showSosConfirmDialog = false },
                containerColor = Color.White,
                title = { Text("SOS Soccorso Alpino (112)", fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F)) },
                text = { Text(sosMsg, color = Color.Black, fontSize = 13.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            showSosConfirmDialog = false
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, sosMsg)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Invia coordinate SOS tramite"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) {
                        Text("Invia Coordinate SOS", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSosConfirmDialog = false }) { Text("Annulla", color = Color.Gray) }
                }
            )
        }

        // GESTIONE MAPPE OFFLINE & AGGIORNAMENTO DATI
        if (showOfflineDialog) {
            AlertDialog(
                onDismissRequest = { showOfflineDialog = false },
                containerColor = Color.White,
                title = { Text("Mappe Offline & Aggiorna Dati", fontWeight = FontWeight.Bold, color = Color.Black) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Scarica l'area inquadrata (mappa, cime 3D, rifugi, sorgenti, ferrate e bollettini) oppure aggiorna i dati locali sotto Wi-Fi.",
                            fontSize = 13.sp,
                            color = Color.DarkGray
                        )
                        Button(
                            onClick = {
                                showOfflineDialog = false
                                mapLibreMap?.let { map ->
                                    val b = map.projection.visibleRegion.latLngBounds
                                    scope.launch {
                                        val (pois, peaks) = RoutingService.fetchPoisAndPeaksInBounds(
                                            context, b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast
                                        )
                                        allLoadedPois = pois
                                        allLoadedPeaks = peaks
                                        val center = map.cameraPosition.target
                                        if (center != null) {
                                            weatherData = RoutingService.fetchFullAlpineWeather(context, center.latitude, center.longitude, null)
                                        }
                                    }
                                    downloadCurrentRegionOffline(context, map, currentLayer) { prog ->
                                        offlineProgress = prog
                                        if (prog == null) refreshSavedRegions()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B4332))
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scarica / Aggiorna Area Inquadrata", color = Color.White)
                        }

                        if (savedRegions.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Aree salvate in locale (${savedRegions.size}):", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black)
                            savedRegions.forEach { (title, reg) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(title, fontSize = 13.sp, color = Color.Black, modifier = Modifier.weight(1f))
                                    IconButton(onClick = {
                                        reg.setDownloadState(OfflineRegion.STATE_ACTIVE)
                                        Toast.makeText(context, "Aggiornamento tessere avviato...", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Aggiorna", tint = Color(0xFF1B4332))
                                    }
                                    IconButton(onClick = {
                                        reg.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                                            override fun onDelete() { refreshSavedRegions() }
                                            override fun onError(error: String) {}
                                        })
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Elimina", tint = Color.Red)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showOfflineDialog = false }) { Text("Chiudi", color = Color(0xFF1B4332)) }
                }
            )
        }

        // SCHEDA METEO ALPINISTICO 5 GIORNI + BOLLETTINO VALANGHE + ALLERTE UFFICIALI
        if (showWeatherSheet) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .shadow(16.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Meteo Alpinismo & Valanghe", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF1B4332))
                        IconButton(onClick = { showWeatherSheet = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Chiudi", tint = Color.Gray)
                        }
                    }

                    // Selettore Quota Reale (Modello vs 2000m, 3000m, 4000m)
                    val altOptions = listOf(null to "Quota Punto", 2000 to "2000 m", 3000 to "3000 m", 4000 to "4000 m")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        altOptions.forEach { (altVal, lbl) ->
                            FilterChip(
                                selected = selectedAltitudeOverride == altVal,
                                onClick = {
                                    selectedAltitudeOverride = altVal
                                    weatherTargetLatLng?.let { triggerWeatherAnalysis(it, altVal) }
                                },
                                label = { Text(lbl, fontSize = 11.sp) }
                            )
                        }
                    }

                    if (isLoadingWeather) {
                        Row(modifier = Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF1B4332))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Scaricamento modelli alta risoluzione e bollettino valanghe...", fontSize = 13.sp, color = Color.Gray)
                        }
                    } else if (weatherData != null) {
                        val wd = weatherData!!

                        // Eventuale Allerta Ufficiale Protezione Civile / MeteoAlarm
                        wd.officialAlert?.let { alertMsg ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFFFFEBEE)
                            ) {
                                Text(alertMsg, color = Color(0xFFC62828), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                            }
                        }

                        // Riquadro Bollettino Valanghe Ufficiale
                        wd.avalanche?.let { av ->
                            val badgeColor = when (av.dangerRating) {
                                1 -> Color(0xFF4CAF50)
                                2 -> Color(0xFFFBC02D)
                                3 -> Color(0xFFF57C00)
                                4, 5 -> Color(0xFFD32F2F)
                                else -> Color(0xFF455A64)
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFF5F7F8)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(shape = RoundedCornerShape(6.dp), color = badgeColor) {
                                            Text(av.dangerLabel, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(av.regionName, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black)
                                    }
                                    Text("Problema: ${av.problemTitle}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1B4332), modifier = Modifier.padding(top = 4.dp))
                                    Text(av.summaryText, fontSize = 11.sp, color = Color.DarkGray, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                        }

                        // Selettore Giorni (5 giorni)
                        if (wd.days.isNotEmpty()) {
                            val safeIndex = minOf(selectedDayIndex, wd.days.size - 1)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                wd.days.forEachIndexed { idx, day ->
                                    FilterChip(
                                        selected = safeIndex == idx,
                                        onClick = { selectedDayIndex = idx },
                                        label = { Text(day.dateLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                    )
                                }
                            }

                            // Tabella Oraria (00 - 21)
                            val currentDay = wd.days[safeIndex]
                            Row(
                                modifier = Modifier.fillMaxWidth().background(Color(0xFF1B4332)).padding(vertical = 6.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Ora", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
                                Text("T / Perc", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
                                Text("Zero 0°C", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.1f))
                                Text("Vento/Raf", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.3f))
                                Text("Nuv.B/M", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            }

                            currentDay.slots.forEach { s ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(s.timeLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.weight(0.9f))
                                    Text("${s.tempAtTarget}°/${s.feelsLike}°", fontSize = 11.sp, color = if (s.tempAtTarget <= 0) Color(0xFF0277BD) else Color.Black, modifier = Modifier.weight(1.2f))
                                    Text("${s.freezingLevel}m", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1B4332), modifier = Modifier.weight(1.1f))
                                    Text("${s.windKmh}/${s.gustsKmh}kmh", fontSize = 11.sp, color = if (s.gustsKmh >= 55) Color(0xFFD32F2F) else Color.Black, modifier = Modifier.weight(1.3f))
                                    Text("${s.cloudLow}%/${s.cloudMid}%", fontSize = 11.sp, color = Color.DarkGray, modifier = Modifier.weight(1f))
                                }
                                HorizontalDivider(color = Color(0xFFEEEEEE))
                            }
                        }
                    } else {
                        Text("Tocca un punto o una vetta per caricare il bollettino meteo e valanghe.", color = Color.Gray, fontSize = 13.sp)
                    }
                }
            }
        }


        // DIALOG CONDIVISIONE LIVE TRACKING VIA EMAIL
        if (showLiveShareDialog) {
            val topicId = remember { "mapyoutdoor_${System.currentTimeMillis().toString().takeLast(6)}" }
            val liveUrl = "https://ntfy.sh/$topicId"
            AlertDialog(
                onDismissRequest = { showLiveShareDialog = false },
                containerColor = Color.White,
                title = { Text("Live Tracking via Email", fontWeight = FontWeight.Bold, color = Color(0xFF1B4332)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Inserisci l'email della persona che ti segue da casa. Durante la registrazione l'app invierà aggiornamenti periodici (accodandoli se sei offline).",
                            fontSize = 13.sp,
                            color = Color.DarkGray
                        )
                        OutlinedTextField(
                            value = contactEmail,
                            onValueChange = { contactEmail = it },
                            label = { Text("Email contatto") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            TrackingService.liveTrackingTopic = topicId
                            showLiveShareDialog = false
                            if (!isRecording) {
                                context.startForegroundService(Intent(context, TrackingService::class.java))
                            }
                            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:")
                                putExtra(Intent.EXTRA_EMAIL, arrayOf(contactEmail))
                                putExtra(Intent.EXTRA_SUBJECT, "Seguimi in diretta su MapyOutdoor")
                                putExtra(Intent.EXTRA_TEXT, "Ciao, puoi seguire i miei aggiornamenti GPS in quota (coordinate, altitudine e link mappa) aprendo questa pagina dal browser:\n\n$liveUrl\n\n(Gli aggiornamenti arrivano automaticamente ogni volta che il telefono aggancia rete).")
                            }
                            try {
                                context.startActivity(emailIntent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Canale Live attivo: $liveUrl", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B4332))
                    ) {
                        Text("Attiva e Invia Email", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLiveShareDialog = false }) { Text("Annulla", color = Color.Gray) }
                }
            )
        }

        // SCHERMATA FOTOCAMERA AR RICONOSCIMENTO CIME
        if (showArCamera) {
            val refLoc = userCurrentLatLng ?: mapLibreMap?.cameraPosition?.target ?: LatLng(45.8326, 6.8652)
            ArPeakFinderOverlay(
                userLatLng = refLoc,
                compassAzimuth = compassAzimuth,
                peaks = allLoadedPeaks,
                onSelectPeak = { pk ->
                    showArCamera = false
                    val dest = LatLng(pk.lat, pk.lon)
                    targetWaypoint = "${pk.name} (${pk.elevationMeters}m)" to dest
                    drawTargetLine(mapLibreMap, userCurrentLatLng, dest)
                    mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(dest, 14.5))
                },
                onClose = { showArCamera = false }
            )
        }

        // CONTROLLI IN BASSO (REGISTRA + POSIZIONE + PANNELLO GPX)
        if (!showWeatherSheet) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                // Bussola circolare grafica in basso a sinistra (Tap = Heading-Up, Hold = Blocca Nord)
                Box(
                    modifier = Modifier
                        .padding(start = 16.dp, bottom = 4.dp)
                        .size(64.dp)
                        .shadow(8.dp, CircleShape)
                        .background(Color(0xFF101820).copy(alpha = 0.92f), CircleShape)
                        .combinedClickable(
                            onClick = {
                                if (!isNorthLocked) {
                                    mapLibreMap?.let { map ->
                                        map.animateCamera(
                                            CameraUpdateFactory.newCameraPosition(
                                                CameraPosition.Builder()
                                                    .target(map.cameraPosition.target)
                                                    .zoom(map.cameraPosition.zoom)
                                                    .tilt(map.cameraPosition.tilt)
                                                    .bearing(compassAzimuth.toDouble())
                                                    .build()
                                            )
                                        )
                                    }
                                    Toast.makeText(context, "Mappa orientata sulla tua direzione", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Nord bloccato (tieni premuto per sbloccare)", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onLongClick = {
                                isNorthLocked = !isNorthLocked
                                if (isNorthLocked) {
                                    mapLibreMap?.let { map ->
                                        map.animateCamera(
                                            CameraUpdateFactory.newCameraPosition(
                                                CameraPosition.Builder()
                                                    .target(map.cameraPosition.target)
                                                    .zoom(map.cameraPosition.zoom)
                                                    .tilt(map.cameraPosition.tilt)
                                                    .bearing(0.0)
                                                    .build()
                                            )
                                        )
                                    }
                                    Toast.makeText(context, "NORD BLOCCATO (0°)", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Nord sbloccato", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val displayAngle = if (isNorthLocked) 0f else -compassAzimuth
                    Box(modifier = Modifier.fillMaxSize().rotate(displayAngle), contentAlignment = Alignment.Center) {
                        Text("N", color = Color(0xFFE63946), fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 3.dp))
                        Text("S", color = Color.LightGray, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp))
                        Text("E", color = Color.LightGray, fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
                        Text("O", color = Color.LightGray, fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
                        Icon(
                            Icons.Default.Navigation,
                            contentDescription = "Ago Bussola",
                            tint = if (isNorthLocked) Color(0xFFFFD166) else Color(0xFFE63946),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    if (isNorthLocked) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Nord Bloccato",
                            tint = Color(0xFFFFD166),
                            modifier = Modifier.size(12.dp).align(Alignment.BottomEnd).padding(4.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            val intent = Intent(context, TrackingService::class.java)
                            if (isRecording) {
                                context.stopService(intent)
                                Toast.makeText(context, "Traccia salvata", Toast.LENGTH_SHORT).show()
                            } else {
                                context.startForegroundService(intent)
                            }
                        },
                        containerColor = if (isRecording) Color(0xFFD32F2F) else Color(0xFF1B4332),
                        contentColor = Color.White,
                        icon = { Icon(if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord, contentDescription = null) },
                        text = { Text(if (isRecording) "Stop (${recordedPoints.size} pt)" else "Registra") }
                    )

                    FloatingActionButton(
                        onClick = {
                            mapLibreMap?.locationComponent?.lastKnownLocation?.let { loc ->
                                userCurrentLatLng = LatLng(loc.latitude, loc.longitude)
                                mapLibreMap?.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15.0)
                                )
                            }
                        },
                        containerColor = Color.White,
                        contentColor = Color(0xFF1B4332),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "La mia posizione")
                    }
                }

                activeTrack?.let { track ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(12.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        color = Color.White
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(track.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.Black, modifier = Modifier.weight(1f))
                                IconButton(onClick = { gpxExporter.launch("${track.name.replace(" ", "_")}.gpx") }) {
                                    Icon(Icons.Default.Share, contentDescription = "Esporta GPX", tint = Color(0xFF1B4332))
                                }
                                IconButton(onClick = {
                                    activeTrack = null
                                    drawTrackOnMap(mapLibreMap, emptyList())
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Chiudi", tint = Color.Gray)
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                                StatBox("Distanza", String.format(Locale.US, "%.2f km", track.totalDistanceMeters / 1000.0))
                                StatBox("Dislivello +", "${track.elevationGain.toInt()} m")
                                StatBox("Dislivello -", "${track.elevationLoss.toInt()} m")
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            ElevationChart(points = track.points)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color = Color(0xFF1B4332),
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Black)
            Text(subtitle, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun StatBox(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = Color.Gray)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B4332))
    }
}

private fun getCleanStyleJson(type: MapLayerType): String {
    return when (type) {
        MapLayerType.ALPINE -> """
        {
          "version": 8,
          "sources": {
            "topo": {
              "type": "raster",
              "tiles": ["https://tile.opentopomap.org/{z}/{x}/{y}.png"],
              "tileSize": 256
            },
            "trails": {
              "type": "raster",
              "tiles": ["https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png"],
              "tileSize": 256
            }
          },
          "layers": [
            { "id": "topo", "type": "raster", "source": "topo" },
            { "id": "hiking", "type": "raster", "source": "trails" }
          ]
        }
        """.trimIndent()
        MapLayerType.SWISSTOPO -> """
        {
          "version": 8,
          "sources": {
            "swiss": {
              "type": "raster",
              "tiles": ["https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-farbe/default/current/3857/{z}/{x}/{y}.jpeg"],
              "tileSize": 256
            }
          },
          "layers": [
            { "id": "swiss-layer", "type": "raster", "source": "swiss" }
          ]
        }
        """.trimIndent()
        MapLayerType.SLOPES -> """
        {
          "version": 8,
          "sources": {
            "topo": {
              "type": "raster",
              "tiles": ["https://tile.opentopomap.org/{z}/{x}/{y}.png"],
              "tileSize": 256
            },
            "slopes": {
              "type": "raster",
              "tiles": ["https://tile.waymarkedtrails.org/slopes/{z}/{x}/{y}.png"],
              "tileSize": 256
            }
          },
          "layers": [
            { "id": "topo", "type": "raster", "source": "topo" },
            { "id": "slopes", "type": "raster", "source": "slopes" }
          ]
        }
        """.trimIndent()
        MapLayerType.SATELLITE -> """
        {
          "version": 8,
          "sources": {
            "sat": {
              "type": "raster",
              "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],
              "tileSize": 256
            }
          },
          "layers": [
            { "id": "sat-layer", "type": "raster", "source": "sat" }
          ]
        }
        """.trimIndent()
    }
}

private fun applyMapStyle(map: MapLibreMap, type: MapLayerType, enable3D: Boolean, sunHour: Float, onLoaded: () -> Unit) {
    var json = getCleanStyleJson(type)
    if (enable3D) {
        val demBlock = """
            "terrain-dem": {
              "type": "raster-dem",
              "tiles": ["https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"],
              "tileSize": 256,
              "encoding": "terrarium"
            },
        """.trimIndent()
        json = json.replace("\"sources\": {", "\"sources\": {\n$demBlock")
        json = json.replace("}\n        }".trim(), "},\n          \"terrain\": { \"source\": \"terrain-dem\", \"exaggeration\": 1.45 }\n        }")
        if (!json.contains("\"terrain\"")) {
            json = json.substringBeforeLast("}") + ",\n  \"terrain\": { \"source\": \"terrain-dem\", \"exaggeration\": 1.45 }\n}"
        }
    }
    map.setStyle(Style.Builder().fromJson(json)) { style ->
        if (enable3D) {
            try {
                val sunAzimuth = ((sunHour - 6f) / 14f * 180f + 90f).coerceIn(0f, 360f)
                val hillshade = HillshadeLayer("terrain-hillshade", "terrain-dem").withProperties(
                    PropertyFactory.hillshadeExaggeration(0.50f),
                    PropertyFactory.hillshadeIlluminationDirection(sunAzimuth),
                    PropertyFactory.hillshadeShadowColor("#0D1B2A")
                )
                style.addLayer(hillshade)
            } catch (_: Exception) {
            }
        }
        onLoaded()
    }
}

private fun drawPeaksOnMap(map: MapLibreMap?, peaks: List<PeakItem>) {
    val style = map?.style ?: return
    val sourceId = "peaks-source"
    val layerId = "peaks-labels"
    val features = peaks.map { pk ->
        Feature.fromGeometry(Point.fromLngLat(pk.lon, pk.lat)).apply {
            addStringProperty("label", "▲ ${pk.name} ${pk.elevationMeters}m")
        }
    }
    val collection = FeatureCollection.fromFeatures(features)
    val existing = style.getSourceAs<GeoJsonSource>(sourceId)
    if (existing != null) {
        existing.setGeoJson(collection)
    } else {
        style.addSource(GeoJsonSource(sourceId, collection))
        val sym = SymbolLayer(layerId, sourceId).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textHaloColor("#1B4332"),
            PropertyFactory.textHaloWidth(2f),
            PropertyFactory.textAllowOverlap(false)
        )
        style.addLayer(sym)
    }
}

private fun drawPoisOnMap(map: MapLibreMap?, pois: List<PoiItem>) {
    val style = map?.style ?: return
    val sourceId = "poi-source"
    val layerId = "poi-circles"

    val features = pois.map { poi ->
        Feature.fromGeometry(Point.fromLngLat(poi.lon, poi.lat)).apply {
            addStringProperty("title", poi.name)
            addStringProperty("cat", poi.category.label)
            addStringProperty("color", poi.category.colorHex)
            addStringProperty("ele", poi.elevation)
        }
    }
    val collection = FeatureCollection.fromFeatures(features)
    val existing = style.getSourceAs<GeoJsonSource>(sourceId)
    if (existing != null) {
        existing.setGeoJson(collection)
    } else {
        style.addSource(GeoJsonSource(sourceId, collection))
        val circleLayer = CircleLayer(layerId, sourceId).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor("#FFFFFF")
        )
        style.addLayer(circleLayer)
    }
}

private fun drawTargetLine(map: MapLibreMap?, start: LatLng?, end: LatLng?) {
    val style = map?.style ?: return
    val sourceId = "target-line-source"
    val layerId = "target-line-layer"
    val points = if (start != null && end != null) {
        listOf(Point.fromLngLat(start.longitude, start.latitude), Point.fromLngLat(end.longitude, end.latitude))
    } else emptyList()

    val feature = Feature.fromGeometry(LineString.fromLngLats(points))
    val existing = style.getSourceAs<GeoJsonSource>(sourceId)
    if (existing != null) {
        existing.setGeoJson(feature)
    } else {
        style.addSource(GeoJsonSource(sourceId, feature))
        val layer = LineLayer(layerId, sourceId).withProperties(
            PropertyFactory.lineColor("#FF9800"),
            PropertyFactory.lineWidth(3.5f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f))
        )
        style.addLayer(layer)
    }
}

private fun drawTrackOnMap(map: MapLibreMap?, points: List<TrackPoint>) {
    val style = map?.style ?: return
    val sourceId = "gpx-track-source"
    val layerId = "gpx-track-layer"

    val geoPoints = points.map { Point.fromLngLat(it.lon, it.lat) }
    val lineString = LineString.fromLngLats(geoPoints)
    val feature = Feature.fromGeometry(lineString)

    val existingSource = style.getSourceAs<GeoJsonSource>(sourceId)
    if (existingSource != null) {
        existingSource.setGeoJson(feature)
    } else {
        style.addSource(GeoJsonSource(sourceId, feature))
        val layer = LineLayer(layerId, sourceId).withProperties(
            PropertyFactory.lineColor("#D32F2F"),
            PropertyFactory.lineWidth(4.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
        style.addLayer(layer)
    }
}

private fun zoomToTrack(map: MapLibreMap?, points: List<TrackPoint>) {
    if (map == null || points.size < 2) return
    val builder = LatLngBounds.Builder()
    points.forEach { builder.include(LatLng(it.lat, it.lon)) }
    map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100))
}

@Suppress("MissingPermission")
private fun enableUserLocation(context: Context, map: MapLibreMap) {
    try {
        val style = map.style ?: return
        val locationComponent = map.locationComponent
        locationComponent.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style).build()
        )
        locationComponent.isLocationComponentEnabled = true
        locationComponent.cameraMode = CameraMode.NONE
        locationComponent.renderMode = RenderMode.COMPASS
    } catch (_: Exception) {
    }
}

private fun downloadCurrentRegionOffline(
    context: Context,
    map: MapLibreMap,
    layerType: MapLayerType,
    onProgress: (Float?) -> Unit
) {
    val bounds = map.projection.visibleRegion.latLngBounds
    val styleJson = getCleanStyleJson(layerType)
    val offlineManager = OfflineManager.getInstance(context)
    offlineManager.setOfflineMapboxTileCountLimit(500_000L)

    val definition = OfflineTilePyramidRegionDefinition(
        styleJson,
        bounds,
        10.0,
        15.0,
        context.resources.displayMetrics.density
    )

    val metadata = "Area_${layerType.name}".toByteArray(Charset.forName("UTF-8"))
    Toast.makeText(context, "Avvio scaricamento offline...", Toast.LENGTH_SHORT).show()

    offlineManager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
        override fun onCreate(offlineRegion: OfflineRegion) {
            offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    val percentage = if (status.requiredResourceCount > 0) {
                        status.completedResourceCount.toFloat() / status.requiredResourceCount.toFloat()
                    } else 0f
                    onProgress(percentage)
                    if (status.isComplete) {
                        onProgress(null)
                        Toast.makeText(context, "Mappa, POI, cime e bollettini aggiornati offline!", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onError(error: OfflineRegionError) {
                    onProgress(null)
                    Toast.makeText(context, "Errore download: ${error.message}", Toast.LENGTH_SHORT).show()
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    onProgress(null)
                    Toast.makeText(context, "Area troppo grande: avvicina lo zoom", Toast.LENGTH_LONG).show()
                }
            })
        }

        override fun onError(error: String) {
            Toast.makeText(context, "Errore offline: $error", Toast.LENGTH_SHORT).show()
        }
    })
}
