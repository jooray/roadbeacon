package app.roadbeacon.osmand

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import net.osmand.aidl.IOsmAndAidlCallback
import net.osmand.aidl.IOsmAndAidlInterface
import net.osmand.aidl.contextmenu.AContextMenuButton
import net.osmand.aidl.contextmenu.ContextMenuButtonsParams
import net.osmand.aidl.contextmenu.RemoveContextMenuButtonsParams
import net.osmand.aidl.map.ALatLon
import net.osmand.aidl.maplayer.AMapLayer
import net.osmand.aidl.maplayer.AddMapLayerParams
import net.osmand.aidl.maplayer.RemoveMapLayerParams
import net.osmand.aidl.maplayer.UpdateMapLayerParams
import net.osmand.aidl.maplayer.point.AMapPoint
import net.osmand.aidl.maplayer.point.AddMapPointParams
import net.osmand.aidl.maplayer.point.RemoveMapPointParams
import net.osmand.aidl.maplayer.point.UpdateMapPointParams
import net.osmand.aidl.mapwidget.AMapWidget
import net.osmand.aidl.mapwidget.AddMapWidgetParams
import net.osmand.aidl.navigation.ADirectionInfo
import net.osmand.aidl.navigation.OnVoiceNavigationParams
import net.osmand.aidl.gpx.AGpxBitmap
import net.osmand.aidl.search.SearchResult

class OsmandAidlHelper(private val context: Context, private val packageName: String) {

    private var mIOsmAndAidlInterface: IOsmAndAidlInterface? = null
    private var bound = false
    private var initialized = false

    private val connectionListeners = mutableSetOf<OsmandConnectionListener>()
    private var contextMenuButtonsListener: ContextMenuButtonsListener? = null

    interface OsmandConnectionListener {
        fun onOsmandConnectionStateChanged(connected: Boolean)
    }

    interface ContextMenuButtonsListener {
        fun onContextMenuButtonClicked(buttonId: Int, pointId: String, layerId: String)
    }

