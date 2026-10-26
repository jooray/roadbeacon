package app.roadbeacon.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.LocationManager
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import app.roadbeacon.R
import app.roadbeacon.RoadbeaconApplication
import app.roadbeacon.databinding.ActivityReportDialogBinding
import app.roadbeacon.model.EventType
import app.roadbeacon.model.RoadEvent
import app.roadbeacon.nostr.EventSigner
import app.roadbeacon.nostr.NostrClient
import kotlinx.coroutines.*

class ReportDialogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportDialogBinding
    private lateinit var app: RoadbeaconApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var selectedType: EventType? = null
    private val typeButtons = mutableListOf<Pair<MaterialButton, EventType>>()
    private var geoLocation: Pair<Double, Double>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportDialogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as RoadbeaconApplication
        geoLocation = parseGeoIntent()

        if (geoLocation != null) {
            binding.txtLocation.text = "Shared location (%.4f\u00b0, %.4f\u00b0)".format(geoLocation!!.first, geoLocation!!.second)
        }

        setupToolbar()
        setupTypeButtons()
        setupSubmit()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Button label with an enlarged emoji on the first line above the type name. */
    private fun typeLabel(type: EventType): CharSequence {
        val emoji = getEmoji(type)
        val text = "$emoji\n${type.displayName}"
        return SpannableString(text).apply {
            setSpan(RelativeSizeSpan(1.8f), 0, emoji.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun getEmoji(type: EventType): String = when (type) {
        EventType.POLICE -> "\uD83D\uDC6E"
        EventType.POLICE_STATION -> "\uD83C\uDFDB\uFE0F"
        EventType.SPEED_CAMERA -> "\uD83D\uDCF7"
        EventType.TRAFFIC_JAM -> "\uD83D\uDE97"
        EventType.ACCIDENT -> "\uD83D\uDCA5"
        EventType.ROAD_CLOSURE -> "\uD83D\uDEAB"
        EventType.CONSTRUCTION -> "\uD83D\uDEA7"
        EventType.HAZARD -> "\u26A0\uFE0F"
        EventType.ROAD_CONDITION -> "\uD83D\uDEE3"
        EventType.POTHOLE -> "\uD83D\uDD73"
        EventType.FOG -> "\uD83C\uDF2B"
        EventType.ICE -> "\uD83E\uDDCA"
        EventType.ANIMAL -> "\uD83E\uDD8C"
        EventType.SURVEILLANCE_CAMERA -> "\uD83C\uDFA5"
        EventType.OTHER -> "\u2139\uFE0F"
    }

    private fun setupTypeButtons() {
        val types = EventType.entries.filter { it != EventType.OTHER }
        val rows = types.chunked(2)
        val density = resources.displayMetrics.density

        for (rowTypes in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
            }

            for ((index, type) in rowTypes.withIndex()) {
                val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = typeLabel(type)
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = (104 * density).toInt()
                    minimumHeight = (104 * density).toInt()
                    cornerRadius = (16 * density).toInt()
                    setPadding(
                        (16 * density).toInt(),
                        (16 * density).toInt(),
                        (16 * density).toInt(),
                        (16 * density).toInt()
                    )
                    textSize = 14f
                    maxLines = 3
                    insetTop = 0
                    insetBottom = 0
                    textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        marginEnd = if (index == 0 && rowTypes.size > 1) (6 * density).toInt() else 0
                        marginStart = if (index == 1) (6 * density).toInt() else 0
                    }
                    setOnClickListener {
                        selectedType = type
                        highlightSelected(type)
                    }
                }
                typeButtons.add(button to type)
                rowLayout.addView(button)
            }

            binding.gridTypes.addView(rowLayout)
        }
    }

    private fun highlightSelected(selected: EventType) {
        val selectedColor = Color.parseColor(selected.colorHex)
        val density = resources.displayMetrics.density
        for ((button, type) in typeButtons) {
            if (type == selected) {
                button.backgroundTintList = ColorStateList.valueOf(selectedColor)
                button.setTextColor(Color.WHITE)
                button.strokeWidth = 0
            } else {
                button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                button.setTextColor(Color.parseColor("#94A3B8"))
                button.strokeWidth = (1 * density).toInt()
                button.strokeColor = ColorStateList.valueOf(Color.parseColor("#334155"))
            }
        }
    }

    private fun parseGeoIntent(): Pair<Double, Double>? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (uri.scheme != "geo") return null
        val ssp = uri.schemeSpecificPart
        val coordPart = ssp.split("?").first()
        val parts = coordPart.split(",")
        if (parts.size < 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return Pair(lat, lon)
    }

    private fun getLastKnownLocation(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        for (provider in providers) {
            try {
                val loc = locationManager.getLastKnownLocation(provider)
                if (loc != null) {
                    return Pair(loc.latitude, loc.longitude)
                }
            } catch (e: Exception) {
                // Try next provider
            }
        }
        return null
    }

    private fun setupSubmit() {
        binding.btnSubmit.setOnClickListener {
            val type = selectedType
            if (type == null) {
                Toast.makeText(this, "Select an event type", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val privKey = app.keyStore.getPrivateKey()
            if (privKey == null) {
                Toast.makeText(this, "No key configured", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val location = geoLocation ?: getLastKnownLocation()
            if (location == null) {
                Toast.makeText(this, "Location not available. Make sure GPS is enabled.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val content = binding.editDescription.text.toString()

            scope.launch {
                try {
                    val event = EventSigner.createReportEvent(
                        type = type,
                        lat = location.first,
                        lon = location.second,
                        privateKey = privKey,
                        content = content
                    )

                    // Add to local cache and map immediately
                    val roadEvent = RoadEvent(
                        id = event.id,
                        pubkey = event.pubkey,
                        type = type,
                        lat = location.first,
                        lon = location.second,
                        createdAt = event.createdAt,
                        expiration = event.createdAt + type.defaultTTL,
                        content = content,
                        geohashes = event.tags.filter { it[0] == "g" }.map { it[1] }
                    )
                    app.eventCache.addReport(roadEvent)
                    app.mapLayerManager?.addOrUpdateEvent(roadEvent)

                    val client = app.nostrClient
                    if (client != null && client.getConnectedCount() > 0) {
                        app.meshBridge?.onOwnEvent(event)
                        client.publish(event) { accepted, total ->
                            scope.launch(Dispatchers.Main) {
                                Toast.makeText(
                                    this@ReportDialogActivity,
                                    "Published to $accepted/$total relays",
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                            }
                        }
                    } else {
                        val tempScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                        val tempClient = NostrClient(tempScope)
                        tempClient.connect(app.settings.relays)
                        delay(3000)
                        app.meshBridge?.onOwnEvent(event)
                        tempClient.publish(event) { accepted, total ->
                            scope.launch(Dispatchers.Main) {
                                Toast.makeText(
                                    this@ReportDialogActivity,
                                    "Published to $accepted/$total relays",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        delay(1000)
                        tempClient.disconnect()
                        tempScope.cancel()
                        withContext(Dispatchers.Main) {
                            finish()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ReportDialogActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
