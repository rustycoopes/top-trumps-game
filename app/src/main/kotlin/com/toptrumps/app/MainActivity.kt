package com.toptrumps.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.toptrumps.session.InvitationState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

public class MainActivity : ComponentActivity() {

    private lateinit var appGraph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appGraph = AppGraph(this)

        setContent {
            AppRoot(appGraph)
        }
    }

    override fun onDestroy() {
        appGraph.close()
        super.onDestroy()
    }
}

@Serializable
private data object Loading

@Serializable
private data object NameEntry

@Serializable
private data object Lobby

@Serializable
private data object Settings

@Serializable
private data object ManualConnect

@Serializable
private data object Connected

@Serializable
private data object Solo

/** Mirrors [DisplayNamePreferences.displayName]'s "loading vs. never-set vs. set" three-way, without a magic string sentinel. */
private sealed interface NameState {
    data object Loading : NameState
    data object Unset : NameState
    data class Known(val name: String) : NameState
}

/**
 * Story 1: first run asks for a display name before anything else; every later launch goes
 * straight to the lobby. The DataStore read is async, so a genuine loading state renders while it
 * resolves rather than blocking the main thread — TDD §11.
 */
@Composable
private fun AppRoot(appGraph: AppGraph) {
    val navController = rememberNavController()
    val nameStateFlow = remember {
        appGraph.displayNamePreferences.displayName.map { name ->
            if (name == null) NameState.Unset else NameState.Known(name)
        }
    }
    val nameState by nameStateFlow.collectAsStateWithLifecycle(initialValue = NameState.Loading)
    val scope = rememberCoroutineScope()

    // One controller for as long as a name exists, shared by every route that touches the lobby
    // (Lobby, Settings' re-registration-on-rename, ManualConnect) — two controllers would race to
    // bind the same fixed LOBBY_PORT. Recreated only if the display name itself changes.
    val knownName = (nameState as? NameState.Known)?.name
    val controller = knownName?.let { name -> remember(name) { appGraph.createLobbyController(name) } }
    DisposableEffect(controller) {
        controller?.start()
        onDispose { controller?.close() }
    }

    NavHost(navController = navController, startDestination = Loading) {
        composable<Loading> {
            LaunchedEffect(nameState) {
                when (val state = nameState) {
                    is NameState.Loading -> Unit
                    is NameState.Unset -> navController.navigate(NameEntry) { popUpTo(Loading) { inclusive = true } }
                    is NameState.Known -> navController.navigate(Lobby) { popUpTo(Loading) { inclusive = true } }
                }
            }
        }

        composable<NameEntry> {
            NameEntryScreen(
                prefill = appGraph.displayNamePreferences.deviceNamePrefill(),
                onSave = { name ->
                    scope.launch {
                        appGraph.displayNamePreferences.setDisplayName(name)
                        navController.navigate(Lobby) { popUpTo(NameEntry) { inclusive = true } }
                    }
                },
            )
        }

        composable<Lobby> {
            if (controller == null) return@composable
            LobbyScreen(
                controller = controller,
                onOpenSettings = { navController.navigate(Settings) },
                onOpenManualConnect = { navController.navigate(ManualConnect) },
                onPlaySolo = { navController.navigate(Solo) },
                onConnected = { _, _ ->
                    navController.navigate(Connected) { popUpTo(Lobby) }
                },
            )
        }

        composable<Settings> {
            SettingsScreen(
                currentName = knownName.orEmpty(),
                onSave = { name -> scope.launch { appGraph.displayNamePreferences.setDisplayName(name) } },
                onBack = { navController.popBackStack() },
            )
        }

        composable<ManualConnect> {
            if (controller == null) return@composable
            val ownAddress by controller.ownAddressHint.collectAsStateWithLifecycle()
            val invitation by controller.invitation.collectAsStateWithLifecycle()
            LaunchedEffect(invitation) {
                val state = invitation
                if (state is InvitationState.Connected) {
                    navController.navigate(Connected) {
                        popUpTo(ManualConnect) { inclusive = true }
                    }
                }
            }

            ManualConnectScreen(
                ownAddress = ownAddress,
                onConnect = { host -> controller.connectManually(host) },
                onBack = { navController.popBackStack() },
            )
        }

        composable<Connected> {
            if (controller == null) return@composable

            val invitationState by controller.invitation.collectAsStateWithLifecycle()
            val connected = invitationState as? InvitationState.Connected
            if (connected == null) {
                // The socket closed out from under us (peer dropped before the match started, or
                // we're mid-navigation away) — nothing left to drive a match over.
                LaunchedEffect(Unit) { navController.navigate(Lobby) { popUpTo(Lobby) { inclusive = true } } }
                return@composable
            }

            val matchController = remember(connected.transport) {
                appGraph.createMatchController(connected.transport, connected.role, knownName.orEmpty())
            }
            DisposableEffect(matchController) { onDispose { matchController.close() } }

            TwoDeviceMatchScreen(
                controller = matchController,
                peerDisplayName = connected.peer.displayName,
                onLeave = {
                    // Closes the connected socket and returns the controller to Idle *before*
                    // navigating — Lobby's own `LaunchedEffect(invitation)` would otherwise see
                    // it still `Connected` on first composition and bounce straight back here.
                    controller.leave()
                    navController.navigate(Lobby) { popUpTo(Lobby) { inclusive = true } }
                },
            )
        }

        composable<Solo> {
            SoloMatchHost(appGraph)
        }
    }
}

/**
 * Player One picks a deck (story 18), then owns the current solo
 * [com.toptrumps.session.MatchSession] for it and swaps it in for a rematch. Rematch stays on the
 * same deck rather than re-prompting — the picker is a match-setup step, not something to repeat
 * every round.
 */
@Composable
private fun SoloMatchHost(appGraph: AppGraph) {
    var chosenDeck by remember { mutableStateOf<DeckSummary?>(null) }

    val deck = chosenDeck
    if (deck == null) {
        // remember, not a direct call: listDecks() validates every deck folder's manifest and
        // every card's image reference, which is real file IO — recomputing it on every
        // recomposition of the picker would redo that walk on the main thread for nothing.
        val decks = remember { appGraph.listDecks() }
        DeckPickerScreen(decks = decks, onPick = { chosenDeck = it })
        return
    }

    var session by remember(deck.id) { mutableStateOf(appGraph.startSoloMatch(deck.id)) }

    MatchScreen(
        session = session,
        deckId = deck.id,
        onRematch = {
            session.close()
            session = appGraph.startSoloMatch(deck.id)
        },
    )
}
