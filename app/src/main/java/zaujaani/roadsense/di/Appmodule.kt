package zaujaani.roadsense.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import zaujaani.roadsense.core.bluetooth.BluetoothGateway
import zaujaani.roadsense.core.gps.GPSGateway
import zaujaani.roadsense.core.sensor.SensorGateway
import zaujaani.roadsense.data.local.*
import zaujaani.roadsense.data.repository.SurveyRepository
import zaujaani.roadsense.data.repository.TelemetryRepository
import zaujaani.roadsense.domain.engine.QualityScoreCalculator
import zaujaani.roadsense.domain.engine.SurveyEngine
import zaujaani.roadsense.domain.usecase.*
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): RoadSenseDatabase = RoadSenseDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideSurveyDao(database: RoadSenseDatabase): SurveyDao = database.surveyDao()

    @Provides
    @Singleton
    fun provideCalibrationDao(database: RoadSenseDatabase): CalibrationDao = database.calibrationDao()

    @Provides
    @Singleton
    fun provideTelemetryDao(database: RoadSenseDatabase): TelemetryDao = database.telemetryDao()

    // ========== DAO BARU ==========
    @Provides
    @Singleton
    fun provideSessionDao(database: RoadSenseDatabase): SessionDao = database.sessionDao()

    @Provides
    @Singleton
    fun provideRoadSegmentDao(database: RoadSenseDatabase): RoadSegmentDao = database.roadSegmentDao()

    // ========== REPOSITORY ==========
    @Provides
    @Singleton
    fun provideSurveyRepository(
        surveyDao: SurveyDao,
        sessionDao: SessionDao,
        roadSegmentDao: RoadSegmentDao,
        calibrationDao: CalibrationDao
    ): SurveyRepository {
        return SurveyRepository(surveyDao, sessionDao, roadSegmentDao, calibrationDao)
    }

    @Provides
    @Singleton
    fun provideTelemetryRepository(
        telemetryDao: TelemetryDao
    ): TelemetryRepository = TelemetryRepository(telemetryDao)

    // ========== GATEWAYS ==========
    @Provides
    @Singleton
    fun provideBluetoothGateway(): BluetoothGateway = BluetoothGateway()

    @Provides
    @Singleton
    fun provideGPSGateway(@ApplicationContext context: Context): GPSGateway = GPSGateway(context)

    @Provides
    @Singleton
    fun provideSensorGateway(@ApplicationContext context: Context): SensorGateway = SensorGateway(context)

    // ========== ENGINE ==========
    @Provides
    @Singleton
    fun provideSurveyEngine(): SurveyEngine = SurveyEngine()

    @Provides
    @Singleton
    fun provideQualityScoreCalculator(): QualityScoreCalculator = QualityScoreCalculator()

    // ========== USECASE ==========
    @Provides
    @Singleton
    fun provideStartSurveyUseCase(
        repository: SurveyRepository,
        surveyEngine: SurveyEngine
    ): StartSurveyUseCase = StartSurveyUseCase(repository, surveyEngine)

    @Provides
    @Singleton
    fun provideStopSurveyUseCase(
        repository: SurveyRepository,
        surveyEngine: SurveyEngine
    ): StopSurveyUseCase = StopSurveyUseCase(repository, surveyEngine)

    @Provides
    @Singleton
    fun providePauseSurveyUseCase(
        repository: SurveyRepository,
        surveyEngine: SurveyEngine
    ): PauseSurveyUseCase = PauseSurveyUseCase(repository, surveyEngine)

    @Provides
    @Singleton
    fun provideResumeSurveyUseCase(
        repository: SurveyRepository,
        surveyEngine: SurveyEngine
    ): ResumeSurveyUseCase = ResumeSurveyUseCase(repository, surveyEngine)

    @Provides
    @Singleton
    fun provideValidateZAxisUseCase(): ValidateZAxisUseCase = ValidateZAxisUseCase()
}