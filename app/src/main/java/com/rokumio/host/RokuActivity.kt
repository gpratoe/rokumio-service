package com.rokumio.host

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Companion screen: discover a Roku over ECP, choose which add-on manifests to
 * install, and push them (plus the running server's address) to the Rokumio
 * channel with a single ECP launch. The channel is the authority on the result;
 * a 2xx here only means the OS accepted the request.
 */
class RokuActivity : AppCompatActivity() {

    private val prefs by lazy { RokuPreferences(this) }

    private lateinit var rokuGroup: RadioGroup
    private lateinit var addonsAdapter: ArrayAdapter<String>
    private lateinit var textServerAddr: TextView
    private lateinit var textSendStatus: TextView

    private val addons = mutableListOf<String>()
    private var selectedAddonIndex = -1
    private var selectedIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_roku)
        setTitle(R.string.roku_activity_title)

        val btnScan = findViewById<Button>(R.id.btn_scan)
        val textRokuStatus = findViewById<TextView>(R.id.text_roku_status)
        val inputManualIp = findViewById<EditText>(R.id.input_manual_ip)
        val btnUseManual = findViewById<Button>(R.id.btn_use_manual)
        val inputAddon = findViewById<EditText>(R.id.input_addon)
        val btnAddAddon = findViewById<Button>(R.id.btn_add_addon)
        val btnRemoveAddon = findViewById<Button>(R.id.btn_remove_addon)
        val listAddons = findViewById<ListView>(R.id.list_addons)
        val btnSend = findViewById<Button>(R.id.btn_send)
        rokuGroup = findViewById(R.id.roku_group)

        // Dev-only channel-id override (hidden in production builds).
        val channelIdSection = findViewById<View>(R.id.channel_id_section)
        if (!BuildConfig.DEV_MODE) {
            channelIdSection.visibility = View.GONE
        } else {
            val inputChannelId = findViewById<EditText>(R.id.input_channel_id)
            val btnApplyChannelId = findViewById<Button>(R.id.btn_apply_channel_id)
            prefs.channelOverride()?.let { inputChannelId.setText(it) }
            btnApplyChannelId.setOnClickListener {
                val id = inputChannelId.text.toString().trim()
                if (id.isEmpty()) return@setOnClickListener
                prefs.saveChannelOverride(id)
                Toast.makeText(this, R.string.channel_id_saved, Toast.LENGTH_SHORT).show()
            }
        }

        // Restore persisted state.
        addons.clear()
        addons.addAll(prefs.addons())
        addonsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, addons)
        listAddons.adapter = addonsAdapter
        prefs.lastRokuIp()?.let {
            selectedIp = it
            showSelectableRoku(it)
        }

        // The address that will be sent, live from the running server.
        textServerAddr = findViewById(R.id.text_server_addr)
        textSendStatus = findViewById(R.id.text_send_status)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServerService.address.collect { address ->
                    textServerAddr.text = if (address != null) {
                        getString(R.string.server_will_send, address)
                    } else {
                        getString(R.string.server_not_running)
                    }
                }
            }
        }

        btnScan.setOnClickListener {
            textRokuStatus.text = getString(R.string.roku_scanning)
            btnScan.isEnabled = false
            lifecycleScope.launch {
                val devices = withContext(Dispatchers.IO) {
                    RokuDiscovery.discover(this@RokuActivity)
                }
                btnScan.isEnabled = true
                if (devices.isEmpty()) {
                    textRokuStatus.text = getString(R.string.roku_none)
                } else {
                    textRokuStatus.text = getString(R.string.roku_found, devices.size)
                    for (device in devices) showSelectableRoku(device.ip)
                }
            }
        }

        rokuGroup.setOnCheckedChangeListener { _, checkedId ->
            val radio = findViewById<RadioButton>(checkedId)
            if (radio != null) {
                selectedIp = radio.tag as? String
                prefs.saveLastRokuIp(selectedIp)
            }
        }

        btnUseManual.setOnClickListener {
            val ip = inputManualIp.text.toString().trim()
            if (ip.isEmpty()) return@setOnClickListener
            showSelectableRoku(ip)
            selectedIp = ip
            prefs.saveLastRokuIp(ip)
        }

        btnAddAddon.setOnClickListener {
            val url = inputAddon.text.toString().trim()
            if (!isLikelyManifest(url)) {
                Toast.makeText(this, R.string.addon_bad, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addons.add(url)
            prefs.saveAddons(addons)
            addonsAdapter.notifyDataSetChanged()
            inputAddon.text.clear()
        }

        listAddons.setOnItemClickListener { _, _, position, _ ->
            selectedAddonIndex = position
        }

        btnRemoveAddon.setOnClickListener {
            if (selectedAddonIndex in addons.indices) {
                addons.removeAt(selectedAddonIndex)
                prefs.saveAddons(addons)
                addonsAdapter.notifyDataSetChanged()
                selectedAddonIndex = -1
                Toast.makeText(this, R.string.addon_removed, Toast.LENGTH_SHORT).show()
            }
        }

        btnSend.setOnClickListener {
            val ip = selectedIp
            if (ip == null) {
                textSendStatus.text = getString(R.string.send_select)
                return@setOnClickListener
            }
            textSendStatus.text = getString(R.string.send_sending)
            val addonsToSend = addons.toList()
            val serverAddress = ServerService.address.value
            val channelId = if (BuildConfig.DEV_MODE) {
                prefs.channelOverride()?.takeIf { it.isNotBlank() } ?: RokuEcp.DEV_CHANNEL_ID
            } else {
                RokuEcp.DEFAULT_CHANNEL_ID
            }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    RokuEcp.push(ip, addonsToSend, serverAddress, channelId)
                }
                textSendStatus.text = result.detail
                if (result.ok) {
                    Toast.makeText(
                        this@RokuActivity,
                        result.detail,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /** Add a radio for one Roku (dedupes by IP) and check it if it's already selected. */
    private fun showSelectableRoku(ip: String) {
        for (i in 0 until rokuGroup.childCount) {
            val existing = rokuGroup.getChildAt(i)
            if (existing is RadioButton && existing.tag == ip) {
                if (selectedIp == ip) existing.isChecked = true
                return
            }
        }
        val radio = RadioButton(this)
        radio.text = ip
        radio.tag = ip
        radio.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        rokuGroup.addView(radio)
        if (selectedIp == ip) radio.isChecked = true
    }

    /** Loose sanity check; the channel re-validates each manifest on install anyway. */
    private fun isLikelyManifest(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.contains("://") && trimmed.contains("manifest.json")
    }
}