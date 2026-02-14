package zaujaani.roadsense.core.maps

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import timber.log.Timber
import zaujaani.roadsense.BuildConfig
import zaujaani.roadsense.data.repository.UserPreferencesRepository
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class OfflineMapManager @Inject constructor(
    private val context: Context,
    private val userPreferencesRepo: UserPreferencesRepository
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private var downloadJob: Job? = null

    private val tileCacheDir: File
        get() {
            val baseDir = File(context.getExternalFilesDir(null), "osmdroid/tiles")
            baseDir.mkdirs()
            return baseDir
        }

    init {
        // Panggil update konfigurasi saat manager dibuat (baca email dari DataStore)
        CoroutineScope(Dispatchers.IO).launch {
            updateOSMConfiguration()
        }
    }

    /**
     * Memperbarui konfigurasi osmdroid berdasarkan email terbaru dari preferences.
     */
    suspend fun updateOSMConfiguration() {
        val email = userPreferencesRepo.getCurrentEmail()
        val userAgent = "Roadsense/${BuildConfig.VERSION_NAME} (Android; contact: $email)"
        withContext(Dispatchers.Main) {
            Configuration.getInstance().apply {
                val prefsOsm = context.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
                load(context, prefsOsm)

                userAgentValue = userAgent
                osmdroidTileCache = tileCacheDir
                tileDownloadThreads = 2
                tileFileSystemThreads = 2
                tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
                tileFileSystemCacheTrimBytes = 250L * 1024 * 1024
            }
            Timber.d("OSM configuration updated with user-agent: $userAgent")
        }
    }

    data class CacheInfo(
        val files: List<File>,
        val totalSizeMB: Long,
        val estimatedTiles: Long,
        val availableSpaceMB: Long
    )

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Preparing(val message: String) : DownloadState()
        data class Downloading(val current: Int, val total: Int, val progress: Float) : DownloadState()
        object Completed : DownloadState()
        data class Error(val message: String) : DownloadState()
        object Cancelled : DownloadState()
    }

    // ========== PUBLIC API ==========

    fun getCacheInfo(): CacheInfo {
        val files = tileCacheDir.listFiles { file ->
            file.isFile && (file.extension == "mbtiles" || file.extension == "sqlite")
        }?.toList() ?: emptyList()
        val totalSizeBytes = files.sumOf { it.length() }
        val totalSizeMB = totalSizeBytes / (1024 * 1024)
        val estimatedTiles = files.sumOf { file ->
            file.length() / (15 * 1024) // asumsi 15KB per tile
        }
        val availableSpaceBytes = tileCacheDir.freeSpace
        val availableSpaceMB = availableSpaceBytes / (1024 * 1024)
        return CacheInfo(files, totalSizeMB, estimatedTiles, availableSpaceMB)
    }

    fun getStoragePath(): String = tileCacheDir.absolutePath

    fun clearCache(): Result<Unit> = try {
        tileCacheDir.listFiles()?.forEach { it.delete() }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun downloadMapUrl(
        url: String,
        fileName: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }
            val body = response.body ?: return@withContext Result.failure(Exception("Empty response"))
            val contentLength = body.contentLength()
            val inputStream = body.byteStream()
            val outputFile = File(tileCacheDir, fileName)
            outputFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (contentLength > 0) {
                        val progress = (totalBytesRead * 100 / contentLength).toInt()
                        onProgress(progress)
                    }
                }
            }
            response.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadTilesForRadius(
        centerLat: Double,
        centerLon: Double,
        radiusKm: Double,
        minZoom: Int,
        maxZoom: Int,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val latRad = Math.toRadians(centerLat)
        val angularRadius = radiusKm / 6371.0
        val minLat = centerLat - Math.toDegrees(angularRadius)
        val maxLat = centerLat + Math.toDegrees(angularRadius)
        val minLon = centerLon - Math.toDegrees(angularRadius / cos(latRad))
        val maxLon = centerLon + Math.toDegrees(angularRadius / cos(latRad))
        val bbox = BoundingBox(maxLat, maxLon, minLat, minLon)

        val fileName = "radius_${System.currentTimeMillis()}.mbtiles"
        val outputFile = File(tileCacheDir, fileName)

        try {
            SQLiteDatabase.openOrCreateDatabase(outputFile, null).use { db ->
                db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);")
                db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT);")
                db.execSQL("INSERT INTO metadata (name, value) VALUES ('name', 'Radius ${radiusKm}km');")
                db.execSQL("INSERT INTO metadata (name, value) VALUES ('format', 'png');")
                db.execSQL("INSERT INTO metadata (name, value) VALUES ('type', 'overlay');")
                db.execSQL("INSERT INTO metadata (name, value) VALUES ('version', '1.0');")
                db.execSQL("INSERT INTO metadata (name, value) VALUES ('description', 'Downloaded from OpenStreetMap');")

                var totalTiles = 0
                for (z in minZoom..maxZoom) {
                    totalTiles += countTilesInBbox(bbox, z)
                }

                var downloaded = 0
                for (z in minZoom..maxZoom) {
                    val tiles = getTilesInBbox(bbox, z)
                    for ((x, y) in tiles) {
                        if (!isActive) {
                            return@withContext Result.failure(Exception("Download cancelled"))
                        }
                        val url = "https://tile.openstreetmap.org/$z/$x/$y.png"
                        try {
                            val request = Request.Builder().url(url).get().build()
                            val response = client.newCall(request).execute()
                            if (response.isSuccessful) {
                                response.body?.bytes()?.let { bytes ->
                                    val tmsY = (1 shl z) - 1 - y
                                    val contentValues = ContentValues().apply {
                                        put("zoom_level", z)
                                        put("tile_column", x)
                                        put("tile_row", tmsY)
                                        put("tile_data", bytes)
                                    }
                                    db.insert("tiles", null, contentValues)
                                }
                            }
                            response.close()
                        } catch (e: Exception) {
                            Timber.e(e, "Gagal download tile $z/$x/$y")
                        }
                        downloaded++
                        val progress = (downloaded * 100 / totalTiles).coerceIn(0, 100)
                        onProgress(progress)
                    }
                }
            }
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadMapArea(
        boundingBox: BoundingBox,
        zoomLevels: IntRange,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val fileName = "area_${System.currentTimeMillis()}.mbtiles"
        val outputFile = File(tileCacheDir, fileName)

        try {
            SQLiteDatabase.openOrCreateDatabase(outputFile, null).use { db ->
                db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);")
                db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT);")
                db.execSQL("INSERT INTO metadata (name, value) VALUES ('name', 'Custom Area');")
                db.execSQL("INSERT INTO metadata (name, value) VALUES ('format', 'png');")
                db.execSQL("INSERT INTO metadata (name, value) VALUES ('type', 'overlay');")
                db.execSQL("INSERT INTO metadata (name, value) VALUES ('version', '1.0');")
                db.execSQL("INSERT INTO metadata (name, value) VALUES ('description', 'Downloaded from OpenStreetMap');")

                var totalTiles = 0
                for (z in zoomLevels) {
                    totalTiles += countTilesInBbox(boundingBox, z)
                }

                var downloaded = 0
                for (z in zoomLevels) {
                    val tiles = getTilesInBbox(boundingBox, z)
                    for ((x, y) in tiles) {
                        if (!isActive) {
                            return@withContext Result.failure(Exception("Download cancelled"))
                        }
                        val url = "https://tile.openstreetmap.org/$z/$x/$y.png"
                        try {
                            val request = Request.Builder().url(url).get().build()
                            val response = client.newCall(request).execute()
                            if (response.isSuccessful) {
                                response.body?.bytes()?.let { bytes ->
                                    val tmsY = (1 shl z) - 1 - y
                                    val contentValues = ContentValues().apply {
                                        put("zoom_level", z)
                                        put("tile_column", x)
                                        put("tile_row", tmsY)
                                        put("tile_data", bytes)
                                    }
                                    db.insert("tiles", null, contentValues)
                                }
                            }
                            response.close()
                        } catch (e: Exception) {
                            Timber.e(e, "Gagal download tile $z/$x/$y")
                        }
                        downloaded++
                        val progress = (downloaded * 100 / totalTiles).coerceIn(0, 100)
                        onProgress(progress)
                    }
                }
            }
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun enableOfflineMode(mapView: MapView) {
        try {
            val archives = tileCacheDir.listFiles { file ->
                file.isFile && (file.extension == "mbtiles" || file.extension == "sqlite")
            }
            if (!archives.isNullOrEmpty()) {
                val provider = OfflineTileProvider(SimpleRegisterReceiver(context), archives)
                mapView.setTileProvider(provider)
                Timber.d("Offline mode enabled with ${archives.size} files")
            } else {
                mapView.setTileSource(TileSourceFactory.MAPNIK)
                Timber.d("No offline tiles, using online")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to set offline provider")
            mapView.setTileSource(TileSourceFactory.MAPNIK)
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = DownloadState.Cancelled
    }

    // ========== UTILITAS ==========
    private fun lonToTileX(lon: Double, zoom: Int): Int {
        return floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
    }

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat)
        val y = (1 shl zoom) * (1 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2
        return floor(y).toInt()
    }

    private fun countTilesInBbox(bbox: BoundingBox, zoom: Int): Int {
        val minTileX = lonToTileX(bbox.lonWest, zoom)
        val maxTileX = lonToTileX(bbox.lonEast, zoom)
        val minTileY = latToTileY(bbox.latNorth, zoom)
        val maxTileY = latToTileY(bbox.latSouth, zoom)
        return (maxTileX - minTileX + 1) * (maxTileY - minTileY + 1)
    }

    private fun getTilesInBbox(bbox: BoundingBox, zoom: Int): List<Pair<Int, Int>> {
        val tiles = mutableListOf<Pair<Int, Int>>()
        val minTileX = lonToTileX(bbox.lonWest, zoom)
        val maxTileX = lonToTileX(bbox.lonEast, zoom)
        val minTileY = latToTileY(bbox.latNorth, zoom)
        val maxTileY = latToTileY(bbox.latSouth, zoom)
        for (x in minTileX..maxTileX) {
            for (y in minTileY..maxTileY) {
                tiles.add(Pair(x, y))
            }
        }
        return tiles
    }
}