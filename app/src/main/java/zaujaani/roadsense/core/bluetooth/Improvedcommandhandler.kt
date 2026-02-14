package zaujaani.roadsense.core.bluetooth

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import zaujaani.roadsense.core.constants.SurveyConstants
import java.util.concurrent.atomic.AtomicInteger

/**
 * ImprovedCommandHandler - Bluetooth command handler dengan ACK validation
 *
 * Features:
 * - Command-response matching dengan timeout
 * - Retry mechanism untuk failed commands
 * - Command queue untuk sequential execution
 * - Response validation
 * - Metrics tracking
 *
 * Protocol:
 * ```
 * Android → ESP32: CMD:START\n
 * ESP32 → Android: ACK:START,OK\n
 *
 * Android → ESP32: CMD:STATUS\n
 * ESP32 → Android: STATUS,RUNNING,SESSION=12345\n
 * ```
 */
class ImprovedCommandHandler(
    private val bluetoothGateway: BluetoothGateway
) {

    companion object {
        private const val COMMAND_TIMEOUT_MS = SurveyConstants.COMMAND_TIMEOUT_MS
        private const val COMMAND_RESPONSE_TIMEOUT_MS = SurveyConstants.COMMAND_RESPONSE_TIMEOUT_MS
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 500L
    }

    /**
     * Command result sealed class
     */
    sealed class CommandResult {
        data class Success(val response: String) : CommandResult()
        data class Timeout(val command: String, val attemptedRetries: Int) : CommandResult()
        data class Error(val message: String, val exception: Exception? = null) : CommandResult()
        data class NotConnected(val message: String = "ESP32 not connected") : CommandResult()
    }

    /**
     * Command metrics
     */
    data class CommandMetrics(
        val totalCommands: Int = 0,
        val successfulCommands: Int = 0,
        val failedCommands: Int = 0,
        val timeoutCommands: Int = 0,
        val averageResponseTimeMs: Long = 0
    )

    private val commandCounter = AtomicInteger(0)
    private val pendingResponses = mutableMapOf<Int, CompletableDeferred<String>>()

    // Response flow untuk parsing incoming data
    private val responseFlow = MutableSharedFlow<String>(extraBufferCapacity = 100)

    // Metrics
    private var metrics = CommandMetrics()
    private val responseTimes = mutableListOf<Long>()

    init {
        // Listen to incoming data dari BluetoothGateway
        // Note: Ini akan di-integrate dengan actual BluetoothGateway flow
        observeIncomingData()
    }

    /**
     * Send command dengan ACK validation dan retry
     *
     * @param command Command string (e.g., "CMD:START")
     * @param expectedResponsePrefix Expected response prefix untuk validation (e.g., "ACK:START")
     * @param retries Number of retry attempts jika timeout
     * @param timeoutMs Timeout untuk response (milliseconds)
     * @return CommandResult
     */
    suspend fun sendCommandWithAck(
        command: String,
        expectedResponsePrefix: String? = null,
        retries: Int = MAX_RETRY_ATTEMPTS,
        timeoutMs: Long = COMMAND_RESPONSE_TIMEOUT_MS
    ): CommandResult = withContext(Dispatchers.IO) {

        // Check connection
        if (!bluetoothGateway.isConnected()) {
            return@withContext CommandResult.NotConnected()
        }

        var attemptCount = 0
        var lastError: Exception? = null

        while (attemptCount < retries) {
            attemptCount++

            try {
                val startTime = System.currentTimeMillis()

                // Generate command ID untuk tracking
                val commandId = commandCounter.incrementAndGet()

                // Prepare deferred result
                val responseDeferred = CompletableDeferred<String>()
                pendingResponses[commandId] = responseDeferred

                // Send command
                val sent = bluetoothGateway.sendCommand(command)
                if (!sent) {
                    throw Exception("Failed to send command to ESP32")
                }

                Timber.d("📤 Sent command #$commandId: $command (attempt $attemptCount/$retries)")

                // Wait for response dengan timeout
                val response = try {
                    withTimeout(timeoutMs) {
                        responseDeferred.await()
                    }
                } catch (e: TimeoutCancellationException) {
                    pendingResponses.remove(commandId)

                    if (attemptCount < retries) {
                        Timber.w("⏱️ Command #$commandId timeout, retrying... (${attemptCount + 1}/$retries)")
                        delay(RETRY_DELAY_MS)
                        continue
                    } else {
                        Timber.e("❌ Command #$commandId timeout after $retries attempts")
                        metrics = metrics.copy(
                            totalCommands = metrics.totalCommands + 1,
                            timeoutCommands = metrics.timeoutCommands + 1
                        )
                        return@withContext CommandResult.Timeout(command, attemptCount)
                    }
                }

                // Validate response
                if (expectedResponsePrefix != null && !response.startsWith(expectedResponsePrefix)) {
                    Timber.w("⚠️ Unexpected response for #$commandId: $response (expected prefix: $expectedResponsePrefix)")

                    if (attemptCount < retries) {
                        delay(RETRY_DELAY_MS)
                        continue
                    } else {
                        metrics = metrics.copy(
                            totalCommands = metrics.totalCommands + 1,
                            failedCommands = metrics.failedCommands + 1
                        )
                        return@withContext CommandResult.Error(
                            "Unexpected response: $response",
                            Exception("Response validation failed")
                        )
                    }
                }

                // Success!
                val responseTime = System.currentTimeMillis() - startTime
                responseTimes.add(responseTime)

                metrics = metrics.copy(
                    totalCommands = metrics.totalCommands + 1,
                    successfulCommands = metrics.successfulCommands + 1,
                    averageResponseTimeMs = responseTimes.average().toLong()
                )

                Timber.d("✅ Command #$commandId successful in ${responseTime}ms: $response")
                return@withContext CommandResult.Success(response)

            } catch (e: Exception) {
                lastError = e
                Timber.w(e, "❌ Error sending command (attempt $attemptCount/$retries)")

                if (attemptCount < retries) {
                    delay(RETRY_DELAY_MS)
                }
            }
        }

        // All retries failed
        metrics = metrics.copy(
            totalCommands = metrics.totalCommands + 1,
            failedCommands = metrics.failedCommands + 1
        )

        CommandResult.Error(
            "Command failed after $retries attempts: ${lastError?.message}",
            lastError
        )
    }

    /**
     * Convenience methods for common commands
     */

    suspend fun startSurvey(): CommandResult {
        return sendCommandWithAck(
            command = "CMD:START",
            expectedResponsePrefix = "ACK:START"
        )
    }

    suspend fun stopSurvey(): CommandResult {
        return sendCommandWithAck(
            command = "CMD:STOP",
            expectedResponsePrefix = "ACK:STOP"
        )
    }

    suspend fun pauseSurvey(): CommandResult {
        return sendCommandWithAck(
            command = "CMD:PAUSE",
            expectedResponsePrefix = "ACK:PAUSE"
        )
    }

    suspend fun resumeSurvey(): CommandResult {
        return sendCommandWithAck(
            command = "CMD:RESUME",
            expectedResponsePrefix = "ACK:RESUME"
        )
    }

    suspend fun getStatus(): CommandResult {
        return sendCommandWithAck(
            command = "CMD:STATUS",
            expectedResponsePrefix = "STATUS"
        )
    }

    suspend fun sendCalibration(
        wheelDiameterCm: Float,
        pulsesPerRotation: Int
    ): CommandResult {
        val command = "CMD:CAL,DIAM=$wheelDiameterCm,PULSE=$pulsesPerRotation"
        return sendCommandWithAck(
            command = command,
            expectedResponsePrefix = "ACK:CAL"
        )
    }

    /**
     * Get command metrics
     */
    fun getMetrics(): CommandMetrics = metrics

    /**
     * Reset metrics
     */
    fun resetMetrics() {
        metrics = CommandMetrics()
        responseTimes.clear()
    }

    /**
     * Get success rate (percentage)
     */
    fun getSuccessRate(): Float {
        return if (metrics.totalCommands > 0) {
            (metrics.successfulCommands.toFloat() / metrics.totalCommands) * 100
        } else {
            0f
        }
    }

    /**
     * Observe incoming data dan match dengan pending responses
     */
    private fun observeIncomingData() {
        // Note: Ini akan di-integrate dengan BluetoothGateway.incomingData flow

        // Contoh integration:
        /*
        CoroutineScope(Dispatchers.IO).launch {
            bluetoothGateway.incomingData.collect { rawData ->
                if (rawData.isEmpty()) return@collect

                // Process response
                processIncomingResponse(rawData)
            }
        }
        */
    }

    /**
     * Process incoming response dan complete pending deferred
     */
    private fun processIncomingResponse(response: String) {
        // Find matching pending response
        // For simplicity, assume FIFO order
        // In production, use command ID matching

        val firstPending = pendingResponses.entries.firstOrNull()
        firstPending?.let { (commandId, deferred) ->
            pendingResponses.remove(commandId)
            deferred.complete(response)
        }

        // Also emit to response flow untuk other listeners
        responseFlow.tryEmit(response)
    }

    /**
     * Wait for specific response pattern
     * Useful untuk waiting firmware status changes
     */
    suspend fun waitForResponse(
        pattern: String,
        timeoutMs: Long = 5000L
    ): Result<String> = withContext(Dispatchers.IO) {

        try {
            val response = withTimeout(timeoutMs) {
                responseFlow.first { it.contains(pattern) }
            }

            Result.success(response)

        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Timeout waiting for response pattern: $pattern"))
        }
    }
}