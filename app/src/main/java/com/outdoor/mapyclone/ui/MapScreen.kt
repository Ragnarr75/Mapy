package com.outdoor.mapyclone.ui

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var currentLayer by remember { mutableStateOf(MapLayerType.OUTDOOR) }
    var showLayerMenu by remember { mutableStateOf(false) }
    var is3DEnabled by remember { mutableStateOf(false) }

    // Ricerca luoghi
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchPlace>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    // POI selezionabili + cache offline
    var allLoadedPois by remember { mutableStateOf<List<PoiItem>>(emptyList()) }
    var activePoiFilters by remember { mutableStateOf<Set<PoiCategory>>(emptySet()) }
    var isLoadingPois by remember { mutableStateOf(false) }

    // Tracce GPX e Navigazione Offline
    var activeTrack by remember { mutableStateOf<GpxTrack?>(null) }
    var routeStart by remember { mutableStateOf<TrackPoint?>(null) }
    var isPlanningMode by remember { mutableStateOf(false) }
    var targetWaypoint by remember { mutableStateOf<Pair<String, LatLng>?>(null) }
    var userCurrentLatLng by remember { mutableStateOf<LatLng?>(null) }

    // Meteo in quota + Strumenti Alpinismo
    var selectedWeatherPoint by remember { mutableStateOf<LatLng?>(null) }
    var weatherReport by remember { mutableStateOf<AlpineWeatherReport?>(null) }
    var isLoadingWeather by remember { mutableStateOf(false) }
    var showAlpineHud by remember { mutableStateOf(false) }
    var baroAltitude by remember { mutableStateOf<Float?>(null) }

    // Download Offline Area
    var offlineProgress by remember { mutableStateOf<Float?>(null) }
    var showOfflineConfirm by remember { mutableStateOf(false) }

    val isRecording by TrackingService.isRecording.collectAsState()
    val recordedPoints by TrackingService.recordedPoints.collectAsState()

    // Sensore barometrico per altimetro reale S23 Ultra
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val hPa = event?.values?.firstOrNull() ?: return
                baroAltitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hPa)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (pressureSensor != null) {
            sensorManager.registerListener(listener, pressureSensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    LaunchedEffect(Unit) {
        OfflineManager.getInstance(context).setOfflineMapboxTileCountLimit(500_000L)
        allLoadedPois = RoutingService.loadCachedPois(context)
    }

    // Aggiorna POI disegnati sulla mappa quando cambiano filtri o lista
    LaunchedEffect(activePoiFilters, allLoadedPois, mapLibreMap) {
        val filtered = allLoadedPois.filter { it.category in activePoiFilters }
        drawPoisOnMap(mapLibreMap, filtered)
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

    // Ricerca luoghi Photon con debounce
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

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    getMapAsync { map ->
                        mapLibreMap = map
                        applyMapStyle(map, currentLayer, is3DEnabled) {
                            enableUserLocation(ctx, map)
                        }
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(44.85, 9.85))
                            .zoom(11.0)
                            .build()

                        // Tocco rapido sulla mappa: controllo se ha toccato un POI
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

                        // Pressione prolungata sulla mappa: Meteo in quota + Rotta/Navigazione Offline
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
                                        Toast.makeText(ctx, "Calcolo percorso...", Toast.LENGTH_SHORT).show()
                                        val route = RoutingService.calculateHikingRoute(start, clicked)
                                        if (route != null) {
                                            activeTrack = route
                                            drawTrackOnMap(map, route.points)
                                            zoomToTrack(map, route.points)
                                        }
                                    }
                                }
                            } else {
                                selectedWeatherPoint = latLng
                                targetWaypoint = String.format(Locale.US, "Punto %.4f, %.4f", latLng.latitude, latLng.longitude) to latLng
                                drawTargetLine(map, userCurrentLatLng, latLng)
                                isLoadingWeather = true
                                weatherReport = null
                                scope.launch {
                                    weatherReport = RoutingService.fetchAlpineWeather(latLng.latitude, latLng.longitude)
                                    isLoadingWeather = false
                                }
                            }
                            true
                        }
                    }
                }
            }
        )

        // BARRA SUPERIORE: RICERCA + CHIP POI SELEZIONABILI
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(6.dp, RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
                color = Color.White
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF1B4332))
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Cerca vette, rifugi, passi...", color = Color.Gray, fontSize = 14.sp) },
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

            // Risultati ricerca Photon
            if (searchResults.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .shadow(4.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White
                ) {
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
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
                    .padding(top = 8.dp)
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
                                        allLoadedPois = RoutingService.fetchPoisInBounds(
                                            context, b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast
                                        )
                                        isLoadingPois = false
                                    }
                                }
                            }
                        },
                        label = {
                            Text(
                                text = if (isLoadingPois && selected) "${cat.label}..." else cat.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.White.copy(alpha = 0.95f),
                            labelColor = Color.Black,
                            selectedContainerColor = Color(android.graphics.Color.parseColor(cat.colorHex)),
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            // HUD Alpinismo (Altimetro barometrico + UTM + Bussola bersaglio)
            if (showAlpineHud || targetWaypoint != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .shadow(4.dp, RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1B4332).copy(alpha = 0.94f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        val loc = mapLibreMap?.locationComponent?.lastKnownLocation
                        if (loc != null) {
                            userCurrentLatLng = LatLng(loc.latitude, loc.longitude)
                        }
                        val altStr = baroAltitude?.let { "${it.toInt()} m (Baro)" }
                            ?: loc?.altitude?.let { "${it.toInt()} m (GPS)" } ?: "N/D"
                        val utmStr = loc?.let { RoutingService.toUtmString(it.latitude, it.longitude) } ?: "Attesa GPS..."

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Quota: $altStr", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("UTM: $utmStr", color = Color(0xFFB7E4C7), fontSize = 11.sp)
                        }

                        targetWaypoint?.let { (wpName, wpLatLng) ->
                            Spacer(modifier = Modifier.height(4.dp))
                            val distTxt = userCurrentLatLng?.let { u ->
                                val d = GpxManager.haversineDistance(u.latitude, u.longitude, wpLatLng.latitude, wpLatLng.longitude)
                                if (d >= 1000) String.format(Locale.US, "%.2f km", d / 1000.0) else "${d.toInt()} m"
                            } ?: "N/D"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Obiettivo: $wpName ($distTxt)",
                                    color = Color(0xFFFFD166),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Chiudi obiettivo",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable {
                                            targetWaypoint = null
                                            drawTargetLine(mapLibreMap, null, null)
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }

        // PULSANTI LATERALI DESTRA
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            // Vista 3D tipo PeakVisor
            SmallFloatingActionButton(
                onClick = {
                    is3DEnabled = !is3DEnabled
                    mapLibreMap?.let { map ->
                        applyMapStyle(map, currentLayer, is3DEnabled) {
                            activeTrack?.let { drawTrackOnMap(map, it.points) }
                            val filtered = allLoadedPois.filter { it.category in activePoiFilters }
                            drawPoisOnMap(map, filtered)
                        }
                        val targetPitch = if (is3DEnabled) 60.0 else 0.0
                        val targetZoom = if (is3DEnabled && map.cameraPosition.zoom < 12.5) 13.0 else map.cameraPosition.zoom
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
                    Toast.makeText(context, if (is3DEnabled) "Vista 3D Orografica attiva (ruota con 2 dita)" else "Vista 2D", Toast.LENGTH_SHORT).show()
                },
                containerColor = if (is3DEnabled) Color(0xFF1B4332) else Color.White,
                contentColor = if (is3DEnabled) Color.White else Color(0xFF1B4332)
            ) {
                Text("3D", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            // Stili mappa (incluso Pendenze/Canali 30-45°)
            SmallFloatingActionButton(
                onClick = { showLayerMenu = !showLayerMenu },
                containerColor = Color.White,
                contentColor = Color(0xFF1B4332)
            ) {
                Icon(Icons.Default.Layers, contentDescription = "Stili Mappa")
            }

            // Strumenti Alpinismo (Altimetro + UTM)
            SmallFloatingActionButton(
                onClick = { showAlpineHud = !showAlpineHud },
                containerColor = if (showAlpineHud) Color(0xFF1B4332) else Color.White,
                contentColor = if (showAlpineHud) Color.White else Color(0xFF1B4332)
            ) {
                Icon(Icons.Default.Explore, contentDescription = "Strumenti Alpinismo")
            }

            // Pianifica percorso
            SmallFloatingActionButton(
                onClick = {
                    isPlanningMode = !isPlanningMode
                    routeStart = null
                    val msg = if (isPlanningMode) "Tieni premuto sulla partenza, poi sulla meta" else "Pianificazione annullata"
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                },
                containerColor = if (isPlanningMode) Color(0xFF1B4332) else Color.White,
                contentColor = if (isPlanningMode) Color.White else Color(0xFF1B4332)
            ) {
                Icon(Icons.Default.DirectionsWalk, contentDescription = "Pianifica")
            }

            // Importa GPX
            SmallFloatingActionButton(
                onClick = { gpxImporter.launch(arrayOf("*/*")) },
                containerColor = Color.White,
                contentColor = Color(0xFF1B4332)
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = "Apri GPX")
            }

            // Download Area Offline (sbloccato e con stile JSON pulito)
            SmallFloatingActionButton(
                onClick = { showOfflineConfirm = true },
                containerColor = Color.White,
                contentColor = Color(0xFF1B4332)
            ) {
                Icon(Icons.Default.DownloadForOffline, contentDescription = "Scarica Offline")
            }
        }

        // MENU LAYER MAPPA
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
                                        applyMapStyle(map, layer, is3DEnabled) {
                                            activeTrack?.let { drawTrackOnMap(map, it.points) }
                                            val filtered = allLoadedPois.filter { it.category in activePoiFilters }
                                            drawPoisOnMap(map, filtered)
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

        // FINESTRA CONFERMA DOWNLOAD AREA OFFLINE
        if (showOfflineConfirm) {
            AlertDialog(
                onDismissRequest = { showOfflineConfirm = false },
                containerColor = Color.White,
                title = { Text("Scarica Area Offline", fontWeight = FontWeight.Bold, color = Color.Black) },
                text = {
                    Text(
                        "Verrà salvata l'area attualmente inquadrata sullo schermo (zoom 10–15) insieme ai Rifugi, Sorgenti, Falesie e Ferrate della zona per la navigazione senza rete.",
                        color = Color.DarkGray,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showOfflineConfirm = false
                            mapLibreMap?.let { map ->
                                val b = map.projection.visibleRegion.latLngBounds
                                scope.launch {
                                    allLoadedPois = RoutingService.fetchPoisInBounds(
                                        context, b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast
                                    )
                                }
                                downloadCurrentRegionOffline(context, map, currentLayer) { prog ->
                                    offlineProgress = prog
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B4332))
                    ) {
                        Text("Scarica Ora", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showOfflineConfirm = false }) {
                        Text("Annulla", color = Color.Gray)
                    }
                }
            )
        }

        // INDICATORE PROGRESSO DOWNLOAD OFFLINE
        offlineProgress?.let { prog ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 125.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF1B4332)
            ) {
                Text(
                    text = "Download offline: ${(prog * 100).toInt()}%",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    fontSize = 13.sp
                )
            }
        }

        // SCHEDA ANALISI METEO IN QUOTA (Zero Termico, Vento, Nuvole)
        if (selectedWeatherPoint != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
                    .shadow(14.dp, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                color = Color.White
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Analisi Meteo Alpinistica", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF1B4332))
                        IconButton(
                            onClick = {
                                selectedWeatherPoint = null
                                weatherReport = null
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Chiudi", tint = Color.Gray)
                        }
                    }

                    if (isLoadingWeather) {
                        Row(modifier = Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF1B4332))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Analisi quota, zero termico e vento...", color = Color.Gray, fontSize = 13.sp)
                        }
                    } else if (weatherReport != null) {
                        val w = weatherReport!!
                        Text(
                            text = w.summaryWarning,
                            color = Color(0xFFD32F2F),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatBox("Quota Punto", "${w.elevationMeters} m")
                            StatBox("Zero Termico", "${w.freezingLevelCurrent} m")
                            StatBox("Range 0°C (24h)", "${w.freezingLevelMin24h}-${w.freezingLevelMax24h}m")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatBox("Temp. Quota", "${w.tempAtElevation} °C")
                            StatBox("Vento / Raffiche", "${w.windSpeedKmh.toInt()}/${w.windGustsKmh.toInt()} km/h")
                            StatBox("Prob. Precip.", "${w.precipitationProbMax}%")
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Nuvolosità: Basse (nebbia) ${w.cloudCoverLow}% • Medie ${w.cloudCoverMid}% • Alte ${w.cloudCoverHigh}%",
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )
                    } else {
                        Text("Dati meteo non disponibili (offline)", color = Color.Gray, fontSize = 13.sp)
                    }
                }
            }
        }

        // CONTROLLI IN BASSO (REGISTRAZIONE + POSIZIONE + PANNELLO TRACCIA GPX)
        if (selectedWeatherPoint == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
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
private fun StatBox(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = Color.Gray)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B4332))
    }
}

