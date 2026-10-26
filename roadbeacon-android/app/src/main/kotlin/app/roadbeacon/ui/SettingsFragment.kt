package app.roadbeacon.ui

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.*
import app.roadbeacon.RoadbeaconApplication
import app.roadbeacon.model.EventType
import app.roadbeacon.util.UnitFormatter
import app.roadbeacon.util.UnitSystem

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var app: RoadbeaconApplication

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        app = requireActivity().application as RoadbeaconApplication

        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        // Display
        val displayCategory = PreferenceCategory(context).apply {
            title = "Display"
        }
        screen.addPreference(displayCategory)

        // Relay management
        val relayCategory = PreferenceCategory(context).apply {
            title = "Relays"
        }
        screen.addPreference(relayCategory)

        val relayListPref = Preference(context).apply {
            title = "Manage Relays"
            summary = "${app.settings.relays.size} relay(s) configured"
            setOnPreferenceClickListener {
                showRelayDialog()
                true
            }
        }
        relayCategory.addPreference(relayListPref)

        // Alert preferences
        val alertCategory = PreferenceCategory(context).apply {
            title = "Alerts"
        }
        screen.addPreference(alertCategory)

        val alertEnabled = SwitchPreferenceCompat(context).apply {
            title = "Enable Alerts"
            isChecked = app.settings.alertEnabled
            setOnPreferenceChangeListener { _, newValue ->
                app.settings.alertEnabled = newValue as Boolean
                true
            }
        }
        alertCategory.addPreference(alertEnabled)

        val alertDistance = SeekBarPreference(context).apply {
            title = "Alert Distance"
            min = 100
            max = 2000
            value = app.settings.alertDistance
            summary = UnitFormatter.formatDistanceMeters(app.settings.alertDistance.toDouble(), app.settings.unitSystem)
            setOnPreferenceChangeListener { pref, newValue ->
                val dist = newValue as Int
                app.settings.alertDistance = dist
                pref.summary = UnitFormatter.formatDistanceMeters(dist.toDouble(), app.settings.unitSystem)
                true
            }
        }
        alertCategory.addPreference(alertDistance)

        val alertSound = SwitchPreferenceCompat(context).apply {
            title = "Alert Sound"
            isChecked = app.settings.alertSound
            setOnPreferenceChangeListener { _, newValue ->
                app.settings.alertSound = newValue as Boolean
                true
            }
        }
        alertCategory.addPreference(alertSound)

        val alertVibration = SwitchPreferenceCompat(context).apply {
            title = "Alert Vibration"
            isChecked = app.settings.alertVibration
            setOnPreferenceChangeListener { _, newValue ->
                app.settings.alertVibration = newValue as Boolean
                true
            }
        }
        alertCategory.addPreference(alertVibration)

        // Speed threshold
        val speedCategory = PreferenceCategory(context).apply {
            title = "Query Settings"
        }
        screen.addPreference(speedCategory)

        val speedThreshold = SeekBarPreference(context).apply {
            title = "Speed Threshold"
            summary = speedThresholdSummary(app.settings.querySpeedThreshold)
            min = 40
            max = 150
            value = app.settings.querySpeedThreshold
            setOnPreferenceChangeListener { pref, newValue ->
                val kmh = newValue as Int
                app.settings.querySpeedThreshold = kmh
                pref.summary = speedThresholdSummary(kmh)
                true
            }
        }
        speedCategory.addPreference(speedThreshold)

        // Units selector (added to the Display category at the top of the screen).
        // References the seek bars above so their summaries refresh immediately on change.
        val unitsPref = ListPreference(context).apply {
            key = "unit_system"
            title = "Units"
            entries = arrayOf("Metric (m, km/h)", "Imperial (ft/mi, mph)")
            entryValues = arrayOf("metric", "imperial")
            value = app.settings.unitSystem.name.lowercase()
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                app.settings.unitSystem = UnitSystem.fromStored(newValue as String)
                alertDistance.summary = UnitFormatter.formatDistanceMeters(
                    app.settings.alertDistance.toDouble(), app.settings.unitSystem
                )
                speedThreshold.summary = speedThresholdSummary(app.settings.querySpeedThreshold)
                true
            }
        }
        displayCategory.addPreference(unitsPref)

        // Event type visibility
        val typesCategory = PreferenceCategory(context).apply {
            title = "Visible Event Types"
        }
        screen.addPreference(typesCategory)

        for (type in EventType.entries) {
            val typePref = SwitchPreferenceCompat(context).apply {
                title = type.displayName
                isChecked = app.settings.visibleTypes.contains(type.value)
                setOnPreferenceChangeListener { _, newValue ->
                    val types = app.settings.visibleTypes.toMutableSet()
                    if (newValue as Boolean) {
                        types.add(type.value)
                    } else {
                        types.remove(type.value)
                    }
                    app.settings.visibleTypes = types
                    true
                }
            }
            typesCategory.addPreference(typePref)
        }

        // OsmAnd package
        val osmandCategory = PreferenceCategory(context).apply {
            title = "OsmAnd"
        }
        screen.addPreference(osmandCategory)

        val osmandPkg = ListPreference(context).apply {
            key = "osmand_package"
            title = "OsmAnd Package"
            entries = arrayOf("OsmAnd+", "OsmAnd Free", "OsmAnd Nightly")
            entryValues = arrayOf("net.osmand.plus", "net.osmand", "net.osmand.dev")
            value = app.settings.osmandPackage
            setOnPreferenceChangeListener { _, newValue ->
                app.settings.osmandPackage = newValue as String
                true
            }
        }
        osmandCategory.addPreference(osmandPkg)

        preferenceScreen = screen
    }

    private fun speedThresholdSummary(kmh: Int): String =
        "${UnitFormatter.formatSpeedKmh(kmh, app.settings.unitSystem)} · switch to wider geohash above this speed"

    private fun showRelayDialog() {
        val relays = app.settings.relays.toMutableList()
        val items = relays.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Relays")
            .setItems(items) { _, which ->
                relays.removeAt(which)
                app.settings.relays = relays
            }
            .setPositiveButton("Add") { _, _ ->
                showAddRelayDialog()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAddRelayDialog() {
        val input = EditText(requireContext()).apply {
            hint = "wss://relay.example.com"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Add Relay")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("wss://") && url.length > 8 && !url.contains(" ")) {
                    val relays = app.settings.relays.toMutableList()
                    relays.add(url)
                    app.settings.relays = relays
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
