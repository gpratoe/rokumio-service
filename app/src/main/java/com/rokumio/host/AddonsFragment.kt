package com.rokumio.host

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * Add-ons screen: maintain the list of manifest URLs to push to the Roku
 * channel. Sends only the add-on list.
 */
class AddonsFragment : Fragment(), ScreenModule {

    override val titleRes: Int = R.string.addons_title
    override val sendEnabled: Boolean = true

    private lateinit var inputAddon: EditText
    private lateinit var addonsContainer: LinearLayout

    private val addons = mutableListOf<String>()
    private val selectedAddons = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_addons, container, false)
        inputAddon = view.findViewById(R.id.input_addon)
        addonsContainer = view.findViewById(R.id.container_addons)
        val btnAdd = view.findViewById<Button>(R.id.btn_add_addon)
        val btnRemove = view.findViewById<Button>(R.id.btn_remove_addon)

        // Restore persisted list (deduping older duplicate entries on load).
        addons.clear()
        addons.addAll(RokuPreferences(requireContext()).addons().distinct())
        renderAddons()

        btnAdd.setOnClickListener {
            val url = inputAddon.text.toString().trim()
            if (!isLikelyManifest(url)) {
                Toast.makeText(requireContext(), R.string.addon_bad, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (addons.contains(url)) {
                Toast.makeText(requireContext(), R.string.addon_duplicate, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addons.add(url)
            RokuPreferences(requireContext()).saveAddons(addons)
            renderAddons()
            inputAddon.text.clear()
        }

        btnRemove.setOnClickListener {
            if (selectedAddons.isEmpty()) {
                Toast.makeText(requireContext(), R.string.addon_none_selected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addons.removeAll(selectedAddons)
            selectedAddons.clear()
            RokuPreferences(requireContext()).saveAddons(addons)
            renderAddons()
            Toast.makeText(requireContext(), R.string.addon_removed, Toast.LENGTH_SHORT).show()
        }
        return view
    }

    /** Rebuild the add-on rows so the card grows to fit every entry. */
    private fun renderAddons() {
        val context = requireContext()
        addonsContainer.removeAllViews()
        for (url in addons) {
            val row = MaterialCheckBox(context)
            row.text = url
            row.textSize = 16f
            row.setTextColor(context.getColor(R.color.text_primary))
            row.isChecked = url in selectedAddons
            row.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedAddons.add(url) else selectedAddons.remove(url)
            }
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addonsContainer.addView(row)
        }
    }

    /** Loose sanity check; the channel re-validates each manifest on install anyway. */
    private fun isLikelyManifest(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.contains("://") && trimmed.contains("manifest.json")
    }

    override fun pendingSend(): SendPayload? =
        if (addons.isEmpty()) null else SendPayload(addons = addons.toList())

    override fun nothingToSendMessage(): Int = R.string.addons_nothing_to_send
}