private fun getCleanStyleJson(type: MapLayerType): String {
    return when (type) {
        MapLayerType.OUTDOOR -> """
        {
          "version": 8,
          "sources": {
            "osm": {
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
            { "id": "topo", "type": "raster", "source": "osm" },
            { "id": "hiking", "type": "raster", "source": "trails" }
          ]
        }
        """.trimIndent()
        MapLayerType.SLOPES -> """
        {
          "version": 8,
          "sources": {
            "osm": {
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
            { "id": "topo", "type": "raster", "source": "osm" },
            { "id": "slopes", "type": "raster", "source": "slopes" }
          ]
        }
        """.trimIndent()
        MapLayerType.WINTER -> """
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

private fun applyMapStyle(map: MapLibreMap, type: MapLayerType, enable3D: Boolean, onLoaded: () -> Unit) {
    map.setStyle(Style.Builder().fromJson(getCleanStyleJson(type))) { style ->
        if (enable3D) {
            try {
                val demSource = RasterDemSource(
                    "terrain-dem",
                    "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png",
                    256
                )
                style.addSource(demSource)
                val hillshade = HillshadeLayer("terrain-hillshade", "terrain-dem").withProperties(
                    PropertyFactory.hillshadeExaggeration(0.6f),
                    PropertyFactory.hillshadeShadowColor("#1B263B")
                )
                style.addLayer(hillshade)
            } catch (_: Exception) {
            }
        }
        onLoaded()
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
            PropertyFactory.circleColor(org.maplibre.android.style.expressions.Expression.get("color")),
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

    val metadata = "MapyOutdoor_Area".toByteArray(Charset.forName("UTF-8"))
    Toast.makeText(context, "Avvio download area offline...", Toast.LENGTH_SHORT).show()

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
                        Toast.makeText(context, "Mappa offline e POI salvati!", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onError(error: OfflineRegionError) {
                    onProgress(null)
                    Toast.makeText(context, "Errore download: ${error.message}", Toast.LENGTH_SHORT).show()
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    onProgress(null)
                    Toast.makeText(context, "Area troppo ampia: avvicina leggermente lo zoom", Toast.LENGTH_LONG).show()
                }
            })
        }

        override fun onError(error: String) {
            Toast.makeText(context, "Errore offline: $error", Toast.LENGTH_SHORT).show()
        }
    })
}
