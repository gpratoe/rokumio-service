package com.rokumio.host

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App shell. Owns the drawer navigation, the shared Roku status + "Send to
 * Rokumio" bar (identical on every screen), and the send dispatch: it asks the
 * active [ScreenModule] for that screen's [SendPayload] subset and pushes only
 * that to the connected Roku. Screens are registered in [fragmentFor] — the
 * single place to add or remove a screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var drawer: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var navView: NavigationView
    private lateinit var btnSend: Button
    private lateinit var textConn: TextView
    private lateinit var dot: View
    private lateinit var chip: View
    private lateinit var btnDonate: MaterialButton

    // One-time request on first launch (Android 13+); the FGS notification that
    // keeps the streaming server visible in the background requires this grant.
    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* outcome is visible in the OS dialog; no follow-up needed here */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawer = findViewById(R.id.drawer_root)
        toolbar = findViewById(R.id.toolbar)
        navView = findViewById(R.id.nav_view)
        btnSend = findViewById(R.id.btn_send)
        textConn = findViewById(R.id.text_roku_conn)
        dot = findViewById(R.id.dot)
        chip = findViewById(R.id.roku_chip)
        btnDonate = findViewById(R.id.btn_donate)

        setSupportActionBar(toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_menu)
        toolbar.setNavigationIconTint(getColor(R.color.text_secondary))
        toolbar.setNavigationOnClickListener {
            drawer.openDrawer(GravityCompat.START)
        }

        if (!BuildConfig.DEV_MODE) {
            navView.menu.removeItem(R.id.nav_dev)
        }
        navView.setNavigationItemSelectedListener { item ->
            navigateTo(item.itemId)
            drawer.closeDrawer(GravityCompat.START)
            true
        }

        chip.setOnClickListener { openConnectSheet() }
        btnSend.setOnClickListener { onSend() }
        btnDonate.setOnClickListener { openDonation() }

        maybeRequestNotificationPermission()

        // Default screen.
        navigateTo(R.id.nav_server)

        // Keep the shared status chip in sync with the live connection.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RokuConnection.device.collect { device -> renderConnection(device) }
            }
        }
    }

    /** Ask for notification permission once, on first run (no-op below API 33). */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = RokuPreferences(this)
        if (prefs.notifAsked()) return
        prefs.saveNotifAsked()
        requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Rebuild the chip: dot color + label. */
    private fun renderConnection(device: RokuDevice?) {
        if (device == null) {
            textConn.text = getString(R.string.roku_conn_none)
            ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(getColor(R.color.stopped)))
        } else {
            textConn.text = getString(R.string.roku_conn, device.displayName())
            ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(getColor(R.color.running)))
        }
    }

    private fun RokuDevice.displayName(): String =
        if (name.isNullOrBlank()) ip else "$name · $ip"

    /** Swap the active screen and sync toolbar title + send visibility. */
    private fun navigateTo(itemId: Int) {
        val fragment = fragmentFor(itemId) ?: return
        val module = fragment as? ScreenModule ?: return
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
        navView.setCheckedItem(itemId)
        toolbar.title = getString(module.titleRes)
        btnSend.visibility = if (module.sendEnabled) View.VISIBLE else View.GONE
    }

    /** Screen registry — the only place screens are wired in. */
    private fun fragmentFor(itemId: Int): Fragment? = when (itemId) {
        R.id.nav_server -> ServerFragment()
        R.id.nav_addons -> AddonsFragment()
        R.id.nav_dev -> if (BuildConfig.DEV_MODE) DevFragment() else null
        else -> null
    }

    /** Slide up the modal connect panel over the current screen. */
    private fun openConnectSheet() {
        ConnectSheetFragment().show(supportFragmentManager, "connect")
    }

    /**
     * Voluntary support link from the drawer footer. Mirrors the Roku client:
     * a short "it's free & open source, a coffee helps" message first, then a
     * Support button that hands off to the browser. Routing follows the client
     * too: Argentina → Cafecito, everywhere else → Buy Me a Coffee.
     */
    private fun openDonation() {
        drawer.closeDrawer(GravityCompat.START)
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_coffee)
            .setTitle(R.string.support_dialog_title)
            .setMessage(R.string.support_dialog_message)
            .setPositiveButton(R.string.support_dialog_confirm) { _, _ -> openDonationUrl() }
            .setNegativeButton(R.string.support_dialog_dismiss, null)
            .show()
    }

    private fun openDonationUrl() {
        val url = if (isArgentina()) {
            "https://cafecito.app/gpratoe"
        } else {
            "https://buymeacoffee.com/gpratoe_"
        }
        try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                    getString(R.string.support_chooser_title)
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.support_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Region detection, layered like the Roku client's GetCountryCode().
     * The user's locale can say one thing while their operator says another
     * (e.g. an English locale device on an Argentine SIM), so we trust the
     * mobile network first, then the SIM, then the locale — no permissions
     * required for any of these.
     */
    private fun isArgentina(): Boolean {
        val telephony = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
        @Suppress("DEPRECATION")
        val iso = listOf(
            telephony?.networkCountryIso,
            telephony?.simCountryIso,
            Locale.getDefault().country
        )
        return iso.any { it != null && it.equals("AR", ignoreCase = true) }
    }

    /** Push only the active screen's changes to the connected Roku. */
    private fun onSend() {
        val module = supportFragmentManager.findFragmentById(R.id.fragment_container)
            as? ScreenModule ?: return
        val device = RokuConnection.device.value
        if (device == null) {
            Toast.makeText(this, R.string.send_select, Toast.LENGTH_SHORT).show()
            openConnectSheet()
            return
        }
        val payload = module.pendingSend()
        if (payload == null) {
            Toast.makeText(this, module.nothingToSendMessage(), Toast.LENGTH_SHORT).show()
            return
        }
        val channelId = if (BuildConfig.DEV_MODE) {
            RokuPreferences(this).channelOverride()?.takeIf { it.isNotBlank() }
                ?: RokuEcp.DEV_CHANNEL_ID
        } else {
            RokuEcp.DEFAULT_CHANNEL_ID
        }
        val ip = device.ip
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                RokuEcp.push(ip, payload, channelId)
            }
            Toast.makeText(
                this@MainActivity,
                result.detail,
                if (result.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
        }
    }
}