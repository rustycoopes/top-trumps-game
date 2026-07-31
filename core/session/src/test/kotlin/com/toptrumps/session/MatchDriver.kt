package com.toptrumps.session

import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.PlayerIntent

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
