package zaujaani.roadsense.domain.usecase

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Z-Axis Validation Helper
 *
 * Real-time validation of vehicle movement untuk mencegah human error
 * Improvement: ~40% accuracy increase menurut Developer Guide
 *
 * Usage:
 * ```
 * val validation = zAxisValidator.validate(accelZ, speed)
 * when (validation) {
 *     is ZAxisValidation.ValidMoving -> enableRecordButton()
 *     is ZAxisValidation.WarningStopped -> showWarning()
 *     is ZAxisValidation.InvalidShake -> disableRecordButton()
 * }
 * ```
 */
@Singleton
class ZAxisValidationHelper @Inject constructor() {

    companion object {
        // Threshold values (dapat di-tune berdasarkan field testing)
        private const val MIN_SPEED_MOVING = 5.0f        // km/h - kendaraan dianggap bergerak
        private const val MIN_SPEED_STOPPED = 1.0f       // km/h - kendaraan dianggap berhenti
        private const val MAX_ACCEL_NORMAL = 2.0f        // G - getaran normal
        private const val MAX_ACCEL_SHAKE = 5.0f         // G - getaran ekstrem
        private const val MIN_ACCEL_FLAT = 0.1f          // G - terlalu datar (suspicious)

        // Time-based validation (optional - untuk detect pattern)
        private const val PATTERN_WINDOW_MS = 5000L      // 5 detik window
    }

    private val recentValidations = mutableListOf<ValidationPoint>()

    data class ValidationPoint(
        val timestamp: Long,
        val accelZ: Float,
        val speed: Float,
        val result: ZAxisValidation
    )

    /**
     * Validate Z-axis with speed
     */
    fun validate(accelZ: Float, speed: Float): ZAxisValidation {
        val now = System.currentTimeMillis()

        val validation = when {
            // Case 1: Valid moving - ideal untuk record
            speed > MIN_SPEED_MOVING && abs(accelZ) < MAX_ACCEL_NORMAL -> {
                ZAxisValidation.ValidMoving(
                    confidence = calculateConfidence(accelZ, speed),
                    message = "Vehicle bergerak normal, siap mencatat"
                )
            }

            // Case 2: Warning - kendaraan berhenti
            speed < MIN_SPEED_STOPPED -> {
                ZAxisValidation.WarningStopped(
                    message = "Kendaraan berhenti atau sangat lambat"
                )
            }

            // Case 3: Invalid - getaran ekstrem
            abs(accelZ) > MAX_ACCEL_SHAKE -> {
                ZAxisValidation.InvalidShake(
                    severity = calculateShakeSeverity(accelZ),
                    message = "Getaran ekstrem terdeteksi (${String.format("%.2f", accelZ)}G)"
                )
            }

            // Case 4: Invalid - tidak ada movement sama sekali
            speed < MIN_SPEED_STOPPED && abs(accelZ) < MIN_ACCEL_FLAT -> {
                ZAxisValidation.InvalidNoMovement(
                    message = "Tidak ada pergerakan kendaraan"
                )
            }

            // Case 5: Suspicious - speed tinggi tapi accel ekstrem
            speed > MIN_SPEED_MOVING && abs(accelZ) > MAX_ACCEL_NORMAL -> {
                ZAxisValidation.SuspiciousPattern(
                    reason = "Speed tinggi (${String.format("%.1f", speed)} km/h) dengan getaran tinggi (${String.format("%.2f", accelZ)}G)",
                    message = "Pola tidak biasa - review data"
                )
            }

            // Default: suspicious
            else -> {
                ZAxisValidation.SuspiciousPattern(
                    reason = "Speed: ${String.format("%.1f", speed)} km/h, Z: ${String.format("%.2f", accelZ)}G",
                    message = "Pola tidak standar"
                )
            }
        }

        // Store untuk pattern analysis
        addToHistory(ValidationPoint(now, accelZ, speed, validation))

        // Log untuk debugging
        if (validation !is ZAxisValidation.ValidMoving) {
            Timber.d("Z-Axis Validation: $validation")
        }

        return validation
    }

