package com.outdoor.mapyclone.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var currentLayer by remember { mutableStateOf(MapLayerType.OUTDOOR) }
    var showLayerMenu by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchPlace>>(emptyList()) }

    var activeTrack by remember { mutableStateOf<GpxTrack?>(null) }
    var routeStart by remember { mutableStateOf<TrackPoint?>(null) }
    var isPlanningMode by remember { mutableStateOf(false) }

    var offlineProgress by remember { mutableStateOf<Float?>(null) }

    val isRecording by TrackingService.isRecording.collectAsState()
    val recordedPoints by TrackingService.recordedPoints.collectAsState()

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
                Toast.makeText(context, "File GPX non valido o vuoto", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, "GPX esportato con successo", Toast.LENGTH_SHORT).show()
            }
        }
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
                        applyMapStyle(map, currentLayer) {
                            enableUserLocation(ctx, map)
                        }
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(45.55, 7.60))
                            .zoom(11.0)
                            .build()

                        map.addOnMapLongClickListener { latLng ->
                            if (isPlanningMode) {
                                val clicked = TrackPoint(latLng.latitude, latLng.longitude)
                                if (routeStart == null) {
                                    routeStart = clicked
                                    Toast.makeText(ctx, "Partenza fissata. Tieni premuto sulla destinazione.", Toast.LENGTH_LONG).show()
                                } else {
                                    val start = routeStart!!
                                    routeStart = null
                                    isPlanningMode = false
                                    scope.launch {
                                        Toast.makeText(ctx, "Calcolo sentiero in corso...", Toast.LENGTH_SHORT).show()
                                        val route = RoutingService.calculateHikingRoute(start, clicked)
                                        if (route != null) {
                                            activeTrack = route
                                            drawTrackOnMap(map, route.points)
                                            zoomToTrack(map, route.points)
                                        } else {
                                            Toast.makeText(ctx, "Percorso non trovato", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            } else {
                                Toast.makeText(
                                    ctx,
                                    String.format(Locale.US, "Coord: %.5f, %.5f", latLng.latitude, latLng.longitude),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            true
                        }
                    }
                }
            }
        )

        // BARRA DI RICERCA SUPERIORE
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(6.dp, RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
                color = Color.White
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF2E7D32))
                    TextField(
                        value = searchQuery,
                        onValueChange = { q ->
                            searchQuery = q
                            scope.launch {
                                searchResults = if (q.length >= 3) RoutingService.searchPlaces(q) else emptyList()
                            }
                        },
                        placeholder = { Text("Cerca vette, rifugi, sentieri...", color = Color.Gray) },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            searchResults = emptyList()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancella")
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
                                        mapLibreMap?.animateCamera(
                                            CameraUpdateFactory.newLatLngZoom(LatLng(place.lat, place.lon), 14.0)
                                        )
                                    }
                                    .padding(12.dp)
                            ) {
                                Text(place.name, fontWeight = FontWeight.Bold, color = Color.Black)
                                Text(place.displayName, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                            }
                            HorizontalDivider(color = Color(0xFFEEEEEE))
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { showLayerMenu = !showLayerMenu },
                containerColor = Color.White,
                contentColor = Color(0xFF2E7D32)
            ) {
                Icon(Icons.Default.Layers, contentDescription = "Stile Mappa")
            }

            SmallFloatingActionButton(
                onClick = {
                    isPlanningMode = !isPlanningMode
                    routeStart = null
                    val msg = if (isPlanningMode) "Modalità percorso: tieni premuto sul punto di partenza" else "Pianificazione annullata"
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                },
                containerColor = if (isPlanningMode) Color(0xFF2E7D32) else Color.White,
                contentColor = if (isPlanningMode) Color.White else Color(0xFF2E7D32)
            ) {
                Icon(Icons.Default.DirectionsWalk, contentDescription = "Pianifica Percorso")
            }

            SmallFloatingActionButton(
                onClick = { gpxImporter.launch(arrayOf("*/*")) },
                containerColor = Color.White,
                contentColor = Color(0xFF2E7D32)
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = "Apri GPX")
            }

            SmallFloatingActionButton(
                onClick = {
                    mapLibreMap?.let { map ->
                        downloadCurrentRegionOffline(context, map) { progress ->
                            offlineProgress = progress
                        }
                    }
                },
                containerColor = Color.White,
                contentColor = Color(0xFF2E7D32)
            ) {
                Icon(Icons.Default.DownloadForOffline, contentDescription = "Scarica Mappa Offline")
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
                                        applyMapStyle(map, layer) {
                                            activeTrack?.let { drawTrackOnMap(map, it.points) }
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentLayer == layer,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF2E7D32))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(layer.label, color = Color.Black, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        // INDICATORE DOWNLOAD OFFLINE
        offlineProgress?.let { prog ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF2E7D32)
            ) {
                Text(
                    text = "Download mappa offline: ${(prog * 100).toInt()}%",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    fontSize = 13.sp
                )
            }
        }

        // CONTROLLI IN BASSO
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
                            Toast.makeText(context, "Registrazione salvata", Toast.LENGTH_SHORT).show()
                        } else {
                            context.startForegroundService(intent)
                        }
                    },
                    containerColor = if (isRecording) Color(0xFFD32F2F) else Color(0xFF2E7D32),
                    contentColor = Color.White,
                    icon = {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = null
                        )
                    },
                    text = { Text(if (isRecording) "Stop (${recordedPoints.size} pt)" else "Registra") }
                )

                FloatingActionButton(
                    onClick = {
                        mapLibreMap?.locationComponent?.lastKnownLocation?.let { loc ->
                            mapLibreMap?.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15.0)
                            )
                        }
                    },
                    containerColor = Color.White,
                    contentColor = Color(0xFF2E7D32),
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
                            Text(
                                text = track.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = Color.Black,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                gpxExporter.launch("${track.name.replace(" ", "_")}.gpx")
                            }) {
                                Icon(Icons.Default.Share, contentDescription = "Esporta GPX", tint = Color(0xFF2E7D32))
                            }
                            IconButton(onClick = {
                                activeTrack = null
                                drawTrackOnMap(mapLibreMap, emptyList())
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Chiudi", tint = Color.Gray)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            StatBox("Distanza", String.format(Locale.US, "%.2f km", track.totalDistanceMeters / 1000.0))
                            StatBox("Dislivello +", "${track.elevationGain.toInt()} m")
                            StatBox("Dislivello -", "${track.elevationLoss.toInt()} m")
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        ElevationChart(points = track.points)
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
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
    }
}

