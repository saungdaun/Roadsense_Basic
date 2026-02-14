package zaujaani.roadsense.features.offlinemaps

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Parcelize
data class OfflineMapFile(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val sizeMB: Double,
    val type: String,
    val lastModified: Long,
    val path: String
) : Parcelable {
    val formattedSize: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "%.2f KB".format(sizeBytes / 1024.0)
            sizeBytes < 1024 * 1024 * 1024 -> "%.2f MB".format(sizeBytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(sizeBytes / (1024.0 * 1024.0 * 1024.0))
        }

    val formattedDate: String
        get() = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(lastModified))

    val fileTypeIcon: String
        get() = when (type.uppercase()) {
            "MBTILES" -> "🗺️"
            "SQLITE" -> "🗄️"
            else -> "📁"
        }
}