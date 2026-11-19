package com.outdoor.mapyclone.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.outdoor.mapyclone.model.PeakItem
import com.outdoor.mapyclone.utils.GpxManager
import com.outdoor.mapyclone.utils.RoutingService
import org.maplibre.android.geometry.LatLng
import java.util.Locale
import kotlin.math.atan2

@Composable
fun ArPeakFinderOverlay(
    userLatLng: LatLng,
    observerElevationMeters: Double = 220.0,
    peaks: List<PeakItem>,
    onSelectPeak: (PeakItem) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraAzimuth by remember { mutableStateOf(0f) }
    var cameraPitchDeg by remember { mutableStateOf(0f) }
    var horizonProfile360 by remember { mutableStateOf<FloatArray?>(null) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Calcola il profilo orizzonte reale 360° (fino a 70 km)
    LaunchedEffect(userLatLng) {
        horizonProfile360 = RoutingService.fetchHorizonProfile360(
            userLatLng.latitude,
            userLatLng.longitude,
            observerElevationMeters
        )
    }

    // Sensore orientamento rimappato per telefono VERTICALE (azimut + inclinazione verticale pitch)
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rotMat = FloatArray(9)
        val outMat = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                    SensorManager.remapCoordinateSystem(rotMat, SensorManager.AXIS_X, SensorManager.AXIS_Z, outMat)
                    SensorManager.getOrientation(outMat, orientation)
                    val deg = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
                    // orientation[1] negativo quando si punta in alto verso le vette
                    val pitch = (-Math.toDegrees(orientation[1].toDouble())).toFloat().coerceIn(-45f, 45f)
                    cameraAzimuth = deg
                    cameraPitchDeg = pitch
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        rotSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(listener) }
    }

    // Raggio esteso a 70 km per rilevare anche i crinali appenninici/alpini dalla pianura
    val visiblePeaks = remember(userLatLng, cameraAzimuth, peaks) {
        peaks.mapNotNull { pk ->
            val dist = GpxManager.haversineDistance(userLatLng.latitude, userLatLng.longitude, pk.lat, pk.lon)
            if (dist > 70_000.0 || dist < 150.0) return@mapNotNull null
            val bearing = RoutingService.calculateBearing(userLatLng.latitude, userLatLng.longitude, pk.lat, pk.lon)
            var diff = bearing - cameraAzimuth
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f
            if (diff in -34f..34f) {
                val elevAngle = Math.toDegrees(atan2((pk.elevationMeters - observerElevationMeters), dist)).toFloat()
                Triple(pk, dist, diff to elevAngle)
            } else null
        }.sortedBy { it.second }.take(15)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview
                            )
                        } catch (_: Exception) {
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )
        }

        // LINEA GIALLA SAGOMATA SUL CONTORNO REALE DELLE MONTAGNE (Profilo DEM 360°)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val hfov = 68f
            val vfov = 85f
            val path = Path()
            val profile = horizonProfile360

            val steps = 68
            for (i in 0..steps) {
                val relAngle = -hfov / 2f + (i.toFloat() / steps) * hfov
                val azDeg = (((cameraAzimuth + relAngle) % 360f + 360f) % 360f).toInt() % 360
                val ridgeElevDeg = profile?.get(azDeg) ?: 0f
                val screenX = (i.toFloat() / steps) * w
                val deltaVertDeg = ridgeElevDeg - cameraPitchDeg
                val screenY = (h * 0.5f) - (deltaVertDeg / vfov) * h
                if (i == 0) path.moveTo(screenX, screenY) else path.lineTo(screenX, screenY)
            }

            drawPath(
                path = path,
                color = Color(0xFFFFD166),
                style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // Etichette AR ancorate sopra il profilo della singola vetta inquadrata
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val widthDp = maxWidth
            val heightDp = maxHeight
            visiblePeaks.forEachIndexed { idx, (pk, dist, angles) ->
                val (angleDiff, elevAngle) = angles
                val normX = ((angleDiff + 34f) / 68f).coerceIn(0.04f, 0.78f)
                val deltaVert = elevAngle - cameraPitchDeg
                val baseNormY = (0.46f - (deltaVert / 85f)).coerceIn(0.14f, 0.72f)
                val stagger = ((idx % 3) * 0.055f)
                val finalY = (baseNormY - stagger).coerceAtLeast(0.12f)

                Surface(
                    modifier = Modifier
                        .offset(x = widthDp * normX, y = heightDp * finalY)
                        .clickable { onSelectPeak(pk) },
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF101820).copy(alpha = 0.86f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("▲ ${pk.name}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        val eleTxt = if (pk.elevationMeters > 0) "${pk.elevationMeters}m • " else ""
                        Text(
                            "$eleTxt${String.format(Locale.US, "%.1f km", dist / 1000.0)}",
                            color = Color(0xFFFFD166),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = 0.78f)) {
                Text(
                    text = "AR Orizzonte 70km • ${cameraAzimuth.toInt()}° (${visiblePeaks.size} cime inquadrate)",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            FilledIconButton(
                onClick = onClose,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.78f))
            ) {
                Icon(Icons.Default.Close, contentDescription = "Chiudi AR", tint = Color.White)
            }
        }
    }
}
