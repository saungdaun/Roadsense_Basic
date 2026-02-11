package zaujaani.roadsense.data.local

import androidx.room.Embedded
import androidx.room.Relation

data class RoadSegmentSummary(
    @Embedded
    val segment: RoadSegment,

    @Relation(
        parentColumn = "sessionId",
        entityColumn = "id"
    )
    val session: SurveySession?
) {
    // Helper properties untuk SummaryAdapter
    val roadName: String get() = segment.roadName
    val condition: String get() = segment.condition
    val surface: String get() = segment.surface
    val confidence: String get() = segment.confidence
    val avgSpeed: Float get() = segment.avgSpeed
    val avgAccuracy: Float get() = segment.avgAccuracy
    val avgVibration: Float get() = segment.avgVibration
    val severity: Int get() = segment.severity
    val timestamp: Long get() = segment.timestamp
    val dataSource: String get() = segment.dataSource
    val segmentCount: Int = 1 // Default, bisa dihitung nanti
    val totalDistance: Float get() = segment.distanceMeters
}