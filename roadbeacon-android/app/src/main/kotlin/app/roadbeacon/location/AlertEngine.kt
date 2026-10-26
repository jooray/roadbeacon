package app.roadbeacon.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import app.roadbeacon.model.RoadEvent
import app.roadbeacon.storage.EventCache
import app.roadbeacon.storage.Settings
import app.roadbeacon.util.UnitFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

class AlertEngine(
    private val context: Context,
    private val settings: Settings,
    private val eventCache: EventCache
) {

    private val alertedEvents = ConcurrentHashMap<String, Long>() // eventId -> last alert time
    private val notifiedEventIds = ConcurrentHashMap.newKeySet<String>() // Events that have been notified (never notify again)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notificationId = AtomicInteger(1000)

    companion object {
        private const val TAG = "AlertEngine"
        private const val COOLDOWN_MS = 600_000L // 10 minutes
        private const val CHANNEL_ID = "roadbeacon_alerts"
        private const val BEARING_THRESHOLD = 45.0 // degrees
    }

    init {
        createNotificationChannel()
    }

    fun checkProximity(location: LocationUpdate) {
        if (!settings.alertEnabled) return

        val now = System.currentTimeMillis()
        val activeEvents = eventCache.getAllActiveReports()

        for (event in activeEvents) {
            if (notifiedEventIds.contains(event.id)) continue
            if (!settings.visibleTypes.contains(event.type.value)) continue

            // Check cooldown
            val lastAlert = alertedEvents[event.id]
            if (lastAlert != null && now - lastAlert < COOLDOWN_MS) continue

            // Check distance
            val distance = haversine(location.lat, location.lon, event.lat, event.lon)
            if (distance > settings.alertDistance) continue

            // Check direction (event must be ahead)
            if (location.speed > 2f) { // Only check bearing if moving
                val bearing = bearingTo(location.lat, location.lon, event.lat, event.lon)
                val bearingDiff = normalizeBearing(bearing - location.bearing)
                if (abs(bearingDiff) > BEARING_THRESHOLD) continue
            }

            // Trigger alert
            triggerAlert(event, distance)
            alertedEvents[event.id] = now
            notifiedEventIds.add(event.id)
        }

        // Cleanup old cooldowns
        alertedEvents.entries.removeIf { now - it.value > COOLDOWN_MS * 2 }
    }

    private fun triggerAlert(event: RoadEvent, distance: Double) {
        Log.d(TAG, "Alert: ${event.type.displayName} at ${distance.toInt()}m")

        if (settings.alertSound) {
            playSound()
        }
        if (settings.alertVibration) {
            vibrate()
        }

        showNotification(event, distance)
    }

    private fun playSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing sound: ${e.message}")
        }
    }

    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.e(TAG, "Error vibrating: ${e.message}")
        }
    }

    private fun showNotification(event: RoadEvent, distance: Double) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("${event.type.displayName} ahead")
            .setContentText("${UnitFormatter.formatDistanceMeters(distance, settings.unitSystem)} away")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId.getAndIncrement(), notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Road Event Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for nearby road events"
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
    }
}

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0 // Earth radius in meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
}

fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLon = Math.toRadians(lon2 - lon1)
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val y = sin(dLon) * cos(lat2Rad)
    val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

fun normalizeBearing(bearing: Double): Double {
    var b = bearing % 360
    if (b > 180) b -= 360
    if (b < -180) b += 360
    return b
}
