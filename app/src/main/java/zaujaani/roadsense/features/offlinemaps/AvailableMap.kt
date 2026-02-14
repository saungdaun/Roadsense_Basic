package zaujaani.roadsense.features.offlinemaps

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class AvailableMap(
    val id: String,
    val name: String,
    val description: String,
    val sizeMb: Long,
    val url: String,
    val provider: String,
    val isDownloaded: Boolean = false // untuk nandain sudah terdownload
) : Parcelable {
    val formattedSize: String
        get() = when {
            sizeMb < 1 -> "${(sizeMb * 1024).toInt()} KB"
            sizeMb < 1024 -> "$sizeMb MB"
            else -> "%.2f GB".format(sizeMb / 1024.0)
        }
}