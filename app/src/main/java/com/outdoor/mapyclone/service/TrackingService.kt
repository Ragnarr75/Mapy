package com.outdoor.mapyclone.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.outdoor.mapyclone.model.TrackPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

class TrackingService : Service() {

    companion object {
        private val _isRecording = MutableStateFlow(false)
        val isRecording = _isRecording.asStateFlow()

        private val _recordedPoints = MutableStateFlow<List<TrackPoint>>(emptyList())
        val recordedPoints = _recordedPoints.asStateFlow()

        var ultraBatteryMode: Boolean = false
        var liveTrackingTopic: String = ""
    }

    private lateinit var locationManager: LocationManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient()
    private val pendingLiveQueue = mutableListOf<TrackPoint>()
    private var lastLivePushTime = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val pt = TrackPoint(
                lat = location.latitude,
                lon = location.longitude,
                ele = location.altitude,
                time = System.currentTimeMillis()
            )
            _recordedPoints.value = _recordedPoints.value + pt
            if (liveTrackingTopic.isNotBlank()) {
                pendingLiveQueue.add(pt)
                val now = System.currentTimeMillis()
                val intervalMs = if (ultraBatteryMode) 600_000L else 180_000L
                if (now - lastLivePushTime >= intervalMs || pendingLiveQueue.size == 1) {
                    lastLivePushTime = now
                    flushLiveQueue()
                }
            }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun flushLiveQueue() {
        val latest = pendingLiveQueue.lastOrNull() ?: return
        val topic = liveTrackingTopic.trim()
        if (topic.isEmpty()) return
        serviceScope.launch {
            try {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val bat = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val mapsUrl = String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", latest.lat, latest.lon)
                val msg = String.format(
                    Locale.US,
                    "Posizione Live MapyOutdoor:\nLat/Lon: %.5f, %.5f\nQuota: %.0f m\nBatteria: %d%%\nMappa: %s",
                    latest.lat, latest.lon, latest.ele, bat, mapsUrl
                )
                val req = Request.Builder()
                    .url("https://ntfy.sh/$topic")
                    .header("Title", "MapyOutdoor Live Tracking")
                    .post(msg.toRequestBody("text/plain".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        pendingLiveQueue.clear()
                    }
                }
            } catch (_: Exception) {
                // Resta in coda e ritenta appena aggancia segnale rete
            }
        }
    }

    @Suppress("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val modeLabel = if (ultraBatteryMode) " (Ultra Risparmio)" else ""
        val notification = NotificationCompat.Builder(this, "tracking_channel")
            .setContentTitle("MapyOutdoor GPS Attivo$modeLabel")
            .setContentText("Registrazione traccia alpinistica in corso...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
        _recordedPoints.value = emptyList()
        _isRecording.value = true

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val minTimeMs = if (ultraBatteryMode) 12_000L else 3_000L
        val minDistM = if (ultraBatteryMode) 15f else 4f
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                minTimeMs,
                minDistM,
                locationListener
            )
        } catch (_: Exception) {
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::locationManager.isInitialized) {
            locationManager.removeUpdates(locationListener)
        }
        if (pendingLiveQueue.isNotEmpty() && liveTrackingTopic.isNotBlank()) {
            flushLiveQueue()
        }
        _isRecording.value = false
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "tracking_channel",
            "Registrazione Traccia GPS",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
