package com.toptrumps.app

import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import com.toptrumps.platform.net.NsdLobbyDiscovery
import com.toptrumps.platform.net.NsdLobbyRegistration
import com.toptrumps.platform.net.WifiNetworkProvider
import com.toptrumps.session.DiscoveryEvent
import com.toptrumps.session.GuestToHost
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val multicastLock: WifiManager.MulticastLock,
    parentScope: CoroutineScope,
    /** A fresh inbound connection whose first frame is a resume rather than a lobby message — see the reconnect-resync ADR. Default no-op so a caller with no live match never needs to care. */
    private val onResumeAttempt: suspend (Transport, GuestToHost.Resume) -> Unit = { _, _ -> },
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

    /** The socket factory bound to the current Wi-Fi network, if one is up — `null` between networks. Lets a guest's reconnect coordinator redial directly without duplicating [setupForNetwork]'s network tracking. */
    public fun currentSocketFactory(): SocketFactory? = socketFactory

    private var started = false

    /** Well under [LobbyReducer]'s 30s peer TTL — see the discovery-refresh note on [setupForNetwork]. */
    private val discoveryRefreshInterval = 15.seconds

    /**
     * Restarts registration, discovery and the listen socket on every Wi-Fi network change —
     * roaming kills discovery with no callback (TDD §8). Idempotent: this controller is now
     * Application-scoped (see [AppGraph.lobbyController]) and re-fetched, not recreated, across
     * an Activity recreation — a second `start()` from the new Activity's recomposition must not
     * launch a second copy of every loop below.
     */
    public fun start() {
        if (started) return
        started = true
        // Some OEMs filter multicast frames once Wi-Fi has been idle a while, silently dropping
        // mDNS packets — held for the controller's whole life, not just discovery, since our own
        // registration announcements are just as vulnerable. See the NSD reliability ADR.
        multicastLock.acquire()
        scope.launch {
            Log.d("TTHandshake", "LobbyController.start(): observing Wi-Fi network")
            wifiNetworkProvider.observeWifiNetwork()
                .catch {
                    Log.d("TTHandshake", "LobbyController: observeWifiNetwork error: ${it.message}")
                    _error.value = it.message ?: "Could not observe the Wi-Fi network"
                }
                .collectLatest { network ->
                    Log.d("TTHandshake", "LobbyController: got Wi-Fi network=$network")
                    _error.value = null
                    try {
                        setupForNetwork(network)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        Log.d("TTHandshake", "LobbyController: setupForNetwork failed: ${t::class.simpleName}: ${t.message}")
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
        if (multicastLock.isHeld) multicastLock.release()
    }

    private suspend fun setupForNetwork(network: Network) {
        val boundListener =
            TcpListener.bind(wifiNetworkProvider.serverSocketFactory(network), scope, ioDispatcher, LOBBY_PORT)
        Log.d("TTHandshake", "LobbyController.setupForNetwork: bound listen socket on port ${boundListener.localPort}")
        try {
            socketFactory = wifiNetworkProvider.socketFactory(network)
            _ownAddressHint.value = wifiNetworkProvider.wifiIpv4Address(network)?.hostAddress
            Log.d("TTHandshake", "LobbyController.setupForNetwork: own address hint = ${_ownAddressHint.value}")
            registration.register(displayName, instanceId, boundListener.localPort)

            coroutineScope {
                launch {
                    boundListener.connections.collect { transport ->
                        Log.d("TTHandshake", "LobbyController: accepted inbound connection transport=${transport.hashCode()}")
                        attachMessageListener(transport)
                    }
                }
                launch {
                    // `onServiceFound` fires once per browse session and never repeats for an
                    // unchanged peer — confirmed live, a peer sat in view for minutes with no
                    // repeat event. The TDD's own TTL design calls for "refresh on
                    // re-announcement" (§8), but nothing produces one without this: restarting the
                    // browse periodically, well under `LobbyReducer`'s 30s TTL, is what makes
                    // Tick's pruning mean "peer is gone" rather than "peer aged out of view mid-match."
                    while (true) {
                        withTimeoutOrNull(discoveryRefreshInterval) {
                            discovery.events(network).collect { event ->
                                _peers.value = LobbyReducer.reduce(_peers.value, event, instanceId)
                                Log.d("TTHandshake", "LobbyController: peers now = ${_peers.value.map { it.displayName + "/" + it.instanceId }}")
                            }
                        }
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
        Log.d("TTHandshake", "LobbyController: attach listener on transport=${transport.hashCode()}")
        val job = scope.launch {
            // A manual pull loop, not `.collect`, so the loop itself decides whether to ask the
            // flow for another frame — checked only *after* fully processing the current one. If
            // this used `.collect` plus `detachMessageListener`'s `job.cancel()` to stop, the
            // frame that arrives the instant this listener transitions the transport to Connected
            // (e.g. the guest's Hello, sent right after receiving InviteAccept) can already be
            // sitting in the channel by then — cancellation is cooperative, so `.collect` can pull
            // and silently discard that frame here before the cancellation takes effect, and
            // MatchController's handshake then waits forever for a Hello that already came and
            // went. Breaking on our own synchronous check instead means we simply never ask.
            while (true) {
                // `firstOrNull`, not `first` — the transport can close (peer dropped, resolve
                // failed) while this is waiting on the next frame, which completes `incoming` with
                // zero elements. `first` treats that as an error and throws `NoSuchElementException`
                // instead of just ending; `firstOrNull` reports it as `null` so this can exit cleanly.
                val bytes = transport.incoming.firstOrNull() ?: break
                val message = runCatching { ProtocolCodec.decodeLobbyMessage(bytes) }.getOrNull()
                if (message == null) {
                    // Not a lobby message — the one other legitimate thing to arrive on a fresh
                    // connection is a resume from a guest whose original socket died (the
                    // reconnect-resync ADR); anything else is a corrupt or hostile frame, dropped.
                    val resume = runCatching { ProtocolCodec.decodeGuestToHost(bytes) }.getOrNull() as? GuestToHost.Resume
                    if (resume != null) {
                        detachMessageListener(transport)
                        onResumeAttempt(transport, resume)
                    }
                } else {
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
                if (lobbyListeners[transport] == null) break
            }
        }
        lobbyListeners[transport] = job
    }

    /** Stops reading [transport] as a lobby socket — called once it's either connected (ownership moves to [MatchController]) or closed (nothing left to read). */
    private fun detachMessageListener(transport: Transport) {
        val removed = lobbyListeners.remove(transport)
        Log.d("TTHandshake", "LobbyController: detach listener on transport=${transport.hashCode()} (was attached=${removed != null})")
        removed?.cancel()
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
        val before = _invitation.value
        val result = InvitationResolver.resolve(before, event, instanceId, displayName)
        Log.d(
            "TTHandshake",
            "LobbyController: ${before::class.simpleName} + ${event::class.simpleName} -> ${result.state::class.simpleName}" +
                (if (result.state is InvitationState.Connected) " transport=${(result.state as InvitationState.Connected).transport.hashCode()} role=${(result.state as InvitationState.Connected).role}" else ""),
        )
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
