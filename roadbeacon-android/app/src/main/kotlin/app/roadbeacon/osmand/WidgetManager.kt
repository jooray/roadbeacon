package app.roadbeacon.osmand

import android.content.Context
import android.content.Intent
import android.util.Log
import app.roadbeacon.RoadbeaconApplication
import app.roadbeacon.model.ConfirmationStatus
import app.roadbeacon.nostr.EventSigner
import app.roadbeacon.nostr.NostrClient
import app.roadbeacon.storage.EventCache
import app.roadbeacon.storage.KeyStore
import app.roadbeacon.ui.ReportDialogActivity
import net.osmand.aidl.contextmenu.AContextMenuButton
import net.osmand.aidl.contextmenu.ContextMenuButtonsParams
import net.osmand.aidl.mapwidget.AMapWidget

class WidgetManager(
    private val context: Context,
    private val aidlHelper: OsmandAidlHelper,
    private val nostrClient: NostrClient,
    private val keyStore: KeyStore,
    private val eventCache: EventCache
) : OsmandAidlHelper.ContextMenuButtonsListener {

    companion object {
        private const val TAG = "WidgetManager"
        private const val WIDGET_ID = "roadbeacon_report"
        private const val CONTEXT_MENU_ID = "roadbeacon_context"
        private const val BUTTON_STILL_THERE = 1
        private const val BUTTON_GONE = 2
    }

    fun setup() {
        Log.d(TAG, "setup() called - setting listener and registering buttons")
        aidlHelper.setContextMenuButtonsListener(this)
        addReportWidget()
        setupContextMenuButtons()
        Log.d(TAG, "setup() complete - listener set: ${this::class.simpleName}")
    }

    private fun addReportWidget() {
        val intent = Intent(context, ReportDialogActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val widget = AMapWidget(
            WIDGET_ID,
            "ic_action_flag",         // menu icon
            "Roadbeacon Report",         // menu title
            "ic_action_flag",         // light icon
            "ic_action_flag",         // dark icon
            "Report",                 // widget text
            "",                       // description
            50,                       // order
            intent                    // intent on click
        )

        aidlHelper.addMapWidget(widget)
    }

    private fun setupContextMenuButtons() {
        Log.d(TAG, "setupContextMenuButtons: creating buttons for layer=${MapLayerManager.LAYER_ID}")

        val stillThereButton = AContextMenuButton(
            BUTTON_STILL_THERE,
            "Still there",
            "",
            "ic_action_done",
            "",
            true,
            true
        )

        val goneButton = AContextMenuButton(
            BUTTON_GONE,
            "Gone",
            "",
            "ic_action_remove_dark",
            "",
            true,
            true
        )

        val params = ContextMenuButtonsParams(
            stillThereButton,
            goneButton,
            CONTEXT_MENU_ID,
            context.packageName,
            MapLayerManager.LAYER_ID,
            -1L,
            emptyList()
        )

        Log.d(TAG, "ContextMenuButtonsParams: id=${params.id}, appPackage=${params.appPackage}, layerId=${params.layerId}")
        val callbackId = aidlHelper.registerContextMenuButtons(params)
        Log.d(TAG, "registerContextMenuButtons returned callbackId=$callbackId (success=${callbackId >= 0})")
    }

    override fun onContextMenuButtonClicked(buttonId: Int, pointId: String, layerId: String) {
        Log.i(TAG, "=== CONTEXT MENU BUTTON CLICKED ===")
        Log.i(TAG, "  buttonId=$buttonId, pointId=$pointId, layerId=$layerId")
        Log.i(TAG, "  expected layerId=${MapLayerManager.LAYER_ID}")

        if (layerId != MapLayerManager.LAYER_ID) {
            Log.w(TAG, "Ignoring click - wrong layer (expected ${MapLayerManager.LAYER_ID}, got $layerId)")
            return
        }

        val privKey = keyStore.getPrivateKey()
        if (privKey == null) {
            Log.w(TAG, "No private key available, cannot publish confirmation")
            return
        }
        Log.d(TAG, "Private key available, proceeding with confirmation")

        val status = when (buttonId) {
            BUTTON_STILL_THERE -> ConfirmationStatus.STILL_THERE
            BUTTON_GONE -> ConfirmationStatus.NO_LONGER_THERE
            else -> {
                Log.w(TAG, "Unknown button ID: $buttonId")
                return
            }
        }

        // Look up the original event to get its coordinates for geohash tags
        val originalEvent = eventCache.getReport(pointId)
        if (originalEvent == null) {
            Log.w(TAG, "Cannot find original event in cache: $pointId")
            return
        }

        Log.i(TAG, "Publishing confirmation: status=${status.value} for event=$pointId")
        val event = EventSigner.createConfirmationEvent(pointId, status, privKey)
        nostrClient.publish(event)
        (context.applicationContext as? RoadbeaconApplication)?.meshBridge?.onOwnEvent(event)
        Log.i(TAG, "Confirmation published: eventId=${event.id.take(8)}")
    }

    fun cleanup() {
        aidlHelper.setContextMenuButtonsListener(null)
        aidlHelper.removeContextMenuButtons(CONTEXT_MENU_ID)
    }
}
