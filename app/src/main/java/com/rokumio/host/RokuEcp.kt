package com.rokumio.host

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to the Rokumio Roku channel over port 8060 using three transports that
 * share one contract (marker `contentId` + URL-encoded `rkio` JSON — see the
 * Roku client's reference, reference/ecp-integration.md):
 *
 *  - DIAL `GET /dial/<name>` — per-channel running/stopped probe that works
 *    WITHOUT developer mode (the channel must declare `dial_title` in its
 *    manifest). Also `POST /dial/<name>` for cold-start launches.
 *  - ECP `POST /input` — live delivery to an already-running channel.
 *  - ECP `GET /query/active-app` — foreground-app fallback when the DIAL probe
 *    is unreachable (also needs no developer mode; reports only the FOREGROUND
 *    app). If the channel is in front, deliver live via /input; otherwise
 *    cold-start via /launch.
 *  - ECP `POST /launch/<channelId>` — universal last resort (works for both
 *    `dev` and the published id).
 *
 * A 2xx response only means the OS accepted the request — the channel is the
 * authority on the outcome.
 */
object RokuEcp {

    /** Published (beta) Rokumio app id. */
    const val DEFAULT_CHANNEL_ID = "881630"

    /** Sideloaded developer channel id. */
    const val DEV_CHANNEL_ID = "dev"

    /** The `dial_title` the channel registers with the Roku DIAL server. */
    const val DIAL_APP_NAME = "rokumio"

    private const val ECP_PORT = 8060
    private const val CONTENT_ID = "rokumio-import"

    private val STATE_PATTERN = Regex("(?is)<state[^>]*>\\s*(.*?)\\s*</state>")
    private val ACTIVE_APP_PATTERN =
        Regex("(?is)<active-app[^>]*>\\s*<app\\s+id=\"([^\"]+)\"")

    enum class DialState { RUNNING, STOPPED, UNKNOWN }
    enum class ActiveAppState { RUNNING, STOPPED, UNKNOWN }

    data class Result(val ok: Boolean, val code: Int, val detail: String)

    /**
     * Smart send: probe the channel's DIAL state, then route
     *  - running -> POST /input (live, no relaunch)
     *  - stopped -> DIAL launch (cold start, params in Main)
     *  - unknown -> query/active-app fallback: channel in front -> /input,
     *    otherwise -> /launch/<channelId> (universal last resort)
     */
    fun push(
        rokuIp: String,
        addons: List<String>,
        serverAddress: String?,
        channelId: String = DEFAULT_CHANNEL_ID
    ): Result = when (dialState(rokuIp)) {
        DialState.RUNNING -> input(rokuIp, addons, serverAddress)
        DialState.STOPPED -> dialLaunch(rokuIp, addons, serverAddress)
        DialState.UNKNOWN -> fallback(rokuIp, addons, serverAddress, channelId)
    }

    /**
     * DIAL couldn't report a state, so ask query/active-app (foreground-only)
     * before falling back to cold start: our channel in front -> live /input,
     * anything else -> /launch/<channelId>.
     */
    private fun fallback(
        rokuIp: String,
        addons: List<String>,
        serverAddress: String?,
        channelId: String
    ): Result = when (activeAppState(rokuIp, channelId)) {
        ActiveAppState.RUNNING -> {
            val r = input(rokuIp, addons, serverAddress)
            Result(
                r.ok,
                r.code,
                "DIAL state unavailable on $rokuIp; active-app shows the channel running. ${r.detail}"
            )
        }
        ActiveAppState.STOPPED -> {
            val r = launch(rokuIp, addons, serverAddress, channelId)
            Result(
                r.ok,
                r.code,
                "DIAL state unavailable on $rokuIp and the channel is not in front; cold-starting via ECP launch. ${r.detail}"
            )
        }
        ActiveAppState.UNKNOWN -> {
            val r = launch(rokuIp, addons, serverAddress, channelId)
            Result(
                r.ok,
                r.code,
                "DIAL and active-app status both unreachable on $rokuIp; attempting ECP launch. ${r.detail}"
            )
        }
    }

    /**
     * GET /query/active-app — reports the FOREGROUND app id. Matching the
     * channelId means RUNNING; a readable non-matching app means STOPPED; a
     * non-200 or parse failure means UNKNOWN. Only the foreground app is
     * reported, so an absent id never means "running in background".
     */
    fun activeAppState(rokuIp: String, channelId: String): ActiveAppState {
        val url = "http://$rokuIp:$ECP_PORT/query/active-app"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return ActiveAppState.UNKNOWN
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val match = ACTIVE_APP_PATTERN.find(body)
            if (match != null && match.groupValues[1] == channelId) {
                ActiveAppState.RUNNING
            } else {
                ActiveAppState.STOPPED
            }
        } catch (e: Exception) {
            ActiveAppState.UNKNOWN
        }
    }

    /**
     * GET /dial/<name>. A 200 with `<state>running</state>` means RUNNING; any
     * other readable state (e.g. stopped) means STOPPED; a non-200 or a parse
     * failure means UNKNOWN (the caller then falls back to ECP launch, which
     * works even if the channel is not DIAL-aware).
     */
    fun dialState(rokuIp: String): DialState {
        val url = "http://$rokuIp:$ECP_PORT/dial/$DIAL_APP_NAME"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return DialState.UNKNOWN
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val match = STATE_PATTERN.find(body)
            if (match == null) {
                DialState.UNKNOWN
            } else {
                when (match.groupValues[1].trim().lowercase()) {
                    "running" -> DialState.RUNNING
                    else -> DialState.STOPPED
                }
            }
        } catch (e: Exception) {
            DialState.UNKNOWN
        }
    }

    /**
     * POST /dial/<name> with `contentId=rokumio-import&rkio=<encoded>` as the
     * body. Roku passes those name/value pairs to the channel's Main(params),
     * so the channel routes them through the same handle-deep-link path.
     */
    fun dialLaunch(rokuIp: String, addons: List<String>, serverAddress: String?): Result {
        val url = "http://$rokuIp:$ECP_PORT/dial/$DIAL_APP_NAME"
        val body = "contentId=$CONTENT_ID&rkio=${enc(payload(addons, serverAddress).toString())}"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=\"utf-8\"")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val detail = if (code in 200..299) {
                "Launched $DIAL_APP_NAME on $rokuIp via DIAL (HTTP $code)."
            } else {
                "Roku rejected the DIAL launch (HTTP $code)."
            }
            conn.disconnect()
            Result(code in 200..299, code, detail)
        } catch (e: Exception) {
            Result(false, -1, "Could not reach $rokuIp: ${e.message}")
        }
    }

    /**
     * POST /launch/<channelId>?contentId=rokumio-import&rkio=<encoded>.
     * Cold-start / universal fallback: terminates and relaunches the channel if
     * running, then arranges the import against the fresh scene.
     */
    fun launch(
        rokuIp: String,
        addons: List<String>,
        serverAddress: String?,
        channelId: String = DEFAULT_CHANNEL_ID
    ): Result = post(rokuIp, "launch/$channelId", addons, serverAddress)

    /**
     * POST /input?contentId=rokumio-import&rkio=<encoded>.
     * While-running delivery: no relaunch, no UI flap. Only works if the channel
     * is already running — use launch() when unsure.
     */
    fun input(
        rokuIp: String,
        addons: List<String>,
        serverAddress: String?
    ): Result = post(rokuIp, "input", addons, serverAddress)

    private fun post(
        rokuIp: String,
        httpPath: String,
        addons: List<String>,
        serverAddress: String?
    ): Result {
        val url = "http://$rokuIp:$ECP_PORT/$httpPath?contentId=$CONTENT_ID&rkio=${enc(payload(addons, serverAddress).toString())}"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0) // empty body, like `curl -d ''`
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val code = conn.responseCode
            val detail = if (code in 200..299) {
                "Delivered to $rokuIp (HTTP $code)."
            } else {
                "Roku rejected the request (HTTP $code)."
            }
            conn.disconnect()
            Result(code in 200..299, code, detail)
        } catch (e: Exception) {
            Result(false, -1, "Could not reach $rokuIp: ${e.message}")
        }
    }

    /** Build the strict-JSON payload for schema 1. Omitted fields stay absent. */
    private fun payload(addons: List<String>, serverAddress: String?): JSONObject {
        val json = JSONObject()
        json.put("schema", 1)
        if (addons.isNotEmpty()) {
            json.put("addons", JSONArray(addons))
        }
        if (!serverAddress.isNullOrBlank()) {
            val settings = JSONObject()
            settings.put("serverAddress", serverAddress)
            json.put("settings", settings)
        }
        return json
    }

    /** URL-encode the whole JSON value once (quotes and braces included). */
    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}