package com.rokumio.host

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

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

        findViewById<Button>(R.id.btn_import).setOnClickListener {
            importPick.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.btn_run).setOnClickListener {
            startForegroundService(Intent(this, ServerService::class.java))
        }
        // Stopping the service destroys the spawned Node process, which shuts
        // the server down completely.
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            stopService(Intent(this, ServerService::class.java))
        }
    }
}
