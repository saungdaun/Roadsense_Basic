package zaujaani.roadsense.features.map

import android.content.Context
import android.graphics.Paint
import android.location.Location
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import zaujaani.roadsense.R
import zaujaani.roadsense.data.local.RoadSegment
import zaujaani.roadsense.domain.model.RoadCondition
import zaujaani.roadsense.domain.model.SurfaceType
import java.util.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

class MapOverlayManager(
    private val context: Context,
    private val mapView: MapView,
    private val trackingPolyline: Polyline,
    private val segmentPolyline: Polyline,
    private val startMarker: Marker,
    private val endMarker: Marker
) {
    private var currentLocationDotMarker: Marker? = null
    private var currentLocationAccuracyPolygon: Polygon? = null
    private var distanceMarker: Marker? = null
    private var mapTapOverlay: Overlay? = null

    // Untuk menyimpan track hasil impor
    private val importedPolylines = mutableListOf<Polyline>()

    private var hasCenteredMap = false

    var onMapTapListener: ((GeoPoint) -> Unit)? = null

    fun updateTrackingPolyline(points: List<GeoPoint>) {
        trackingPolyline.setPoints(points)
        mapView.postInvalidate()
    }

    fun updateSegmentPolyline(points: List<GeoPoint>, sensorDistance: Float) {
        segmentPolyline.setPoints(points)
        when (points.size) {
            1 -> {
                startMarker.position = points[0]
                startMarker.title = context.getString(R.string.segment_start)
                if (!mapView.overlays.contains(startMarker)) {
                    mapView.overlays.add(startMarker)
                }
                mapView.overlays.remove(endMarker)
            }
            2 -> {
                startMarker.position = points[0]
                endMarker.position = points[1]
                startMarker.title = context.getString(R.string.segment_start)
                endMarker.title = context.getString(R.string.segment_end)

                if (!mapView.overlays.contains(startMarker)) mapView.overlays.add(startMarker)
                if (!mapView.overlays.contains(endMarker)) mapView.overlays.add(endMarker)

                distanceMarker?.let { mapView.overlays.remove(it) }

                val mid = GeoPoint(
                    (points[0].latitude + points[1].latitude) / 2,
                    (points[0].longitude + points[1].longitude) / 2
                )
                distanceMarker = Marker(mapView).apply {
                    position = mid
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = null
                    title = context.getString(R.string.distance_sensor, sensorDistance)
                }.also { mapView.overlays.add(it) }
            }
        }
        mapView.invalidate()
    }

    fun clearSegmentCreationMarkers() {
        mapView.overlays.remove(startMarker)
        mapView.overlays.remove(endMarker)
        distanceMarker?.let { mapView.overlays.remove(it) }
        distanceMarker = null
    }

    fun updateCurrentLocation(location: Location) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)
        val accuracyMeters = location.accuracy.toDouble()

        if (currentLocationAccuracyPolygon == null) {
            currentLocationAccuracyPolygon = Polygon(mapView).apply {
                points = createCirclePolygonPoints(geoPoint, accuracyMeters)
                outlinePaint.color = ContextCompat.getColor(context, R.color.location_blue)
                outlinePaint.strokeWidth = 2f
                fillPaint.color = ContextCompat.getColor(context, R.color.accuracy_circle_fill)
                fillPaint.alpha = 32
            }.also { mapView.overlays.add(it) }
        } else {
            currentLocationAccuracyPolygon?.points = createCirclePolygonPoints(geoPoint, accuracyMeters)
        }

        if (currentLocationDotMarker == null) {
            currentLocationDotMarker = Marker(mapView).apply {
                position = geoPoint
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ContextCompat.getDrawable(context, R.drawable.ic_location_dot)
                title = context.getString(R.string.position_reference, location.accuracy)
            }.also { mapView.overlays.add(it) }
        } else {
            currentLocationDotMarker?.position = geoPoint
            currentLocationDotMarker?.title = context.getString(R.string.position_reference, location.accuracy)
        }

        if (!hasCenteredMap) {
            mapView.controller.animateTo(geoPoint, 18.0, 800L)
            hasCenteredMap = true
        }
    }

    private fun createCirclePolygonPoints(center: GeoPoint, radius: Double, sides: Int = 36): ArrayList<GeoPoint> {
        val points = ArrayList<GeoPoint>()
        val earthRadius = 6378137.0
        val lat = Math.toRadians(center.latitude)
        val lon = Math.toRadians(center.longitude)

        for (i in 0 until sides) {
            val angle = (2 * PI * i) / sides
            val dLat = (radius / earthRadius) * cos(angle)
            val dLon = (radius / earthRadius) * sin(angle) / cos(lat)
            points.add(
                GeoPoint(
                    Math.toDegrees(lat + dLat),
                    Math.toDegrees(lon + dLon)
                )
            )
        }
        points.add(points[0])
        return points
    }

    fun displaySavedSegments(segments: List<RoadSegment>) {
        val toRemove = mapView.overlays.filter { overlay ->
            overlay is Polyline && overlay != trackingPolyline && overlay != segmentPolyline && overlay !in importedPolylines
        }.toList()
        toRemove.forEach { mapView.overlays.remove(it) }

        segments.forEach { segment ->
            Polyline(mapView).apply {
                setPoints(
                    listOf(
                        GeoPoint(segment.startLatitude, segment.startLongitude),
                        GeoPoint(segment.endLatitude, segment.endLongitude)
                    )
                )
                val color = when (segment.condition) {
                    RoadCondition.GOOD.code -> ContextCompat.getColor(context, R.color.condition_good)
                    RoadCondition.MODERATE.code -> ContextCompat.getColor(context, R.color.condition_moderate)
                    RoadCondition.LIGHT_DAMAGE.code -> ContextCompat.getColor(context, R.color.condition_light_damage)
                    else -> ContextCompat.getColor(context, R.color.condition_heavy_damage)
                }
                outlinePaint.color = color
                outlinePaint.strokeWidth = 8f

                val surfaceName = when (segment.surface) {
                    SurfaceType.ASPHALT.code -> context.getString(R.string.asphalt)
                    SurfaceType.CONCRETE.code -> context.getString(R.string.concrete)
                    SurfaceType.GRAVEL.code -> context.getString(R.string.gravel)
                    SurfaceType.DIRT.code -> context.getString(R.string.dirt)
                    else -> context.getString(R.string.other)
                }

                title = context.getString(R.string.segment_title, segment.roadName, segment.distanceMeters)
                subDescription = context.getString(R.string.segment_subdesc, surfaceName, segment.dataSource)
            }.also { mapView.overlays.add(it) }
        }
        mapView.invalidate()
    }

    // ==================== FUNGSI UNTUK IMPORT GPS ====================
    fun addImportedTrack(points: List<GeoPoint>, color: Int = ContextCompat.getColor(context, R.color.purple_500)) {
        val polyline = Polyline(mapView).apply {
            setPoints(points)
            outlinePaint.color = color
            outlinePaint.strokeWidth = 6f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
        }
        mapView.overlays.add(polyline)
        importedPolylines.add(polyline)
        mapView.invalidate()
    }

    fun clearImportedTracks() {
        importedPolylines.forEach { mapView.overlays.remove(it) }
        importedPolylines.clear()
        mapView.invalidate()
    }

    fun getImportedTracksCount(): Int = importedPolylines.size
    // ================================================================

    fun setupMapTapOverlay() {
        if (mapTapOverlay == null) {
            mapTapOverlay = object : Overlay() {
                override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
                    val projection = mapView?.projection
                    val geoPoint = projection?.fromPixels(
                        e?.x?.toInt() ?: 0,
                        e?.y?.toInt() ?: 0
                    )?.let { GeoPoint(it) }
                    geoPoint?.let { onMapTapListener?.invoke(it) }
                    return true
                }
            }.also { mapView.overlays.add(it) }
        }
    }

    fun removeMapTapOverlay() {
        mapTapOverlay?.let { mapView.overlays.remove(it) }
        mapTapOverlay = null
    }

    fun centerMapOnLocation(location: GeoPoint, zoom: Double = 16.0, animate: Boolean = true) {
        if (animate) {
            mapView.controller.animateTo(location, zoom, 1000L)
        } else {
            mapView.controller.setCenter(location)
            mapView.controller.setZoom(zoom)
        }
    }

    fun onDestroy() {
        listOf(
            currentLocationDotMarker,
            currentLocationAccuracyPolygon,
            distanceMarker,
            mapTapOverlay
        ).forEach { it?.let { overlay -> mapView.overlays.remove(overlay) } }
    }
}