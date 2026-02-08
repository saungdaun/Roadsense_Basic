package zaujaani.roadsense.ui.help

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import zaujaani.roadsense.R
import zaujaani.roadsense.databinding.FragmentHelpBinding

class HelpFragment : Fragment() {

    private var _binding: FragmentHelpBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHelpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup toolbar jika ada di layout
        setupToolbar()

        // Setup UI jika ada elemen yang perlu diinisialisasi
        setupUI()
    }

    private fun setupToolbar() {
        // Jika ada toolbar, atur title
        // Note: Toolbar sudah dihandle oleh MainActivity dengan Navigation Drawer
    }

    private fun setupUI() {
        // Jika ada data yang perlu dimuat atau diinisialisasi, bisa dilakukan di sini
        // Contoh: load versi dari BuildConfig
        // binding.tvVersion.text = "Version ${BuildConfig.VERSION_NAME}"

        // Atau jika ingin menampilkan data dinamis
        // binding.tvAppName.text = getString(R.string.app_name)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}