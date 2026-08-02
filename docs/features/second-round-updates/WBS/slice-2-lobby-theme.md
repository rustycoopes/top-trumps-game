# Slice 2 — Lobby theme

> Part of the `second-round-updates` feature. PRD: [`../PRD.md`](../PRD.md)

**Delivers:** A themed lobby screen — peer list, invite/accept prompts, and connection-state
screens — replacing today's unstyled Material defaults (`LobbyScreen.kt`), built as an extension of
the theme `card-visual-identity` already introduced rather than a competing visual language.

## What to build

`LobbyScreen.kt` today is plain `Column`/`Row`/`LazyColumn` with stock Material3 `Button`/`TextButton`/
`AlertDialog` — no colour, typography, or layout identity of its own. `card-visual-identity`
deliberately scoped this screen out of its own brief (see that PRD's Out of Scope: "Restyling lobby,
settings, name entry, or manual connect... those screens carry no card visuals and are not part of
this feature's brief"). This slice picks that up.

**Step 1 — mockups before code.** Produce 2–3 distinct visual-direction options (e.g. dark
competitive/esports, warm playful card-game, clean minimalist) as reviewable mockups, get sign-off on
one direction, *then* implement it. Do not skip straight to implementation from a single assumed
direction — this was an explicit requirement when this feature was scoped, not an optional nicety.

**Step 2 — implement the chosen direction** across the lobby's actual states: the empty/searching
peer list, a populated peer list, an outbound invite pending, an inbound invite prompt, and the
connected/waiting-for-handshake screens already themed will need to be checked for a consistent feel
alongside them (those live in `TwoDeviceMatchScreen.kt`'s `StatusScreen`, already plain — decide during
implementation whether bringing them in line is in-scope here or a natural follow-up, since they sit
right next to the lobby in the user's actual flow).

## Design notes

Extend, don't replace, the theme system `card-visual-identity` introduced (`app/src/main/kotlin/com/toptrumps/app/theme/Theme.kt`, `Color.kt`, the bundled Anton display font) —
the chosen mockup direction should read as the same app, not a third clashing visual language
alongside the card theme and whatever Material defaults remain elsewhere. Reuse
`MaterialTheme`/`Color.kt` tokens where they already fit rather than hardcoding new ones parallel to
them.

Name entry, settings, and manual-connect stay on Material defaults for this slice — see the PRD's Out
of Scope.

## Blocked by

None structurally — the theme foundation this builds on already shipped in
`card-visual-identity` slice 2. The mockup-and-sign-off checkpoint is this slice's own first step, not
an external blocker.

## Acceptance criteria

- [ ] 2–3 visual-direction mockups produced and reviewed; one direction signed off before
      implementation begins
- [ ] The lobby's peer list, invite-pending, and inbound-invite-prompt states are all themed
      consistently with the chosen direction
- [ ] The theme reuses `card-visual-identity`'s existing colour/typography tokens rather than
      introducing an unrelated parallel set
- [ ] Name entry, settings, and manual-connect are unchanged (explicitly out of scope this slice)
- [ ] No regression to lobby functionality — discovery, invite/accept/decline, and the tiebreak path
      all still work exactly as before, verified on two physical devices per this repo's standing NSD
      testing constraint

## Testing

Lobby behaviour itself has no new automatable seam — this is a visual/interaction-chrome change, not
a logic change, and `NsdManager`/lobby behaviour is manual-on-device per `CLAUDE.md` throughout this
repo. Verify by hand on two physical devices that the reskin doesn't regress the discovery/invite flow
underneath it, alongside the visual review of the chosen mockup direction.

## Delivered

_Not yet delivered._
