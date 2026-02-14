package zaujaani.roadsense.features.offlinemaps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsense.core.maps.OfflineMapManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class OfflineMapsViewModel @Inject constructor(
    private val offlineMapManager: OfflineMapManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<OfflineMapsUiState>(OfflineMapsUiState.Loading)
    val uiState: StateFlow<OfflineMapsUiState> = _uiState.asStateFlow()

    fun loadCacheInfo() {
        viewModelScope.launch {
            try {
                _uiState.value = OfflineMapsUiState.Loading

                val cacheInfo = offlineMapManager.getCacheInfo()
                val storagePath = offlineMapManager.getStoragePath()

                if (cacheInfo.files.isEmpty()) {
                    _uiState.value = OfflineMapsUiState.Empty
                } else {
                    _uiState.value = OfflineMapsUiState.Success(
                        files = cacheInfo.files.map { file ->
                            OfflineMapFile(
                                file = file,
                                name = file.name,
                                sizeBytes = file.length(),
                                sizeMB = file.length() / (1024.0 * 1024.0),
                                type = file.extension.uppercase(Locale.US),
                                lastModified = file.lastModified(),
                                path = file.absolutePath
                            )
                        },
                        totalSizeMB = cacheInfo.totalSizeMB,
                        estimatedTiles = cacheInfo.estimatedTiles,
                        availableSpaceMB = cacheInfo.availableSpaceMB,
                        maxCacheSizeMB = 500L, // Default dari OfflineMapManager
                        storagePath = storagePath
                    )
                }

                Timber.d("Cache info loaded: ${cacheInfo.files.size} files, ${cacheInfo.totalSizeMB} MB")

            } catch (e: Exception) {
                Timber.e(e, "Failed to load cache info")
                _uiState.value = OfflineMapsUiState.Error(
                    message = "Failed to load cache: ${e.message ?: "Unknown error"}"
                )
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
        return try {
            offlineMapManager.clearCache().onSuccess {
                Timber.d("All cache cleared")
                loadCacheInfo()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error clearing cache")
            Result.failure(e)
        }
    }
}

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

data class OfflineMapFile(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val sizeMB: Double,
    val type: String,
    val lastModified: Long,
    val path: String
) {
    val formattedSize: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "%.2f KB".format(sizeBytes / 1024.0)
            sizeBytes < 1024 * 1024 * 1024 -> "%.2f MB".format(sizeMB)
            else -> "%.2f GB".format(sizeBytes / (1024.0 * 1024.0 * 1024.0))
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
            return sdf.format(Date(lastModified))
        }

    val fileTypeIcon: String
        get() = when (type.lowercase(Locale.US)) {
            "sqlite", "db" -> "🗄️"
            "mbtiles" -> "🗺️"
            "zip" -> "📦"
            else -> "📁"
        }
}