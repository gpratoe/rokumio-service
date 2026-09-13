package com.rokumio.host

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Discovers Roku devices on the LAN via SSDP (the Roku ECP discovery mechanism).
 *
 * A Roku with ECP enabled responds to a `ST: roku:ecp` M-SEARCH on the standard
 * SSDP multicast group with a 200-OK whose LOCATION header points at its ECP
 * root (http://<ip>:8060/). We send the M-SEARCH once and collect responses for
 * a short window. Receiving multicast on Android requires a WifiManager
 * MulticastLock; we acquire one for the duration of the scan.
 */
object RokuDiscovery {

    private const val SSDP_GROUP = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SERVICE_TYPE = "roku:ecp"

    /** Runs a scan. Must be called off the main thread. Returns found devices (possibly empty). */
    fun discover(context: Context, timeoutMs: Long = 3000L): List<RokuDevice> {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("rokumio-ssdp")
        lock.setReferenceCounted(false)

        val socket = MulticastSocket()
        try {
            lock.acquire()

            val group = InetAddress.getByName(SSDP_GROUP)
            socket.reuseAddress = true
            socket.networkInterface = wifiInterface() ?: socket.networkInterface
            socket.soTimeout = 500
            socket.joinGroup(group)

            val msearch = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_GROUP:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: $SERVICE_TYPE\r\n" +
                "\r\n"
            val payload = msearch.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(payload, payload.size, group, SSDP_PORT))

            val found = LinkedHashMap<String, RokuDevice>()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val buf = ByteArray(4096)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val location = header(text, "location")
                val host = location?.let { uriHost(it) }
                if (host != null && !found.containsKey(host)) {
                    found[host] = RokuDevice(host, location, friendlyName(location))
                }
            }
            return found.values.toList()
        } catch (e: Exception) {
            android.util.Log.w("RokuDiscovery", "SSDP scan failed: $e")
            return emptyList()
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore.
            }
            if (lock.isHeld) lock.release()
        }
    }

    /** First up, non-loopback interface with a site-local address (i.e. WiFi). */
    private fun wifiInterface(): NetworkInterface? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    if (addrs.nextElement().isSiteLocalAddress) return nif
                }
            }
        } catch (e: Exception) {
            // Fall through to null.
        }
        return null
    }

    /** Read a header value from an SSDP response (case-insensitive name). */
    private fun header(response: String, name: String): String? {
        for (line in response.lines()) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals(name, ignoreCase = true)) {
                return line.substring(idx + 1).trim()
            }
        }
        return null
    }

    private fun uriHost(location: String): String? = try {
        URI(location.trim()).host
    } catch (e: Exception) {
        null
    }

    /** Fetch the ECP Device Description and return its `<friendlyName>`. */
    private fun friendlyName(location: String?): String? {
        if (location == null) return null
        return try {
            val conn = URI(location).toURL().openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            val body = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.disconnect()
                return null
            }
            conn.disconnect()
            val match = NAME_PATTERN.find(body)
            match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private val NAME_PATTERN =
        Regex("(?is)<friendlyName[^>]*>\\s*(.*?)\\s*</friendlyName>")
}