private fun applyMapStyle(map: MapLibreMap, type: MapLayerType, onLoaded: () -> Unit) {
    val styleJson = when (type) {
        MapLayerType.OUTDOOR -> """
        {
          "version": 8,
          "sources": {
            "osm": {
              "type": "raster",
              "tiles": ["https://tile.opentopomap.org/{z}/{x}/{y}.png"],
              "tileSize": 256,
              "attribution": "OpenTopoMap"
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
        MapLayerType.BASE -> """
        {
          "version": 8,
          "sources": {
            "osm": {
              "type": "raster",
              "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
              "tileSize": 256
            }
          },
          "layers": [
            { "id": "osm-layer", "type": "raster", "source": "osm" }
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

    map.setStyle(Style.Builder().fromJson(styleJson)) {
        onLoaded()
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
    onProgress: (Float?) -> Unit
) {
    val bounds = map.projection.visibleRegion.latLngBounds
    val styleJson = map.style?.json ?: return
    val offlineManager = OfflineManager.getInstance(context)

    val definition = OfflineTilePyramidRegionDefinition(
        styleJson,
        bounds,
        10.0,
        15.0,
        context.resources.displayMetrics.density
    )

    val metadata = "MapyOutdoor_Offline".toByteArray()
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
                        Toast.makeText(context, "Mappa offline salvata!", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(error: OfflineRegionError) {
                    onProgress(null)
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
