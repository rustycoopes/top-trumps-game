package com.toptrumps.app

import android.net.Network
import com.toptrumps.platform.net.NsdLobbyDiscovery
import com.toptrumps.platform.net.NsdLobbyRegistration
import com.toptrumps.platform.net.WifiNetworkProvider
import com.toptrumps.session.DiscoveryEvent
import com.toptrumps.session.InvitationEffect
import com.toptrumps.session.InvitationEvent
import com.toptrumps.session.InvitationResolver
import com.toptrumps.session.InvitationState
import com.toptrumps.session.LobbyMessage
import com.toptrumps.session.LobbyPeer
import com.toptrumps.session.LOBBY_PORT
import com.toptrumps.session.LobbyReducer
import com.toptrumps.session.ProtocolCodec
import com.toptrumps.session.TcpListener
import com.toptrumps.session.TcpTransport
import com.toptrumps.session.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import javax.net.SocketFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Wires the pure [LobbyReducer] and [InvitationResolver] together with real NSD, sockets and a
 * Wi-Fi network — this is the only place in `:app` that owns lobby lifecycle. One instance per
 * visit to the lobby screen; [close] tears it down, the same app-scoped-holder pattern
 * [AppGraph] already uses for a solo [com.toptrumps.session.MatchSession].
 */
