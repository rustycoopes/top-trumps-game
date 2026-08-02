package com.toptrumps.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.toptrumps.app.theme.TopTrumpsTheme
import com.toptrumps.session.InvitationState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

public class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 already forces edge-to-edge regardless of this call (WBS slice-7-polish) —
        // this is what additionally makes the system bar icon contrast follow the content instead
        // of defaulting to a scrim.
        enableEdgeToEdge()
        // Application-scoped, not created here — a two-device match's state (and the socket
        // underneath it) must not die just because this Activity was recreated. See
        // TopTrumpsApplication and the foreground-service ADR.
        val appGraph = (application as TopTrumpsApplication).appGraph

        setContent {
            TopTrumpsTheme {
                AppRoot(appGraph)
            }
        }
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
private data object CardGallery

@Serializable
private data object History

@Serializable
private data object Stats

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
    // bind the same fixed LOBBY_PORT. `appGraph.lobbyController` hands back the *same* instance
    // across an Activity recreation rather than building a new one — deliberately not torn down
    // in a `DisposableEffect.onDispose` here, since a recreation disposes this composition too,
    // and closing on every recreation is exactly the "socket dies with the Activity" bug this
    // slice exists to fix. `start()` is idempotent, so re-calling it after a recreation is safe.
    val knownName = (nameState as? NameState.Known)?.name
    val controller = knownName?.let { name -> remember(name) { appGraph.lobbyController(name) } }
    LaunchedEffect(controller) { controller?.start() }

    // One inset-handling point for every screen (WBS slice-7-polish: "the layout handles system
    // insets properly", and specifically the match screen's score bar/round counter clearing the
    // status and navigation bars on a gesture-nav device) rather than each of the ~13 screens
    // padding itself — `safeDrawingPadding` also covers the IME, which is a free keyboard-avoidance
    // fix for NameEntry/Settings/ManualConnect's text fields.
    NavHost(
        navController = navController,
        startDestination = Loading,
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    ) {
        composable<Loading> {
            LaunchedEffect(nameState) {
                when (val state = nameState) {
                    is NameState.Loading -> Unit
                    is NameState.Unset -> navController.navigate(NameEntry) { popUpTo(Loading) { inclusive = true } }
                    is NameState.Known -> {
                        // A fresh Activity instance always starts its NavHost at `Loading` — an
                        // Activity recreation now leaves the match itself alone (see
                        // `appGraph.lobbyController`/`activeMatch`), but without this check the
                        // user would still visually land back in the Lobby instead of their live
                        // match screen. `Connected` reads its content from the same persisted
                        // `LobbyController`/`MatchController`, so this is a pure navigation fix.
                        val destination = if (appGraph.activeMatch.value != null) Connected else Lobby
                        navController.navigate(destination) { popUpTo(Loading) { inclusive = true } }
                    }
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
                onOpenHistory = { navController.navigate(History) },
                onPlaySolo = { navController.navigate(Solo) },
                onConnected = { _, _ ->
                    navController.navigate(Connected) { popUpTo(Lobby) }
                },
            )
        }

        composable<History> {
            HistoryScreen(
                repository = appGraph.historyRepository,
                onOpenStats = { navController.navigate(Stats) },
                onBack = { navController.popBackStack() },
            )
        }

        composable<Stats> {
            StatsScreen(
                repository = appGraph.historyRepository,
                onBack = { navController.popBackStack() },
            )
        }

        composable<Settings> {
            val muted by appGraph.soundPreferences.muted.collectAsStateWithLifecycle()
            SettingsScreen(
                currentName = knownName.orEmpty(),
                onSave = { name -> scope.launch { appGraph.displayNamePreferences.setDisplayName(name) } },
                onBack = { navController.popBackStack() },
                muted = muted,
                onSetMuted = { value -> scope.launch { appGraph.soundPreferences.setMuted(value) } },
                onOpenCardGallery = if (BuildConfig.DEBUG) ({ navController.navigate(CardGallery) }) else null,
            )
        }

        // Debug-build-only (card-visual-identity WBS slice 3) — the route itself is never
        // registered in a release build, not just hidden behind a Settings button that happens
        // not to appear.
        if (BuildConfig.DEBUG) {
            composable<CardGallery> {
                CardGalleryScreen(appGraph = appGraph, onBack = { navController.popBackStack() })
            }
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
                // we're mid-navigation away) — nothing left to drive a match over. Also the one
                // path back to Lobby that doesn't go through the "Leave" button below, so it must
                // do the same active-match cleanup itself rather than leak it.
                LaunchedEffect(Unit) {
                    appGraph.activeMatch.value?.let { stale ->
                        stale.close()
                        appGraph.clearActiveMatch(stale)
                    }
                    navController.navigate(Lobby) { popUpTo(Lobby) { inclusive = true } }
                }
                return@composable
            }

            // Reuses the already-active controller across a recreation rather than building a
            // second one over the same live transport — a fresh `HostMatchSession` would re-deal
            // the match from round 1 on a socket the peer already associates with one in progress.
            val matchController = remember(connected.transport) {
                appGraph.activeMatch.value ?: appGraph.createMatchController(
                    transport = connected.transport,
                    role = connected.role,
                    displayName = knownName.orEmpty(),
                    peerInstanceId = connected.peer.instanceId,
                    peerDisplayName = connected.peer.displayName,
                    lobbyController = controller,
                )
            }

            // Cleanup lives here, not in a `DisposableEffect.onDispose` — disposal also fires on
            // a mere Activity recreation, which must *not* tear down a live match. Every real exit
            // path calls this explicitly: the "Leave" family of buttons below, and the `connected
            // == null` branch above for the "peer vanished before we got here" case.
            val leaveMatch: () -> Unit = {
                matchController.close()
                appGraph.clearActiveMatch(matchController)
                // Closes the connected socket and returns the controller to Idle *before*
                // navigating — Lobby's own `LaunchedEffect(invitation)` would otherwise see
                // it still `Connected` on first composition and bounce straight back here.
                controller.leave()
                navController.navigate(Lobby) { popUpTo(Lobby) { inclusive = true } }
            }

            TwoDeviceMatchScreen(
                controller = matchController,
                peerDisplayName = connected.peer.displayName,
                onLeave = leaveMatch,
                soundEffects = appGraph.soundEffects,
                imageLoader = appGraph.imageLoader,
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
        imageLoader = appGraph.imageLoader,
        onRematch = {
            session.close()
            session = appGraph.startSoloMatch(deck.id)
        },
        soundEffects = appGraph.soundEffects,
    )
}
