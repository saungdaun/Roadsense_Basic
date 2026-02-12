package zaujaani.roadsense.features.map

import android.content.Context
import android.view.View
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import zaujaani.roadsense.R
import zaujaani.roadsense.domain.model.Confidence
import zaujaani.roadsense.features.map.MapViewModel
import zaujaani.roadsense.features.survey.SurveyBottomSheet

class SurveyController(
    private val context: Context,
    private val viewModel: MapViewModel,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val snackbarView: View,
    private val fragmentManager: FragmentManager
) {
    fun startSurvey() {
        lifecycleScope.launch {
            val result = viewModel.startSurvey()
            when (result) {
                is MapViewModel.SurveyStartResult.SUCCESS -> {
                    Snackbar.make(snackbarView, context.getString(R.string.survey_started), Snackbar.LENGTH_SHORT).show()
                }
                is MapViewModel.SurveyStartResult.ERROR -> {
                    Snackbar.make(snackbarView, result.message, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    fun stopSurveyWithConfirmation() {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.stop_survey_title))
            .setMessage(context.getString(R.string.stop_survey_message))
            .setPositiveButton(context.getString(R.string.stop_and_save)) { _, _ ->
                lifecycleScope.launch {
                    viewModel.stopSurvey()
                    Snackbar.make(snackbarView, context.getString(R.string.survey_stopped), Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }

    fun pauseResumeSurvey() {
        if (viewModel.uiState.value.isPaused) {
            viewModel.resumeSurvey()
        } else {
            viewModel.pauseSurvey()
        }
    }

    fun startSegmentCreation(): Boolean {
        val success = viewModel.startSegmentCreation()
        if (success) {
            Snackbar.make(snackbarView, context.getString(R.string.msg_start_segment), Snackbar.LENGTH_LONG).show()
        } else {
            MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.cannot_create_segment))
                .setMessage(context.getString(R.string.survey_required_for_segment))
                .setPositiveButton(context.getString(R.string.ok), null)
                .show()
        }
        return success
    }

    fun handleMapTapForSegment(geoPoint: GeoPoint) {
        if (!viewModel.uiState.value.isCreatingSegment) return

        if (viewModel.segmentCreationPoints.value.isEmpty()) {
            viewModel.setSegmentStartPoint(geoPoint)
            Snackbar.make(snackbarView, context.getString(R.string.msg_segment_start_set), Snackbar.LENGTH_SHORT).show()
        } else {
            viewModel.setSegmentEndPoint(geoPoint)
            val validation = viewModel.completeSegmentCreation()

            if (validation.isValid) {
                showSurveyBottomSheet(
                    confidence = validation.confidence,
                    messages = validation.messages
                )
            } else {
                MaterialAlertDialogBuilder(context)
                    .setTitle(context.getString(R.string.z_axis_validation_failed))
                    .setMessage(validation.messages.joinToString("\n"))
                    .setPositiveButton(context.getString(R.string.ok)) { _, _ ->
                        viewModel.cancelSegmentCreation()
                    }
                    .show()
            }
        }
    }

    private fun showSurveyBottomSheet(confidence: Confidence, messages: List<String>) {
        val bottomSheet = SurveyBottomSheet.newInstance(confidence, messages)
        bottomSheet.setOnSaveListener { roadName, condition, surface, notes ->
            lifecycleScope.launch {
                viewModel.saveSegment(roadName, condition, surface, notes)
            }
        }
        bottomSheet.show(fragmentManager, "SurveyBottomSheet")
    }

    fun cancelSegmentCreation() {
        viewModel.cancelSegmentCreation()
    }

    fun centerMapOnCurrentLocation(): Boolean {
        val location = viewModel.uiState.value.currentLocation
        return if (location != null) {
            true
        } else {
            Snackbar.make(snackbarView, context.getString(R.string.gps_unavailable_short), Snackbar.LENGTH_SHORT).show()
            false
        }
    }
}