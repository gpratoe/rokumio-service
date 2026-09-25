package com.rokumio.host

/**
 * A screen of the app. Every screen is a Fragment that knows:
 *  - its drawer title,
 *  - which subset of app state it manages (its `SendPayload`),
 *  - how to explain an empty send.
 *
 * Adding a screen = create a Fragment implementing this, add a drawer menu
 * entry, and register it in MainActivity's fragment registry.
 */
interface ScreenModule {
    /** Screen title shown in the toolbar and the drawer. */
    val titleRes: Int

    /** Whether this screen has anything meaningful to push to Roku. */
    val sendEnabled: Boolean

    /**
     * What this screen would send right now, or null when there is nothing to
     * send (e.g. the server is stopped, no add-ons are queued).
     */
    fun pendingSend(): SendPayload?

    /** Toast resource explaining why `pendingSend()` returned null. */
    fun nothingToSendMessage(): Int
}