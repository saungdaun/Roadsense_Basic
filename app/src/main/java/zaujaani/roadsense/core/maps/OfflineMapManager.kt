package zaujaani.roadsense.core.maps

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import timber.log.Timber
import zaujaani.roadsense.BuildConfig
import zaujaani.roadsense.data.repository.UserPreferencesRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * OfflineMapManager - Versi stabil dengan dukungan penuh manajemen cache offline.
 */
@Singleton
class OfflineMapManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val userPreferencesRepo: UserPreferencesRepository
) {

    companion object {
        private const val TILE_CACHE_DIR = "osmdroid_tiles"
        private const val DEFAULT_MAX_CACHE_SIZE_MB = 500L
        private const val DEFAULT_MIN_ZOOM = 12
        private const val DEFAULT_MAX_ZOOM = 18
        private const val APPROX_TILE_SIZE_KB = 15

        // Tile source yang mengizinkan bulk download
        private val BULK_TILE_SOURCE = object : XYTileSource(
            "MapnikBulk",
            0, 19, 256, ".png",
            arrayOf(
                "https://a.tile.openstreetmap.org/",
                "https://b.tile.openstreetmap.org/",
                "https://c.tile.openstreetmap.org/"
            )
        ) {
            override fun getTileSourcePolicy(): TileSourcePolicy {
                return TileSourcePolicy(
                    2,
                    TileSourcePolicy.FLAG_NO_PREVENTIVE
                )
            }
        }
    }

    // State download
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val tileWriter: SqlTileWriter by lazy { SqlTileWriter() }
    private val isCancelled = AtomicBoolean(false)

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Preparing(val message: String) : DownloadState()
        data class Downloading(val current: Int, val total: Int, val progress: Float) : DownloadState()
        data class Completed(val tilesDownloaded: Int) : DownloadState()
        data class Error(val message: String) : DownloadState()
        object Cancelled : DownloadState()
    }

    // Data classes untuk info cache
    data class CacheInfo(
        val totalSizeMB: Long,
        val estimatedTiles: Long,
        val files: List<File>,
        val availableSpaceMB: Long
    )

    data class StorageInfo(
        val path: String,
        val totalSpaceBytes: Long,
        val usableSpaceBytes: Long,
        val usedSpaceBytes: Long,
        val totalSpaceMB: Long,
        val usableSpaceMB: Long,
        val usedSpaceMB: Long
    ) {
        val usagePercent: Int
            get() = ((usedSpaceBytes.toFloat() / totalSpaceBytes) * 100).toInt()
    }

    data class CacheBreakdown(
        val byType: Map<String, CacheTypeInfo>,
        val totalFiles: Int
    )

    data class CacheTypeInfo(
        val extension: String,
        val fileCount: Int,
        val totalBytes: Long
    ) {
        val totalMB: Double
            get() = totalBytes / (1024.0 * 1024.0)
    }

    init {
        setupOSMDroidConfig()
        // Perbarui konfigurasi dengan email dari preferences (async)
        CoroutineScope(Dispatchers.IO).launch {
            updateOSMConfiguration()
        }
    }

    private fun setupOSMDroidConfig() {
        val config = Configuration.getInstance()
        val cacheDir = File(appContext.cacheDir, TILE_CACHE_DIR).apply { mkdirs() }

        config.osmdroidBasePath = cacheDir
        config.osmdroidTileCache = cacheDir
        config.tileFileSystemCacheMaxBytes = DEFAULT_MAX_CACHE_SIZE_MB * 1024 * 1024
        config.tileFileSystemCacheTrimBytes = (DEFAULT_MAX_CACHE_SIZE_MB * 0.8).toLong() * 1024 * 1024
        config.userAgentValue = "RoadSense/1.0 (pending email)" // Sementara, akan diganti nanti

        Timber.d("OSMDroid configured: ${cacheDir.absolutePath}")
    }

    /**
     * Memperbarui user-agent berdasarkan email yang tersimpan di preferences.
     * Fungsi ini harus dipanggil setiap kali email berubah (misal dari SettingsFragment).
     */
    suspend fun updateOSMConfiguration() {
        val email = userPreferencesRepo.getCurrentEmail()
        val userAgent = if (email.isNotBlank()) {
            "Roadsense/${BuildConfig.VERSION_NAME} (Android; contact: $email)"
        } else {
            "Roadsense/${BuildConfig.VERSION_NAME} (Android; contact: no-email@example.com)"
        }
        withContext(Dispatchers.Main) {
            Configuration.getInstance().userAgentValue = userAgent
            Timber.d("OSM configuration updated with user-agent: $userAgent")
        }
    }

    // ==================== PUBLIC API ====================

    /**
     * Get storage path untuk display ke user
     */
    fun getStoragePath(): String {
        val cacheDir = File(appContext.cacheDir, TILE_CACHE_DIR)
        return cacheDir.absolutePath
    }

    /**
     * Get detailed storage info
     */
    fun getStorageInfo(): StorageInfo {
        val cacheDir = File(appContext.cacheDir, TILE_CACHE_DIR)
        val totalSpace = cacheDir.totalSpace
        val usableSpace = cacheDir.usableSpace
        val usedSpace = totalSpace - usableSpace

        return StorageInfo(
            path = cacheDir.absolutePath,
            totalSpaceBytes = totalSpace,
            usableSpaceBytes = usableSpace,
            usedSpaceBytes = usedSpace,
            totalSpaceMB = totalSpace / (1024 * 1024),
            usableSpaceMB = usableSpace / (1024 * 1024),
            usedSpaceMB = usedSpace / (1024 * 1024)
        )
    }

    /**
     * Check if storage has enough space for download
     */
    fun hasEnoughSpace(requiredMB: Long): Boolean {
        val availableMB = getAvailableSpaceMB()
        val bufferMB = 100L // Keep 100MB buffer
        return availableMB >= (requiredMB + bufferMB)
    }

    /**
     * Get cache file types breakdown
     */
    suspend fun getCacheBreakdown(): CacheBreakdown = withContext(Dispatchers.IO) {
        val cacheDir = File(appContext.cacheDir, TILE_CACHE_DIR)
        if (!cacheDir.exists()) {
            return@withContext CacheBreakdown(emptyMap(), 0)
        }

        val typeMap = mutableMapOf<String, CacheTypeInfo>()
        var totalFiles = 0

        cacheDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                totalFiles++
                val ext = file.extension.lowercase()
                val existing = typeMap[ext] ?: CacheTypeInfo(ext, 0, 0)
                typeMap[ext] = existing.copy(
                    fileCount = existing.fileCount + 1,
                    totalBytes = existing.totalBytes + file.length()
                )
            }
        }

        CacheBreakdown(typeMap, totalFiles)
    }

    /**
     * Mendapatkan informasi cache: total ukuran (bytes), perkiraan jumlah tile, dan daftar semua file.
     */
    suspend fun getCacheInfo(): CacheInfo = withContext(Dispatchers.IO) {
        val cacheDir = File(appContext.cacheDir, TILE_CACHE_DIR)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
            return@withContext CacheInfo(0, 0, emptyList(), getAvailableSpaceMB())
        }

        var totalBytes = 0L
        val files = mutableListOf<File>()

        // Recursively walk through all files including subdirectories
        cacheDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                totalBytes += file.length()
                files.add(file)
                Timber.v("Found cache file: ${file.name} (${file.length()} bytes)")
            }
        }

        // Estimate tiles berdasarkan total bytes
        val estimatedTiles = if (totalBytes > 0) {
            totalBytes / (APPROX_TILE_SIZE_KB * 1024)
        } else {
            0L
        }

        val result = CacheInfo(
            totalSizeMB = totalBytes / (1024 * 1024),
            estimatedTiles = estimatedTiles,
            files = files.sortedByDescending { it.length() }, // Sort by size, largest first
            availableSpaceMB = getAvailableSpaceMB()
        )

        Timber.d("getCacheInfo: ${files.size} files, ${result.totalSizeMB} MB, ~${result.estimatedTiles} tiles")
        return@withContext result
    }

    /**
     * Aktifkan mode offline pada MapView.
     * Akan memuat tile dari cache jika ada.
     */
    fun enableOfflineMode(mapView: MapView) {
        val cacheDir = File(appContext.cacheDir, TILE_CACHE_DIR)
        val archives = cacheDir.listFiles { file ->
            file.extension == "sqlite" || file.extension == "mbtiles" || file.extension == "zip"
        }

        Timber.d("enableOfflineMode: found ${archives?.size ?: 0} archive files")

        if (!archives.isNullOrEmpty()) {
            try {
                val tileProvider = OfflineTileProvider(
                    SimpleRegisterReceiver(appContext),
                    archives
                )
                mapView.setTileProvider(tileProvider)
                Timber.d("OfflineTileProvider aktif dengan ${archives.size} file")
            } catch (e: Exception) {
                Timber.e(e, "Gagal memuat offline tile, fallback ke online")
                mapView.setTileSource(TileSourceFactory.MAPNIK)
            }
        } else {
            Timber.d("Tidak ada file offline, gunakan MAPNIK")
            mapView.setTileSource(TileSourceFactory.MAPNIK)
        }

        // Gunakan koneksi data jika tile tidak ada di cache
        mapView.setUseDataConnection(true)
    }

    /**
     * Set MapView ke mode offline sepenuhnya (tidak ada koneksi data).
     */
    fun setStrictOfflineMode(mapView: MapView) {
        mapView.setUseDataConnection(false)
    }

    /**
     * Download area tertentu.
     */
    fun downloadMapArea(
        activityContext: Context,
        boundingBox: BoundingBox,
        zoomLevels: IntRange = DEFAULT_MIN_ZOOM..DEFAULT_MAX_ZOOM,
        coroutineScope: CoroutineScope,
        onResult: (Result<Int>) -> Unit = {}
    ) {
        isCancelled.set(false)
        _downloadState.value = DownloadState.Preparing("Menghitung tiles...")

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val estimatedTiles = estimateTileCount(boundingBox, zoomLevels)
                val estimatedSizeMB = (estimatedTiles * APPROX_TILE_SIZE_KB) / 1024
                Timber.d("Estimasi tiles: $estimatedTiles (~${estimatedSizeMB}MB)")

                val availableSpaceMB = getAvailableSpaceMB()
                if (estimatedSizeMB > availableSpaceMB) {
                    val error = "Ruang tidak cukup: butuh $estimatedSizeMB MB, tersedia $availableSpaceMB MB"
                    _downloadState.value = DownloadState.Error(error)
                    onResult(Result.failure(Exception(error)))
                    return@launch
                }

                val manager = withContext(Dispatchers.Main) {
                    val mapView = MapView(activityContext).apply {
                        setTileSource(BULK_TILE_SOURCE)
                    }
                    CacheManager(mapView)
                }

                var downloaded = 0
                val total = estimatedTiles

                val result = withContext(Dispatchers.Main) {
                    suspendCoroutine<Result<Int>> { cont ->
                        val callback = object : CacheManager.CacheManagerCallback {
                            override fun onTaskComplete() {
                                if (isCancelled.get()) {
                                    cont.resume(Result.failure(CancellationException()))
                                } else {
                                    _downloadState.value = DownloadState.Completed(downloaded)
                                    cont.resume(Result.success(downloaded))
                                }
                            }

                            override fun onTaskFailed(errors: Int) {
                                if (isCancelled.get()) {
                                    cont.resume(Result.failure(CancellationException()))
                                } else {
                                    val errMsg = "Download gagal dengan $errors error"
                                    _downloadState.value = DownloadState.Error(errMsg)
                                    cont.resume(Result.failure(Exception(errMsg)))
                                }
                            }

                            override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                                if (isCancelled.get()) return
                                downloaded = progress
                                _downloadState.value = DownloadState.Downloading(
                                    current = downloaded,
                                    total = total,
                                    progress = downloaded.toFloat() / total
                                )
                            }

                            override fun downloadStarted() {
                                Timber.d("Download started")
                            }

                            override fun setPossibleTilesInArea(total: Int) {}
                        }

                        manager.downloadAreaAsync(activityContext, boundingBox, zoomLevels.first, zoomLevels.last, callback)
                    }
                }

                onResult(result)

            } catch (e: CancellationException) {
                _downloadState.value = DownloadState.Cancelled
                onResult(Result.failure(e))
            } catch (e: Exception) {
                Timber.e(e, "Download error")
                _downloadState.value = DownloadState.Error(e.message ?: "Unknown error")
                onResult(Result.failure(e))
            }
        }
    }

    fun cancelDownload() {
        isCancelled.set(true)
        _downloadState.value = DownloadState.Cancelled
    }

    /**
     * Hapus semua cache (termasuk semua file dalam direktori).
     */
    suspend fun clearCache(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Detach tile writer untuk melepaskan kunci file
            tileWriter.onDetach()
            val cacheDir = File(appContext.cacheDir, TILE_CACHE_DIR)
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
            cacheDir.mkdirs()
            // Re-inisialisasi writer jika perlu (akan dibuat ulang saat dibutuhkan)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Gagal menghapus cache")
            Result.failure(e)
        }
    }

    private fun estimateTileCount(bbox: BoundingBox, zoomLevels: IntRange): Int {
        var total = 0
        for (z in zoomLevels) {
            val latSpan = bbox.latNorth - bbox.latSouth
            val lonSpan = bbox.lonEast - bbox.lonWest
            val tilesX = ((lonSpan / 360.0) * (1 shl z)).toInt() + 1
            val tilesY = ((latSpan / 180.0) * (1 shl z)).toInt() + 1
            total += tilesX * tilesY
        }
        return total
    }

    private fun getAvailableSpaceMB(): Long {
        return appContext.cacheDir.usableSpace / (1024 * 1024)
    }
}