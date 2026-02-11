package zaujaani.roadsense.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bluetooth Gateway - Mengelola komunikasi dengan ESP32 RoadSense Bridge
 *
 * Protocol Format (dari ESP32):
 * DATA|sessionId|timestamp|distance|speed|accelZ|iri|lat|lon|alt|gps_speed|gps_acc|bat|state|crc
 *
 * Commands (ke ESP32):
 * START_SURVEY|sessionId
 * STOP_SURVEY
 * PAUSE_SURVEY
 * RESUME_SURVEY
 * CALIBRATE_WHEEL|circumference
 * CALIBRATE_ACCEL_Z|offset
 * GET_STATUS
 */
@Singleton
class BluetoothGateway @Inject constructor() {

    companion object {
        private const val ESP32_NAME = "RoadsenseLogger-v3.7"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val BUFFER_SIZE = 1024
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingData = MutableStateFlow<String>("")
    val incomingData: StateFlow<String> = _incomingData.asStateFlow()

    private var isReading = false

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    init {
        try {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get Bluetooth adapter")
        }
    }

    /**
     * Connect to ESP32 device
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (bluetoothAdapter == null) {
                _connectionState.value = ConnectionState.Error("Bluetooth not available")
                return@withContext false
            }

            if (!bluetoothAdapter!!.isEnabled) {
                _connectionState.value = ConnectionState.Error("Bluetooth is disabled")
                return@withContext false
            }

            _connectionState.value = ConnectionState.Connecting
            Timber.d("🔵 Connecting to $ESP32_NAME...")

            // Find ESP32 device
            val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
            val esp32Device = pairedDevices?.find { it.name == ESP32_NAME }

            if (esp32Device == null) {
                _connectionState.value = ConnectionState.Error("$ESP32_NAME not found in paired devices")
                Timber.w("⚠️ ESP32 device not found")
                return@withContext false
            }

            // Create socket and connect
            bluetoothSocket = esp32Device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothSocket?.connect()

            inputStream = bluetoothSocket?.inputStream
            outputStream = bluetoothSocket?.outputStream

            _connectionState.value = ConnectionState.Connected
            Timber.d("✅ Connected to $ESP32_NAME")

            // Start reading data
            startReading()

            true
        } catch (e: IOException) {
            Timber.e(e, "❌ Bluetooth connection failed")
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
            disconnect()
            false
        } catch (e: Exception) {
            Timber.e(e, "❌ Unexpected error during connection")
            _connectionState.value = ConnectionState.Error("Error: ${e.message}")
            disconnect()
            false
        }
    }

    /**
     * Disconnect from ESP32
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            isReading = false
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()

            inputStream = null
            outputStream = null
            bluetoothSocket = null

            _connectionState.value = ConnectionState.Disconnected
            Timber.d("🔴 Disconnected from Bluetooth")
        } catch (e: IOException) {
            Timber.e(e, "Error during disconnect")
        }
    }

    /**
     * Send command to ESP32
     */
    suspend fun sendCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (_connectionState.value != ConnectionState.Connected) {
                Timber.w("⚠️ Cannot send command, not connected")
                return@withContext false
            }

            val message = "$command\n"
            outputStream?.write(message.toByteArray())
            outputStream?.flush()

            Timber.d("📤 Sent: $command")
            true
        } catch (e: IOException) {
            Timber.e(e, "❌ Failed to send command: $command")
            _connectionState.value = ConnectionState.Error("Send failed")
            false
        }
    }

    /**
     * Start reading data from ESP32
     */
    private fun startReading() {
        isReading = true
        Thread {
            val buffer = ByteArray(BUFFER_SIZE)
            val stringBuffer = StringBuilder()

            while (isReading && _connectionState.value == ConnectionState.Connected) {
                try {
                    val bytesRead = inputStream?.read(buffer) ?: -1

                    if (bytesRead > 0) {
                        val data = String(buffer, 0, bytesRead)
                        stringBuffer.append(data)

                        // Process complete lines
                        var newlineIndex = stringBuffer.indexOf("\n")
                        while (newlineIndex != -1) {
                            val line = stringBuffer.substring(0, newlineIndex).trim()
                            stringBuffer.delete(0, newlineIndex + 1)

                            if (line.isNotEmpty()) {
                                _incomingData.value = line
                                Timber.v("📥 Received: $line")
                            }

                            newlineIndex = stringBuffer.indexOf("\n")
                        }
                    } else if (bytesRead == -1) {
                        // Connection closed
                        Timber.w("⚠️ Bluetooth connection closed by remote device")
                        _connectionState.value = ConnectionState.Disconnected
                        break
                    }
                } catch (e: IOException) {
                    if (isReading) {
                        Timber.e(e, "❌ Error reading from Bluetooth")
                        _connectionState.value = ConnectionState.Error("Read error")
                    }
                    break
                }
            }

            Timber.d("🛑 Stopped reading from Bluetooth")
        }.start()
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean {
        return _connectionState.value == ConnectionState.Connected
    }

    /**
     * Get paired devices
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }
}