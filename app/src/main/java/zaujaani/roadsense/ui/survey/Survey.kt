package zaujaani.roadsense.ui.survey

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import zaujaani.roadsense.R

class Survey : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_survey, container, false)
    }

    companion object {
        /**
         * Factory method untuk membuat instance fragment
         */
        @JvmStatic
        fun newInstance() = Survey()
    }
}