package com.rokumio.host

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Server screen: import server.js, start/stop the streaming server, and see its
 * live status/address. Sends only the server address to Roku — and only when
 * the server is actually running.
 */
class ServerFragment : Fragment(), ScreenModule {

    override val titleRes: Int = R.string.server_title
    override val sendEnabled: Boolean = true

    private lateinit var locator: ServerLocator
    private lateinit var btnToggle: Button
    private lateinit var textSvState: TextView
    private lateinit var textAddr: TextView

    // Lifts a persisted read grant so the file the user picked stays readable
    // across launches without needing a storage permission.
    private val importPick = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val ok = locator.importServerJs(uri, requireContext().contentResolver)
        Toast.makeText(
            requireContext(),
            if (ok) R.string.server_imported else R.string.server_import_failed,
            Toast.LENGTH_SHORT
        ).show()
    }

    // Re-asks for notification permission if it was denied at first launch,
    // since the running-server banner + its Stop action need the grant. Either
    // way the server still starts.
    private val requestNotifStart = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                requireContext(),
                R.string.server_notif_denied,
                Toast.LENGTH_LONG
            ).show()
        }
        startServer()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_server, container, false)
        locator = ServerLocator(requireContext())
        btnToggle = view.findViewById(R.id.btn_toggle)
        textSvState = view.findViewById(R.id.text_svstate)
        textAddr = view.findViewById(R.id.text_addr)

        view.findViewById<Button>(R.id.btn_import).setOnClickListener {
            importPick.launch(arrayOf("*/*"))
        }
        btnToggle.setOnClickListener {
            if (ServerService.running.value) {
                // Stopping the service destroys the spawned Node process.
                requireContext().stopService(Intent(requireContext(), ServerService::class.java))
            } else {
                ensureNotificationPermissionOrStart()
            }
        }
        return view
    }

    /** If the foreground-service notification permission was never granted, ask
     *  for it first; otherwise start the server right away. */
    private fun ensureNotificationPermissionOrStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifStart.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startServer()
        }
    }

    private fun startServer() {
        ContextCompat.startForegroundService(
            requireContext(),
            Intent(requireContext(), ServerService::class.java)
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServerService.running.collect { running ->
                    btnToggle.text = getString(
                        if (running) R.string.server_stop else R.string.server_start
                    )
                    textSvState.text = getString(
                        if (running) R.string.server_running else R.string.server_stopped
                    )
                    textSvState.setTextColor(
                        requireContext().getColor(
                            if (running) R.color.running else R.color.stopped
                        )
                    )
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServerService.address.collect { address ->
                    textAddr.text = address ?: getString(R.string.addr_placeholder)
                }
            }
        }
    }

    override fun pendingSend(): SendPayload? {
        val address = ServerService.address.value ?: return null
        return SendPayload(serverAddress = address)
    }

    override fun nothingToSendMessage(): Int = R.string.server_nothing_to_send
}