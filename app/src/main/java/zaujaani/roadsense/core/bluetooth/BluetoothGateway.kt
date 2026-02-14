package zaujaani.roadsense.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import zaujaani.roadsense.core.constants.SurveyConstants
import zaujaani.roadsense.core.events.RealtimeRoadsenseBus
import zaujaani.roadsense.domain.model.ESP32SensorData
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BluetoothGateway - IMPROVED VERSION
 *
 * Improvements:
 * ✅ Auto-reconnect dengan exponential backoff
 * ✅ Better error handling dengan sealed class
 * ✅ Connection state machine
 * ✅ Retry mechanism dengan max attempts
 * ✅ Connection timeout handling
 * ✅ Thread-safe operations
 */
@Singleton
class BluetoothGateway @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val bus: RealtimeRoadsenseBus
) {

    companion object {
        private val ESP32_NAME = SurveyConstants.ESP32_DEVICE_NAME
        private val SPP_UUID = UUID.fromString(SurveyConstants.BLUETOOTH_SPP_UUID)
        private const val BUFFER_SIZE = SurveyConstants.BLUETOOTH_BUFFER_SIZE
        private const val SOCKET_TIMEOUT_MS = SurveyConstants.BLUETOOTH_CONNECTION_TIMEOUT_MS

        // Auto-reconnect configuration
        private const val MAX_RECONNECT_ATTEMPTS = SurveyConstants.BLUETOOTH_MAX_RECONNECT_ATTEMPTS
        private const val RECONNECT_BASE_DELAY = SurveyConstants.BLUETOOTH_RECONNECT_DELAY_BASE_MS
        private const val RECONNECT_MAX_DELAY = SurveyConstants.BLUETOOTH_RECONNECT_DELAY_MAX_MS
        private const val RECONNECT_MULTIPLIER = SurveyConstants.BLUETOOTH_RECONNECT_DELAY_MULTIPLIER
    }

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }

    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val bluetoothScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readingJob: Job? = null
    private var reconnectJob: Job? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingData = MutableStateFlow("")
    val incomingData: StateFlow<String> = _incomingData.asStateFlow()

    private var reconnectAttempts = 0
    private var isAutoReconnectEnabled = true

    /**
     * Connection State - Enhanced with error details
     */
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Reconnecting(
            val attempt: Int,
            val maxAttempts: Int,
            val nextRetryIn: Long
        ) : ConnectionState()
        data class Error(
            val type: ErrorType,
            val message: String,
            val canRetry: Boolean = true
        ) : ConnectionState()
    }

    /**
     * Error Types untuk better debugging
     */
    enum class ErrorType {
        BLUETOOTH_UNAVAILABLE,
        BLUETOOTH_DISABLED,
        DEVICE_NOT_FOUND,
        CONNECTION_FAILED,
        CONNECTION_LOST,
        TIMEOUT,
        PERMISSION_DENIED,
        MAX_RETRY_REACHED,
        UNKNOWN
    }

    init {
        if (bluetoothAdapter == null) {
            Timber.e("❌ Bluetooth tidak tersedia di perangkat ini")
            _connectionState.value = ConnectionState.Error(
                ErrorType.BLUETOOTH_UNAVAILABLE,
                "Bluetooth adapter not available"
            )
        }
    }

    /**
     * Connect to ESP32 with retry mechanism
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (bluetoothAdapter == null) {
            _connectionState.value = ConnectionState.Error(
                ErrorType.BLUETOOTH_UNAVAILABLE,
                "Bluetooth tidak tersedia",
                canRetry = false
            )
            return@withContext false
        }

        if (!bluetoothAdapter!!.isEnabled) {
            _connectionState.value = ConnectionState.Error(
                ErrorType.BLUETOOTH_DISABLED,
                "Bluetooth dimatikan",
                canRetry = false
            )
            return@withContext false
        }

        _connectionState.value = ConnectionState.Connecting
        Timber.d("🔵 Menghubungkan ke $ESP32_NAME...")

        try {
            // Find paired device
            val device = bluetoothAdapter!!.bondedDevices.find { it.name == ESP32_NAME }
            if (device == null) {
                _connectionState.value = ConnectionState.Error(
                    ErrorType.DEVICE_NOT_FOUND,
                    "$ESP32_NAME tidak ditemukan di daftar paired"
                )
                return@withContext false
            }

            // Cancel discovery to prevent interference
            bluetoothAdapter?.cancelDiscovery()

            // Create socket and connect with timeout
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)

            withTimeout(SOCKET_TIMEOUT_MS.toLong()) {
                bluetoothSocket?.connect()
            }

            inputStream = bluetoothSocket?.inputStream
            outputStream = bluetoothSocket?.outputStream

            _connectionState.value = ConnectionState.Connected
            reconnectAttempts = 0  // Reset counter on successful connection

            // Start reading data
            startDataReading()

            Timber.d("✅ Terhubung ke $ESP32_NAME")
            return@withContext true

        } catch (e: TimeoutCancellationException) {
            Timber.e(e, "❌ Connection timeout")
            _connectionState.value = ConnectionState.Error(
                ErrorType.TIMEOUT,
                "Connection timeout setelah ${SOCKET_TIMEOUT_MS}ms"
            )
            disconnect()
            return@withContext false

        } catch (e: IOException) {
            Timber.e(e, "❌ IOException during connection")
            _connectionState.value = ConnectionState.Error(
                ErrorType.CONNECTION_FAILED,
                "Gagal terhubung: ${e.message}"
            )
            disconnect()
            return@withContext false

        } catch (e: Exception) {
            Timber.e(e, "❌ Unexpected error during connection")
            _connectionState.value = ConnectionState.Error(
                ErrorType.UNKNOWN,
                "Error tidak diketahui: ${e.message}"
            )
            disconnect()
            return@withContext false
        }
    }

    /**
     * Auto-reconnect dengan exponential backoff
     *
     * Delay pattern: 1s, 2s, 4s, 8s, 16s
     * Max attempts: 5
     */
    suspend fun startAutoReconnect() {
        if (!isAutoReconnectEnabled) {
            Timber.d("Auto-reconnect disabled")
            return
        }

        reconnectJob?.cancel()
        reconnectJob = bluetoothScope.launch {
            var delay = RECONNECT_BASE_DELAY
            reconnectAttempts = 0

            while (isActive && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempts++

                _connectionState.value = ConnectionState.Reconnecting(
                    attempt = reconnectAttempts,
                    maxAttempts = MAX_RECONNECT_ATTEMPTS,
                    nextRetryIn = delay
                )

                Timber.d("🔄 Reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS (delay: ${delay}ms)")

                // Wait before retry
                delay(delay)

                // Try to connect
                try {
                    if (connect()) {
                        Timber.d("✅ Auto-reconnect successful!")
                        return@launch
                    }
                } catch (e: Exception) {
                    Timber.w(e, "❌ Reconnect attempt $reconnectAttempts failed")
                }

                // Calculate next delay (exponential backoff)
                delay = (delay * RECONNECT_MULTIPLIER).coerceAtMost(RECONNECT_MAX_DELAY)
            }

            // Max attempts reached
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                Timber.e("❌ Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached")
                _connectionState.value = ConnectionState.Error(
                    ErrorType.MAX_RETRY_REACHED,
                    "Gagal reconnect setelah $MAX_RECONNECT_ATTEMPTS percobaan",
                    canRetry = false
                )
            }
        }
    }

    /**
     * Stop auto-reconnect attempts
     */
    fun stopAutoReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /**
     * Enable/disable auto-reconnect
     */
    fun setAutoReconnectEnabled(enabled: Boolean) {
        isAutoReconnectEnabled = enabled
        Timber.d("Auto-reconnect ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Start reading data from Bluetooth
     */
    private fun startDataReading() {
        readingJob?.cancel()
        readingJob = bluetoothScope.launch {
            val buffer = ByteArray(BUFFER_SIZE)
            var dataBuffer = ""

            while (isActive && bluetoothSocket?.isConnected == true) {
                try {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) {
                        val chunk = String(buffer, 0, bytesRead)
                        dataBuffer += chunk

                        // Process complete lines
                        while (dataBuffer.contains("\n")) {
                            val lineEnd = dataBuffer.indexOf('\n')
                            val line = dataBuffer.substring(0, lineEnd).trim()
                            dataBuffer = dataBuffer.substring(lineEnd + 1)

                            if (line.isNotEmpty()) {
                                processDataLine(line)
                            }
                        }
                    } else {
                        // Connection lost
                        Timber.w("⚠️ Bluetooth connection lost (bytesRead=$bytesRead)")
                        _connectionState.value = ConnectionState.Error(
                            ErrorType.CONNECTION_LOST,
                            "Koneksi terputus"
                        )

                        // Trigger auto-reconnect
                        if (isAutoReconnectEnabled) {
                            startAutoReconnect()
                        }
                        break
                    }
                } catch (e: IOException) {
                    Timber.e(e, "❌ Error reading Bluetooth data")
                    _connectionState.value = ConnectionState.Error(
                        ErrorType.CONNECTION_LOST,
                        "Error reading data: ${e.message}"
                    )

                    // Trigger auto-reconnect
                    if (isAutoReconnectEnabled) {
                        startAutoReconnect()
                    }
                    break
                }
            }
        }
    }

    /**
     * Process incoming data line
     */
    private fun processDataLine(line: String) {
        _incomingData.value = line
        Timber.v("📥 BT RX: $line")

        // Parse RS2 format
        val sensorData = ESP32SensorData.fromBluetoothPacket(line)
        if (sensorData != null) {
            bus.publishSensorData(sensorData)
        } else {
            Timber.w("⚠️ Failed to parse packet: $line")
        }
    }

    /**
     * Send command to ESP32
     */
    suspend fun sendCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (bluetoothSocket?.isConnected != true) {
                Timber.w("⚠️ Cannot send command: not connected")
                return@withContext false
            }

            outputStream?.write("$command\n".toByteArray())
            outputStream?.flush()
            Timber.d("📤 BT TX: $command")
            return@withContext true

        } catch (e: IOException) {
            Timber.e(e, "❌ Error sending command: $command")
            return@withContext false
        }

    }

    /**
     * Mengirim perintah kalibrasi ke ESP32.
     * Format: CMD:CAL,DIAM=<diameter>,PULSE=<pulses>
     */
    suspend fun sendCalibration(wheelDiameterCm: Float, pulsesPerRotation: Int): Boolean {
        val command = "CMD:CAL,DIAM=${wheelDiameterCm},PULSE=${pulsesPerRotation}"
        return sendCommand(command)
    }

    /**
     * Disconnect from ESP32
     */
    fun disconnect() {
        try {
            stopAutoReconnect()
            readingJob?.cancel()

            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()

            bluetoothSocket = null
            inputStream = null
            outputStream = null

            _connectionState.value = ConnectionState.Disconnected
            bus.publishSensorData(null)

            Timber.d("🔴 Disconnected from $ESP32_NAME")

        } catch (e: Exception) {
            Timber.e(e, "Error during disconnect")
        }
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean {
        return bluetoothSocket?.isConnected == true
    }

    /**
     * Get current error if any
     */
    fun getCurrentError(): ErrorType? {
        return when (val state = _connectionState.value) {
            is ConnectionState.Error -> state.type
            else -> null
        }
    }

    /**
     * Manual retry connection
     */
    suspend fun retryConnection(): Boolean {
        stopAutoReconnect()
        reconnectAttempts = 0
        return connect()
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        disconnect()
        bluetoothScope.cancel()
    }
}