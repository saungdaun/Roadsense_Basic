package zaujaani.roadsense.features.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsense.data.local.RoadSenseDatabase
import zaujaani.roadsense.data.repository.UserPreferencesRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository,
    private val database: RoadSenseDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ========== STATE FLOW ==========
    private val _settingsState = MutableStateFlow(SettingsState())
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    data class SettingsState(
        val isGpsEnabled: Boolean = true,
        val isBluetoothEnabled: Boolean = true,
        val areNotificationsEnabled: Boolean = true,
        val isAutoSaveEnabled: Boolean = true,
        val isVibrationDetectionEnabled: Boolean = true,
        val appVersion: String = "1.0.0",
        val databaseSize: String = "0 KB"
    )

    init {
        loadSettings()
        loadAppVersion()
        // Panggil loadDatabaseSize dalam coroutine
        viewModelScope.launch {
            loadDatabaseSize()
        }
    }

    // ========== LOAD SETTINGS FROM DATASTORE ==========
    private fun loadSettings() {
        viewModelScope.launch {
            preferencesRepository.settingsFlow
                .collect { settings ->
                    _settingsState.value = _settingsState.value.copy(
                        isGpsEnabled = settings[UserPreferencesRepository.GPS_ENABLED] as Boolean,
                        isBluetoothEnabled = settings[UserPreferencesRepository.BLUETOOTH_ENABLED] as Boolean,
                        areNotificationsEnabled = settings[UserPreferencesRepository.NOTIFICATIONS_ENABLED] as Boolean,
                        isAutoSaveEnabled = settings[UserPreferencesRepository.AUTO_SAVE_ENABLED] as Boolean,
                        isVibrationDetectionEnabled = settings[UserPreferencesRepository.VIBRATION_DETECTION_ENABLED] as Boolean
                    )
                }
        }
    }

    private fun loadAppVersion() {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val version = packageInfo.versionName ?: "1.0.0"
            _settingsState.value = _settingsState.value.copy(appVersion = version)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get app version")
        }
    }

    /**
     * Load database size – dipanggil di dalam coroutine
     */
    private suspend fun loadDatabaseSize() {
        try {
            // Nama database sesuai yang didaftarkan di Room
            val dbName = "roadsense_database"
            val dbFile = context.getDatabasePath(dbName)
            val size = if (dbFile.exists()) {
                val bytes = dbFile.length()
                when {
                    bytes < 1024 -> "$bytes B"
                    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                    else -> "${bytes / (1024 * 1024)} MB"
                }
            } else {
                "0 KB"
            }
            _settingsState.value = _settingsState.value.copy(databaseSize = size)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get database size")
        }
    }

    // ========== SETTERS (PERSISTENT) ==========
    fun setGpsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setGpsEnabled(enabled)
        }
    }

    fun setBluetoothEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setBluetoothEnabled(enabled)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setNotificationsEnabled(enabled)
        }
    }

    fun setAutoSaveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoSaveEnabled(enabled)
        }
    }

    fun setVibrationDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setVibrationDetectionEnabled(enabled)
        }
    }

    // ========== EXPORT DATABASE ==========
    fun exportDatabase(onSuccess: (Uri) -> Unit = {}, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val dbName = "roadsense_database"
                val dbFile = context.getDatabasePath(dbName)
                if (!dbFile.exists()) {
                    throw Exception("Database file not found")
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val exportFileName = "roadsense_export_$timestamp.db"
                val exportFile = File(context.getExternalFilesDir(null), exportFileName)

                dbFile.copyTo(exportFile, overwrite = true)

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    exportFile
                )

                _snackbarMessage.value = "Database berhasil diekspor"
                onSuccess(uri)

                // Refresh ukuran database
                loadDatabaseSize()

            } catch (e: Exception) {
                Timber.e(e, "Export database failed")
                _snackbarMessage.value = "Gagal mengekspor database: ${e.message}"
                onError(e.message ?: "Unknown error")
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ========== CLEAR CACHE ==========
    fun clearCache(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val cacheDir = context.cacheDir
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()

                val externalCacheDir = context.externalCacheDir
                externalCacheDir?.deleteRecursively()
                externalCacheDir?.mkdirs()

                _snackbarMessage.value = "Cache berhasil dibersihkan"
                onComplete()
            } catch (e: Exception) {
                Timber.e(e, "Clear cache failed")
                _snackbarMessage.value = "Gagal membersihkan cache: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ========== RESET TO DEFAULTS ==========
    fun resetToDefaults() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                preferencesRepository.resetToDefaults()
                _snackbarMessage.value = "Pengaturan direset ke default"
            } catch (e: Exception) {
                _snackbarMessage.value = "Gagal reset pengaturan"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ========== OPEN SYSTEM SETTINGS ==========
    fun openGpsSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ========== CLEAR SNACKBAR ==========
    fun clearSnackbar() {
        _snackbarMessage.value = null
    }
}