    private val mIOsmAndAidlCallback = object : IOsmAndAidlCallback.Stub() {
        override fun onSearchComplete(resultSet: List<SearchResult>) {}
        override fun onUpdate() {}
        override fun onAppInitialized() {}
        override fun onGpxBitmapCreated(bitmap: AGpxBitmap?) {}
        override fun updateNavigationInfo(directionInfo: ADirectionInfo?) {}
        override fun onContextMenuButtonClicked(buttonId: Int, pointId: String, layerId: String) {
            Log.d(TAG, "AIDL callback onContextMenuButtonClicked: button=$buttonId, point=$pointId, layer=$layerId")
            contextMenuButtonsListener?.onContextMenuButtonClicked(buttonId, pointId, layerId)
        }
        override fun onVoiceRouterNotify(params: OnVoiceNavigationParams?) {}
    }

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            mIOsmAndAidlInterface = IOsmAndAidlInterface.Stub.asInterface(service)
            initialized = true
            connectionListeners.forEach { it.onOsmandConnectionStateChanged(true) }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            mIOsmAndAidlInterface = null
            connectionListeners.forEach { it.onOsmandConnectionStateChanged(false) }
        }
    }

    fun addConnectionListener(listener: OsmandConnectionListener) {
        connectionListeners.add(listener)
    }

    fun removeConnectionListener(listener: OsmandConnectionListener) {
        connectionListeners.remove(listener)
    }

    fun setContextMenuButtonsListener(listener: ContextMenuButtonsListener?) {
        contextMenuButtonsListener = listener
    }

    fun isConnected(): Boolean = mIOsmAndAidlInterface != null

    fun connect() {
        if (bindService()) {
            bound = true
        } else {
            bound = false
            initialized = true
        }
    }

    fun disconnect() {
        try {
            if (mIOsmAndAidlInterface != null) {
                mIOsmAndAidlInterface = null
                context.unbindService(mConnection)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
        }
    }

    private fun bindService(): Boolean {
        return if (mIOsmAndAidlInterface == null) {
            val intent = Intent("net.osmand.aidl.OsmandAidlService")
            intent.`package` = packageName
            context.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
        } else {
            true
        }
    }

    fun addMapLayer(layer: AMapLayer): Boolean {
        if (mIOsmAndAidlInterface != null) {
            try {
                return mIOsmAndAidlInterface!!.addMapLayer(AddMapLayerParams(layer))
            } catch (e: RemoteException) {
                Log.e(TAG, "addMapLayer error: ${e.message}")
            }
        }
        return false
    }

    fun removeMapLayer(layerId: String): Boolean {
        if (mIOsmAndAidlInterface != null) {
            try {
                return mIOsmAndAidlInterface!!.removeMapLayer(RemoveMapLayerParams(layerId))
            } catch (e: RemoteException) {
                Log.e(TAG, "removeMapLayer error: ${e.message}")
            }
        }
        return false
    }

    fun addMapPoint(layerId: String, point: AMapPoint): Boolean {
        if (mIOsmAndAidlInterface != null) {
            try {
                return mIOsmAndAidlInterface!!.addMapPoint(AddMapPointParams(layerId, point))
            } catch (e: RemoteException) {
                Log.e(TAG, "addMapPoint error: ${e.message}")
            }
        }
        return false
    }

    fun updateMapPoint(layerId: String, point: AMapPoint): Boolean {
        if (mIOsmAndAidlInterface != null) {
            try {
                return mIOsmAndAidlInterface!!.updateMapPoint(UpdateMapPointParams(layerId, point, false))
            } catch (e: RemoteException) {
                Log.e(TAG, "updateMapPoint error: ${e.message}")
            }
        }
        return false
    }

    fun removeMapPoint(layerId: String, pointId: String): Boolean {
        if (mIOsmAndAidlInterface != null) {
            try {
                return mIOsmAndAidlInterface!!.removeMapPoint(RemoveMapPointParams(layerId, pointId))
            } catch (e: RemoteException) {
                Log.e(TAG, "removeMapPoint error: ${e.message}")
            }
        }
        return false
    }

    fun addMapWidget(widget: AMapWidget): Boolean {
        if (mIOsmAndAidlInterface != null) {
            try {
                return mIOsmAndAidlInterface!!.addMapWidget(AddMapWidgetParams(widget))
            } catch (e: RemoteException) {
                Log.e(TAG, "addMapWidget error: ${e.message}")
            }
        }
        return false
    }

    private var contextMenuCallbackId: Long = -1L

    fun registerContextMenuButtons(params: ContextMenuButtonsParams): Long {
        if (mIOsmAndAidlInterface != null) {
            try {
                contextMenuCallbackId = mIOsmAndAidlInterface!!.addContextMenuButtons(params, mIOsmAndAidlCallback)
                Log.d(TAG, "registerContextMenuButtons: callbackId=$contextMenuCallbackId, menuId=${params.id}, layerId=${params.layerId}")
                return contextMenuCallbackId
            } catch (e: RemoteException) {
                Log.e(TAG, "registerContextMenuButtons error: ${e.message}")
            }
        } else {
            Log.w(TAG, "registerContextMenuButtons: OsmAnd not connected")
        }
        return -1L
    }

    fun removeContextMenuButtons(paramsId: String): Boolean {
        if (mIOsmAndAidlInterface != null) {
            try {
                return mIOsmAndAidlInterface!!.removeContextMenuButtons(
                    RemoveContextMenuButtonsParams(paramsId, contextMenuCallbackId)
                )
            } catch (e: RemoteException) {
                Log.e(TAG, "removeContextMenuButtons error: ${e.message}")
            }
        }
        return false
    }

    companion object {
        private const val TAG = "OsmandAidlHelper"
    }
}
