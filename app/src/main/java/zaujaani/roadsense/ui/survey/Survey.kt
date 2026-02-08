package zaujaani.roadsense.ui.survey

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.card.MaterialCardView
import zaujaani.roadsense.R
import zaujaani.roadsense.databinding.FragmentSurveyBinding

class SurveyFragment : Fragment() {

    private var _binding: FragmentSurveyBinding? = null
    private val binding get() = _binding!!

    // Track current selections
    private var selectedSurface: SurfaceType? = null
    private var selectedCondition: RoadCondition? = null

    // Track expanded states
    private var isSurfaceExpanded = true
    private var isConditionExpanded = true

    enum class SurfaceType(val displayName: String, val color: Int) {
        ASPAL("Aspal", R.color.asphalt_blue),
        BETON("Beton", R.color.concrete_gray),
        TANAH("Tanah", R.color.earth_brown)
    }

    enum class RoadCondition(val displayName: String, val color: Int) {
        GOOD("Good", R.color.good_green),
        FAIR("Fair", R.color.fair_yellow),
        LIGHT("Light Damage", R.color.light_damage_orange),
        HEAVY("Heavy Damage", R.color.heavy_damage_red)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSurveyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSurfaceSelection()
        setupConditionSelection()
        setupFAB()
    }

    private fun setupSurfaceSelection() {
        // Initially show all options
        binding.layoutSurfaceOptions.visibility = View.VISIBLE
        binding.layoutSurfaceSelected.visibility = View.GONE

        // Aspal button click
        binding.btnSurfaceAspal.setOnClickListener {
            selectSurface(SurfaceType.ASPAL)
        }

        // Beton button click
        binding.btnSurfaceBeton.setOnClickListener {
            selectSurface(SurfaceType.BETON)
        }

        // Tanah button click
        binding.btnSurfaceTanah.setOnClickListener {
            selectSurface(SurfaceType.TANAH)
        }

        // Click on selected surface to expand/collapse
        binding.layoutSurfaceSelected.setOnClickListener {
            toggleSurfaceExpansion()
        }

        // Click on card header to expand if collapsed
        binding.cardSurfaceType.setOnClickListener {
            if (!isSurfaceExpanded && selectedSurface != null) {
                toggleSurfaceExpansion()
            }
        }
    }

    private fun setupConditionSelection() {
        // Initially show all options
        binding.layoutConditionOptions.visibility = View.VISIBLE
        binding.layoutConditionSelected.visibility = View.GONE

        // Good button click
        binding.btnConditionGood.setOnClickListener {
            selectCondition(RoadCondition.GOOD)
        }

        // Fair button click
        binding.btnConditionFair.setOnClickListener {
            selectCondition(RoadCondition.FAIR)
        }

        // Light Damage button click
        binding.btnConditionLight.setOnClickListener {
            selectCondition(RoadCondition.LIGHT)
        }

        // Heavy Damage button click
        binding.btnConditionHeavy.setOnClickListener {
            selectCondition(RoadCondition.HEAVY)
        }

        // Click on selected condition to expand/collapse
        binding.layoutConditionSelected.setOnClickListener {
            toggleConditionExpansion()
        }

        // Click on card header to expand if collapsed
        binding.cardRoadCondition.setOnClickListener {
            if (!isConditionExpanded && selectedCondition != null) {
                toggleConditionExpansion()
            }
        }
    }

    private fun selectSurface(surface: SurfaceType) {
        selectedSurface = surface

        // Update selected display
        binding.textSurfaceName.text = surface.displayName
        binding.indicatorSurface.setBackgroundColor(
            ContextCompat.getColor(requireContext(), surface.color)
        )
        binding.textSurfaceSelected.text = surface.displayName

        // Collapse to show only selected
        isSurfaceExpanded = false
        animateTransition(binding.cardSurfaceType)
        binding.layoutSurfaceOptions.visibility = View.GONE
        binding.layoutSurfaceSelected.visibility = View.VISIBLE
    }

    private fun selectCondition(condition: RoadCondition) {
        selectedCondition = condition

        // Update selected display
        binding.textConditionName.text = condition.displayName
        binding.indicatorCondition.setBackgroundColor(
            ContextCompat.getColor(requireContext(), condition.color)
        )
        binding.textConditionSelected.text = condition.displayName

        // Collapse to show only selected
        isConditionExpanded = false
        animateTransition(binding.cardRoadCondition)
        binding.layoutConditionOptions.visibility = View.GONE
        binding.layoutConditionSelected.visibility = View.VISIBLE
    }

    private fun toggleSurfaceExpansion() {
        isSurfaceExpanded = !isSurfaceExpanded
        animateTransition(binding.cardSurfaceType)

        if (isSurfaceExpanded) {
            // Show all options
            binding.layoutSurfaceOptions.visibility = View.VISIBLE
            binding.layoutSurfaceSelected.visibility = View.GONE
            binding.textSurfaceSelected.text = "Select"
        } else {
            // Show only selected
            binding.layoutSurfaceOptions.visibility = View.GONE
            binding.layoutSurfaceSelected.visibility = View.VISIBLE
        }
    }

    private fun toggleConditionExpansion() {
        isConditionExpanded = !isConditionExpanded
        animateTransition(binding.cardRoadCondition)

        if (isConditionExpanded) {
            // Show all options
            binding.layoutConditionOptions.visibility = View.VISIBLE
            binding.layoutConditionSelected.visibility = View.GONE
            binding.textConditionSelected.text = "Select"
        } else {
            // Show only selected
            binding.layoutConditionOptions.visibility = View.GONE
            binding.layoutConditionSelected.visibility = View.VISIBLE
        }
    }

    private fun animateTransition(view: ViewGroup) {
        val transition = AutoTransition().apply {
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
        }
        TransitionManager.beginDelayedTransition(view, transition)
    }

    private fun setupFAB() {
        var isRunning = false

        binding.fabSurveyAction.setOnClickListener {
            isRunning = !isRunning

            if (isRunning) {
                // Start survey
                binding.fabSurveyAction.setImageResource(R.drawable.ic_stop_24)
                binding.textSurveyStatus.text = getString(R.string.status_running)
                binding.fabSurveyAction.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.heavy_damage_red)
            } else {
                // Stop survey
                binding.fabSurveyAction.setImageResource(R.drawable.ic_play_arrow_24)
                binding.textSurveyStatus.text = getString(R.string.status_ready)
                binding.fabSurveyAction.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.primary)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/*
 * ADDITIONAL ICONS NEEDED (create these in drawable folder):
 * - ic_stop_24.xml (for stopping survey)
 *
 * You may also want to add:
 * - Haptic feedback when selecting options
 * - Sound effects for confirmation
 * - Toast messages for user feedback
 * - Validation before starting survey (check if surface & condition selected)
 * - Save survey data to database
 * - Update distance and duration in real-time
 * - Handle Bluetooth and GPS connection status updates
 */