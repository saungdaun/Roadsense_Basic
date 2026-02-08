package zaujaani.roadsense.data.models

data class SessionDisplayData(
    val sessionId: String = "#000000",
    val status: SurveyState = SurveyState.IDLE,
    val activeSurface: SurfaceType? = null,
    val activeCondition: RoadCondition? = null
)