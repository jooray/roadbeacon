package app.roadbeacon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.roadbeacon.location.AlertEngine
import app.roadbeacon.location.GeohashUtil
import app.roadbeacon.location.LocationMonitor
import app.roadbeacon.location.LocationStartResult
import app.roadbeacon.model.*
import app.roadbeacon.nostr.*
import app.roadbeacon.mesh.MeshBridge
import app.roadbeacon.mesh.MeshCoreApiClient
import app.roadbeacon.osmand.MapLayerManager
import app.roadbeacon.osmand.OsmandAidlHelper
import app.roadbeacon.osmand.WidgetManager
import app.roadbeacon.storage.EventCache
import app.roadbeacon.ui.MainActivity
import kotlinx.coroutines.*

class RoadbeaconService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var nostrClient: NostrClient
    private lateinit var locationMonitor: LocationMonitor
    private lateinit var alertEngine: AlertEngine
    private lateinit var osmandHelper: OsmandAidlHelper
    private lateinit var mapLayerManager: MapLayerManager
    private lateinit var widgetManager: WidgetManager
    private lateinit var eventCache: EventCache

    private var currentSubId: String? = null
    private var confirmSubId: String? = null
    private var osmandReconnectJob: Job? = null
    private var subscriptionDebounceJob: Job? = null
    private var monitoring = false

    companion object {
        private const val TAG = "RoadbeaconService"
        private const val NOTIFICATION_ID = 1
        private const val SUBSCRIPTION_DEBOUNCE_MS = 5000L
        private const val MESH_FIX_MAX_AGE_MS = 10 * 60 * 1000L

        const val ACTION_START = "app.roadbeacon.action.START"
        const val ACTION_STOP = "app.roadbeacon.action.STOP"
        const val ACTION_APPLY_MESH = "app.roadbeacon.action.APPLY_MESH"
    }

    override fun onCreate() {
        super.onCreate()

        val app = application as RoadbeaconApplication
        eventCache = app.eventCache

        nostrClient = NostrClient(serviceScope)
        app.nostrClient = nostrClient

        locationMonitor = LocationMonitor(this)
        alertEngine = AlertEngine(this, app.settings, eventCache)
        osmandHelper = OsmandAidlHelper(this, app.settings.osmandPackage)
        mapLayerManager = MapLayerManager(osmandHelper)
        app.mapLayerManager = mapLayerManager
        widgetManager = WidgetManager(this, osmandHelper, nostrClient, app.keyStore, eventCache)

        applyMeshSettings()

        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_APPLY_MESH -> applyMeshSettings()
            else -> {
                if (!monitoring) {
                    startMonitoring()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Starts, stops or restarts the mesh bridge from the current settings, without a restart. */
    private var meshUrlInUse: String? = null

    private fun applyMeshSettings() {
        val app = application as RoadbeaconApplication
        val enabled = app.settings.meshEnabled
        if (!enabled) {
            app.meshBridge?.stop()
            app.meshBridge = null
            app.meshClient = null
            return
        }
        if (app.meshBridge != null && meshUrlInUse == app.settings.meshUrl) return
        app.meshBridge?.stop()
        meshUrlInUse = app.settings.meshUrl
        val client = MeshCoreApiClient(app.settings, serviceScope)
        val bridge = MeshBridge(
            settings = app.settings,
            transport = client,
            scope = serviceScope,
            onInbound = { event ->
                when (event.kind) {
                    1315 -> handleReportEvent(event)
                    1316 -> handleConfirmationEvent(event)
                }
            },
            publishToRelays = { event -> nostrClient.publish(event) },
            position = {
                // Only a recent fix: an hour-old position says nothing about what is near now.
                locationMonitor.getLastLocation()
                    ?.takeIf { System.currentTimeMillis() - it.timestamp < MESH_FIX_MAX_AGE_MS }
                    ?.let { it.lat to it.lon }
            },
            reportExists = { id -> eventCache.getReport(id) != null },
            liveReportLocation = { id ->
                eventCache.getAllActiveReports().firstOrNull { it.id == id }?.let { it.lat to it.lon }
            }
        )
        app.meshClient = client
        app.meshBridge = bridge
        bridge.start()
        Log.i(TAG, "MeshCore bridge started: ${app.settings.meshUrl}")
    }

    override fun onDestroy() {
        (application as RoadbeaconApplication).meshBridge?.stop()
        (application as RoadbeaconApplication).meshBridge = null
        (application as RoadbeaconApplication).meshClient = null
        stopMonitoring()
        (application as RoadbeaconApplication).nostrClient = null
        (application as RoadbeaconApplication).mapLayerManager = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring() {
        monitoring = true
        val app = application as RoadbeaconApplication

        // Generate key if none exists
        if (!app.keyStore.hasKey()) {
            app.keyStore.generateKeyPair()
        }

        // Connect to Nostr relays
        nostrClient.connect(app.settings.relays)

        // Start location monitoring
        when (val result = locationMonitor.start()) {
            LocationStartResult.Success -> Log.d(TAG, "Location monitoring started")
            LocationStartResult.PermissionDenied -> Log.e(TAG, "Location permission denied")
            is LocationStartResult.Error -> Log.e(TAG, "Location start error: ${result.exception.message}")
        }

        // Connect to OsmAnd with auto-reconnect
        connectOsmand()
        startOsmandReconnect()

        // Start event cache cleanup
        eventCache.start()

        // Subscribe to confirmations for any existing cached reports
        subscribeToConfirmations()

        // Remove expired events from OsmAnd map and re-subscribe when cache changes
        eventCache.onEventsRemoved = { ids ->
            ids.forEach { mapLayerManager.removeEvent(it) }
            // Re-subscribe when events removed (cache changed)
            subscribeToConfirmations()
        }

        // Listen for incoming events
        serviceScope.launch {
            nostrClient.events.collect { event ->
                handleNostrEvent(event)
            }
        }

        // Forward relay connection count to app for UI observation
        serviceScope.launch {
            nostrClient.connectionCount.collect { count ->
                app.relayConnectionCount.value = count
            }
        }

        // Listen for location changes → check alerts
        serviceScope.launch {
            locationMonitor.locations.collect { location ->
                alertEngine.checkProximity(location)
            }
        }

        // Listen for geohash changes → update subscriptions (debounced)
        serviceScope.launch {
            locationMonitor.geohashChanged.collect { (geohash, precision) ->
                // Cancel any pending subscription update
                subscriptionDebounceJob?.cancel()
                subscriptionDebounceJob = serviceScope.launch {
                    delay(SUBSCRIPTION_DEBOUNCE_MS)
                    updateSubscription(geohash, precision)
                }
            }
        }
    }

    private fun connectOsmand() {
        osmandHelper.addConnectionListener(object : OsmandAidlHelper.OsmandConnectionListener {
            override fun onOsmandConnectionStateChanged(connected: Boolean) {
                (application as RoadbeaconApplication).osmandConnected.value = connected
                if (connected) {
                    Log.d(TAG, "OsmAnd connected, setting up layer and widgets")
                    mapLayerManager.createLayer()
                    widgetManager.setup()
                    eventCache.getAllActiveReports().forEach { mapLayerManager.addOrUpdateEvent(it) }
                } else {
                    Log.d(TAG, "OsmAnd disconnected")
                }
            }
        })
        osmandHelper.connect()
    }

    private fun startOsmandReconnect() {
        osmandReconnectJob = serviceScope.launch {
            while (isActive) {
                delay(10_000) // Check every 10 seconds
                if (!osmandHelper.isConnected()) {
                    Log.d(TAG, "Attempting OsmAnd reconnect...")
                    try {
                        osmandHelper.disconnect()
                    } catch (e: Exception) {
                        // Ignore disconnect errors
                    }
                    osmandHelper.connect()
                }
            }
        }
    }

    private fun stopMonitoring() {
        monitoring = false
        osmandReconnectJob?.cancel()
        subscriptionDebounceJob?.cancel()
        eventCache.onEventsRemoved = null
        locationMonitor.stop()
        nostrClient.disconnect()
        try {
            widgetManager.cleanup()
            mapLayerManager.removeLayer()
            osmandHelper.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error during OsmAnd cleanup: ${e.message}")
        }
        eventCache.stop()

        currentSubId?.let { nostrClient.closeSubscription(it) }
        confirmSubId?.let { nostrClient.closeSubscription(it) }
    }

    private fun updateSubscription(geohash: String, precision: Int) {
        currentSubId?.let { nostrClient.closeSubscription(it) }

        val neighbors = GeohashUtil.neighbors(geohash)
        // The window must cover the longest-lived report type (speed cameras, 30 days); anything
        // shorter hides valid reports on a cold start. The cache drops what has expired its type.
        val since = (System.currentTimeMillis() / 1000) - EventType.entries.maxOf { it.defaultTTL }

        Log.d(TAG, "Subscription update: geohash=$geohash, precision=$precision")
        Log.d(TAG, "Query geohashes: ${neighbors.joinToString(", ")}")
        Log.d(TAG, "Query since: $since (${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(since * 1000))})")

        val filter = NostrFilter(
            kinds = listOf(1315),
            gTags = neighbors,
            since = since
        )

        currentSubId = nostrClient.subscribe(filter)
        Log.d(TAG, "Subscribed with subId=$currentSubId to ${neighbors.size} geohash cells")
    }

    private fun handleNostrEvent(event: NostrEvent) {
        when (event.kind) {
            1315 -> handleReportEvent(event)
            1316 -> handleConfirmationEvent(event)
        }
        // Offer relay events to the radio when "relay -> radio" is on; after the cache has them,
        // so the bridge sees whether the report is still shown.
        (application as RoadbeaconApplication).meshBridge?.onRelayEvent(event)
    }

    private fun handleReportEvent(event: NostrEvent) {
        Log.d(TAG, "Received event: id=${event.id.take(8)}, kind=${event.kind}")
        if (!BinaryCodec.isCanonical(event)) {
            Log.d(TAG, "Ignoring a report outside the current format: ${event.id.take(8)}")
            return
        }
        // The same report from a second relay or the radio: keep the cached one and its counts.
        if (eventCache.getReport(event.id) != null) return

        val typeTag = event.tags.find { it[0] == "t" }
        if (typeTag == null) {
            Log.w(TAG, "Event ${event.id.take(8)} missing 't' tag, skipping")
            return
        }

        val latTag = event.tags.find { it[0] == "lat" }
        val lonTag = event.tags.find { it[0] == "lon" }
        val expTag = event.tags.find { it[0] == "expiration" }
        val gTags = event.tags.filter { it[0] == "g" }

        Log.d(TAG, "Event tags: type=${typeTag[1]}, lat=${latTag?.get(1)}, lon=${lonTag?.get(1)}, geohashes=${gTags.map { it[1] }}")

        val type = EventType.fromTypeName(typeTag[1])
        val lat = latTag?.get(1)?.toDoubleOrNull()
        val lon = lonTag?.get(1)?.toDoubleOrNull()

        if (lat == null || lon == null) {
            Log.w(TAG, "Event ${event.id.take(8)} missing lat/lon, skipping")
            return
        }

        val expiration = expTag?.get(1)?.toLongOrNull() ?: RoadEventFormat.expirationFor(event.createdAt, type)

        val roadEvent = RoadEvent(
            id = event.id,
            pubkey = event.pubkey,
            type = type,
            lat = lat,
            lon = lon,
            createdAt = event.createdAt,
            expiration = expiration,
            content = event.content,
            geohashes = gTags.map { it[1] }
        )

        Log.d(TAG, "Adding event to cache: ${type.typeName} at $lat,$lon")
        eventCache.addReport(roadEvent)
        mapLayerManager.addOrUpdateEvent(roadEvent)
        subscribeToConfirmations()
        // Votes heard on the radio before this report can be applied now.
        (application as RoadbeaconApplication).meshBridge?.onReportKnown(event.id)
    }

    private fun handleConfirmationEvent(event: NostrEvent) {
        Log.d(TAG, "Confirmation received: ${event.id.take(8)}")
        if (!BinaryCodec.isCanonical(event)) {
            Log.d(TAG, "Ignoring a vote outside the current format: ${event.id.take(8)}")
            return
        }
        val eTag = event.tags.find { it[0] == "e" } ?: return
        val statusTag = event.tags.find { it[0] == "status" } ?: return
        val status = ConfirmationStatus.fromValue(statusTag[1]) ?: return  // Skip invalid

        val confirmation = Confirmation(
            id = event.id,
            pubkey = event.pubkey,
            eventId = eTag[1],
            status = status,
            createdAt = event.createdAt
        )

        eventCache.addConfirmation(confirmation)

        val report = eventCache.getReport(eTag[1])
        if (report != null) {
            val confs = eventCache.admissible(report, eventCache.getConfirmations(report.id))
            val updatedReport = report.copy(
                confirmCount = confs.count { it.status == ConfirmationStatus.STILL_THERE },
                denyCount = confs.count { it.status == ConfirmationStatus.NO_LONGER_THERE }
            )
            Log.d(TAG, "Counts updated for ${event.id.take(8)}: +${updatedReport.confirmCount}/-${updatedReport.denyCount}")
            eventCache.addReport(updatedReport)
            mapLayerManager.addOrUpdateEvent(updatedReport)
        }
    }

    private fun subscribeToConfirmations() {
        confirmSubId?.let { nostrClient.closeSubscription(it) }

        val eventIds = eventCache.getAllActiveReports().map { it.id }
        if (eventIds.isEmpty()) {
            Log.d(TAG, "subscribeToConfirmations: no event IDs, subscription cleared")
            confirmSubId = null
            return
        }
        Log.d(TAG, "subscribeToConfirmations: ${eventIds.size} events")

        val filter = NostrFilter(
            kinds = listOf(1316),
            eTags = eventIds
        )

        confirmSubId = nostrClient.subscribe(filter)
    }

    private fun createNotification(): Notification {
        val channelId = "roadbeacon_service"
        val channel = NotificationChannel(
            channelId,
            "Roadbeacon Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Roadbeacon")
            .setContentText("Monitoring road events")
            .setSmallIcon(R.drawable.ic_stat_roadbeacon)
            .setColor(0xFF474DD3.toInt())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

}
