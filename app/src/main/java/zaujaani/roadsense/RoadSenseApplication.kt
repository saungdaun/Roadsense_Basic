package zaujaani.roadsense

import android.app.Application
import timber.log.Timber

class RoadSenseApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.d("RoadSense Application created")
    }
}
