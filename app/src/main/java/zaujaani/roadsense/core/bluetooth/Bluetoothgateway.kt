package zaujaani.roadsense.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import zaujaani.roadsense.core.events.RealtimeRoadsenseBus
import zaujaani.roadsense.domain.model.ESP32SensorData
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: RealtimeRoadsenseBus
) {

    companion object {
        private const val ESP32_NAME = "RoadsenseLogger-v3.7"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val BUFFER_SIZE = 1024
    }

    // BluetoothManager – cara modern, tidak deprecated
    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }

    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    // Coroutine scope khusus untuk operasi Bluetooth
    private val bluetoothScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Job untuk membaca data, akan dibatalkan saat disconnect
    private var readingJob: Job? = null

    // State untuk koneksi
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Incoming data sebagai StateFlow (setiap line string)
    private val _incomingData = MutableStateFlow("")
    val incomingData: StateFlow<String> = _incomingData.asStateFlow()

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    init {
        if (bluetoothAdapter == null) {
            Timber.e("❌ Bluetooth tidak tersedia di perangkat ini")
        }
    }

    // -------------------------------------------------------------------------
    //  PUBLIC API
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        // Validasi awal
        if (bluetoothAdapter == null) {
            _connectionState.value = ConnectionState.Error("Bluetooth tidak tersedia")
            return@withContext false
        }
        if (!bluetoothAdapter!!.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth dimatikan")
            return@withContext false
        }

        _connectionState.value = ConnectionState.Connecting
        Timber.d("🔵 Menghubungkan ke $ESP32_NAME...")

        try {
            // Cari perangkat yang sudah dipasangkan
            val device = bluetoothAdapter!!.bondedDevices.find { it.name == ESP32_NAME }
            if (device == null) {
                _connectionState.value = ConnectionState.Error("$ESP32_NAME tidak ditemukan di daftar paired")
                return@withContext false
            }

            // Buat socket dan konek
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID).also {
                it.connect()
            }

            inputStream = bluetoothSocket?.inputStream
            outputStream = bluetoothSocket?.outputStream

            _connectionState.value = ConnectionState.Connected

            // Mulai membaca data secara async via callbackFlow
            startReading()

            true
        } catch (e: IOException) {
            Timber.e(e, "❌ Gagal konek Bluetooth")
            _connectionState.value = ConnectionState.Error("Koneksi gagal: ${e.message}")
            disconnect() // cleanup
            false
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        // Batalkan job membaca jika ada
        readingJob?.cancel()
        readingJob = null

        try {
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Timber.e(e, "Error saat disconnect")
        } finally {
            inputStream = null
            outputStream = null
            bluetoothSocket = null
            _connectionState.value = ConnectionState.Disconnected
            Timber.d("🔴 Bluetooth disconnected")
        }
    }

    suspend fun sendCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        if (_connectionState.value != ConnectionState.Connected) {
            Timber.w("⚠️ Tidak bisa kirim command, status = ${_connectionState.value}")
            return@withContext false
        }

        try {
            val message = "$command\n"
            outputStream?.write(message.toByteArray())
            outputStream?.flush()
            Timber.d("📤 Command terkirim: $command")
            true
        } catch (e: IOException) {
            Timber.e(e, "❌ Gagal kirim command")
            _connectionState.value = ConnectionState.Error("Gagal kirim: ${e.message}")
            false
        }
    }

    suspend fun sendCalibration(wheelDiameterCm: Float, pulsesPerRotation: Int): Boolean {
        val command = "CMD:CAL,DIAM=${wheelDiameterCm},PULSE=${pulsesPerRotation}"
        return sendCommand(command)
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.Connected

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> =
        bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()

    // -------------------------------------------------------------------------
    //  PRIVATE METHODS – COROUTINE BASED READING
    // -------------------------------------------------------------------------

    /**
     * Memulai flow pembacaan dari InputStream menggunakan callbackFlow.
     * Setiap line yang diterima akan diemit ke _incomingData dan juga dipublish ke event bus.
     */
    private fun startReading() {
        if (inputStream == null) {
            Timber.e("InputStream null, tidak bisa membaca")
            return
        }

        readingJob = callbackFlow {
            val stream = inputStream ?: throw IOException("InputStream null")
            val buffer = ByteArray(BUFFER_SIZE)
            val stringBuilder = StringBuilder()

            while (isConnected() && !isClosedForSend) {
                try {
                    val bytesRead = stream.read(buffer)
                    if (bytesRead == -1) {
                        // EOF – koneksi terputus dari sisi remote
                        close()
                        break
                    }

                    val chunk = String(buffer, 0, bytesRead)
                    stringBuilder.append(chunk)

                    var newlineIndex = stringBuilder.indexOf("\n")
                    while (newlineIndex != -1) {
                        val line = stringBuilder.substring(0, newlineIndex).trim()
                        stringBuilder.delete(0, newlineIndex + 1)

                        if (line.isNotEmpty()) {
                            // Kirim ke flow
                            trySend(line)

                            // Parse & publish ke event bus
                            ESP32SensorData.fromBluetoothPacket(line)?.let { sensorData ->
                                bus.publishSensorData(sensorData)
                            }
                        }
                        newlineIndex = stringBuilder.indexOf("\n")
                    }
                } catch (e: IOException) {
                    // Jika masih connected, anggap error, lalu stop
                    if (isConnected()) {
                        _connectionState.value = ConnectionState.Error("Read error: ${e.message}")
                    }
                    close(e)
                    break
                }
            }

            // Cleanup
            close()
        }
            .catch { e ->
                Timber.e(e, "❌ Bluetooth read flow error")
                if (isConnected()) {
                    _connectionState.value = ConnectionState.Error("Flow error: ${e.message}")
                }
            }
            .onEach { line ->
                // Update StateFlow dengan line terbaru
                _incomingData.value = line
            }
            .launchIn(bluetoothScope)  // dikoleksi di scope khusus
    }
}