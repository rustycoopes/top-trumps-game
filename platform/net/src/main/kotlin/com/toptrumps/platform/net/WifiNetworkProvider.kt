package com.toptrumps.platform.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import javax.net.ServerSocketFactory
import javax.net.SocketFactory

/**
 * Obtains the Wi-Fi [Network] via [ConnectivityManager.registerNetworkCallback] — needs only
 * `ACCESS_NETWORK_STATE`, a normal permission — and exposes socket factories bound to it. A
 * phone with Wi-Fi *and* cellular, where the Wi-Fi has no internet, otherwise routes a plain
 * `Socket(host, port)` over cellular and fails; this is the most likely connection failure in an
 * ordinary house. See the Wi-Fi network binding ADR.
 */
public class WifiNetworkProvider(private val connectivityManager: ConnectivityManager) {

    /**
     * Emits every time a Wi-Fi network becomes available — including a re-emit after roaming or
     * reassociation, which kills discovery with no dedicated callback of its own (TDD §8). The
     * caller is expected to tear down and rebuild its lobby state on each new value.
     */
    public fun observeWifiNetwork(): Flow<Network> = callbackFlow {
        // A default NetworkRequest requires NET_CAPABILITY_INTERNET, which the ADR's whole
        // scenario — a Wi-Fi network with no internet — fails by definition. Without removing
        // it, `onAvailable` would never fire for exactly the network this class exists to bind.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(network)
            }
        }
        connectivityManager.registerNetworkCallback(request, callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    /** Already *is* a [SocketFactory] — [Network.getSocketFactory] pins outbound dials to Wi-Fi. */
    public fun socketFactory(network: Network): SocketFactory = network.socketFactory

    /** Binds to the Wi-Fi link address rather than the wildcard address. */
    public fun serverSocketFactory(network: Network): ServerSocketFactory {
        val address = wifiIpv4Address(network)
            ?: error("No IPv4 address on the Wi-Fi network — cannot bind a listening socket")
        return WifiServerSocketFactory(address)
    }

    /** The device's own IPv4 on Wi-Fi, for the manual-connect fallback. No `ACCESS_WIFI_STATE`, no location gate. */
    public fun wifiIpv4Address(network: Network): InetAddress? =
        connectivityManager.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address }
}

private class WifiServerSocketFactory(private val bindAddress: InetAddress) : ServerSocketFactory() {
    override fun createServerSocket(port: Int): ServerSocket = createServerSocket(port, 50)

    override fun createServerSocket(port: Int, backlog: Int): ServerSocket =
        ServerSocket().apply { bind(InetSocketAddress(bindAddress, port), backlog) }

    // Deliberately ignores the caller-supplied `ifAddress`: this factory's entire purpose is
    // binding to the Wi-Fi link address, not whatever the caller happens to pass — no current
    // caller passes a different one, but silently honouring one would defeat the network binding.
    override fun createServerSocket(port: Int, backlog: Int, ifAddress: InetAddress?): ServerSocket =
        createServerSocket(port, backlog)
}
