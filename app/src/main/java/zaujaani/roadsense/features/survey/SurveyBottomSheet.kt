package zaujaani.roadsense.features.survey

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsense.R
import zaujaani.roadsense.databinding.BottomSheetSurveyBinding
import zaujaani.roadsense.domain.model.Confidence
import zaujaani.roadsense.domain.model.RoadCondition
import zaujaani.roadsense.domain.model.SurfaceType
import zaujaani.roadsense.features.map.MapViewModel
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class SurveyBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSurveyBinding? = null
    private val binding get() = _binding!!

    private val mapViewModel: MapViewModel by viewModels(
        ownerProducer = { requireParentFragment() }
    )

    private var selectedCondition: RoadCondition = RoadCondition.MODERATE
    private var selectedSurface: SurfaceType = SurfaceType.ASPHALT
    private var confidence: Confidence = Confidence.MEDIUM
    private var validationMessages: List<String> = emptyList()
    private var photoUris = mutableListOf<Uri>()

    // ✅ CALLBACK UNTUK SAVE
    private var onSaveCallback: ((String, String, String, String?) -> Unit)? = null

    fun setOnSaveListener(callback: (String, String, String, String?) -> Unit) {
        onSaveCallback = callback
    }

    // ========== PERMISSION LAUNCHERS ==========
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openCamera()
        } else {
            showMessage("Izin kamera diperlukan untuk mengambil foto")
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            updatePhotoPreview()
        }
    }

    // ========== LIFECYCLE ==========
    companion object {
        private const val ARG_CONFIDENCE = "confidence"
        private const val ARG_VALIDATION_MESSAGES = "validation_messages"
        private const val MAX_PHOTOS = 3

        fun newInstance(
            confidence: Confidence,
            messages: List<String>
        ): SurveyBottomSheet {
            return SurveyBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONFIDENCE, confidence.name)
                    putStringArrayList(ARG_VALIDATION_MESSAGES, ArrayList(messages))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            args.getString(ARG_CONFIDENCE)?.let {
                confidence = Confidence.valueOf(it)
            }
            args.getStringArrayList(ARG_VALIDATION_MESSAGES)?.let {
                validationMessages = it
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSurveyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupConfidenceDisplay()
        setupConditionSelection()
        setupSurfaceSelection()
        setupMediaButtons()
        setupActionButtons()

        binding.sliderSeverity.value = 5f
        binding.etRoadName.hint = "Nama Jalan/Ruas"
        binding.etNotes.hint = "Catatan tambahan (opsional)"
        binding.etRoadName.requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ========== UI SETUP ==========
    private fun setupConfidenceDisplay() {
        binding.tvConfidence.text = "Confidence: ${confidence.displayName}"
        binding.tvConfidence.setTextColor(
            when (confidence) {
                Confidence.HIGH -> ContextCompat.getColor(requireContext(), R.color.confidence_high)
                Confidence.MEDIUM -> ContextCompat.getColor(requireContext(), R.color.confidence_medium)
                Confidence.LOW -> ContextCompat.getColor(requireContext(), R.color.confidence_low)
            }
        )

        binding.tvConfidenceHelp.text = when (confidence) {
            Confidence.HIGH -> "✅ Data sangat akurat (GPS < 5m, Kecepatan ideal, Getaran valid)"
            Confidence.MEDIUM -> "⚠️ Data cukup akurat (1-2 parameter kurang optimal)"
            Confidence.LOW -> "❓ Data perlu verifikasi manual (GPS buruk atau kecepatan/getaran tidak normal)"
        }

        // ✅ FIXED: Removed unnecessary safe calls
        binding.tvValidationMessages.visibility = if (validationMessages.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvValidationMessages.text = validationMessages.joinToString("\n")

        binding.warningCard.visibility = if (confidence == Confidence.LOW) View.VISIBLE else View.GONE
        binding.tvWarning.text = "⚠️ Confidence rendah - Pastikan data sudah benar atau ulang perekaman"
    }

    private fun setupConditionSelection() {
        binding.chipModerate.isChecked = true
        binding.chipGood.setOnCheckedChangeListener { _, isChecked -> if (isChecked) selectedCondition = RoadCondition.GOOD }
        binding.chipModerate.setOnCheckedChangeListener { _, isChecked -> if (isChecked) selectedCondition = RoadCondition.MODERATE }
        binding.chipLightDamage.setOnCheckedChangeListener { _, isChecked -> if (isChecked) selectedCondition = RoadCondition.LIGHT_DAMAGE }
        binding.chipHeavyDamage.setOnCheckedChangeListener { _, isChecked -> if (isChecked) selectedCondition = RoadCondition.HEAVY_DAMAGE }
    }

    private fun setupSurfaceSelection() {
        binding.chipAsphalt.isChecked = true
        binding.chipAsphalt.setOnCheckedChangeListener { _, isChecked -> if (isChecked) selectedSurface = SurfaceType.ASPHALT }
        binding.chipConcrete.setOnCheckedChangeListener { _, isChecked -> if (isChecked) selectedSurface = SurfaceType.CONCRETE }
        binding.chipGravel.setOnCheckedChangeListener { _, isChecked -> if (isChecked) selectedSurface = SurfaceType.GRAVEL }
        binding.chipDirt.setOnCheckedChangeListener { _, isChecked -> if (isChecked) selectedSurface = SurfaceType.DIRT }
        binding.chipOther.setOnCheckedChangeListener { _, isChecked -> if (isChecked) selectedSurface = SurfaceType.OTHER }
    }

    private fun setupMediaButtons() {
        binding.btnTakePhoto.setOnClickListener { checkCameraPermission() }
        binding.btnRecordVoice.setOnClickListener { startVoiceRecording() }
        binding.btnAddTag.setOnClickListener { showTagDialog() } // ✅ FIXED: Removed safe call
    }

    private fun setupActionButtons() {
        binding.btnSave.setOnClickListener { saveSurvey() }
        binding.btnCancel.setOnClickListener { cancelSurvey() }
    }

    // ========== CAMERA ==========
    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                openCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                showMessage("Aplikasi memerlukan izin kamera untuk mendokumentasikan kondisi jalan")
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun openCamera() {
        if (photoUris.size >= MAX_PHOTOS) {
            showMessage("Maksimal $MAX_PHOTOS foto per segmen")
            return
        }
        val uri = createImageUri()
        uri?.let {
            photoUris.add(it)
            takePictureLauncher.launch(it)
        }
    }

    private fun createImageUri(): Uri? {
        val timestamp = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val filename = "roadsense_${dateFormat.format(Date(timestamp))}.jpg"
        return context?.contentResolver?.let { resolver ->
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)?.let { baseUri ->
                val contentValues = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RoadSense")
                }
                resolver.insert(baseUri, contentValues)
            }
        }
    }

    private fun updatePhotoPreview() {
        if (photoUris.isNotEmpty()) {
            binding.photoPreview.visibility = View.VISIBLE
            binding.tvPhotoCount.text = "${photoUris.size}/$MAX_PHOTOS foto tersimpan"
            if (photoUris.size >= MAX_PHOTOS) {
                binding.btnTakePhoto.isEnabled = false
                binding.btnTakePhoto.text = "Maksimal $MAX_PHOTOS foto"
            }
        } else {
            binding.photoPreview.visibility = View.GONE
        }
    }

    // ========== VOICE RECORDING ==========
    private fun startVoiceRecording() {
        showMessage("Fitur rekaman suara akan segera tersedia")
    }

    // ========== TAGS ==========
    private fun showTagDialog() {
        val tags = arrayOf(
            "Lubang", "Retak", "Bergelombang", "Licin",
            "Banjir", "Konstruksi", "Peringatan", "Berbahaya"
        )
        val selectedTags = mutableListOf<String>()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Pilih Tag Kondisi")
            .setMultiChoiceItems(tags, null) { _, which, isChecked ->
                if (isChecked) selectedTags.add(tags[which]) else selectedTags.remove(tags[which])
            }
            .setPositiveButton("Simpan") { _, _ ->
                binding.etTags.setText(selectedTags.joinToString(", "))
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ========== SAVE / CANCEL ==========
    private fun saveSurvey() {
        val roadName = binding.etRoadName.text.toString().trim()
        if (roadName.isEmpty()) {
            showMessage("Nama jalan tidak boleh kosong")
            binding.etRoadName.requestFocus()
            return
        }

        val notes = buildNotesString()

        binding.btnSave.isEnabled = false
        binding.btnSave.text = "Menyimpan..."

        lifecycleScope.launch {
            try {
                // ✅ GUNAKAN CALLBACK
                onSaveCallback?.invoke(
                    roadName,
                    selectedCondition.code,
                    selectedSurface.code,
                    notes
                )
                showMessage("✅ Segmen berhasil disimpan!")
                dismiss()
            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to save segment")
                showMessage("❌ Gagal menyimpan: ${e.message}")
                binding.btnSave.isEnabled = true
                binding.btnSave.text = "Simpan"
            }
        }
    }

    private fun buildNotesString(): String? {
        val notesList = mutableListOf<String>()
        binding.etNotes.text.toString().trim().takeIf { it.isNotEmpty() }?.let { notesList.add("Notes: $it") }
        binding.etTags.text.toString().trim().takeIf { it.isNotEmpty() }?.let { notesList.add("Tags: $it") }
        val severity = binding.sliderSeverity.value.toInt()
        notesList.add("Severity: $severity/10")
        if (photoUris.isNotEmpty()) notesList.add("Photos: ${photoUris.size}")
        notesList.add("Confidence: ${confidence.displayName}")
        return notesList.joinToString(" | ").takeIf { it.isNotEmpty() }
    }

    private fun cancelSurvey() {
        lifecycleScope.launch {
            mapViewModel.cancelSegmentCreation()
        }
        dismiss()
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }
}