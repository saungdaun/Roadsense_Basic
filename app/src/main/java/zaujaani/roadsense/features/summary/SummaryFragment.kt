package zaujaani.roadsense.features.summary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import zaujaani.roadsense.databinding.FragmentSummaryBinding
import zaujaani.roadsense.features.summary.adapter.SummaryAdapter
import zaujaani.roadsense.data.local.RoadSegmentSummary   // ✅ WAJIB
import zaujaani.roadsense.domain.model.RoadCondition      // ✅ WAJIB

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
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        loadData()
        setupSwipeRefresh()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        binding.toolbar.title = if (sessionId != -1L) {
            "Session #$sessionId Summary"
        } else {
            "All Surveys Summary"
        }
    }

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

    private fun setupSwipeRefresh() {
        binding.swipeRefresh?.setOnRefreshListener { loadData() }
    }

    private fun setupClickListeners() {
        binding.btnExportAll?.setOnClickListener { exportAllToPDF() }
        binding.btnFilter?.setOnClickListener { showFilterDialog() }
        binding.btnStats?.setOnClickListener { showStatistics() }
    }

    private fun loadData() {
        binding.swipeRefresh?.isRefreshing = true

        viewModel.loadSummary(sessionId)

        lifecycleScope.launch {
            viewModel.summaryData.collect { summaryList ->
                binding.swipeRefresh?.isRefreshing = false

                if (summaryList.isNotEmpty()) {
                    adapter.submitList(summaryList)
                    binding.emptyState?.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    updateTotals(summaryList)
                } else {
                    binding.emptyState?.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                }
            }
        }
    }

    private fun updateTotals(summaryList: List<RoadSegmentSummary>) {
        // ✅ GUNAKAN fold UNTUK MENGHINDARI AMBIGUITY sumOf
        val totalDistance = summaryList.fold(0f) { acc, item -> acc + item.totalDistance }
        binding.tvTotalDistance?.text = String.format("%.2f km", totalDistance / 1000)

        val totalSegments = summaryList.fold(0) { acc, item -> acc + item.segmentCount }
        binding.tvSegmentCount?.text = "$totalSegments Segmen"

        val conditionStats = calculateConditionStats(summaryList)
        binding.tvConditionStats?.text = conditionStats

        val confidenceStats = calculateConfidenceStats(summaryList)
        binding.tvConfidenceStats?.text = confidenceStats
    }

    private fun calculateConditionStats(summaryList: List<RoadSegmentSummary>): String {
        val conditionGroups = summaryList.groupBy { it.condition }
        return conditionGroups.map { (condition, items) ->
            val totalDistance = items.fold(0f) { acc, item -> acc + item.totalDistance }
            "${RoadCondition.fromCode(condition).displayName}: ${String.format("%.2f km", totalDistance / 1000)}"
        }.joinToString(" • ")
    }

    private fun calculateConfidenceStats(summaryList: List<RoadSegmentSummary>): String {
        val confidenceGroups = summaryList.groupBy { it.confidence }
        return confidenceGroups.map { (confidence, items) ->
            val text = when (confidence) {
                "HIGH" -> "Tinggi"
                "MEDIUM" -> "Sedang"
                else -> "Rendah"
            }
            "$text: ${items.size}"
        }.joinToString(" • ")
    }

    // ========== SummaryActions Implementation ==========
    override fun onExportClicked(summary: RoadSegmentSummary) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Export Segment")
            .setMessage("Export data for ${summary.roadName}?")
            .setPositiveButton("Export") { _, _ -> exportSingleSegment(summary) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onEditClicked(summary: RoadSegmentSummary) {
        showToast("Edit feature coming soon")
    }

    override fun onDeleteClicked(summary: RoadSegmentSummary) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Segment")
            .setMessage("Are you sure you want to delete ${summary.roadName}?")
            .setPositiveButton("Delete") { _, _ -> deleteSegment(summary) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportSingleSegment(summary: RoadSegmentSummary) {
        showToast("Exporting ${summary.roadName}...")
    }

    private fun exportAllToPDF() {
        lifecycleScope.launch {
            val summaries = viewModel.summaryData.value
            if (summaries.isNotEmpty()) {
                showToast("Exporting ${summaries.size} segments to PDF...")
            } else {
                showToast("No data to export")
            }
        }
    }

    private fun deleteSegment(summary: RoadSegmentSummary) {
        lifecycleScope.launch {
            showToast("Deleting ${summary.roadName}...")
            loadData()
        }
    }

    private fun showSegmentDetail(summary: RoadSegmentSummary) {
        showToast("Showing details for ${summary.roadName}")
    }

    private fun showSegmentActions(summary: RoadSegmentSummary) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(summary.roadName)
            .setItems(arrayOf("View on Map", "Export Data", "Edit Details", "Delete")) { _, which ->
                when (which) {
                    0 -> viewOnMap(summary)
                    1 -> onExportClicked(summary)
                    2 -> onEditClicked(summary)
                    3 -> onDeleteClicked(summary)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun viewOnMap(summary: RoadSegmentSummary) {
        findNavController().navigateUp()
    }

    private fun showFilterDialog() {
        val conditions = arrayOf("All", "Good", "Moderate", "Light Damage", "Heavy Damage")
        val surfaces = arrayOf("All", "Asphalt", "Concrete", "Gravel", "Dirt", "Other")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Filter Segments")
            .setMultiChoiceItems(conditions, null) { _, _, _ -> }
            .setMultiChoiceItems(surfaces, null) { _, _, _ -> }
            .setPositiveButton("Apply", null)
            .setNegativeButton("Reset", null)
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun showStatistics() {
        lifecycleScope.launch {
            val summaries = viewModel.summaryData.value
            if (summaries.isNotEmpty()) {
                val stats = buildString {
                    appendLine("📊 Survey Statistics")
                    appendLine()
                    appendLine("Total Segments: ${summaries.size}")
                    appendLine("Total Distance: ${String.format("%.2f km", summaries.fold(0f) { acc, s -> acc + s.totalDistance } / 1000)}")
                    appendLine()
                    appendLine("By Condition:")
                    summaries.groupBy { it.condition }.forEach { (condition, items) ->
                        val distance = items.fold(0f) { acc, s -> acc + s.totalDistance }
                        appendLine("  ${RoadCondition.fromCode(condition).displayName}: ${String.format("%.2f km", distance / 1000)}")
                    }
                    appendLine()
                    appendLine("By Confidence:")
                    summaries.groupBy { it.confidence }.forEach { (confidence, items) ->
                        val text = when (confidence) {
                            "HIGH" -> "Tinggi"
                            "MEDIUM" -> "Sedang"
                            else -> "Rendah"
                        }
                        appendLine("  $text: ${items.size} segments")
                    }
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Statistics")
                    .setMessage(stats)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}