package com.rokumio.host

/**
 * The subset of app state a screen wants to push to the Roku channel. Null
 * fields are omitted from the deep-link JSON, so a screen only ever sends its
 * own changes: the server screen sends just the address, the add-ons screen
 * sends just the add-on list. The channel import is additive (it never clears),
 * so partial payloads are safe.
 */
data class SendPayload(
    /** Manifest URLs to install, or null to leave the channel's add-ons alone. */
    val addons: List<String>? = null,
    /** Server baseUrl to link, or null to leave the channel's setting alone. */
    val serverAddress: String? = null
)