package zaujaani.roadsense.features.map

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.core.content.ContextCompat
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import zaujaani.roadsense.R

object MapInitializer {

    data class InitializationResult(
        val trackingPolyline: Polyline,
        val segmentPolyline: Polyline,
        val startMarker: Marker,
        val endMarker: Marker   // ✅ diperbaiki dari EndMarker menjadi Marker
    )

    fun initializeMap(context: Context, mapView: MapView): InitializationResult {
        mapView.apply {
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(15.0)
        }

        val trackingPolyline = Polyline(mapView).apply {
            outlinePaint.color = ContextCompat.getColor(context, R.color.tracking_line)
            outlinePaint.strokeWidth = 8f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
        }
        mapView.overlays.add(trackingPolyline)

        val segmentPolyline = Polyline(mapView).apply {
            outlinePaint.color = ContextCompat.getColor(context, R.color.segment_line)
            outlinePaint.strokeWidth = 12f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
        }
        mapView.overlays.add(segmentPolyline)

        val startMarker = Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_marker_start)
            isDraggable = true
        }

        val endMarker = Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_marker_end)
            isDraggable = true
        }

        return InitializationResult(trackingPolyline, segmentPolyline, startMarker, endMarker)
    }
}