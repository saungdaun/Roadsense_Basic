package zaujaani.roadsense.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "roadsense_settings"
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    companion object {
        val GPS_ENABLED = booleanPreferencesKey("gps_enabled")
        val BLUETOOTH_ENABLED = booleanPreferencesKey("bluetooth_enabled")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val AUTO_SAVE_ENABLED = booleanPreferencesKey("auto_save_enabled")
        val VIBRATION_DETECTION_ENABLED = booleanPreferencesKey("vibration_detection_enabled")
    }

    // ✅ FIXED: Use Pair() constructor instead of 'to' operator
    private val defaultValues: Map<Preferences.Key<*>, Any> = mapOf(
        Pair(GPS_ENABLED, true),
        Pair(BLUETOOTH_ENABLED, true),
        Pair(NOTIFICATIONS_ENABLED, true),
        Pair(AUTO_SAVE_ENABLED, true),
        Pair(VIBRATION_DETECTION_ENABLED, true)
    )

    val settingsFlow: Flow<Map<Preferences.Key<*>, Any>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            defaultValues.mapValues { (key, defaultValue) ->
                prefs[key] ?: defaultValue
            }
        }

    fun isGpsEnabled(): Flow<Boolean> = settingsFlow.map { it[GPS_ENABLED] as Boolean }
    fun isBluetoothEnabled(): Flow<Boolean> = settingsFlow.map { it[BLUETOOTH_ENABLED] as Boolean }
    fun areNotificationsEnabled(): Flow<Boolean> = settingsFlow.map { it[NOTIFICATIONS_ENABLED] as Boolean }
    fun isAutoSaveEnabled(): Flow<Boolean> = settingsFlow.map { it[AUTO_SAVE_ENABLED] as Boolean }
    fun isVibrationDetectionEnabled(): Flow<Boolean> = settingsFlow.map { it[VIBRATION_DETECTION_ENABLED] as Boolean }

    suspend fun setGpsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[GPS_ENABLED] = enabled
        }
    }

    suspend fun setBluetoothEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[BLUETOOTH_ENABLED] = enabled
        }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[NOTIFICATIONS_ENABLED] = enabled
        }
    }

    suspend fun setAutoSaveEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[AUTO_SAVE_ENABLED] = enabled
        }
    }

    suspend fun setVibrationDetectionEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[VIBRATION_DETECTION_ENABLED] = enabled
        }
    }

    suspend fun resetToDefaults() {
        dataStore.edit { prefs ->
            prefs[GPS_ENABLED] = true
            prefs[BLUETOOTH_ENABLED] = true
            prefs[NOTIFICATIONS_ENABLED] = true
            prefs[AUTO_SAVE_ENABLED] = true
            prefs[VIBRATION_DETECTION_ENABLED] = true
        }
    }
}