package zaujaani.roadsense.features.summary

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import zaujaani.roadsense.R
import zaujaani.roadsense.databinding.ItemSegmentSummaryBinding
import zaujaani.roadsense.data.local.RoadSegmentSummary
import zaujaani.roadsense.domain.model.RoadCondition
import zaujaani.roadsense.domain.model.SurfaceType
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class SummaryAdapter(
    private val onItemClick: (RoadSegmentSummary) -> Unit = {},
    private val onItemLongClick: (RoadSegmentSummary) -> Boolean = { false }
) : ListAdapter<RoadSegmentSummary, SummaryAdapter.SummaryViewHolder>(DiffCallback()) {

    /**
     * Interface untuk aksi dari adapter ke fragment
     */
    interface SummaryActions {
        fun onExportClicked(summary: RoadSegmentSummary)
        fun onDeleteClicked(summary: RoadSegmentSummary)
        fun onEditClicked(summary: RoadSegmentSummary)
    }

    var actionsListener: SummaryActions? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SummaryViewHolder {
        val binding = ItemSegmentSummaryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SummaryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SummaryViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class SummaryViewHolder(
        private val binding: ItemSegmentSummaryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // Klik item biasa
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }

            // Long klik untuk menu konteks
            binding.root.setOnLongClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    showContextMenu(getItem(position))
                    true
                } else false
            }

            // Tombol aksi di card
            binding.btnExport.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    actionsListener?.onExportClicked(getItem(position))
                }
            }

            binding.btnEdit.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    actionsListener?.onEditClicked(getItem(position))
                }
            }

            binding.btnDelete.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    actionsListener?.onDeleteClicked(getItem(position))
                }
            }
        }

        fun bind(summary: RoadSegmentSummary) {
            with(binding) {
                // Nama jalan + jumlah segmen (jika lebih dari 1)
                tvRoadName.text = if (summary.segmentCount > 1) {
                    "${summary.roadName} (${summary.segmentCount} segmen)"
                } else {
                    summary.roadName
                }

                // Confidence badge
                setConfidenceBadge(summary.confidence)

                // Chip kondisi dan surface
                setConditionChip(summary.condition)
                setSurfaceChip(summary.surface)

                // Jarak (gunakan totalDistance dari summary, bukan per segment)
                tvDistance.text = formatDistance(summary.totalDistance)

                // Severity
                setSeverityBadge(summary.severity)

                // Kecepatan rata-rata
                setSpeedInfo(summary.avgSpeed)

                // Vibrasi rata-rata
                setVibrationInfo(summary.avgVibration)

                // Akurasi GPS rata-rata
                setGpsInfo(summary.avgAccuracy)

                // Timestamp
                setTimestamp(summary.timestamp)

                // Jumlah segmen (tampilkan jika >1)
                setSegmentCount(summary.segmentCount)

                // Sumber data (sensor / GPS)
                setDataSource(summary.dataSource)
            }
        }

        // ---------- HELPER FUNCTIONS ----------

        private fun ItemSegmentSummaryBinding.setConfidenceBadge(confidence: String) {
            val (bgColor, textColor, label) = when (confidence) {
                "HIGH" -> Triple(
                    R.color.confidence_high_bg,
                    R.color.confidence_high_text,
                    "TINGGI"
                )
                "MEDIUM" -> Triple(
                    R.color.confidence_medium_bg,
                    R.color.confidence_medium_text,
                    "SEDANG"
                )
                else -> Triple(
                    R.color.confidence_low_bg,
                    R.color.confidence_low_text,
                    "RENDAH"
                )
            }
            tvConfidence.text = label
            tvConfidence.setBackgroundColor(
                ContextCompat.getColor(itemView.context, bgColor)
            )
            tvConfidence.setTextColor(
                ContextCompat.getColor(itemView.context, textColor)
            )
        }

        private fun ItemSegmentSummaryBinding.setConditionChip(condition: String) {
            chipCondition.text = RoadCondition.fromCode(condition).displayName
            chipCondition.setChipBackgroundColorResource(
                when (condition) {
                    "GOOD" -> R.color.condition_good
                    "MODERATE" -> R.color.condition_moderate
                    "LIGHT_DAMAGE" -> R.color.condition_light_damage
                    else -> R.color.condition_heavy_damage
                }
            )
        }

        private fun ItemSegmentSummaryBinding.setSurfaceChip(surface: String) {
            chipSurface.text = SurfaceType.fromCode(surface).displayName
            chipSurface.setChipBackgroundColorResource(
                when (surface) {
                    "ASPHALT" -> R.color.surface_asphalt
                    "CONCRETE" -> R.color.surface_concrete
                    "GRAVEL" -> R.color.surface_gravel
                    "DIRT" -> R.color.surface_dirt
                    else -> R.color.surface_other
                }
            )
        }

        private fun formatDistance(meters: Float): String {
            return if (meters >= 1000) {
                String.format(Locale.getDefault(), "%.2f km", meters / 1000)
            } else {
                String.format(Locale.getDefault(), "%.0f m", meters)
            }
        }

        private fun ItemSegmentSummaryBinding.setSeverityBadge(severity: Int) {
            tvSeverity.text = "Tingkat Kerusakan: $severity/10"
            tvSeverity.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    when {
                        severity >= 8 -> R.color.severity_critical
                        severity >= 6 -> R.color.severity_high
                        severity >= 4 -> R.color.severity_medium
                        severity >= 2 -> R.color.severity_low
                        else -> R.color.severity_none
                    }
                )
            )
        }

        private fun ItemSegmentSummaryBinding.setSpeedInfo(speed: Float) {
            tvSpeed.text = String.format(Locale.getDefault(), "%.1f km/h", speed)
            tvSpeed.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    when {
                        speed > 30 -> R.color.speed_high
                        speed > 20 -> R.color.speed_medium
                        else -> R.color.speed_low
                    }
                )
            )
        }

        private fun ItemSegmentSummaryBinding.setVibrationInfo(vibration: Float) {
            val absVibration = abs(vibration)
            tvVibration.text = String.format(Locale.getDefault(), "%.2f g", absVibration)
            tvVibration.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    when {
                        absVibration > 2.0 -> R.color.vibration_spike
                        absVibration > 1.2 -> R.color.vibration_rough
                        absVibration > 0.5 -> R.color.vibration_moderate
                        else -> R.color.vibration_smooth
                    }
                )
            )
        }

        private fun ItemSegmentSummaryBinding.setGpsInfo(accuracy: Float) {
            val (text, colorRes) = when {
                accuracy < 5 -> Pair("Sangat Baik", R.color.gps_excellent)
                accuracy < 10 -> Pair("Baik", R.color.gps_good)
                accuracy < 20 -> Pair("Cukup", R.color.gps_fair)
                accuracy < 50 -> Pair("Buruk", R.color.gps_poor)
                else -> Pair("Sangat Buruk", R.color.gps_very_poor)
            }
            tvGpsInfo.text = "$text (${accuracy.toInt()}m)"
            tvGpsInfo.setTextColor(ContextCompat.getColor(itemView.context, colorRes))
        }

        private fun ItemSegmentSummaryBinding.setTimestamp(timestamp: Long) {
            val dateFormat = SimpleDateFormat("dd MMM, yyyy HH:mm", Locale.getDefault())
            tvTimestamp.text = dateFormat.format(Date(timestamp))
        }

        private fun ItemSegmentSummaryBinding.setSegmentCount(count: Int) {
            if (count > 1) {
                tvSegmentCount.visibility = View.VISIBLE
                tvSegmentCount.text = "$count segmen"
            } else {
                tvSegmentCount.visibility = View.GONE
            }
        }

        private fun ItemSegmentSummaryBinding.setDataSource(dataSource: String) {
            // Gunakan safe call karena mungkin view-nya optional
            tvDataSource?.let { source ->
                when (dataSource) {
                    "SENSOR_PRIMARY" -> {
                        tvDataWarning?.visibility = View.GONE
                        source.text = "Sumber: Sensor ESP32"
                    }
                    "GPS_ONLY" -> {
                        tvDataWarning?.visibility = View.VISIBLE
                        tvDataWarning?.text = "⚠️ Data hanya dari GPS"
                        source.text = "Sumber: GPS saja"
                    }
                    else -> {
                        source.text = "Sumber: $dataSource"
                    }
                }
            }
        }

        private fun showContextMenu(summary: RoadSegmentSummary) {
            AlertDialog.Builder(itemView.context)
                .setTitle(summary.roadName)
                .setItems(arrayOf("Ekspor", "Edit", "Hapus", "Lihat Detail")) { _, which ->
                    when (which) {
                        0 -> actionsListener?.onExportClicked(summary)
                        1 -> actionsListener?.onEditClicked(summary)
                        2 -> actionsListener?.onDeleteClicked(summary)
                        3 -> onItemClick(summary)
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<RoadSegmentSummary>() {
        override fun areItemsTheSame(oldItem: RoadSegmentSummary, newItem: RoadSegmentSummary): Boolean {
            // Gunakan ID segmen, bukan ID summary
            return oldItem.segment.id == newItem.segment.id
        }

        override fun areContentsTheSame(oldItem: RoadSegmentSummary, newItem: RoadSegmentSummary): Boolean {
            return oldItem == newItem
        }
    }
}