package com.rokumio.host

/** A Roku device reachable via ECP on the local network. */
data class RokuDevice(
    /** IPv4 address, e.g. "192.168.1.50". */
    val ip: String,
    /** SSDP LOCATION, e.g. "http://192.168.1.50:8060/". Optional (manual entries). */
    val location: String? = null
)