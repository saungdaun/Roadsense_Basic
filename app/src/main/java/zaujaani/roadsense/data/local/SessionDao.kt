package zaujaani.roadsense.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insertSurveySession(session: SurveySession): Long

    @Update
    suspend fun updateSurveySession(session: SurveySession)

    @Query("SELECT * FROM survey_sessions WHERE id = :sessionId")
    suspend fun getSurveySessionById(sessionId: Long): SurveySession?

    @Query("SELECT * FROM survey_sessions ORDER BY startTime DESC")
    fun getAllSurveySessions(): Flow<List<SurveySession>>

    @Query("SELECT * FROM survey_sessions WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSession(): SurveySession?
}