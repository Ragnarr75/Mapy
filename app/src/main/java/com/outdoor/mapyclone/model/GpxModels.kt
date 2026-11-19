package com.outdoor.mapyclone.model

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double = 0.0,
    val time: Long = System.currentTimeMillis()
)

data class GpxTrack(
    val name: String,
    val points: List<TrackPoint>,
    val totalDistanceMeters: Double,
    val elevationGain: Double,
    val elevationLoss: Double,
    val maxElevation: Double,
    val minElevation: Double
)

data class SearchPlace(
    val name: String,
    val displayName: String,
    val lat: Double,
    val lon: Double
)

enum class MapLayerType(val label: String) {
    OUTDOOR("Escursionistica"),
    BASE("Base"),
    WINTER("Invernale"),
    SATELLITE("Satellitare")
}
