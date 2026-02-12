package zaujaani.roadsense.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import zaujaani.roadsense.core.bluetooth.BluetoothGateway
import zaujaani.roadsense.core.events.RealtimeRoadsenseBus
import zaujaani.roadsense.core.gps.GPSGateway
import zaujaani.roadsense.core.sensor.SensorGateway
import zaujaani.roadsense.data.local.*
import zaujaani.roadsense.data.repository.SurveyRepository
import zaujaani.roadsense.data.repository.TelemetryRepository
import zaujaani.roadsense.domain.engine.QualityScoreCalculator
import zaujaani.roadsense.domain.engine.SurveyEngine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RoadSenseDatabase =
        RoadSenseDatabase.getInstance(context)

    // ===== DAO =====
    @Provides
    @Singleton
    fun provideCalibrationDao(db: RoadSenseDatabase): CalibrationDao = db.calibrationDao()

    @Provides
    @Singleton
    fun provideSessionDao(db: RoadSenseDatabase): SessionDao = db.sessionDao()

    @Provides
    @Singleton
    fun provideRoadSegmentDao(db: RoadSenseDatabase): RoadSegmentDao = db.roadSegmentDao()

    @Provides
    @Singleton
    fun provideTelemetryDao(db: RoadSenseDatabase): TelemetryDao = db.telemetryDao()

    // ===== REPOSITORY =====
    @Provides
    @Singleton
    fun provideSurveyRepository(
        sessionDao: SessionDao,
        roadSegmentDao: RoadSegmentDao,
        calibrationDao: CalibrationDao
    ): SurveyRepository = SurveyRepository(sessionDao, roadSegmentDao, calibrationDao)

    @Provides
    @Singleton
    fun provideTelemetryRepository(telemetryDao: TelemetryDao): TelemetryRepository =
        TelemetryRepository(telemetryDao)

    // ===== EVENT BUS =====
    @Provides
    @Singleton
    fun provideRealtimeRoadsenseBus(): RealtimeRoadsenseBus = RealtimeRoadsenseBus()

    // ===== GATEWAYS =====
    // 🟢 PERBAIKAN: Tambahkan parameter @ApplicationContext Context
    @Provides
    @Singleton
    fun provideBluetoothGateway(
        @ApplicationContext context: Context,
        bus: RealtimeRoadsenseBus
    ): BluetoothGateway = BluetoothGateway(context, bus)

    @Provides
    @Singleton
    fun provideGPSGateway(
        @ApplicationContext context: Context,
        bus: RealtimeRoadsenseBus
    ): GPSGateway = GPSGateway(context, bus)

    @Provides
    @Singleton
    fun provideSensorGateway(@ApplicationContext context: Context): SensorGateway =
        SensorGateway(context)

    // ===== ENGINE =====
    @Provides
    @Singleton
    fun provideSurveyEngine(bus: RealtimeRoadsenseBus): SurveyEngine =
        SurveyEngine(bus)

    @Provides
    @Singleton
    fun provideQualityScoreCalculator(): QualityScoreCalculator =
        QualityScoreCalculator()
}