package app.roadbeacon.osmand

import android.graphics.Color
import android.util.Log
import app.roadbeacon.model.RoadEvent
import net.osmand.aidl.map.ALatLon
import net.osmand.aidl.maplayer.AMapLayer
import net.osmand.aidl.maplayer.point.AMapPoint

class MapLayerManager(private val aidlHelper: OsmandAidlHelper) {

    companion object {
        private const val TAG = "MapLayerManager"
        const val LAYER_ID = "roadbeacon_events"
        private const val Z_ORDER = 5.5f
    }

    private val displayedPoints = mutableSetOf<String>()

    fun createLayer() {
        Log.d(TAG, "Creating map layer: $LAYER_ID")
        val layer = AMapLayer(
            LAYER_ID,
            "Roadbeacon Events",
            Z_ORDER,
            emptyList()
        )
        val result = aidlHelper.addMapLayer(layer)
        Log.d(TAG, "addMapLayer result: $result")
    }

    fun addOrUpdateEvent(event: RoadEvent) {
        val point = createMapPoint(event)
        Log.d(TAG, "addOrUpdateEvent: ${event.id.take(8)} ${event.type.typeName} at ${event.lat},${event.lon}")
        if (displayedPoints.contains(event.id)) {
            val result = aidlHelper.updateMapPoint(LAYER_ID, point)
            Log.d(TAG, "updateMapPoint result: $result")
        } else {
            val result = aidlHelper.addMapPoint(LAYER_ID, point)
            Log.d(TAG, "addMapPoint result: $result")
            displayedPoints.add(event.id)
        }
    }

    fun removeEvent(id: String) {
        aidlHelper.removeMapPoint(LAYER_ID, id)
        displayedPoints.remove(id)
    }

    fun removeAllEvents() {
        displayedPoints.toList().forEach { id ->
            aidlHelper.removeMapPoint(LAYER_ID, id)
        }
        displayedPoints.clear()
    }

    fun removeLayer() {
        aidlHelper.removeMapLayer(LAYER_ID)
        displayedPoints.clear()
    }

    private fun createMapPoint(event: RoadEvent): AMapPoint {
        val colorInt = try {
            Color.parseColor(event.type.colorHex)
        } catch (e: Exception) {
            Color.GRAY
        }

        val shortName = event.type.displayName.take(3).uppercase()
        val details = buildString {
            append(event.type.displayName)
            if (event.content.isNotEmpty()) {
                append(": ")
                append(event.content)
            }
            append("\nConfirm: ${event.confirmCount} | Deny: ${event.denyCount}")
        }

        val params = mutableMapOf<String, String>()
        params["color"] = String.format("#%06X", colorInt and 0xFFFFFF)
        params[AMapPoint.POINT_TYPE_ICON_NAME_PARAM] = event.type.iconName

        return AMapPoint(
            event.id,
            shortName,
            details,
            event.type.displayName,
            LAYER_ID,
            colorInt,
            ALatLon(event.lat, event.lon),
            emptyList(),
            params
        )
    }
}
