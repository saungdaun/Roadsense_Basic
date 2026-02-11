package zaujaani.roadsense.domain.engine

import androidx.annotation.Keep
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Quality Score Calculator
 *
 * Menghitung skor kualitas data berdasarkan beberapa faktor:
 * - IRI average (semakin rendah semakin baik)
 * - Data density (semakin banyak data point semakin reliable)
 * - GPS availability (bonus jika GPS selalu tersedia)
 * - Sensor consistency (pengecekan outlier)
 */
@Singleton
class QualityScoreCalculator @Inject constructor() {

    /**
     * Calculate overall quality score (0-100)
     */
    @Keep
    fun calculateQualityScore(
        avgIRI: Double,
        dataPointCount: Int,
        gpsAvailabilityPercent: Double,
        hasOutliers: Boolean = false
    ): Double {

        // 1. IRI Score (0-40 points)
        val iriScore = calculateIRIScore(avgIRI)

        // 2. Data Density Score (0-30 points)
        val densityScore = calculateDensityScore(dataPointCount)

        // 3. GPS Availability Score (0-20 points)
        val gpsScore = gpsAvailabilityPercent * 0.2

        // 4. Consistency Score (0-10 points)
        val consistencyScore = if (hasOutliers) 5.0 else 10.0

        val totalScore = iriScore + densityScore + gpsScore + consistencyScore

        return totalScore.coerceIn(0.0, 100.0)
    }

    /**
     * Calculate IRI-based score (0-40 points)
     * Lower IRI = higher score
     */
    private fun calculateIRIScore(avgIRI: Double): Double {
        return when {
            avgIRI < 2.0 -> 40.0  // EXCELLENT
            avgIRI < 4.0 -> 30.0  // GOOD
            avgIRI < 6.0 -> 20.0  // FAIR
            avgIRI < 8.0 -> 10.0  // POOR
            else -> 5.0           // BAD
        }
    }

    /**
     * Calculate data density score (0-30 points)
     * More data points = more reliable
     */
    private fun calculateDensityScore(dataPointCount: Int): Double {
        return when {
            dataPointCount >= 5000 -> 30.0
            dataPointCount >= 2000 -> 25.0
            dataPointCount >= 1000 -> 20.0
            dataPointCount >= 500 -> 15.0
            dataPointCount >= 100 -> 10.0
            else -> 5.0
        }
    }

    /**
     * Validate Z-axis data for outliers
     * Returns true if data has significant outliers
     */
    @Keep
    fun hasZAxisOutliers(zValues: List<Double>, threshold: Double = 3.0): Boolean {
        if (zValues.isEmpty()) return false

        val mean = zValues.average()
        val stdDev = calculateStandardDeviation(zValues, mean)

        // Count how many values are beyond threshold * stdDev from mean
        val outlierCount = zValues.count { abs(it - mean) > threshold * stdDev }

        // If more than 10% are outliers, flag as having outliers
        return (outlierCount.toDouble() / zValues.size) > 0.1
    }

    /**
     * Calculate standard deviation
     */
    private fun calculateStandardDeviation(values: List<Double>, mean: Double): Double {
        if (values.isEmpty()) return 0.0

        val variance = values.map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(variance)
    }

    /**
     * Calculate GPS availability percentage
     */
    @Keep
    fun calculateGPSAvailability(totalPoints: Int, gpsAvailablePoints: Int): Double {
        if (totalPoints == 0) return 0.0
        return (gpsAvailablePoints.toDouble() / totalPoints) * 100.0
    }

    /**
     * Get quality rating from score
     */
    @Keep
    fun getQualityRating(score: Double): String {
        return when {
            score >= 90 -> "EXCELLENT"
            score >= 75 -> "VERY GOOD"
            score >= 60 -> "GOOD"
            score >= 40 -> "FAIR"
            score >= 20 -> "POOR"
            else -> "BAD"
        }
    }
}