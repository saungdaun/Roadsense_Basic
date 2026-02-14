package zaujaani.roadsense.features.offlinemaps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsense.core.maps.OfflineMapManager
import java.io.File
import java.util.*
import javax.inject.Inject

@HiltViewModel
class OfflineMapsViewModel @Inject constructor(
    private val offlineMapManager: OfflineMapManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<OfflineMapsUiState>(OfflineMapsUiState.Loading)
    val uiState: StateFlow<OfflineMapsUiState> = _uiState.asStateFlow()

    private val _availableMaps = MutableStateFlow<List<AvailableMap>>(emptyList())
    val availableMaps: StateFlow<List<AvailableMap>> = _availableMaps.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadProgress>> = _downloadProgress.asStateFlow()

    init {
        loadAvailableMaps()
        loadCacheInfo()
    }

    fun loadCacheInfo() {
        viewModelScope.launch {
            try {
                _uiState.value = OfflineMapsUiState.Loading
                val cacheInfo = offlineMapManager.getCacheInfo()
                val storagePath = offlineMapManager.getStoragePath()

                if (cacheInfo.files.isEmpty()) {
                    _uiState.value = OfflineMapsUiState.Empty
                } else {
                    val files = cacheInfo.files.map { file ->
                        OfflineMapFile(
                            file = file,
                            name = file.name,
                            sizeBytes = file.length(),
                            sizeMB = file.length() / (1024.0 * 1024.0),
                            type = file.extension.uppercase(Locale.US),
                            lastModified = file.lastModified(),
                            path = file.absolutePath
                        )
                    }
                    _uiState.value = OfflineMapsUiState.Success(
                        files = files,
                        totalSizeMB = cacheInfo.totalSizeMB,
                        estimatedTiles = cacheInfo.estimatedTiles,
                        availableSpaceMB = cacheInfo.availableSpaceMB,
                        maxCacheSizeMB = 500L,
                        storagePath = storagePath
                    )
                }
                updateDownloadedStatus(cacheInfo.files.map { it.nameWithoutExtension })
            } catch (e: Exception) {
                Timber.e(e, "Failed to load cache info")
                _uiState.value = OfflineMapsUiState.Error("Failed to load cache: ${e.message ?: "Unknown error"}")
            }
        }
    }

    fun loadAvailableMaps() {
        viewModelScope.launch {
            _availableMaps.value = AvailableMapsProvider.getAvailableMaps()
        }
    }

    private fun updateDownloadedStatus(downloadedIds: List<String>) {
        _availableMaps.update { list ->
            list.map { map ->
                map.copy(isDownloaded = downloadedIds.contains(map.id))
            }
        }
    }

    fun downloadMap(availableMap: AvailableMap) {
        viewModelScope.launch {
            _downloadProgress.update { it + (availableMap.id to DownloadProgress(0)) }
            val result = offlineMapManager.downloadMapUrl(
                url = availableMap.url,
                fileName = "${availableMap.id}.mbtiles",
                onProgress = { progress ->
                    _downloadProgress.update { it + (availableMap.id to DownloadProgress(progress)) }
                }
            )
            result.onSuccess { file ->
                _downloadProgress.update { it.minus(availableMap.id) }
                loadCacheInfo()
            }.onFailure { error ->
                _downloadProgress.update { it.minus(availableMap.id) }
                _uiState.value = OfflineMapsUiState.Error("Gagal download: ${error.message}")
            }
        }
    }

    fun downloadRadiusMap(centerLat: Double, centerLon: Double, radiusKm: Double) {
        val id = "radius_${System.currentTimeMillis()}"
        viewModelScope.launch {
            _downloadProgress.update { it + (id to DownloadProgress(0)) }
            val result = offlineMapManager.downloadTilesForRadius(
                centerLat = centerLat,
                centerLon = centerLon,
                radiusKm = radiusKm,
                minZoom = 10,
                maxZoom = 17,
                onProgress = { progress ->
                    _downloadProgress.update { it + (id to DownloadProgress(progress)) }
                }
            )
            result.onSuccess { file ->
                _downloadProgress.update { it.minus(id) }
                loadCacheInfo()
            }.onFailure { error ->
                _downloadProgress.update { it.minus(id) }
                _uiState.value = OfflineMapsUiState.Error("Gagal download radius: ${error.message}")
            }
        }
    }

    suspend fun deleteFile(file: File): Result<Unit> {
        return try {
            if (file.delete()) {
                Timber.d("File deleted: ${file.name}")
                loadCacheInfo()
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete file: ${file.name}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error deleting file: ${file.name}")
            Result.failure(e)
        }
    }

    suspend fun clearAllCache(): Result<Unit> {
        return offlineMapManager.clearCache().onSuccess {
            loadCacheInfo()
        }
    }
}