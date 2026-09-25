package com.rokumio.host

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connect panel: a modal bottom sheet that slides over the current screen to
 * discover Rokus on the LAN (or enter an IP manually) and pick the device the
 * channel will be pushed to. Selection lives in the shared [RokuConnection]
 * state, never persisted — a fresh launch starts disconnected.
 */
class ConnectSheetFragment : BottomSheetDialogFragment() {

    private lateinit var rokuGroup: RadioGroup
    private lateinit var textCurrent: TextView
    private lateinit var textStatus: TextView
    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_connect, container, false)
        rokuGroup = view.findViewById(R.id.roku_group)
        textCurrent = view.findViewById(R.id.text_current)
        textStatus = view.findViewById(R.id.text_status)
        btnScan = view.findViewById(R.id.btn_scan)
        btnDisconnect = view.findViewById(R.id.btn_disconnect)
        val inputManual = view.findViewById<EditText>(R.id.input_manual_ip)

        btnScan.setOnClickListener {
            textStatus.text = getString(R.string.roku_scanning)
            btnScan.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val devices = withContext(Dispatchers.IO) {
                    RokuDiscovery.discover(requireContext())
                }
                btnScan.isEnabled = true
                if (devices.isEmpty()) {
                    textStatus.text = getString(R.string.roku_none)
                } else {
                    textStatus.text = getString(R.string.roku_found, devices.size)
                    for (device in devices) showDevice(device)
                }
            }
        }

        rokuGroup.setOnCheckedChangeListener { _, checkedId ->
            val radio = rokuGroup.findViewById<RadioButton>(checkedId)
            val device = radio?.tag as? RokuDevice
            if (device != null) RokuConnection.select(device)
        }

        view.findViewById<Button>(R.id.btn_use_manual).setOnClickListener {
            val ip = inputManual.text.toString().trim()
            if (ip.isEmpty()) return@setOnClickListener
            val device = RokuDevice(ip)
            showDevice(device)
            RokuConnection.select(device)
        }

        btnDisconnect.setOnClickListener {
            RokuConnection.select(null)
        }

        // Keep this panel's summary + disconnect affordance live.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RokuConnection.device.collect { device ->
                    btnDisconnect.isEnabled = device != null
                    textCurrent.text = if (device == null) {
                        getString(R.string.connect_current_none)
                    } else {
                        getString(R.string.connect_current, device.displayName())
                    }
                }
            }
        }
        return view
    }

    /** Add a radio for one Roku (dedupes by IP) and check it if it's selected. */
    private fun showDevice(device: RokuDevice) {
        val ip = device.ip
        for (i in 0 until rokuGroup.childCount) {
            val existing = rokuGroup.getChildAt(i)
            if (existing is RadioButton && (existing.tag as? RokuDevice)?.ip == ip) {
                if (RokuConnection.device.value?.ip == ip) existing.isChecked = true
                return
            }
        }
        val radio = RadioButton(requireContext())
        radio.text = device.displayName()
        radio.tag = device
        radio.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        rokuGroup.addView(radio)
        if (RokuConnection.device.value?.ip == ip) radio.isChecked = true
    }

    private fun RokuDevice.displayName(): String =
        if (name.isNullOrBlank()) ip else "$name · $ip"
}