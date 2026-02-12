package zaujaani.roadsense.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DeviceCalibration::class,
        SurveySession::class,
        RawTracking::class,      // ✅ Masih dipakai? Cek nanti, amankan dulu
        RoadSegment::class,
        TelemetryRaw::class      // ✅ Raw telemetry untuk audit trail
    ],
    version = 3,                // ⚠️ Naikkan versi karena hapus tabel lama
    exportSchema = false
)
abstract class RoadSenseDatabase : RoomDatabase() {

    // ========== DAO BARU (HANYA YANG DIPAKAI) ==========
    abstract fun calibrationDao(): CalibrationDao
    abstract fun sessionDao(): SessionDao
    abstract fun roadSegmentDao(): RoadSegmentDao
    abstract fun telemetryDao(): TelemetryDao   // ✅ untuk TelemetryRaw

    // ❌ HAPUS semua DAO legacy
    // abstract fun surveyDao(): SurveyDao
    // abstract fun telemetryEntityDao(): TelemetryDao (untuk TelemetryEntity)

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
                    .fallbackToDestructiveMigration() // untuk development
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}