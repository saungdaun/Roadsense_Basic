package zaujaani.roadsense.features.summary

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsense.R
import zaujaani.roadsense.databinding.FragmentSummaryBinding
import zaujaani.roadsense.data.local.RoadSegmentSummary
import zaujaani.roadsense.domain.model.RoadCondition
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class SummaryFragment : Fragment(), SummaryAdapter.SummaryActions {

    private var _binding: FragmentSummaryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SummaryViewModel by viewModels()
    private lateinit var adapter: SummaryAdapter

    private val sessionId: Long by lazy {
        arguments?.getLong("sessionId", -1L) ?: -1L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSummaryBinding.inflate(inflater, container, false)
        setHasOptionsMenu(true) // 🔥 untuk sort menu
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
        setupClickListeners()
        observeViewModel()

        viewModel.loadSummary(sessionId)
    }

    // ========== TOOLBAR ==========
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        binding.toolbar.title = if (sessionId != -1L) {
            "Session #$sessionId"
        } else {
            "All Surveys"
        }
    }

    // ========== RECYCLERVIEW ==========
    private fun setupRecyclerView() {
        adapter = SummaryAdapter(
            onItemClick = { segment -> showSegmentDetail(segment) },
            onItemLongClick = { segment ->
                showSegmentActions(segment)
                true
            }
        ).apply { actionsListener = this@SummaryFragment }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@SummaryFragment.adapter
            setHasFixedSize(true)
        }
    }

    // ========== SWIPE REFRESH ==========
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh() // ✅ USED!
        }
    }

    // ========== CLICK LISTENERS ==========
    private fun setupClickListeners() {
        binding.btnFilter?.setOnClickListener { showFilterDialog() }
        binding.btnExportAll?.setOnClickListener { exportAllToCsv() } // ✅ USED!
        binding.btnStats?.setOnClickListener { showStatistics() }
    }

    // ========== OBSERVE VIEWMODEL (STATE FLOW) ==========
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                // 🔥 filteredData adalah StateFlow, bukan LiveData
                viewModel.filteredData.collectLatest { list ->
                    binding.swipeRefresh.isRefreshing = false
                    if (list.isNotEmpty()) {
                        adapter.submitList(list)
                        binding.emptyState?.visibility = View.GONE
                        binding.recyclerView.visibility = View.VISIBLE
                        updateStatistics(list)
                    } else {
                        binding.emptyState?.visibility = View.VISIBLE
                        binding.recyclerView.visibility = View.GONE
                        updateStatistics(emptyList())
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.isLoading.collectLatest { loading ->
                    // Jika ada progress bar, uncomment
                    // binding.progressBar?.isVisible = loading
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.error.collectLatest { errorMsg ->
                    // Jika ada TextView error, uncomment
                    // binding.tvError?.isVisible = errorMsg != null
                    // binding.tvError?.text = errorMsg ?: ""
                    if (errorMsg != null) {
                        Timber.e("Summary error: $errorMsg")
                    }
                }
            }
        }
    }

    // ========== STATISTIK ==========
    private fun updateStatistics(list: List<RoadSegmentSummary>) {
        val stats = viewModel.getStatistics() // ✅ USED!
        binding.tvTotalDistance?.text = String.format(
            Locale.getDefault(),
            "%.2f km",
            stats.totalDistance / 1000
        )
        binding.tvSegmentCount?.text = "${stats.totalSegments} Segmen"

        val conditionText = stats.conditionDistribution.entries.joinToString(" • ") { (cond, dist) ->
            "${RoadCondition.fromCode(cond).displayName}: ${String.format(Locale.getDefault(), "%.2f km", dist / 1000)}"
        }
        binding.tvConditionStats?.text = conditionText

        val confidenceText = list.groupBy { it.confidence }.entries.joinToString(" • ") { (conf, items) ->
            val label = when (conf) {
                "HIGH" -> "Tinggi"
                "MEDIUM" -> "Sedang"
                else -> "Rendah"
            }
            "$label: ${items.size}"
        }
        binding.tvConfidenceStats?.text = confidenceText
    }

    // ========== FILTER DIALOG ==========
    private fun showFilterDialog() {
        val options = viewModel.getFilterOptions() // ✅ USED!

        val conditions = options.conditions.toMutableList().apply { add(0, "Semua") }
        val surfaces = options.surfaces.toMutableList().apply { add(0, "Semua") }
        val confidences = options.confidences.toMutableList().apply { add(0, "Semua") }

        // Pilih kondisi
        showSingleChoiceDialog(
            title = "Pilih Kondisi",
            items = conditions
        ) { selectedCondition ->
            val condition = selectedCondition.takeIf { it != "Semua" }
            // Pilih surface
            showSingleChoiceDialog(
                title = "Pilih Surface",
                items = surfaces
            ) { selectedSurface ->
                val surface = selectedSurface.takeIf { it != "Semua" }
                // Pilih confidence
                showSingleChoiceDialog(
                    title = "Pilih Confidence",
                    items = confidences
                ) { selectedConfidence ->
                    val confidence = selectedConfidence.takeIf { it != "Semua" }
                    // ✅ TERAPKAN FILTER
                    viewModel.setFilterCondition(condition)
                    viewModel.setFilterSurface(surface)
                    viewModel.setFilterConfidence(confidence)
                }
            }
        }
    }

    private fun showSingleChoiceDialog(
        title: String,
        items: List<String>,
        onSelected: (String) -> Unit
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which ->
                onSelected(items[which])
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ========== SORT DIALOG (VIA MENU) ==========
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_summary_sort, menu) // 🔥 buat menu file
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort_date_desc -> {
                viewModel.setSortBy(SummaryViewModel.SortBy.DATE_DESC)
                true
            }
            R.id.action_sort_date_asc -> {
                viewModel.setSortBy(SummaryViewModel.SortBy.DATE_ASC)
                true
            }
            R.id.action_sort_distance_desc -> {
                viewModel.setSortBy(SummaryViewModel.SortBy.DISTANCE_DESC)
                true
            }
            R.id.action_sort_distance_asc -> {
                viewModel.setSortBy(SummaryViewModel.SortBy.DISTANCE_ASC)
                true
            }
            R.id.action_sort_severity_desc -> {
                viewModel.setSortBy(SummaryViewModel.SortBy.SEVERITY_DESC)
                true
            }
            R.id.action_sort_severity_asc -> {
                viewModel.setSortBy(SummaryViewModel.SortBy.SEVERITY_ASC)
                true
            }
            R.id.action_sort_speed_desc -> {
                viewModel.setSortBy(SummaryViewModel.SortBy.SPEED_DESC)
                true
            }
            R.id.action_sort_speed_asc -> {
                viewModel.setSortBy(SummaryViewModel.SortBy.SPEED_ASC)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ========== EXPORT CSV ==========
    private fun exportAllToCsv() {
        lifecycleScope.launch {
            val csvData = viewModel.exportToCsv() // ✅ USED!
            if (csvData.isBlank()) {
                showToast("Tidak ada data untuk diekspor")
                return@launch
            }
            saveAndShareCsv(csvData)
        }
    }

    private fun saveAndShareCsv(csvData: String) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "roadsense_export_$timeStamp.csv"
            val file = File(requireContext().getExternalFilesDir(null), fileName)
            FileWriter(file).use { writer ->
                writer.write(csvData)
            }

            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Ekspor CSV"))
            showToast("File CSV berhasil dibuat")
        } catch (e: Exception) {
            Timber.e(e, "Export CSV gagal")
            showToast("Gagal mengekspor CSV")
        }
    }

    // ========== STATISTICS DIALOG ==========
    private fun showStatistics() {
        val stats = viewModel.getStatistics() // ✅ USED!
        val message = buildString {
            appendLine("📊 Statistik Survey")
            appendLine()
            appendLine("Total Segmen: ${stats.totalSegments}")
            appendLine("Total Jarak: ${String.format(Locale.getDefault(), "%.2f km", stats.totalDistance / 1000)}")
            appendLine("Rata-rata Kecepatan: ${String.format(Locale.getDefault(), "%.1f km/h", stats.avgSpeed)}")
            appendLine("Rata-rata Kerusakan: ${String.format(Locale.getDefault(), "%.1f/10", stats.avgSeverity)}")
            appendLine()
            appendLine("Berdasarkan Kondisi:")
            stats.conditionDistribution.forEach { (cond, dist) ->
                appendLine("  • ${RoadCondition.fromCode(cond).displayName}: ${String.format(Locale.getDefault(), "%.2f km", dist / 1000)}")
            }
            appendLine()
            appendLine("Berdasarkan Surface:")
            stats.surfaceDistribution.forEach { (surf, dist) ->
                val surfaceName = when (surf) {
                    "ASPHALT" -> "Aspal"
                    "CONCRETE" -> "Beton"
                    "GRAVEL" -> "Kerikil"
                    "DIRT" -> "Tanah"
                    else -> "Lainnya"
                }
                appendLine("  • $surfaceName: ${String.format(Locale.getDefault(), "%.2f km", dist / 1000)}")
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Statistik")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ========== SUMMARY ACTIONS (dari adapter) ==========
    override fun onExportClicked(summary: RoadSegmentSummary) {
        showToast("Export single segment belum tersedia")
    }

    override fun onEditClicked(summary: RoadSegmentSummary) {
        showToast("Edit segmen belum tersedia")
    }

    override fun onDeleteClicked(summary: RoadSegmentSummary) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Hapus Segmen")
            .setMessage("Yakin ingin menghapus ${summary.roadName}?")
            .setPositiveButton("Hapus") { _, _ ->
                lifecycleScope.launch {
                    // TODO: panggil repository.deleteSegment(summary.segment.id)
                    showToast("Segmen dihapus")
                    viewModel.refresh()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showSegmentDetail(summary: RoadSegmentSummary) {
        showToast("Detail: ${summary.roadName}")
    }

    private fun showSegmentActions(summary: RoadSegmentSummary) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(summary.roadName)
            .setItems(arrayOf("Lihat di Peta", "Ekspor", "Edit", "Hapus")) { _, which ->
                when (which) {
                    0 -> viewOnMap(summary)
                    1 -> onExportClicked(summary)
                    2 -> onEditClicked(summary)
                    3 -> onDeleteClicked(summary)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun viewOnMap(summary: RoadSegmentSummary) {
        findNavController().navigateUp()
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}