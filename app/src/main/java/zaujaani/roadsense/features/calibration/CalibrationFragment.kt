package zaujaani.roadsense.features.calibration

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsense.databinding.FragmentCalibrationBinding

@AndroidEntryPoint
class CalibrationFragment : Fragment() {

    private var _binding: FragmentCalibrationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CalibrationViewModel by viewModels()

    // TextWatcher disimpan sebagai field agar bisa di-remove
    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = updateSummary()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCalibrationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupInputListeners()
        setupSaveButton()
        observeViewModel()

        // Muat kalibrasi aktif
        viewModel.loadCalibration()
    }

    override fun onDestroyView() {
        // Hindari memory leak
        binding.etDeviceName.removeTextChangedListener(textWatcher)
        binding.etDiameter.removeTextChangedListener(textWatcher)
        binding.etPulsesPerRotation.removeTextChangedListener(textWatcher)
        super.onDestroyView()
        _binding = null
    }

    // ========== SETUP UI ==========
    private fun setupInputListeners() {
        binding.etDeviceName.addTextChangedListener(textWatcher)
        binding.etDiameter.addTextChangedListener(textWatcher)
        binding.etPulsesPerRotation.addTextChangedListener(textWatcher)

        binding.radioGroupUnit.setOnCheckedChangeListener { _, _ ->
            updateSummary()
        }
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener { saveCalibration() }
    }

    // ========== OBSERVE STATE FLOW ==========
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.calibrationState.collect { state ->
                    when (state) {
                        is CalibrationViewModel.CalibrationState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.btnSave.isEnabled = false
                        }
                        is CalibrationViewModel.CalibrationState.Loaded -> {
                            binding.progressBar.visibility = View.GONE
                            state.calibration?.let { calibration ->
                                populateFields(calibration)
                            }
                        }
                        is CalibrationViewModel.CalibrationState.Saved -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnSave.isEnabled = true
                            Snackbar.make(binding.root, "✅ Kalibrasi berhasil disimpan", Snackbar.LENGTH_SHORT).show()
                        }
                        is CalibrationViewModel.CalibrationState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnSave.isEnabled = true
                            Snackbar.make(binding.root, "❌ ${state.message}", Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    // ========== POPULATE FORM ==========
    private fun populateFields(calibration: zaujaani.roadsense.data.local.DeviceCalibration) {
        binding.etDeviceName.setText(calibration.deviceName)
        binding.etDiameter.setText(calibration.wheelDiameter.toString())
        // Default ke cm jika unit tidak dikenal
        when (calibration.wheelDiameterUnit) {
            "cm" -> binding.radioCm.isChecked = true
            "mm" -> binding.radioMm.isChecked = true
            "m"  -> binding.radioMeter.isChecked = true
            else -> binding.radioCm.isChecked = true
        }
        binding.etPulsesPerRotation.setText(calibration.pulsesPerRotation.toString())
        binding.etVehicleType.setText(calibration.vehicleType ?: "")
        binding.etTirePressure.setText(calibration.tirePressure?.toString() ?: "")
        binding.etLoadWeight.setText(calibration.loadWeight?.toString() ?: "")
        binding.etNotes.setText(calibration.notes ?: "")
    }

    // ========== UPDATE SUMMARY ==========
    private fun updateSummary() {
        val diameterStr = binding.etDiameter.text.toString()
        val pulsesStr = binding.etPulsesPerRotation.text.toString()

        if (diameterStr.isEmpty() || pulsesStr.isEmpty()) {
            binding.tvSummary.text = "📝 Masukkan diameter dan pulsa per putaran"
            return
        }

        val diameter = diameterStr.toFloatOrNull()
        val pulses = pulsesStr.toIntOrNull()

        if (diameter == null || pulses == null || diameter <= 0 || pulses <= 0) {
            binding.tvSummary.text = "⚠️ Diameter dan pulsa harus angka positif"
            return
        }

        // Unit default jika tidak ada yang dipilih
        val selectedRadioId = binding.radioGroupUnit.checkedRadioButtonId
        val unit = when (selectedRadioId) {
            binding.radioCm.id -> "cm"
            binding.radioMm.id -> "mm"
            binding.radioMeter.id -> "m"
            else -> "cm"
        }
        // Pastikan ada radio yang terpilih
        if (selectedRadioId == -1) {
            binding.radioCm.isChecked = true
        }

        val distancePerPulse = viewModel.calculateDistancePerPulse(diameter, unit, pulses)
        val summary = viewModel.getCalibrationSummary(diameter, unit, pulses)

        binding.tvSummary.text = """
            📏 Keliling roda: ${summary["circumference_cm"]}
            ⚙️ Jarak per pulsa: ${summary["distance_per_pulse_mm"]}
            🔄 Jarak per 1000 pulsa: ${summary["distance_per_1000_pulses_m"]}
            📊 Presisi: ${String.format("%.4f", distancePerPulse)} m/pulsa
        """.trimIndent()
    }

    // ========== SAVE CALIBRATION ==========
    private fun saveCalibration() {
        val deviceName = binding.etDeviceName.text.toString().trim()
        if (deviceName.isEmpty()) {
            Snackbar.make(binding.root, "Nama device tidak boleh kosong", Snackbar.LENGTH_SHORT).show()
            return
        }

        val diameterStr = binding.etDiameter.text.toString()
        val pulsesStr = binding.etPulsesPerRotation.text.toString()

        if (diameterStr.isEmpty() || pulsesStr.isEmpty()) {
            Snackbar.make(binding.root, "Diameter dan pulsa per putaran wajib diisi", Snackbar.LENGTH_SHORT).show()
            return
        }

        val diameter = diameterStr.toFloatOrNull()
        val pulses = pulsesStr.toIntOrNull()

        if (diameter == null || pulses == null || diameter <= 0 || pulses <= 0) {
            Snackbar.make(binding.root, "Diameter dan pulsa harus angka positif", Snackbar.LENGTH_SHORT).show()
            return
        }

        val selectedRadioId = binding.radioGroupUnit.checkedRadioButtonId
        val unit = when (selectedRadioId) {
            binding.radioCm.id -> "cm"
            binding.radioMm.id -> "mm"
            binding.radioMeter.id -> "m"
            else -> "cm"
        }

        val vehicleType = binding.etVehicleType.text.toString().takeIf { it.isNotBlank() }
        val tirePressure = binding.etTirePressure.text.toString().toFloatOrNull()
        val loadWeight = binding.etLoadWeight.text.toString().toFloatOrNull()
        val notes = binding.etNotes.text.toString().takeIf { it.isNotBlank() }

        viewModel.saveCalibration(
            deviceName = deviceName,
            wheelDiameter = diameter,
            wheelDiameterUnit = unit,
            pulsesPerRotation = pulses,
            vehicleType = vehicleType,
            tirePressure = tirePressure,
            loadWeight = loadWeight,
            notes = notes
        )
    }
}