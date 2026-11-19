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

enum class PoiCategory(val id: String, val label: String, val colorHex: String) {
    HUT("hut", "Rifugi / Bivacchi", "#E65100"),
    WATER("water", "Acqua / Sorgenti", "#0288D1"),
    CLIMBING("climbing", "Falesie", "#8E24AA"),
    FERRATA("ferrata", "Vie Ferrate", "#D32F2F")
}

data class PoiItem(
    val id: Long,
    val name: String,
    val category: PoiCategory,
    val lat: Double,
    val lon: Double,
    val elevation: String = ""
)

data class AlpineWeatherReport(
    val elevationMeters: Int,
    val freezingLevelCurrent: Int,
    val freezingLevelMin24h: Int,
    val freezingLevelMax24h: Int,
    val tempAtElevation: Double,
    val windSpeedKmh: Double,
    val windGustsKmh: Double,
    val cloudCoverLow: Int,
    val cloudCoverMid: Int,
    val cloudCoverHigh: Int,
    val precipitationProbMax: Int,
    val summaryWarning: String
)

enum class MapLayerType(val label: String) {
    OUTDOOR("Escursionistica"),
    SLOPES("Pendenze / Canali 30-45°"),
    WINTER("Invernale"),
    SATELLITE("Satellitare")
}