public class LobbyController(
    private val displayName: String,
    private val instanceId: String,
    private val discovery: NsdLobbyDiscovery,
    private val registration: NsdLobbyRegistration,
    private val wifiNetworkProvider: WifiNetworkProvider,
    parentScope: CoroutineScope,
) {
    // A child of `parentScope` rather than `parentScope` itself, so closing this controller never
    // cancels the graph-wide scope other components share — matching `startSoloMatch`'s `matchScope`.
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val ioDispatcher = Dispatchers.IO

    private val _peers = MutableStateFlow<List<LobbyPeer>>(emptyList())
    public val peers: StateFlow<List<LobbyPeer>> = _peers.asStateFlow()

    private val _invitation = MutableStateFlow<InvitationState>(InvitationState.Idle)
    public val invitation: StateFlow<InvitationState> = _invitation.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    public val error: StateFlow<String?> = _error.asStateFlow()

    private val _ownAddressHint = MutableStateFlow<String?>(null)
    public val ownAddressHint: StateFlow<String?> = _ownAddressHint.asStateFlow()

    @Volatile
    private var socketFactory: SocketFactory? = null

    /** Restarts registration, discovery and the listen socket on every Wi-Fi network change — roaming kills discovery with no callback (TDD §8). */
    public fun start() {
        scope.launch {
            wifiNetworkProvider.observeWifiNetwork()
                .catch { _error.value = it.message ?: "Could not observe the Wi-Fi network" }
                .collectLatest { network ->
                    _error.value = null
                    try {
                        setupForNetwork(network)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        _error.value = t.message ?: "Could not start the lobby"
                    }
                }
        }
        scope.launch { tickLoop() }
    }

    public fun invite(peer: LobbyPeer) {
        scope.launch {
            val factory = socketFactory
            if (factory == null) {
                _error.value = "No Wi-Fi network yet"
                return@launch
            }
            _error.value = null
            try {
                val address = discovery.resolve(peer.instanceId)
                val transport = TcpTransport.connect(factory, address.host, address.port, scope, ioDispatcher)
                attachMessageListener(transport)
                closeIfTapInviteWasIgnored(transport, applyInvitation(InvitationEvent.TapInvite(peer, transport, Clock.System.now())))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // A failed resolve/connect is the most reliable liveness signal available — drop the peer rather than retry.
                _peers.value = _peers.value.filterNot { it.instanceId == peer.instanceId }
                _error.value = "Couldn't reach ${peer.displayName} — they may have left."
            }
        }
    }

    /** The manual-connect fallback (story 10) — no discovered [LobbyPeer], so the peer's identity is learned only once they answer. */
    public fun connectManually(host: String, port: Int = LOBBY_PORT) {
        scope.launch {
            val factory = socketFactory
            if (factory == null) {
                _error.value = "No Wi-Fi network yet"
                return@launch
            }
            _error.value = null
            try {
                val transport = TcpTransport.connect(factory, host, port, scope, ioDispatcher)
                attachMessageListener(transport)
                val placeholder = LobbyPeer(instanceId = "", displayName = host, lastSeenAt = Clock.System.now())
                val event = InvitationEvent.TapInvite(placeholder, transport, Clock.System.now())
                closeIfTapInviteWasIgnored(transport, applyInvitation(event))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _error.value = "Couldn't connect to $host:$port"
            }
        }
    }

    /**
     * `invite()`/`connectManually()` dial before applying `TapInvite`, so an inbound invite can
     * race in and flip local state away from `Idle` during that gap — [InvitationResolver] then
     * ignores `TapInvite` with an empty effects list, and nobody else owns this transport. Close
     * it here rather than leak the socket and its [attachMessageListener] collector.
     */
    private fun closeIfTapInviteWasIgnored(transport: Transport, resultingState: InvitationState) {
        val ours = when (resultingState) {
            is InvitationState.OutboundPending -> resultingState.transport === transport
            is InvitationState.Connected -> resultingState.transport === transport
            else -> false
        }
        if (!ours) {
            detachMessageListener(transport)
            transport.close()
        }
    }

    public fun cancelOutbound() { applyInvitation(InvitationEvent.CancelOutbound) }
    public fun acceptInbound() { applyInvitation(InvitationEvent.AcceptInbound) }
    public fun declineInbound() { applyInvitation(InvitationEvent.DeclineInbound) }
    public fun leave() { applyInvitation(InvitationEvent.Leave) }

    /** Cancels every coroutine this controller started — network observation, discovery, the listen socket, registration. */
    public fun close() {
        scope.cancel()
    }

    private suspend fun setupForNetwork(network: Network) {
        val boundListener =
            TcpListener.bind(wifiNetworkProvider.serverSocketFactory(network), scope, ioDispatcher, LOBBY_PORT)
        try {
            socketFactory = wifiNetworkProvider.socketFactory(network)
            _ownAddressHint.value = wifiNetworkProvider.wifiIpv4Address(network)?.hostAddress
            registration.register(displayName, instanceId, boundListener.localPort)

            coroutineScope {
                launch { boundListener.connections.collect { transport -> attachMessageListener(transport) } }
                launch {
                    discovery.events(network).collect { event ->
                        _peers.value = LobbyReducer.reduce(_peers.value, event, instanceId)
                    }
                }
            }
        } finally {
            socketFactory = null
            registration.unregister()
            boundListener.stop()
        }
    }

    private suspend fun tickLoop() {
        while (true) {
            delay(5.seconds)
            val now = Clock.System.now()
            _peers.value = LobbyReducer.reduce(_peers.value, DiscoveryEvent.Tick(now), instanceId)
            applyInvitation(InvitationEvent.Tick(now))
        }
    }

    /**
     * One listener per lobby-phase transport, tracked so it can be torn down the moment that
     * transport stops being a lobby socket. Without this, a transport that resolves to
     * [InvitationState.Connected] would keep being read here *and* by whatever reads it next
     * (e.g. [MatchController]'s handshake and match session) — [Transport.incoming] is a plain
     * `Channel`-backed flow, not a broadcast, so two concurrent collectors race for every frame
     * and each one only ever sees the frames it happens to win.
     */
    private val lobbyListeners = mutableMapOf<Transport, Job>()

    private fun attachMessageListener(transport: Transport) {
        lobbyListeners[transport] = scope.launch {
            transport.incoming.collect { bytes ->
                val message = runCatching { ProtocolCodec.decodeLobbyMessage(bytes) }.getOrNull() ?: return@collect
                val event = when (message) {
                    is LobbyMessage.Invite -> InvitationEvent.InboundInviteArrived(
                        LobbyPeer(message.fromInstanceId, message.fromDisplayName, Clock.System.now()),
                        transport,
                    )
                    is LobbyMessage.InviteAccept ->
                        if (transportMatchesPending(transport)) InvitationEvent.OutboundAccepted else null
                    is LobbyMessage.InviteDecline ->
                        if (transportMatchesPending(transport)) InvitationEvent.OutboundDeclined(message.reason) else null
                    is LobbyMessage.InviteCancel ->
                        if (transportMatchesPending(transport)) InvitationEvent.InboundCancelled else null
                }
                if (event != null) applyInvitation(event)
            }
        }
    }

    /** Stops reading [transport] as a lobby socket — called once it's either connected (ownership moves to [MatchController]) or closed (nothing left to read). */
    private fun detachMessageListener(transport: Transport) {
        lobbyListeners.remove(transport)?.cancel()
    }

    private fun transportMatchesPending(transport: Transport): Boolean = when (val state = _invitation.value) {
        is InvitationState.OutboundPending -> state.transport === transport
        is InvitationState.InboundPrompt -> state.transport === transport
        else -> false
    }

    /**
     * Effects for one event run in one coroutine, in order — a `Send` that came before a
     * `CloseTransport` (every cancel/decline/BUSY path pairs the two, courtesy message first)
     * must actually be written before the socket closes. Firing them off independently races
     * `close()`'s `outgoing.close()` against the not-yet-started send, which would throw
     * `ClosedSendChannelException` on the channel and could drop the courtesy message entirely.
     */
    private fun applyInvitation(event: InvitationEvent): InvitationState {
        val result = InvitationResolver.resolve(_invitation.value, event, instanceId, displayName)
        _invitation.value = result.state
        // Connected means a match may start reading this same transport next; closed means
        // there's nothing left to read. Either way, this controller must stop listening now.
        val newState = result.state
        if (newState is InvitationState.Connected) detachMessageListener(newState.transport)
        scope.launch {
            result.effects.forEach { effect ->
                when (effect) {
                    is InvitationEffect.Send ->
                        runCatching { effect.transport.send(ProtocolCodec.encodeLobbyMessage(effect.message)) }
                    is InvitationEffect.CloseTransport -> {
                        detachMessageListener(effect.transport)
                        effect.transport.close()
                    }
                }
            }
        }
        return result.state
    }
}
