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

data class PeakItem(
    val name: String,
    val lat: Double,
    val lon: Double,
    val elevationMeters: Int
)

data class HourlyAlpineSlot(
    val timeLabel: String,
    val tempAtTarget: Double,
    val feelsLike: Double,
    val freezingLevel: Int,
    val windKmh: Int,
    val gustsKmh: Int,
    val snowLimitMeters: Int,
    val precipMm: Double,
    val cloudLow: Int,
    val cloudMid: Int,
    val cloudHigh: Int
)

data class DailyAlpineForecast(
    val dateLabel: String,
    val slots: List<HourlyAlpineSlot>
)

data class AvalancheBulletin(
    val regionName: String,
    val dangerRating: Int,
    val dangerLabel: String,
    val problemTitle: String,
    val elevationThreshold: String,
    val summaryText: String
)

data class AlpineFullWeather(
    val baseElevation: Int,
    val days: List<DailyAlpineForecast>,
    val avalanche: AvalancheBulletin?,
    val officialAlert: String?
)

enum class MapLayerType(val label: String) {
    ALPINE("Alpina / Sentieri CAI"),
    SWISSTOPO("Swisstopo (Alpi)"),
    SLOPES("Pendenze Canali 30-45°"),
    SATELLITE("Satellitare 3D")
}
