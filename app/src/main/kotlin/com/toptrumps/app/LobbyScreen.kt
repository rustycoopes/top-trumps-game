package com.toptrumps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toptrumps.session.InvitationState
import com.toptrumps.session.LobbyPeer
import com.toptrumps.session.Role

/**
 * Story 3–9: advertises this device and browses for others simultaneously, tap-to-invite with an
 * accept/decline round trip, and the success condition for this whole slice — a connected socket
 * and a screen that says so, via [onConnected].
 */
@Composable
public fun LobbyScreen(
    controller: LobbyController,
    onOpenSettings: () -> Unit,
    onOpenManualConnect: () -> Unit,
    onOpenHistory: () -> Unit,
    onPlaySolo: () -> Unit,
    onConnected: (Role, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val peers by controller.peers.collectAsStateWithLifecycle()
    val invitation by controller.invitation.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()

    // Not started here: the caller (AppRoot) owns this controller's start/close lifecycle,
    // since it's shared with the ManualConnect and Settings routes too — starting it again on
    // every visit to this screen would attempt a second bind of the fixed LOBBY_PORT.
    LaunchedEffect(invitation) {
        val state = invitation
        if (state is InvitationState.Connected) {
            onConnected(state.role, state.peer.displayName)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenSettings) { Text("Settings") }
            TextButton(onClick = onOpenManualConnect) { Text("Connect by address") }
            TextButton(onClick = onOpenHistory) { Text("History") }
        }

        Text("Lobby")

        if (error != null) {
            Text(error.orEmpty())
        }

        if (peers.isEmpty()) {
            Text("No one else here yet — make sure both devices are on the same Wi-Fi.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(peers, key = LobbyPeer::instanceId) { peer ->
                    Button(
                        onClick = { controller.invite(peer) },
                        enabled = invitation is InvitationState.Idle,
                    ) {
                        Text(peer.displayName)
                    }
                }
            }
        }

        HorizontalDivider()
        OutlinedButton(onClick = onPlaySolo) { Text("Play solo") }
    }

    when (val state = invitation) {
        is InvitationState.InboundPrompt -> InboundInviteDialog(
            fromDisplayName = state.peer.displayName,
            onAccept = controller::acceptInbound,
            onDecline = controller::declineInbound,
        )
        is InvitationState.OutboundPending -> OutboundPendingDialog(
            toDisplayName = state.peer.displayName,
            onCancel = controller::cancelOutbound,
        )
        else -> Unit
    }
}

@Composable
private fun InboundInviteDialog(fromDisplayName: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Invitation") },
        text = { Text("$fromDisplayName invited you to play.") },
        confirmButton = { Button(onClick = onAccept) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Decline") } },
    )
}

@Composable
private fun OutboundPendingDialog(toDisplayName: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Waiting for a reply") },
        text = { Text("Waiting for $toDisplayName to accept…") },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
