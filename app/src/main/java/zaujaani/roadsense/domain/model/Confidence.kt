package zaujaani.roadsense.domain.model

import zaujaani.roadsense.core.constants.SurveyConstants

enum class Confidence(val displayName: String) {
    HIGH("Tinggi"),
    MEDIUM("Sedang"),
    LOW("Rendah");

    companion object {
        fun calculate(speed: Float, accuracy: Float, vibration: Float): Confidence {
            // Validation based on requirements:
            // ✅ Speed < 20 km/h (CONFIDENCE_SPEED_THRESHOLD_KMH)
            // ✅ Accuracy < 5 meter (CONFIDENCE_ACCURACY_THRESHOLD_M)
            // ✅ Vibration spike > 1.5g (CONFIDENCE_VIBRATION_THRESHOLD)

            val speedValid = speed < SurveyConstants.CONFIDENCE_SPEED_THRESHOLD_KMH
            val accuracyValid = accuracy < SurveyConstants.CONFIDENCE_ACCURACY_THRESHOLD_M
            val vibrationValid = vibration > SurveyConstants.CONFIDENCE_VIBRATION_THRESHOLD

            // Beri skor berdasarkan kombinasi
            val score = when {
                speedValid && accuracyValid && vibrationValid -> 3 // HIGH
                speedValid && accuracyValid -> 2 // MEDIUM
                speedValid && vibrationValid -> 2 // MEDIUM (akurasi jelek tapi ada vibration spike)
                accuracyValid && vibrationValid -> 2 // MEDIUM (speed tinggi tapi akurasi dan vibration baik)
                else -> 1 // LOW
            }

            return when (score) {
                3 -> HIGH
                2 -> MEDIUM
                else -> LOW
            }
        }
    }
}