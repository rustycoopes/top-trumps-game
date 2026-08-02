package com.toptrumps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import com.toptrumps.session.ConnectionState

/**
 * The whole two-device journey from a freshly connected socket to a playing match — story
 * 18/PRD's "handshake", then [MatchScreen] once [MatchPhase.InMatch] is reached. [peerDisplayName]
 * is shown throughout since it's known from the lobby invitation well before the handshake
 * confirms anything about the peer's build or deck.
 */
@Composable
public fun TwoDeviceMatchScreen(
    controller: MatchController,
    peerDisplayName: String,
    onLeave: () -> Unit,
    soundEffects: SoundEffects,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(controller) { controller.start() }
    val phase by controller.phase.collectAsStateWithLifecycle()

    when (val current = phase) {
        is MatchPhase.Handshaking ->
            StatusScreen(modifier, "Connected to $peerDisplayName", "Confirming you're both running the same version…", onLeave)

        is MatchPhase.VersionMismatch ->
            StatusScreen(
                modifier,
                "Version mismatch",
                "Your app doesn't match $peerDisplayName's build — make sure you're both updated to the same version, then reconnect.",
                onLeave,
            )

        is MatchPhase.PickingDeck ->
            DeckPickerScreen(decks = current.decks, onPick = { controller.pickDeck(it.id) }, modifier = modifier)

        is MatchPhase.WaitingForHost ->
            StatusScreen(modifier, "Connected to $peerDisplayName", "Waiting for $peerDisplayName to choose a deck…", onLeave)

        is MatchPhase.DeckMismatch ->
            StatusScreen(
                modifier,
                "Deck mismatch",
                "Your copy of the '${current.deckId}' deck doesn't match $peerDisplayName's — reinstall so you're both running the same app build, then reconnect.",
                onLeave,
            )

        is MatchPhase.ConnectionLost ->
            StatusScreen(
                modifier,
                "Connection lost",
                "Lost the connection to $peerDisplayName before the match could start.",
                onLeave,
            )

        is MatchPhase.InMatch -> {
            // Only InMatch has a live session — the countdown/abandon/left story is entirely
            // about a drop *during* play (the heartbeat only starts once a session exists); a
            // drop during the handshake above already has its own ConnectionLost dead end.
            val connectionState by current.session.connectionState.collectAsStateWithLifecycle()
            when (val connection = connectionState) {
                is ConnectionState.Connected ->
                    MatchScreen(
                        session = current.session,
                        deckId = current.deckId,
                        imageLoader = imageLoader,
                        localSeat = current.localSeat,
                        rematchLabel = "Back to lobby",
                        onRematch = onLeave,
                        soundEffects = soundEffects,
                        onLeaveMatch = { current.session.leave(); onLeave() },
                        modifier = modifier,
                    )

                is ConnectionState.PeerUnreachable ->
                    StatusScreen(
                        modifier,
                        "$peerDisplayName seems to have disconnected",
                        // The reconnect-resync ADR's host/guest asymmetry: a guest actively
                        // redials, a host can only wait for one to arrive — the copy must not
                        // imply the host is doing something it isn't.
                        if (current.localSeat == "GUEST") {
                            "Trying to reconnect… ${connection.secondsRemaining}s left before the match is abandoned."
                        } else {
                            "Waiting for $peerDisplayName to reconnect… ${connection.secondsRemaining}s left before the match is abandoned."
                        },
                        onLeave,
                    )

                is ConnectionState.Abandoned ->
                    StatusScreen(
                        modifier,
                        "Match abandoned",
                        "$peerDisplayName couldn't be reached in time — the match has ended.",
                        onLeave,
                    )

                is ConnectionState.PeerLeft ->
                    StatusScreen(modifier, "$peerDisplayName left the game", "The match has ended.", onLeave)
            }
        }
    }
}

@Composable
private fun StatusScreen(modifier: Modifier, title: String, message: String, onLeave: () -> Unit) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title)
        Text(message)
        Button(onClick = onLeave) { Text("Leave") }
    }
}
