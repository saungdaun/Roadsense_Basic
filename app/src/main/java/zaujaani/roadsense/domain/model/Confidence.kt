package zaujaani.roadsense.domain.model

enum class Confidence(val displayName: String) {
    HIGH("Tinggi"),
    MEDIUM("Sedang"),
    LOW("Rendah");

    companion object {
        fun calculate(speed: Float, accuracy: Float, vibration: Float): Confidence {
            // Validation based on requirements:
            // ✅ Speed < 20 km/h
            // ✅ Accuracy < 5 meter
            // ✅ Vibration spike > 1.5g

            val speedValid = speed < 20f
            val accuracyValid = accuracy < 5f
            val vibrationValid = vibration > 1.5f

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