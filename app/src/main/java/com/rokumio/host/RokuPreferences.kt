package com.rokumio.host

import android.content.Context
import org.json.JSONArray

/** Persists companion state: the add-on manifest URL list and the last Roku picked. */
class RokuPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("rokumio", Context.MODE_PRIVATE)

    fun addons(): List<String> {
        val raw = prefs.getString(KEY_ADDONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAddons(addons: List<String>) {
        prefs.edit().putString(KEY_ADDONS, JSONArray(addons).toString()).apply()
    }

    /** Dev-only override for the channel id used by the ECP launch fallback. */
    fun channelOverride(): String? = prefs.getString(KEY_CHANNEL_ID, null)

    fun saveChannelOverride(id: String) {
        prefs.edit().putString(KEY_CHANNEL_ID, id).apply()
    }

    /** Whether the POST_NOTIFICATIONS permission prompt has been shown before. */
    fun notifAsked(): Boolean = prefs.getBoolean(KEY_NOTIF_ASKED, false)

    fun saveNotifAsked() {
        prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
    }

    private companion object {
        const val KEY_ADDONS = "addons"
        const val KEY_CHANNEL_ID = "channel_id"
        const val KEY_NOTIF_ASKED = "notif_asked"
    }
}