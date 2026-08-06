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

Issue [#40](https://github.com/rustycoopes/top-trumps-game/issues/40), branch
`slice-2-lobby-theme`, 2026-08-06.

Three visual directions (dark competitive "Face-Off", warm tabletop "The Pack", clean minimalist
"Signal") were mocked up as an interactive HTML artifact with real lobby copy and state toggles
(empty/populated peer list, inbound/outbound invite) and reviewed before any Compose code was
written, per this slice's own first-step requirement. "The Pack" was signed off.

`LobbyScreen.kt` was rewritten around a new `LobbyPalette` (`app/src/main/kotlin/com/toptrumps/app/theme/LobbyPalette.kt`)
— a warm parchment background/surface pair plus `DeckTheme.DEFAULT`'s accent/onAccent reused
directly for the theme's yellow-on-ink identity, rather than inventing a new colour, per the WBS's
"extend, don't replace" requirement. Peer rows are card-shaped (rounded corners, 2dp ink border,
Anton-font "INVITE" chip) with a hard-offset accent box composed behind them for a drop-shadow
effect matching the tabletop direction; the peer list, empty-lobby hint, and the play-solo CTA all
picked up the same dashed-border/divider motif via a custom `drawWithContent`/`Canvas` treatment
(now sharing one `LobbyDashPattern` constant). The inbound-invite and outbound-pending dialogs were
re-themed onto `LobbyPalette`/`DisplayFontFamily` without changing their structure. Name entry,
settings, and manual-connect were left untouched, as scoped.

`TwoDeviceMatchScreen.kt`'s `StatusScreen` was deliberately left un-themed rather than folded into
this slice's diff — filed as a follow-up, [#42](https://github.com/rustycoopes/top-trumps-game/issues/42).

Code review (`code-review-master` and `code-quality-guardian`, run in parallel) found and fixed:
a `LobbyPalette` doc comment that overstated how closely it mirrors `CardPalette`'s explicit-
parameter-injection pattern (corrected to describe it honestly as a bare singleton, since the lobby
has exactly one visual direction with nothing to inject); two independently hand-rolled dash-pattern
arrays with unexplained differing magic numbers (unified into one `LobbyDashPattern` constant);
a missing `.clip(shape)` on the peer row that let its ripple indication draw past the card's rounded
corners; missing `role = Role.Button` on both `Modifier.clickable` call sites that replaced Material
`Button`s (a real TalkBack/semantics regression); the "Play Solo" CTA's touch target sitting under
the 48dp accessibility minimum (Material's `Button` guarantees this for free, `Modifier.clickable`
does not); and the invite dialog's "Accept" button having been silently downgraded from a filled
`Button` to a `TextButton`, weakening its primary-action visual weight against "Decline" — restored
as a filled, palette-coloured `Button`. `code-review-master`'s first attempt failed mid-run on a
weekly API rate limit and was re-run to completion once the window reset; nothing else was found
beyond what's listed here.

`:app:compileDebugKotlin` and `:app:testDebugUnitTest` both pass with no regressions. No physical
device was available this session to run the two-device manual verification this slice's acceptance
criteria call for (discovery, invite/accept/decline, and the tiebreak path underneath the new
visuals) — that pass is still outstanding and should be done on the next two-device session.
