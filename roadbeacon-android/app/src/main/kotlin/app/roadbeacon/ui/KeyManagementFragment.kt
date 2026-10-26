package app.roadbeacon.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import app.roadbeacon.R
import app.roadbeacon.RoadbeaconApplication

class KeyManagementFragment : Fragment() {

    private lateinit var app: RoadbeaconApplication
    private lateinit var txtNpub: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_key_management, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = requireActivity().application as RoadbeaconApplication

        // Set up toolbar navigation
        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        toolbar?.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        txtNpub = view.findViewById(R.id.txt_npub)
        val btnGenerate = view.findViewById<View>(R.id.btn_generate)
        val btnImport = view.findViewById<View>(R.id.btn_import)
        val switchEphemeral = view.findViewById<MaterialSwitch>(R.id.switch_ephemeral)

        updateNpubDisplay()

        btnGenerate.setOnClickListener {
            AlertDialog.Builder(requireContext(), R.style.Theme_Roadbeacon_Dialog)
                .setTitle("Generate New Key")
                .setMessage("This will replace your current key. Make sure you have backed up your nsec!")
                .setPositiveButton("Generate") { _, _ ->
                    app.keyStore.generateKeyPair()
                    updateNpubDisplay()
                    Toast.makeText(requireContext(), "New key generated", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnImport.setOnClickListener {
            val input = EditText(requireContext()).apply {
                hint = "nsec1... or hex private key"
                setTextColor(resources.getColor(R.color.onSurface, null))
                setHintTextColor(resources.getColor(R.color.onSurfaceVariant, null))
                setPadding(32, 32, 32, 32)
            }
            AlertDialog.Builder(requireContext(), R.style.Theme_Roadbeacon_Dialog)
                .setTitle("Import Key")
                .setView(input)
                .setPositiveButton("Import") { _, _ ->
                    try {
                        app.keyStore.importNsec(input.text.toString().trim())
                        updateNpubDisplay()
                        Toast.makeText(requireContext(), "Key imported", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Invalid key: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        switchEphemeral.isChecked = app.settings.useEphemeralKeys
        switchEphemeral.setOnCheckedChangeListener { _, isChecked ->
            app.settings.useEphemeralKeys = isChecked
            Toast.makeText(
                requireContext(), 
                if (isChecked) "Ephemeral mode enabled" else "Ephemeral mode disabled", 
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateNpubDisplay() {
        val npub = app.keyStore.getNpub()
        txtNpub.text = npub?.chunked(16)?.joinToString("\n") ?: "No key configured"
    }
}
