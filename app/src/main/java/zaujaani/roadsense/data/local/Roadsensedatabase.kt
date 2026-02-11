package zaujaani.roadsense.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SurveyEntity::class,
        TelemetryEntity::class,
        DeviceCalibration::class,
        SurveySession::class,
        RawTracking::class,
        RoadSegment::class,
        TelemetryRaw::class
    ],
    version = 2,
    exportSchema = false
)
abstract class RoadSenseDatabase : RoomDatabase() {

    abstract fun surveyDao(): SurveyDao
    abstract fun calibrationDao(): CalibrationDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun sessionDao(): SessionDao           // ✅ BARU
    abstract fun roadSegmentDao(): RoadSegmentDao   // ✅ BARU

    companion object {
        @Volatile
        private var INSTANCE: RoadSenseDatabase? = null

        fun getInstance(context: Context): RoadSenseDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RoadSenseDatabase::class.java,
                    "roadsense_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}