    /**
     * Calculate confidence score (0.0 - 1.0)
     */
    private fun calculateConfidence(accelZ: Float, speed: Float): Float {
        var confidence = 1.0f

        // Reduce confidence if speed too low
        if (speed < MIN_SPEED_MOVING * 1.5f) {
            confidence *= (speed / (MIN_SPEED_MOVING * 1.5f))
        }

        // Reduce confidence if accel too high
        if (abs(accelZ) > MAX_ACCEL_NORMAL * 0.5f) {
            confidence *= (1.0f - (abs(accelZ) - MAX_ACCEL_NORMAL * 0.5f) / (MAX_ACCEL_NORMAL * 0.5f))
        }

        return confidence.coerceIn(0.0f, 1.0f)
    }

    /**
     * Calculate shake severity (LOW, MEDIUM, HIGH, EXTREME)
     */
    private fun calculateShakeSeverity(accelZ: Float): ShakeSeverity {
        val absAccel = abs(accelZ)
        return when {
            absAccel > 10.0f -> ShakeSeverity.EXTREME
            absAccel > 7.0f -> ShakeSeverity.HIGH
            absAccel > 5.0f -> ShakeSeverity.MEDIUM
            else -> ShakeSeverity.LOW
        }
    }

    /**
     * Add validation to history
     */
    private fun addToHistory(point: ValidationPoint) {
        recentValidations.add(point)

        // Keep only recent points (last 5 seconds)
        val cutoff = System.currentTimeMillis() - PATTERN_WINDOW_MS
        recentValidations.removeAll { it.timestamp < cutoff }
    }

    /**
     * Analyze recent pattern untuk detect anomalies
     */
    fun analyzeRecentPattern(): PatternAnalysis {
        if (recentValidations.size < 5) {
            return PatternAnalysis.InsufficientData
        }

        val validCount = recentValidations.count { it.result is ZAxisValidation.ValidMoving }
        val invalidCount = recentValidations.count {
            it.result is ZAxisValidation.InvalidShake || it.result is ZAxisValidation.InvalidNoMovement
        }

        val validPercentage = validCount.toFloat() / recentValidations.size

        return when {
            validPercentage > 0.8f -> PatternAnalysis.Consistent
            validPercentage > 0.5f -> PatternAnalysis.Intermittent
            invalidCount > recentValidations.size * 0.5f -> PatternAnalysis.PoorQuality
            else -> PatternAnalysis.Unstable
        }
    }

    /**
     * Get quality score dari recent pattern
     */
    fun getRecentQualityScore(): Float {
        if (recentValidations.isEmpty()) return 0f

        val validCount = recentValidations.count { it.result is ZAxisValidation.ValidMoving }
        return validCount.toFloat() / recentValidations.size
    }

    /**
     * Reset history (call saat start new survey)
     */
    fun reset() {
        recentValidations.clear()
        Timber.d("Z-Axis validation history cleared")
    }
}

/**
 * Validation result sealed class
 */
sealed class ZAxisValidation(
    val canRecord: Boolean,
    val color: ValidationColor
) {
    data class ValidMoving(
        val confidence: Float,
        val message: String
    ) : ZAxisValidation(canRecord = true, color = ValidationColor.GREEN)

    data class WarningStopped(
        val message: String
    ) : ZAxisValidation(canRecord = false, color = ValidationColor.YELLOW)

    data class InvalidShake(
        val severity: ShakeSeverity,
        val message: String
    ) : ZAxisValidation(canRecord = false, color = ValidationColor.RED)

    data class InvalidNoMovement(
        val message: String
    ) : ZAxisValidation(canRecord = false, color = ValidationColor.RED)

    data class SuspiciousPattern(
        val reason: String,
        val message: String
    ) : ZAxisValidation(canRecord = false, color = ValidationColor.ORANGE)
}

enum class ValidationColor {
    GREEN,      // Valid - good to go
    YELLOW,     // Warning - review
    ORANGE,     // Suspicious - caution
    RED         // Invalid - do not record
}

enum class ShakeSeverity {
    LOW,
    MEDIUM,
    HIGH,
    EXTREME
}

sealed class PatternAnalysis {
    object InsufficientData : PatternAnalysis()
    object Consistent : PatternAnalysis()      // Good quality
    object Intermittent : PatternAnalysis()    // Acceptable
    object Unstable : PatternAnalysis()        // Poor but usable
    object PoorQuality : PatternAnalysis()     // Not recommended
}