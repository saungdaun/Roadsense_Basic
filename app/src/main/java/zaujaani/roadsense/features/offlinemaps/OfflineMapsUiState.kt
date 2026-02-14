package zaujaani.roadsense.features.offlinemaps

sealed class OfflineMapsUiState {
    object Loading : OfflineMapsUiState()
    data class Success(
        val files: List<OfflineMapFile>,
        val totalSizeMB: Long,
        val estimatedTiles: Long,
        val availableSpaceMB: Long,
        val maxCacheSizeMB: Long,
        val storagePath: String
    ) : OfflineMapsUiState()
    object Empty : OfflineMapsUiState()
    data class Error(val message: String) : OfflineMapsUiState()
}