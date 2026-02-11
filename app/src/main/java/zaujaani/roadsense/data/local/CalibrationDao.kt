package zaujaani.roadsense.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CalibrationDao {

    @Insert
    suspend fun insertCalibration(calibration: DeviceCalibration): Long

    @Update
    suspend fun updateCalibration(calibration: DeviceCalibration)

    @Query("SELECT * FROM device_calibration WHERE isActive = 1 ORDER BY lastUsed DESC LIMIT 1")
    suspend fun getActiveCalibration(): DeviceCalibration?

    @Query("SELECT * FROM device_calibration ORDER BY lastUsed DESC")
    fun getAllCalibrations(): Flow<List<DeviceCalibration>>

    @Query("UPDATE device_calibration SET isActive = 0 WHERE isActive = 1")
    suspend fun deactivateAllCalibrations()

    @Query("UPDATE device_calibration SET isActive = 1, lastUsed = :timestamp WHERE id = :calibrationId")
    suspend fun activateCalibration(calibrationId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM device_calibration WHERE id = :calibrationId")
    suspend fun deleteCalibration(calibrationId: Long)

    // Query untuk mendapatkan kalibrasi berdasarkan device
    @Query("SELECT * FROM device_calibration WHERE deviceName = :deviceName ORDER BY lastUsed DESC")
    fun getCalibrationsByDevice(deviceName: String): Flow<List<DeviceCalibration>>
}