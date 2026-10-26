package app.roadbeacon

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import app.roadbeacon.nostr.NostrClient
import app.roadbeacon.osmand.MapLayerManager
import app.roadbeacon.storage.EventCache
import app.roadbeacon.storage.KeyStore
import app.roadbeacon.storage.Settings
import kotlinx.coroutines.flow.MutableStateFlow

class RoadbeaconApplication : Application() {

    lateinit var settings: Settings
        private set
    lateinit var keyStore: KeyStore
        private set
    lateinit var eventCache: EventCache
        private set

    // Shared NostrClient set by the service when running
    var nostrClient: NostrClient? = null

    // Shared MapLayerManager set by the service when running
    var mapLayerManager: MapLayerManager? = null

    // Observable OsmAnd connection state
    val osmandConnected = MutableStateFlow(false)

    // Observable relay connection count (updated by service)
    val relayConnectionCount = MutableStateFlow(0)

    override fun onCreate() {
        super.onCreate()

        settings = Settings(this)
        keyStore = KeyStore(settings)
        eventCache = EventCache()

        detectOsmandPackage()
        createServiceNotificationChannel()
    }

    private fun detectOsmandPackage() {
        val candidates = listOf(
            "net.osmand.plus",
            "net.osmand",
            "net.osmand.dev"
        )
        for (pkg in candidates) {
            if (isPackageInstalled(pkg)) {
                settings.osmandPackage = pkg
                return
            }
        }
        // Keep default if none found
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun createServiceNotificationChannel() {
        val channel = NotificationChannel(
            "roadbeacon_service",
            "Roadbeacon Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background monitoring for road events"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
