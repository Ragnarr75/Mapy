package com.outdoor.mapyclone.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.outdoor.mapyclone.model.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class TrackingService : Service() {

    companion object {
        private val _isRecording = MutableStateFlow(false)
        val isRecording = _isRecording.asStateFlow()

        private val _recordedPoints = MutableStateFlow<List<TrackPoint>>(emptyList())
        val recordedPoints = _recordedPoints.asStateFlow()

        private val _startTime = MutableStateFlow(0L)
        val startTime = _startTime.asStateFlow()

        fun clearTrack() {
            _recordedPoints.value = emptyList()
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    val pt = TrackPoint(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        ele = if (loc.hasAltitude()) loc.altitude else 0.0,
                        time = System.currentTimeMillis()
                    )
                    _recordedPoints.value = _recordedPoints.value + pt
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "tracking_channel")
            .setContentTitle("MapyOutdoor - Registrazione attiva")
            .setContentText("Registrazione traccia GPS in corso...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
        _isRecording.value = true
        _startTime.value = System.currentTimeMillis()
        _recordedPoints.value = emptyList()

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateDistanceMeters(3f)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRecording.value = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
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
