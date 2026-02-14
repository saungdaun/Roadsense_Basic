package zaujaani.roadsense.features.offlinemaps

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import zaujaani.roadsense.R
import zaujaani.roadsense.databinding.ItemOfflineMapBinding

/**
 * Adapter untuk menampilkan daftar file peta offline yang sudah diunduh.
 * Menggunakan ListAdapter untuk performa dan animasi otomatis.
 */
class OfflineMapAdapter(
    private val onDeleteClick: (OfflineMapFile) -> Unit,
    private val onFileClick: (OfflineMapFile) -> Unit
) : ListAdapter<OfflineMapFile, OfflineMapAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOfflineMapBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onDeleteClick, onFileClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemOfflineMapBinding,
        private val onDeleteClick: (OfflineMapFile) -> Unit,
        private val onFileClick: (OfflineMapFile) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(mapFile: OfflineMapFile) {
            binding.apply {
                // Ikon berdasarkan tipe file
                tvFileIcon.text = mapFile.fileTypeIcon

                // Nama file
                tvFileName.text = mapFile.name

                // Ukuran file dengan kode warna
                tvFileSize.text = mapFile.formattedSize
                tvFileSize.setTextColor(
                    when {
                        mapFile.sizeMB < 10 -> ContextCompat.getColor(
                            itemView.context,
                            android.R.color.holo_green_dark
                        )
                        mapFile.sizeMB < 50 -> ContextCompat.getColor(
                            itemView.context,
                            android.R.color.holo_orange_dark
                        )
                        else -> ContextCompat.getColor(
                            itemView.context,
                            android.R.color.holo_red_dark
                        )
                    }
                )

                // Tanggal modifikasi
                tvModified.text = mapFile.formattedDate

                // Label tipe file
                tvFileType.text = mapFile.type

                // Klik listener
                root.setOnClickListener { onFileClick(mapFile) }
                btnDelete.setOnClickListener { onDeleteClick(mapFile) }

                // Path file (disembunyikan, untuk keperluan dialog)
                tvFilePath.text = mapFile.path
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<OfflineMapFile>() {
            override fun areItemsTheSame(
                oldItem: OfflineMapFile,
                newItem: OfflineMapFile
            ): Boolean {
                return oldItem.path == newItem.path
            }

            override fun areContentsTheSame(
                oldItem: OfflineMapFile,
                newItem: OfflineMapFile
            ): Boolean {
                return oldItem == newItem
            }
        }
    }
}