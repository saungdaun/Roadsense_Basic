package zaujaani.roadsense.features.map

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import zaujaani.roadsense.R
import zaujaani.roadsense.databinding.FragmentMapSurveyBinding
import zaujaani.roadsense.domain.usecase.ZAxisValidation

class UIStateBinder(
    private val binding: FragmentMapSurveyBinding,
    private val context: Context
) {
    fun bindUiState(state: MapViewModel.MapUiState) {
        // Recording status
        binding.tvRecordingStatus.visibility = if (state.isTracking && !state.isPaused) View.VISIBLE else View.GONE
        binding.tvRecordingStatus.text = if (state.isPaused) {
            context.getString(R.string.status_paused_ui)
        } else {
            context.getString(R.string.status_recording)
        }
        binding.tvRecordingStatus.setBackgroundColor(
            ContextCompat.getColor(context, if (state.isPaused) R.color.pause_yellow else R.color.stop_red)
        )

        // Data displays
        binding.tvSpeed.text = context.getString(R.string.speed_format, state.currentSpeed)
        binding.tvDistance.text = context.getString(R.string.distance_format, state.tripDistance / 1000)
        binding.tvVibration.text = context.getString(R.string.vibration_format, kotlin.math.abs(state.currentVibration))

        // ESP32 connection
        binding.tvConnectionStatus.text = if (state.esp32Connected) {
            context.getString(R.string.esp32_connected)
        } else {
            context.getString(R.string.esp32_disconnected)
        }
        binding.tvConnectionStatus.setTextColor(
            ContextCompat.getColor(context, if (state.esp32Connected) R.color.connected_green else R.color.disconnected_red)
        )

        // Packet info
        binding.tvPacketInfo.text = context.getString(
            R.string.packet_info_format,
            state.packetCount,
            state.errorCount
        )

        bindGpsStatus(state)
    }

    fun bindGpsStatus(state: MapViewModel.MapUiState) {
        when (state.gpsSignalStrength) {
            MapViewModel.SignalStrength.NONE -> {
                binding.tvGpsStatus.text = context.getString(R.string.gps_unavailable)
                binding.tvGpsStatus.setTextColor(ContextCompat.getColor(context, R.color.gps_none))
            }
            else -> {
                binding.tvGpsStatus.text = context.getString(R.string.gps_accuracy, state.gpsAccuracy)
                binding.tvGpsStatus.setTextColor(ContextCompat.getColor(context, R.color.gps_good))
            }
        }
    }

    fun bindControlButtons(isTracking: Boolean, isPaused: Boolean, isReady: Boolean) {
        if (isTracking) {
            binding.btnStartStop.text = context.getString(R.string.btn_stop)
            binding.btnStartStop.icon = ContextCompat.getDrawable(context, R.drawable.ic_stop)
            binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(context, R.color.stop_red)
            binding.btnStartStop.iconTint = ContextCompat.getColorStateList(context, R.color.white)
            binding.btnStartStop.isEnabled = true

            binding.btnPauseResume.isEnabled = true
            if (isPaused) {
                binding.btnPauseResume.text = context.getString(R.string.btn_resume)
                binding.btnPauseResume.icon = ContextCompat.getDrawable(context, R.drawable.ic_play_arrow)
            } else {
                binding.btnPauseResume.text = context.getString(R.string.btn_pause)
                binding.btnPauseResume.icon = ContextCompat.getDrawable(context, R.drawable.ic_pause)
            }
            binding.btnPauseResume.iconTint = ContextCompat.getColorStateList(context, R.color.white)

            binding.btnAddSurvey.isEnabled = true
        } else {
            binding.btnStartStop.text = context.getString(R.string.btn_start)
            binding.btnStartStop.icon = ContextCompat.getDrawable(context, R.drawable.ic_play_arrow)
            binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(context, R.color.start_green)
            binding.btnStartStop.iconTint = ContextCompat.getColorStateList(context, R.color.white)
            binding.btnStartStop.isEnabled = isReady

            binding.btnPauseResume.isEnabled = false
            binding.btnPauseResume.text = context.getString(R.string.btn_pause)
            binding.btnPauseResume.icon = ContextCompat.getDrawable(context, R.drawable.ic_pause)
            binding.btnPauseResume.iconTint = ContextCompat.getColorStateList(context, R.color.white)

            binding.btnAddSurvey.isEnabled = false
        }
    }

    fun bindDeviceReadyState(state: MapViewModel.DeviceReadyState) {
        when (state) {
            MapViewModel.DeviceReadyState.READY -> {
                binding.btnStartStop.isEnabled = true
                binding.tvStatus.text = context.getString(R.string.status_ready_sensor)
                binding.tvStatus.setBackgroundColor(ContextCompat.getColor(context, R.color.status_ready))
            }
            MapViewModel.DeviceReadyState.NOT_CONNECTED -> {
                binding.btnStartStop.isEnabled = false
                binding.tvStatus.text = context.getString(R.string.esp32_disconnected)
            }
            MapViewModel.DeviceReadyState.CALIBRATION_NEEDED -> {
                binding.btnStartStop.isEnabled = false
                binding.tvStatus.text = context.getString(R.string.calibration_needed)
            }
            MapViewModel.DeviceReadyState.GPS_DISABLED,
            MapViewModel.DeviceReadyState.BLUETOOTH_DISABLED -> {
                binding.btnStartStop.isEnabled = false
            }
        }
    }

    fun bindZAxisValidation(validation: ZAxisValidation?) {
        if (validation == null) {
            binding.tvZAxisStatus.text = context.getString(R.string.vibration_default)
            binding.btnAddSurvey.isEnabled = false
            return
        }

        val (colorRes, statusText, isAddEnabled) = when (validation) {
            is ZAxisValidation.ValidMoving ->
                Triple(R.color.good_green, validation.message, true)
            is ZAxisValidation.WarningStopped ->
                Triple(R.color.fair_yellow, validation.message, false)
            is ZAxisValidation.InvalidShake ->
                Triple(R.color.heavy_damage_red, validation.message, false)
            is ZAxisValidation.InvalidNoMovement ->
                Triple(R.color.heavy_damage_red, validation.message, false)
            is ZAxisValidation.SuspiciousPattern ->
                Triple(R.color.fair_yellow, validation.message, false)
        }

        binding.tvZAxisStatus.apply {
            text = statusText
            val drawable = compoundDrawablesRelative[0]
            drawable?.mutate()?.setTint(ContextCompat.getColor(context, colorRes))
            setCompoundDrawablesRelative(drawable, null, null, null)
        }

        binding.tvZAxisMessage.apply {
            if (validation is ZAxisValidation.ValidMoving) {
                val percent = (validation.confidence * 100).toInt()
                text = context.getString(R.string.confidence_level, "$percent%")
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        binding.btnAddSurvey.isEnabled = isAddEnabled
    }
}