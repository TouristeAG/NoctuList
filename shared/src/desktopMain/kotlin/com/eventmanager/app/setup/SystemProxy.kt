package com.eventmanager.app.setup

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

object SystemProxy {
    /** The proxy the operating system uses for https, or null when there is none. */
    fun detect(): ProxyInfo? = runCatching {
        System.setProperty("java.net.useSystemProxies", "true")
        val proxy = ProxySelector.getDefault()?.select(URI("https://nodejs.org/"))?.firstOrNull { it.type() == Proxy.Type.HTTP }
        (proxy?.address() as? InetSocketAddress)?.let { ProxyInfo(it.hostString, it.port) }
    }.getOrNull()
}
