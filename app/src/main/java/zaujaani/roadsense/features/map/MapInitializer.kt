package zaujaani.roadsense.features.map

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import timber.log.Timber
import zaujaani.roadsense.R
import java.io.File

object MapInitializer {

    data class InitializationResult(
        val trackingPolyline: Polyline,
        val segmentPolyline: Polyline,
        val startMarker: Marker,
        val endMarker: Marker
    )

    fun initializeMap(context: Context, mapView: MapView): InitializationResult {
        val baseDir = File(context.getExternalFilesDir(null), "osmdroid")
        val tileCacheDir = File(baseDir, "tiles/cache")
        val tileArchiveDir = File(baseDir, "tiles")
        tileCacheDir.mkdirs()
        tileArchiveDir.mkdirs()

        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidTileCache = tileCacheDir
            val prefs = context.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
            load(context, prefs)
        }

        val archives = tileArchiveDir.listFiles { file ->
            file.extension == "sqlite" || file.extension == "zip" || file.extension == "mbtiles"
        }
        if (!archives.isNullOrEmpty()) {
            try {
                val provider = OfflineTileProvider(SimpleRegisterReceiver(context), archives)
                mapView.setTileProvider(provider)
                Timber.d("✅ Offline tiles loaded (${archives.size} files)")
            } catch (e: Exception) {
                Timber.e(e, "Offline tile failed — fallback to MAPNIK")
                mapView.setTileSource(TileSourceFactory.MAPNIK)
            }
        } else {
            Timber.d("No offline tiles — using MAPNIK")
            mapView.setTileSource(TileSourceFactory.MAPNIK)
        }

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