package com.toptrumps.session

import com.toptrumps.rules.Deck
import com.toptrumps.rules.MatchConfig
import com.toptrumps.rules.MatchState
import com.toptrumps.rules.PlayerIntent
import com.toptrumps.rules.RulesEngine
import com.toptrumps.rules.Seat
import com.toptrumps.rules.StepResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlin.random.Random

/**
 * A player's side of one match. No hardcoded `Dispatchers.*`: every implementation runs its
 * coroutines on the [CoroutineScope] its caller supplies, which is what makes slice 6's
 * virtual-time tests possible later without a signature change now.
 */
public interface MatchSession {
    /** `null` only for the guest before its first view has arrived. */
    public val view: StateFlow<MatchView?>
    public fun submit(intent: PlayerIntent)
    public fun close()
}

/**
 * Runs [RulesEngine] against the authoritative [MatchState] and pushes a [MatchView] to the
 * guest after every transition. The host's own UI is served by [RulesEngine.project] directly —
 * it never sees [MatchState] either, per the structural-redaction ADR.
 */
public class HostMatchSession(
    deck: Deck,
    config: MatchConfig,
    random: Random,
    private val transport: Transport,
    private val scope: CoroutineScope,
) : MatchSession {

    private var state: MatchState = RulesEngine.deal(deck, config, random)

    private val _view = MutableStateFlow(RulesEngine.project(state, Seat.HOST).toMatchView())
    override val view: StateFlow<MatchView?> = _view.asStateFlow()

    init {
        scope.launch { pushGuestView() }
        scope.launch {
            transport.incoming.collect { bytes ->
                applyIntent(Seat.GUEST, ProtocolCodec.decodeIntent(bytes))
            }
        }
    }

    override fun submit(intent: PlayerIntent) {
        // Routed through `scope`, same as the guest-intent path below, rather than mutating
        // `state` synchronously on the caller's thread — the TDD requires all state mutation
        // confined to a single dispatcher. It's the caller's job to supply a confined `scope`
        // (:core:session cannot pin one itself without a hardcoded `Dispatchers.*` literal).
        scope.launch { applyIntent(Seat.HOST, intent) }
    }

    private fun applyIntent(seat: Seat, intent: PlayerIntent) {
        when (val result = RulesEngine.apply(state, seat, intent)) {
            is StepResult.Applied -> {
                state = result.state
                _view.value = RulesEngine.project(state, Seat.HOST).toMatchView()
                scope.launch { pushGuestView() }
            }
            is StepResult.Rejected -> Unit // slice 1 has no error channel back to the sender yet.
        }
    }

    private suspend fun pushGuestView() {
        val guestView = RulesEngine.project(state, Seat.GUEST).toMatchView()
        transport.send(ProtocolCodec.encodeView(guestView))
    }

    override fun close() {
        transport.close()
    }
}

/** The guest never runs [RulesEngine] — every [MatchView] it shows came from the host over the wire. */
public class GuestMatchSession(
    private val transport: Transport,
    private val scope: CoroutineScope,
) : MatchSession {

    private val _view = MutableStateFlow<MatchView?>(null)
    override val view: StateFlow<MatchView?> = _view.asStateFlow()

    init {
        scope.launch {
            transport.incoming.collect { bytes ->
                _view.value = ProtocolCodec.decodeView(bytes)
            }
        }
    }

    override fun submit(intent: PlayerIntent) {
        scope.launch { transport.send(ProtocolCodec.encodeIntent(intent)) }
    }

    override fun close() {
        transport.close()
    }
}
