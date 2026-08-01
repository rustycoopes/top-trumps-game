package com.toptrumps.platform.net

import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.toptrumps.session.DiscoveryEvent
import com.toptrumps.session.LobbyReducer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public const val SERVICE_TYPE: String = "_toptrumps._tcp"

/** A resolved peer, obtained from exactly one `resolveService` call — TDD §8. */
public data class ResolvedPeerAddress(public val host: String, public val port: Int)

/**
 * Wraps [NsdManager] discovery, translating callbacks into [DiscoveryEvent]s with **zero
 * resolves** — the lobby renders from `onServiceFound` alone, per the instance-name ADR.
 * [resolve] is the one call per game, user-paced, made only when someone taps invite.
 *
 * A fresh [NsdManager.DiscoveryListener] backs every [events] collection — reusing one throws,
 * and `stopServiceDiscovery` is asynchronous (TDD §8).
 */
public class NsdLobbyDiscovery(private val nsdManager: NsdManager) {

    private val rawByInstanceId = ConcurrentHashMap<String, NsdServiceInfo>()

    /** [network] pins discovery to the Wi-Fi link on API 33+, where the `Network`-bound overload exists — see the network-binding ADR. Ignored below API 33. */
    public fun events(network: Network? = null): Flow<DiscoveryEvent> = callbackFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("startDiscovery failed: errorCode=$errorCode"))
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.matchesServiceType()) return
                val parsed = LobbyReducer.parseInstanceName(serviceInfo.serviceName) ?: return
                rawByInstanceId[parsed.instanceId] = serviceInfo
                trySend(DiscoveryEvent.ServiceFound(serviceInfo.serviceName, Clock.System.now()))
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.matchesServiceType()) return
                LobbyReducer.parseInstanceName(serviceInfo.serviceName)?.let { rawByInstanceId.remove(it.instanceId) }
                trySend(DiscoveryEvent.ServiceLost(serviceInfo.serviceName))
            }
        }

        if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val directExecutor = Executor { it.run() }
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, network, directExecutor, listener)
        } else {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }
        awaitClose { nsdManager.stopServiceDiscovery(listener) }
    }

    /**
     * Resolves a peer's host and port. A failed resolve is the most reliable liveness signal
     * available — `onServiceLost` goodbye packets are routinely dropped — so the caller should
     * treat failure as "peer is gone" and remove it, not retry.
     */
    public suspend fun resolve(instanceId: String): ResolvedPeerAddress {
        val serviceInfo = rawByInstanceId[instanceId]
            ?: error("No cached NsdServiceInfo for instance id $instanceId — peer left before invite could resolve")
        return suspendCancellableCoroutine { continuation ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    continuation.resumeWithException(IllegalStateException("resolveService failed: errorCode=$errorCode"))
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    if (host == null) {
                        continuation.resumeWithException(IllegalStateException("resolveService returned no host"))
                    } else {
                        continuation.resume(ResolvedPeerAddress(host, info.port))
                    }
                }
            }
            nsdManager.resolveService(serviceInfo, listener)
        }
    }
}

/** Some Android versions report `serviceType` with a trailing dot — TDD §8. */
private fun NsdServiceInfo.matchesServiceType(): Boolean = serviceType.trimEnd('.') == SERVICE_TYPE
