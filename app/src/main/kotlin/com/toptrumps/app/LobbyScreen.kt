package com.toptrumps.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toptrumps.app.theme.DisplayFontFamily
import com.toptrumps.app.theme.LobbyPalette
import com.toptrumps.session.InvitationState
import com.toptrumps.session.LobbyPeer
import com.toptrumps.session.Role

/** Shared dash rhythm for every hand-drawn dashed line in this screen — [DashedDivider] and [dashedBorder]. */
private val LobbyDashPattern = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)

/** Shared rounded-card shape — [PeerRow] and, via [lobbyCard], [HistoryScreen]/[StatsScreen]'s list rows. */
private val LobbyCardShape = RoundedCornerShape(14.dp)

/**
 * Story 3–9: advertises this device and browses for others simultaneously, tap-to-invite with an
 * accept/decline round trip, and the success condition for this whole slice — a connected socket
 * and a screen that says so, via [onConnected]. Themed as "The Pack" (second-round-updates slice
 * 2): [LobbyPalette], not `MaterialTheme.colorScheme` — see that object's own doc for why.
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LobbyPalette.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LobbyTextLink("Settings", onOpenSettings)
            LobbyTextLink("Connect by address", onOpenManualConnect)
            LobbyTextLink("History", onOpenHistory)
        }

        Text(
            "Lobby",
            style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 34.sp),
            color = LobbyPalette.ink,
        )

        if (error != null) {
            Text(error.orEmpty(), color = LobbyPalette.ink)
        }

        if (peers.isEmpty()) {
            LobbyHintBox("No one else here yet — make sure both devices are on the same Wi-Fi.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(peers, key = LobbyPeer::instanceId) { peer ->
                    PeerRow(
                        displayName = peer.displayName,
                        enabled = invitation is InvitationState.Idle,
                        onInvite = { controller.invite(peer) },
                    )
                }
            }
        }

        DashedDivider()
        LobbyPrimaryButton("Play Solo", onPlaySolo)
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
internal fun LobbyTextLink(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = LobbyPalette.inkDim)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A card-shaped row, echoing [com.toptrumps.app.card.TrumpCard]'s bordered/rounded language rather than a bare list item. The whole row is the tap target, matching the original plain `Button`'s behaviour. */
@Composable
private fun PeerRow(displayName: String, enabled: Boolean, onInvite: () -> Unit) {
    val shape = LobbyCardShape
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        // The hard offset "shadow" — an accent-coloured duplicate of the card shape, peeking out
        // behind and below the real card, composed first so the real card draws over it.
        Box(
            Modifier
                .matchParentSize()
                .padding(top = 4.dp, start = 4.dp)
                .background(LobbyPalette.accent, shape),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable(enabled = enabled, onClick = onInvite, role = SemanticsRole.Button)
                .background(LobbyPalette.surface, shape)
                .border(2.dp, LobbyPalette.ink, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(width = 26.dp, height = 36.dp)
                    .background(LobbyPalette.accent, RoundedCornerShape(4.dp))
                    .border(2.dp, LobbyPalette.ink, RoundedCornerShape(4.dp)),
            )
            Text(
                displayName,
                modifier = Modifier.weight(1f),
                color = LobbyPalette.ink,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Box(
                Modifier
                    .background(LobbyPalette.ink, RoundedCornerShape(8.dp))
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                Text(
                    "INVITE",
                    style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 11.sp),
                    color = LobbyPalette.accent,
                )
            }
        }
    }
}

/** Dashed-border empty-state box — [LobbyScreen]'s no-peers hint and, since second-round-updates#53, [HistoryScreen] and [StatsScreen]'s empty-list hints. */
@Composable
internal fun LobbyHintBox(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .dashedBorder(LobbyPalette.divider, 2.dp, 16.dp)
            .padding(vertical = 26.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            color = LobbyPalette.inkDim,
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
        )
    }
}

/** Shared filled-button treatment for "The Pack" theme — [LobbyScreen]'s Play Solo action and, since second-round-updates#52, [SettingsScreen]'s Save action. */
@Composable
internal fun LobbyPrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(LobbyPalette.ink)
            .clickable(onClick = onClick, role = SemanticsRole.Button)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = LobbyPalette.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** Hand-drawn section divider — [LobbyScreen]'s pre-Play-Solo rule and, since second-round-updates#53, [HistoryScreen] and [StatsScreen]'s section breaks. */
@Composable
internal fun DashedDivider() {
    val color = LobbyPalette.divider
    Canvas(modifier = Modifier.fillMaxWidth().height(2.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            pathEffect = LobbyDashPattern,
        )
    }
}

private fun Modifier.dashedBorder(color: Color, width: Dp, cornerRadius: Dp): Modifier = this.drawWithContent {
    drawContent()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
        style = Stroke(width = width.toPx(), pathEffect = LobbyDashPattern),
    )
}

/** The bordered/rounded static-row treatment shared by [HistoryScreen]'s match rows and [StatsScreen]'s stat rows — [PeerRow] keeps its own chain since it also needs `clip`+`clickable` ordering for its ripple and offset shadow. */
internal fun Modifier.lobbyCard(): Modifier = this
    .background(LobbyPalette.surface, LobbyCardShape)
    .border(2.dp, LobbyPalette.ink, LobbyCardShape)
    .padding(horizontal = 14.dp, vertical = 12.dp)

@Composable
private fun InboundInviteDialog(fromDisplayName: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        containerColor = LobbyPalette.surface,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                "Invitation",
                style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 18.sp),
                color = LobbyPalette.ink,
            )
        },
        text = { Text("$fromDisplayName invited you to play.", color = LobbyPalette.inkDim) },
        confirmButton = {
            // Filled, not a TextButton like Decline: this is the primary action on the one dialog
            // where the user is making a consequential accept/decline choice, and needs the same
            // visual weight the original Material `Button` gave it.
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = LobbyPalette.ink, contentColor = LobbyPalette.accent),
            ) {
                Text("Accept", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline, colors = ButtonDefaults.textButtonColors(contentColor = LobbyPalette.inkDim)) {
                Text("Decline")
            }
        },
    )
}

@Composable
private fun OutboundPendingDialog(toDisplayName: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = LobbyPalette.surface,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                "Waiting for a reply",
                style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 18.sp),
                color = LobbyPalette.ink,
            )
        },
        text = { Text("Waiting for $toDisplayName to accept…", color = LobbyPalette.inkDim) },
        confirmButton = {
            TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = LobbyPalette.inkDim)) {
                Text("Cancel")
            }
        },
    )
}
