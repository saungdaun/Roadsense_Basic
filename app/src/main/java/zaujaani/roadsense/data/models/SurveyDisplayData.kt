package zaujaani.roadsense.data.models

data class SurveyDisplayData(
    val tripDistance: String = "0.0 m",
    val accelerationZ: String = "0.00 g",
    val currentSpeed: String = "0 km/h",
    val gpsAccuracy: String = "10 m",
    val segmentCount: String = "0",
    val elapsedTime: String = "00:00:00"
)