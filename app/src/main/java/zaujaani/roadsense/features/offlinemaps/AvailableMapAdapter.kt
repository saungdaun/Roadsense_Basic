package zaujaani.roadsense.features.offlinemaps

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import zaujaani.roadsense.databinding.ItemAvailableMapBinding

class AvailableMapAdapter(
    private val onDownloadClick: (AvailableMap) -> Unit,
    private val onItemClick: (AvailableMap) -> Unit
) : ListAdapter<AvailableMap, AvailableMapAdapter.ViewHolder>(DiffCallback) {

    private var downloadProgressMap: Map<String, DownloadProgress> = emptyMap()

    fun setDownloadProgress(progressMap: Map<String, DownloadProgress>) {
        val oldMap = downloadProgressMap
        downloadProgressMap = progressMap
        // Notify item yang berubah
        val changedIds = (oldMap.keys + progressMap.keys).filter {
            oldMap[it] != progressMap[it]
        }
        changedIds.forEach { id ->
            val position = currentList.indexOfFirst { it.id == id }
            if (position != -1) {
                notifyItemChanged(position)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAvailableMapBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onDownloadClick, onItemClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val progress = downloadProgressMap[item.id]
        holder.bind(item, progress)
    }

    class ViewHolder(
        private val binding: ItemAvailableMapBinding,
        private val onDownloadClick: (AvailableMap) -> Unit,
        private val onItemClick: (AvailableMap) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(map: AvailableMap, progress: DownloadProgress?) {
            binding.apply {
                tvRegionName.text = map.name//<<--Unresolved reference 'tvRegionName'.
                tvRegionDescription.text = map.description//<<--Unresolved reference 'tvRegionDescription'.
                tvRegionSize.text = "Ukuran: ${map.formattedSize}"//<<--Unresolved reference 'tvRegionSize'.

                if (map.isDownloaded) {
                    btnDownload.text = "Terpasang"//<<--Unresolved reference 'btnDownload'.
                    btnDownload.isEnabled = false//<<--Unresolved reference 'btnDownload'.
                    progressBar.isVisible = false//<<--Unresolved reference 'progressBar'.
                    tvProgress.text = ""//<<--Unresolved reference 'tvProgress'.
                } else if (progress != null) {
                    btnDownload.text = "Mendownload"
                    btnDownload.isEnabled = false
                    progressBar.isVisible = true
                    progressBar.progress = progress.progress
                    tvProgress.text = "${progress.progress}%"
                } else {
                    btnDownload.text = "Download"
                    btnDownload.isEnabled = true
                    progressBar.isVisible = false
                    tvProgress.text = ""
                }

                btnDownload.setOnClickListener {
                    if (!map.isDownloaded && progress == null) {
                        onDownloadClick(map)
                    }
                }

                root.setOnClickListener {
                    onItemClick(map)
                }
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<AvailableMap>() {
            override fun areItemsTheSame(oldItem: AvailableMap, newItem: AvailableMap): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: AvailableMap, newItem: AvailableMap): Boolean {
                return oldItem == newItem
            }
        }
    }
}