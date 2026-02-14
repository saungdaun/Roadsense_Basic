package zaujaani.roadsense.domain.model

enum class RoadCondition(
    val displayName: String,
    val code: String
) {
    GOOD("Baik", "GOOD"),
    MODERATE("Sedang", "MODERATE"),
    LIGHT_DAMAGE("Rusak Ringan", "LIGHT_DAMAGE"),
    HEAVY_DAMAGE("Rusak Berat", "HEAVY_DAMAGE");

    companion object {

        fun fromCode(code: String): RoadCondition {
            return entries.find { it.code == code } ?: MODERATE
        }
    }
}
