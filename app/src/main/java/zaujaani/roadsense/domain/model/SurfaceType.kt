package zaujaani.roadsense.domain.model

enum class SurfaceType(val displayName: String, val code: String) {
    ASPHALT("Aspal", "ASPHALT"),
    CONCRETE("Beton", "CONCRETE"),
    GRAVEL("Kerikil", "GRAVEL"),
    DIRT("Tanah", "DIRT"),
    OTHER("Lainnya", "OTHER");

    companion object {
        fun fromCode(code: String): SurfaceType {
            return entries.firstOrNull { it.code == code } ?: ASPHALT
        }
    }
}
