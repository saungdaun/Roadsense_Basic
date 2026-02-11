package zaujaani.roadsense.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RoadSegmentDao {

    @Insert
    suspend fun insertRoadSegment(segment: RoadSegment): Long

    @Update
    suspend fun updateRoadSegment(segment: RoadSegment)

    @Delete
    suspend fun deleteRoadSegment(segment: RoadSegment)

    @Query("DELETE FROM road_segments WHERE id = :segmentId")
    suspend fun deleteRoadSegmentById(segmentId: Long)

    @Query("SELECT * FROM road_segments ORDER BY timestamp DESC")
    fun getAllRoadSegments(): Flow<List<RoadSegment>>

    @Query("SELECT * FROM road_segments WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    fun getRoadSegmentsBySession(sessionId: Long): Flow<List<RoadSegment>>

    @Query("SELECT * FROM road_segments WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    suspend fun getRoadSegmentsBySessionSync(sessionId: Long): List<RoadSegment>
}