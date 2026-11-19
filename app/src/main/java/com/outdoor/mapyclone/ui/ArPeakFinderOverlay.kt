package com.outdoor.mapyclone.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.graphics.Color
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

@Composable
fun ArPeakFinderOverlay(
    userLatLng: LatLng,
    compassAzimuth: Float,
    peaks: List<PeakItem>,
    onSelectPeak: (PeakItem) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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

    // Filtra vette entro 35 km nel campo visivo orizzontale della fotocamera (~68 gradi)
    val visiblePeaks = remember(userLatLng, compassAzimuth, peaks) {
        peaks.mapNotNull { pk ->
            val dist = GpxManager.haversineDistance(userLatLng.latitude, userLatLng.longitude, pk.lat, pk.lon)
            if (dist > 35_000.0) return@mapNotNull null
            val bearing = RoutingService.calculateBearing(userLatLng.latitude, userLatLng.longitude, pk.lat, pk.lon)
            var diff = bearing - compassAzimuth
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f
            if (diff in -34f..34f) {
                Triple(pk, dist, diff)
            } else null
        }.sortedBy { it.second }.take(12)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
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
        } else {
            Text(
                "Concedi il permesso Fotocamera per l'identificazione cime in Realtà Aumentata.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
        }

        // Linea orizzonte e mira centrale
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFFFFD166).copy(alpha = 0.6f))
        )

        // Etichette AR posizionate dinamicamente in base all'angolo azimuth della fotocamera
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val widthDp = maxWidth
            visiblePeaks.forEachIndexed { idx, (pk, dist, angleDiff) ->
                val normX = ((angleDiff + 34f) / 68f).coerceIn(0.05f, 0.85f)
                val yOffset = (160 + (idx % 4) * 58).dp
                Surface(
                    modifier = Modifier
                        .offset(x = widthDp * normX, y = yOffset)
                        .clickable { onSelectPeak(pk) },
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF1B4332).copy(alpha = 0.88f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("▲ ${pk.name}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(
                            "${pk.elevationMeters}m • ${String.format(Locale.US, "%.1f km", dist / 1000.0)}",
                            color = Color(0xFFFFD166),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // Barra superiore AR con azimut e tasto chiudi
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = 0.7f)) {
                Text(
                    text = "AR Orizzonte • Bussola ${compassAzimuth.toInt()}° (${visiblePeaks.size} cime)",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            FilledIconButton(
                onClick = onClose,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.7f))
            ) {
                Icon(Icons.Default.Close, contentDescription = "Chiudi AR", tint = Color.White)
            }
        }
    }
}
