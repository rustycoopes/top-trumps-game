package com.toptrumps.session

import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.PlayerIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * Records every frame that passes through [incoming] without altering behaviour — used to capture
 * every frame the host sends to the guest across a full match, per the WBS's frame-level leak
 * assertion. A test utility with no decision-making of its own, not a double.
 */
internal class RecordingTransport(private val delegate: Transport) : Transport {
    val frames: MutableList<ByteArray> = mutableListOf()
    override val incoming: Flow<ByteArray> = delegate.incoming.onEach { frames += it }
    override suspend fun send(bytes: ByteArray) = delegate.send(bytes)
    override fun close() = delegate.close()
}

/**
 * Sequences moves across a host/guest [MatchSession] pair for tests. A test utility containing
 * no behaviour of its own — every decision (which metric to play) comes from the caller's
 * `pick` callback — not a double, per the WBS testing guidance.
 */
internal class MatchDriver(private val host: MatchSession, private val guest: MatchSession) {

    fun currentView(): MatchView.InProgress = host.view.value as MatchView.InProgress

    /** Submits from whichever side is actually this round's chooser. */
    fun choose(metric: MetricKey) {
        val chooser = (currentView().round as RemoteRoundState.AwaitingChoice).chooser
        val intent = PlayerIntent.ChooseMetric(metric)
        if (chooser == "HOST") host.submit(intent) else guest.submit(intent)
    }

    /** Either side may submit — [PlayerIntent.AdvanceRound] isn't gated to a seat. */
    fun advance() {
        host.submit(PlayerIntent.AdvanceRound)
    }

    /** Plays [pick] until the round resolves (following any tiebreak chain), then advances. */
    fun playRound(pick: (MatchView.InProgress, RemoteRoundState.AwaitingChoice) -> String) {
        while (true) {
            val view = currentView()
            when (val round = view.round) {
                is RemoteRoundState.Resolved -> {
                    advance()
                    return
                }
                is RemoteRoundState.AwaitingChoice -> choose(MetricKey(pick(view, round)))
            }
        }
    }

    /** Plays whole rounds until the match finishes. */
    fun playMatch(pick: (MatchView.InProgress, RemoteRoundState.AwaitingChoice) -> String) {
        while (host.view.value !is MatchView.Finished) {
            playRound(pick)
        }
    }
}
