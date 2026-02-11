package zaujaani.roadsense.features.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _settingsState = MutableLiveData(SettingsState())
    val settingsState: LiveData<SettingsState> = _settingsState

    data class SettingsState(
        val isGpsEnabled: Boolean = true,
        val isBluetoothEnabled: Boolean = true,
        val areNotificationsEnabled: Boolean = true,
        val isAutoSaveEnabled: Boolean = true,
        val isVibrationDetectionEnabled: Boolean = true
    )

    fun setGpsEnabled(enabled: Boolean) {
        _settingsState.value = _settingsState.value?.copy(isGpsEnabled = enabled)
        Timber.d("GPS setting changed: $enabled")
    }

    fun setBluetoothEnabled(enabled: Boolean) {
        _settingsState.value = _settingsState.value?.copy(isBluetoothEnabled = enabled)
        Timber.d("Bluetooth setting changed: $enabled")
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        _settingsState.value = _settingsState.value?.copy(areNotificationsEnabled = enabled)
        Timber.d("Notifications setting changed: $enabled")
    }

    fun setAutoSaveEnabled(enabled: Boolean) {
        _settingsState.value = _settingsState.value?.copy(isAutoSaveEnabled = enabled)
        Timber.d("Auto-save setting changed: $enabled")
    }

    fun setVibrationDetectionEnabled(enabled: Boolean) {
        _settingsState.value = _settingsState.value?.copy(isVibrationDetectionEnabled = enabled)
        Timber.d("Vibration detection setting changed: $enabled")
    }

    fun exportDatabase() {
        viewModelScope.launch {
            Timber.d("Exporting database...")
            // TODO: Implement database export
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            Timber.d("Clearing cache...")
            // TODO: Implement cache clearing
        }
    }
}