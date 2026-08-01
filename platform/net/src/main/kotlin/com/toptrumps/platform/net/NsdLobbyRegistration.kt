package com.toptrumps.platform.net

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.toptrumps.session.LobbyReducer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Registers this device in the lobby under `<displayName>·<instanceId>`. The port must already
 * be bound by the caller (a [com.toptrumps.session.TcpListener]) and read before calling
 * [register] — `setPort(0)` on the registration itself is rejected. A fresh
 * [NsdManager.RegistrationListener] per registration, matching the discovery-side rule (TDD §8).
 */
public class NsdLobbyRegistration(private val nsdManager: NsdManager) {

    private var activeListener: NsdManager.RegistrationListener? = null

    /** Returns the *actual* registered service name — never assume it matches what was requested; Android auto-renames on collision. */
    public suspend fun register(displayName: String, instanceId: String, port: Int): String {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = LobbyReducer.buildInstanceName(displayName, instanceId)
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        return suspendCancellableCoroutine { continuation ->
            val listener = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    continuation.resumeWithException(IllegalStateException("registerService failed: errorCode=$errorCode"))
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

                override fun onServiceRegistered(info: NsdServiceInfo) {
                    continuation.resume(info.serviceName)
                }
            }
            activeListener = listener
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    /** Idempotent: unregistering without an active registration is a silent no-op. */
    public fun unregister() {
        activeListener?.let { nsdManager.unregisterService(it) }
        activeListener = null
    }
}
