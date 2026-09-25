package com.rokumio.host

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * Developer screen (dev builds only): the channel-id override used by the ECP
 * launch fallback. Not reachable in production builds, and it has nothing to
 * send to Roku.
 */
class DevFragment : Fragment(), ScreenModule {

    override val titleRes: Int = R.string.dev_title
    override val sendEnabled: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_dev, container, false)
        val prefs = RokuPreferences(requireContext())
        val inputChannelId = view.findViewById<EditText>(R.id.input_channel_id)

        prefs.channelOverride()?.let { inputChannelId.setText(it) }

        view.findViewById<Button>(R.id.btn_apply_channel_id).setOnClickListener {
            val id = inputChannelId.text.toString().trim()
            if (id.isEmpty()) return@setOnClickListener
            prefs.saveChannelOverride(id)
            Toast.makeText(requireContext(), R.string.channel_id_saved, Toast.LENGTH_SHORT).show()
        }
        return view
    }

    override fun pendingSend(): SendPayload? = null

    override fun nothingToSendMessage(): Int = 0
}