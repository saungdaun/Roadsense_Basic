package zaujaani.roadsense.features.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsense.data.local.RoadSegmentSummary
import zaujaani.roadsense.data.repository.SurveyRepository
import javax.inject.Inject

@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val repository: SurveyRepository
) : ViewModel() {

    private val _summaryData = MutableStateFlow<List<RoadSegmentSummary>>(emptyList())
    @Suppress("Unused")
    val summaryData: StateFlow<List<RoadSegmentSummary>> = _summaryData

    private val _filteredData = MutableStateFlow<List<RoadSegmentSummary>>(emptyList())
    val filteredData: StateFlow<List<RoadSegmentSummary>> = _filteredData

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _selectedSessionId = MutableStateFlow<Long>(-1L)
    @Suppress("Unused")
    val selectedSessionId: StateFlow<Long> = _selectedSessionId

    private val _filterCondition = MutableStateFlow<String?>(null)
    private val _filterSurface = MutableStateFlow<String?>(null)
    private val _filterConfidence = MutableStateFlow<String?>(null)
    private val _sortBy = MutableStateFlow(SortBy.DATE_DESC)

    enum class SortBy {
        DATE_ASC, DATE_DESC,
        DISTANCE_ASC, DISTANCE_DESC,
        SEVERITY_ASC, SEVERITY_DESC,
        SPEED_ASC, SPEED_DESC
    }

    init {
        combine(
            _summaryData,
            _filterCondition,
            _filterSurface,
            _filterConfidence,
            _sortBy
        ) { data, condition, surface, confidence, sort ->
            applyFiltersAndSort(data, condition, surface, confidence, sort)
        }.onEach { filtered ->
            _filteredData.value = filtered
        }.launchIn(viewModelScope)
    }

    fun loadSummary(sessionId: Long = -1L) {
        _isLoading.value = true
        _error.value = null
        _selectedSessionId.value = sessionId

        viewModelScope.launch {
            try {
                val summaries = if (sessionId == -1L) {
                    getAllSessionsSegments()
                } else {
                    repository.getSummaryBySession(sessionId)
                }

                _summaryData.value = summaries
                Timber.d("✅ Loaded ${summaries.size} road segments")
            } catch (e: Exception) {
                _error.value = "Gagal memuat summary: ${e.message}"
                Timber.e(e, "Failed to load summary")
            } finally {
                _isLoading.value = false
            }
        }
    }

    // 🔥 FIX: Gunakan firstOrNull() untuk menghindari blocking
    private suspend fun getAllSessionsSegments(): List<RoadSegmentSummary> {
        val allSegments = mutableListOf<RoadSegmentSummary>()
        val sessions = repository.getAllSessions().firstOrNull() ?: emptyList()
        sessions.forEach { session ->
            try {
                val segments = repository.getSummaryBySession(session.id)
                allSegments.addAll(segments)
                Timber.d("Loaded ${segments.size} segments from session ${session.id}")
            } catch (e: Exception) {
                Timber.e(e, "Error loading segments for session ${session.id}")
            }
        }
        return allSegments.sortedByDescending { it.segment.timestamp }
    }

    // 🔥 NEW: Hapus segmen
    suspend fun deleteSegment(segmentId: Long) {
        repository.deleteRoadSegment(segmentId)
    }

    fun setFilterCondition(condition: String?) {
        _filterCondition.value = condition
    }

    fun setFilterSurface(surface: String?) {
        _filterSurface.value = surface
    }

    fun setFilterConfidence(confidence: String?) {
        _filterConfidence.value = confidence
    }

    fun setSortBy(sortBy: SortBy) {
        _sortBy.value = sortBy
    }

    private fun applyFiltersAndSort(
        data: List<RoadSegmentSummary>,
        condition: String?,
        surface: String?,
        confidence: String?,
        sortBy: SortBy
    ): List<RoadSegmentSummary> {
        return data
            .filter { segment ->
                (condition == null || segment.segment.condition.equals(condition, ignoreCase = true)) &&
                        (surface == null || segment.segment.surface.equals(surface, ignoreCase = true)) &&
                        (confidence == null || segment.segment.confidence.equals(confidence, ignoreCase = true))
            }
            .sortedWith(
                when (sortBy) {
                    SortBy.DATE_ASC -> compareBy { it.segment.timestamp }
                    SortBy.DATE_DESC -> compareByDescending { it.segment.timestamp }
                    SortBy.DISTANCE_ASC -> compareBy { it.segment.distanceMeters }
                    SortBy.DISTANCE_DESC -> compareByDescending { it.segment.distanceMeters }
                    SortBy.SEVERITY_ASC -> compareBy { it.segment.severity }
                    SortBy.SEVERITY_DESC -> compareByDescending { it.segment.severity }
                    SortBy.SPEED_ASC -> compareBy { it.segment.avgSpeed }
                    SortBy.SPEED_DESC -> compareByDescending { it.segment.avgSpeed }
                }
            )
    }

    fun getFilterOptions(): FilterOptions {
        val data = _summaryData.value
        return FilterOptions(
            conditions = data.map { it.segment.condition }.distinct(),
            surfaces = data.map { it.segment.surface }.distinct(),
            confidences = data.map { it.segment.confidence }.distinct()
        )
    }

    data class FilterOptions(
        val conditions: List<String>,
        val surfaces: List<String>,
        val confidences: List<String>
    )

    fun getStatistics(): SummaryStatistics {
        val data = _filteredData.value
        return if (data.isEmpty()) {
            SummaryStatistics()
        } else {
            SummaryStatistics(
                totalSegments = data.size,
                totalDistance = data.sumOf { it.segment.distanceMeters.toDouble() }.toFloat(),
                avgSpeed = data.map { it.segment.avgSpeed }.average().toFloat(),
                avgSeverity = data.map { it.segment.severity.toFloat() }.average().toFloat(),
                conditionDistribution = data.groupBy { it.segment.condition }
                    .mapValues { (_, segments) -> segments.sumOf { it.segment.distanceMeters.toDouble() }.toFloat() },
                surfaceDistribution = data.groupBy { it.segment.surface }
                    .mapValues { (_, segments) -> segments.sumOf { it.segment.distanceMeters.toDouble() }.toFloat() }
            )
        }
    }

    data class SummaryStatistics(
        val totalSegments: Int = 0,
        val totalDistance: Float = 0f,
        val avgSpeed: Float = 0f,
        val avgSeverity: Float = 0f,
        val conditionDistribution: Map<String, Float> = emptyMap(),
        val surfaceDistribution: Map<String, Float> = emptyMap()
    )

    fun exportToCsv(): String {
        val data = _filteredData.value
        val csv = StringBuilder()

        // Header
        csv.append("Road Name,Start Lat,Start Lng,End Lat,End Lng,Distance (m),Condition,Surface,")
        csv.append("Confidence,Avg Speed,Avg Accuracy,Avg Vibration,Severity,Timestamp,Surveyor\n")

        // Data rows
        data.forEach { summary ->
            csv.append("\"${summary.segment.roadName}\",")
            csv.append("${summary.segment.startLatitude},${summary.segment.startLongitude},")
            csv.append("${summary.segment.endLatitude},${summary.segment.endLongitude},")
            csv.append("${summary.segment.distanceMeters},")
            csv.append("${summary.segment.condition},${summary.segment.surface},")
            csv.append("${summary.segment.confidence},${summary.segment.avgSpeed},")
            csv.append("${summary.segment.avgAccuracy},${summary.segment.avgVibration},")
            csv.append("${summary.segment.severity},${summary.segment.timestamp},")
            csv.append("\"${summary.session?.surveyorName ?: "Unknown"}\"\n")
        }

        return csv.toString()
    }

    fun refresh() {
        loadSummary(_selectedSessionId.value)
    }
}