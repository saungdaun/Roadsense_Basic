package zaujaani.roadsense.domain.usecase

import zaujaani.roadsense.data.local.SurveyEntity
import zaujaani.roadsense.data.repository.SurveyRepository
import zaujaani.roadsense.domain.engine.SurveyEngine
import javax.inject.Inject

/**
 * Start Survey Use Case
 */
class StartSurveyUseCase @Inject constructor(
    private val repository: SurveyRepository,
    private val surveyEngine: SurveyEngine
) {
    suspend fun execute(
        roadName: String? = null,
        wheelCircumference: Double = 2.0,
        accelZOffset: Double = 0.0
    ): Result<Pair<String, Long>> {
        return try {
            // Generate session ID
            val sessionId = surveyEngine.generateSessionId()

            // Create survey entity
            val survey = SurveyEntity(
                sessionId = sessionId,
                startTime = System.currentTimeMillis(),
                status = "RUNNING",
                roadName = roadName,
                wheelCircumference = wheelCircumference,
                accelZOffset = accelZOffset
            )

            // Insert to database
            val surveyId = repository.createSurvey(survey)

            // Start engine
            surveyEngine.startSurvey(sessionId, surveyId)

            Result.success(Pair(sessionId, surveyId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Stop Survey Use Case
 */
class StopSurveyUseCase @Inject constructor(
    private val repository: SurveyRepository,
    private val surveyEngine: SurveyEngine
) {
    suspend fun execute(): Result<Long> {
        return try {
            val surveyId = surveyEngine.getCurrentSurveyId()

            if (surveyId == -1L) {
                return Result.failure(Exception("No active survey"))
            }

            // Update survey status
            repository.updateSurveyStatus(
                surveyId = surveyId,
                status = "COMPLETED",
                endTime = System.currentTimeMillis()
            )

            // Calculate final statistics
            repository.calculateSurveyStatistics(surveyId)

            // Stop engine
            surveyEngine.stopSurvey()

            Result.success(surveyId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Pause Survey Use Case
 */
class PauseSurveyUseCase @Inject constructor(
    private val repository: SurveyRepository,
    private val surveyEngine: SurveyEngine
) {
    suspend fun execute(): Result<Long> {
        return try {
            val surveyId = surveyEngine.getCurrentSurveyId()

            if (surveyId == -1L) {
                return Result.failure(Exception("No active survey"))
            }

            // Update survey status
            repository.updateSurveyStatus(
                surveyId = surveyId,
                status = "PAUSED",
                endTime = null
            )

            // Pause engine
            surveyEngine.pauseSurvey()

            Result.success(surveyId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Resume Survey Use Case
 */
class ResumeSurveyUseCase @Inject constructor(
    private val repository: SurveyRepository,
    private val surveyEngine: SurveyEngine
) {
    suspend fun execute(): Result<Long> {
        return try {
            val surveyId = surveyEngine.getCurrentSurveyId()

            if (surveyId == -1L) {
                return Result.failure(Exception("No active survey"))
            }

            // Update survey status
            repository.updateSurveyStatus(
                surveyId = surveyId,
                status = "RUNNING",
                endTime = null
            )

            // Resume engine
            surveyEngine.resumeSurvey()

            Result.success(surveyId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Validate Z-Axis Use Case
 * Untuk validasi kalibrasi sensor
 */
class ValidateZAxisUseCase @Inject constructor() {

    fun execute(zValue: Double, expectedOffset: Double = 0.0): ValidationResult {
        val deviation = kotlin.math.abs(zValue - expectedOffset)

        return when {
            deviation < 0.05 -> ValidationResult.EXCELLENT
            deviation < 0.1 -> ValidationResult.GOOD
            deviation < 0.2 -> ValidationResult.ACCEPTABLE
            else -> ValidationResult.NEEDS_CALIBRATION
        }
    }

    enum class ValidationResult {
        EXCELLENT,
        GOOD,
        ACCEPTABLE,
        NEEDS_CALIBRATION
    }
}