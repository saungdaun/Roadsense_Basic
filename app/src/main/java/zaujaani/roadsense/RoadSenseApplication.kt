package zaujaani.roadsense

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * RoadSense Application Class
 *
 * Initialized dengan:
 * - Hilt Dependency Injection
 * - Timber logging
 */
@HiltAndroidApp
class RoadSenseApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.d("🚀 RoadSense Application initialized")
    }
}