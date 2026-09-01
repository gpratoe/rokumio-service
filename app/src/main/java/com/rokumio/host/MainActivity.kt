package com.rokumio.host

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val locator by lazy { ServerLocator(this) }

    // Lifts a persisted read grant so the file the user picked stays readable
    // across launches without needing a storage permission.
    private val importPick = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val ok = locator.importServerJs(uri, contentResolver)
        val msg = if (ok) "server.js imported" else "Failed to import server.js"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnImport = findViewById<Button>(R.id.btn_import)
        val btnToggle = findViewById<Button>(R.id.btn_toggle)
        val textSvState = findViewById<TextView>(R.id.text_svstate)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServerService.running.collect { running ->
                    btnToggle.text =
                        if (running) "Stop server" else "Start server"
                    textSvState.text =
                        if (running) "RUNNING" else "STOPPED"
                    if (running) textSvState.setTextColor(Color.GREEN) else textSvState.setTextColor(Color.RED)

                }
            }
        }
        btnImport.setOnClickListener {
            importPick.launch(arrayOf("*/*"))
        }
        btnToggle.setOnClickListener {
            if (ServerService.running.value) {
                // Stopping the service destroys the spawned Node process, which shuts
                // the server down completely.
                stopService(Intent(this, ServerService::class.java))
            } else {
                startForegroundService(Intent(this, ServerService::class.java))
            }
        }

    }

}
