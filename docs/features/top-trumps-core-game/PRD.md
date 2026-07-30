# PRD: top-trumps-core-game

## Problem Statement

I want to play Top Trumps with my family on our phones, in the same room, without anyone needing a physical deck.

Physical Top Trumps decks have real friction: they get lost, cards go missing so the deck no longer deals evenly, and only the person holding the deck can see the cards — which means the whole game depends on players honestly reading out their own numbers. Setting up a game means finding the box first.

The digital alternatives are worse in a different way. They want accounts, internet connections, and adverts, and they match you against strangers rather than the person sitting opposite you. Nothing I've found simply lets two people in the same house open an app, see each other, and start a game.

There is also nothing that lets me build my own themed decks about subjects my family actually cares about.

## Solution

An Android app for two players in the same room, each on their own phone.

You open the app and immediately see the other players nearby by name — no accounts, no sign-in, no internet connection, no typing in codes. You tap someone to invite them; they accept, and you're in a game. As the person who sent the invite you become Player One and choose which themed deck to play.

The app deals fifteen cards to each of you. You see only your own current card; your opponent sees only theirs. On your turn you pick one of the five stats on your card and commit to it. Both screens then reveal that stat on both cards side by side, the winner is shown, and both cards slide into the winner's pile. If the two values are identical, you pick a second stat to settle it — still without seeing the rest of your opponent's card.

Once a round is decided, your opponent's full card flips over so you can see the machine you just beat or lost to. Your win pile is a growing collection you can browse at any point. After fifteen rounds the player holding the most cards wins.

The launch deck is **Motorcycles** — thirty significant machines spanning a century, compared on engine capacity, top speed, age, dry weight and length. More themed decks can be added later as pure content, with no app update needed beyond dropping in a folder.

There is also a solo practice mode against a simple opponent, so you can play — or test a rule — without a second phone.

## User Stories

### Identity and first run

1. As a new player, I want to be asked for a display name the first time I open the app, so that other players see a human name rather than a device model.
2. As a new player, I want my name pre-filled from my device name, so that I can accept the default and get straight into a game.
3. As a returning player, I want my name remembered between launches, so that I never re-enter it.
4. As a player, I want to change my display name later, so that I can correct a typo or use a nickname.
5. As a player, I want to use the app with no account, no sign-in and no internet connection, so that there is nothing between me and a game.

### Discovery and lobby

6. As a player, I want the app to show me other players on my Wi-Fi network automatically when I open it, so that I don't have to type codes or IP addresses.
7. As a player, I want to see each nearby player listed by their display name, so that I know who I'm about to invite.
8. As a player, I want the list to update live as people open and close the app, so that it reflects who is actually available right now.
9. As a player, I want to be told clearly when no one else is found, along with a hint that both devices must be on the same Wi-Fi, so that I can diagnose the common failure myself.
10. As a player on a network where discovery is blocked, I want a manual fallback to connect by entering the host's address or a short code, so that a restrictive router doesn't make the app unusable.
11. As a player, I want to tap a nearby player to invite them to a game, so that starting a match is a single deliberate action.
12. As an invited player, I want to see who invited me and be able to accept or decline, so that I am never dragged into a game without consenting.
13. As an inviting player, I want to see that my invitation is pending and be able to cancel it, so that I'm not left waiting with no feedback.
14. As an inviting player, I want to be told if my invitation is declined or times out, so that I know to try someone else.
15. As a player, I want to become Player One when I am the one who sent the invitation, so that the roles are unambiguous.
16. As a player, I want simultaneous mutual invitations to resolve deterministically into a single game, so that two people tapping at once doesn't produce two conflicting matches or a deadlock.
17. As a player, I want to be told if the other player's app is a different version or has a different deck, so that we discover the mismatch before dealing rather than half way through.

### Setting up a match

18. As Player One, I want to choose which themed deck we play, so that I control the subject of the match.
19. As Player One, I want to see the decks available on my device in a picker, so that adding a new deck later needs no new UI.
20. As Player Two, I want to see which deck Player One has chosen while I wait, so that I know what I'm about to play.
21. As Player One, I want to start the game when I'm ready, so that my opponent isn't dropped into a match before they've looked up.
22. As a player, I want fifteen cards dealt to me at random from the thirty-card deck, so that every match is different.
23. As a player, I want the deal to be fair and unpredictable, so that neither player can be handed a systematically stronger hand.

### Playing a round

24. As a player, I want to see only my own current card, so that the game is a genuine contest of judgement rather than perfect information.
25. As a player, I want my card to show a picture, the name of the item, and all five of its stats, so that I can decide what to play on.
26. As a player, I want each stat labelled with its unit, so that I understand what the number means.
27. As a player, I want to see clearly whether a stat is won by the higher or the lower value, so that I don't play a stat expecting the wrong outcome.
28. As a player, I want to know whether it is my turn or my opponent's, so that I'm never uncertain who the game is waiting on.
29. As the player whose turn it is, I want to select one stat from my card, so that I choose the ground on which the round is fought.
30. As the player whose turn it is, I want to confirm my selection before it is sent, so that a mis-tap doesn't cost me the round.
31. As the waiting player, I want to see that my opponent is choosing, so that I understand why nothing is happening.
32. As the waiting player, I want to see which stat my opponent chose, so that the comparison makes sense to me.
33. As a player, I want both cards' values for the chosen stat shown side by side on both screens, so that the comparison is transparent and neither of us has to trust the other.
34. As a player, I want the winner and loser of the round announced clearly, so that there is no ambiguity about what just happened.
35. As a player, I want both cards to move visibly into the winner's pile, so that I can follow where the cards went.
36. As a player, I want to see my opponent's full card — picture, name and all five stats — once the round is decided, so that I learn about the item I just played against.
37. As a player, I want my opponent's other stats to stay hidden until the round is decided, so that the contest stays a real judgement call.
38. As a player, I want to move to the next round at a pace I control, so that I'm not rushed past a result I want to look at.
39. As a player, I want the turn to alternate between us, so that we each get an equal share of the choices across the match.

### Ties

40. As a player, I want to be told when the chosen stat is a draw, so that I understand why no one won yet.
41. As the player whose turn it is, I want to choose a second stat to settle a tie, so that the tiebreak is my decision rather than a hidden rule.
42. As a player, I want the already-played stat excluded from the tiebreak choice, so that I can't accidentally loop on a value I know is level.
43. As a player, I want to still not see my opponent's remaining stats during a tiebreak, so that picking the tiebreak is a real gamble rather than a formality.
44. As a player, I want a tiebreak that ties again to prompt another choice, so that the round always reaches a conclusion.
45. As a player, I want to see which stat finally settled a tied round, so that I understand exactly why I won or lost.

### Score, piles and the end of a match

46. As a player, I want to see both scores at all times, so that I always know where I stand.
47. As a player, I want to see which round we're on out of fifteen, so that I know how much of the match is left.
48. As a player, I want to tap my score to browse the cards I've won, so that my pile feels like a collection rather than a number.
49. As a player, I want to open any card in my win pile at full size, so that I can look properly at something I won earlier.
50. As a player, I want to return from my win pile to the live round without losing my place, so that browsing never costs me the game.
51. As a player, I want the match to end after all fifteen rounds, so that it has a definite length.
52. As a player, I want a clear victory or defeat screen with the final score, so that the result feels like a conclusion.
53. As a player, I want to see a summary of the match I just played, so that I can review how it went.
54. As a player, I want to be offered a rematch at the end, so that we can play again without going back through discovery and invitations.
55. As a player, I want to leave a finished match cleanly and return to the lobby, so that I can start a game with someone else.

### Interruptions

56. As a player, I want my screen to stay awake during a match, so that the game doesn't die because I paused to think.
57. As a player, I want a brief interruption — a notification, a glance at another app, a Wi-Fi hiccup — not to end the match, so that a fifteen-round game is actually completable in a real house.
58. As a player, I want to see that my opponent has dropped and that the game is waiting for them, with a countdown, so that I know whether to wait or give up.
59. As a player who dropped, I want the app to reconnect me to the match in progress automatically, so that I return to exactly where I was.
60. As a player, I want the match abandoned with a clear explanation if my opponent doesn't return in time, so that I'm not left staring at a frozen screen.
61. As a player, I want to be able to quit a match deliberately, so that I can stop playing without force-closing the app.
62. As a player, I want my opponent told that I quit rather than that I crashed, so that they aren't left waiting on a countdown for nothing.

### Solo practice

63. As a player with no one to play against, I want a solo mode against the app, so that I can play whenever I like.
64. As a solo player, I want the same rules, cards, tiebreaks and scoring as a two-player match, so that practice is genuinely representative.
65. As a solo player, I want the opponent to make plausible choices rather than random ones, so that the practice game isn't trivial.
66. As a new player, I want to learn the game in solo mode before playing a real opponent, so that my first real match isn't spent reading rules.

### Deck content

67. As a player, I want the Motorcycles deck to cover machines from across the last hundred years, so that the comparisons are varied and interesting.
68. As a player, I want each card to show a real photograph of the actual model, so that I recognise the machine rather than a generic illustration.
69. As a player, I want stats to be accurate to published figures, so that the game teaches me something true.
70. As a player, I want ages shown in years rather than as a raw date, so that the stat reads naturally on the card.
71. As a player, I want age to stay correct as years pass, so that the deck never silently goes stale.
72. As a player, I want stats in units I actually use, so that the numbers mean something to me at a glance.
73. As a player, I want every card to be able to win on something, so that no card in my hand is dead weight.

### Feel

74. As a player, I want cards to flip and move rather than snap between states, so that it feels like a card game rather than a table of numbers.
75. As a player, I want a sound when I select a stat, when a card flips, and when I win or lose, so that the game gives me feedback beyond the screen.
76. As a player, I want to mute the sound, so that I can play without disturbing anyone.
77. As a player, I want the game to work in portrait held one-handed, so that I can play it the way I hold my phone.

### History

78. As a player, I want my completed matches recorded, so that the game has continuity beyond a single sitting.
79. As a player, I want to see my head-to-head record against each person I've played, so that there are bragging rights at stake.
80. As a player, I want to see my overall record and win rate, so that I can tell whether I'm improving.
81. As a player, I want to see which cards have won me the most rounds, so that I learn the deck.
82. As a player, I want history kept only on my own device, so that playing the game shares nothing about me anywhere.

## Implementation Decisions

### Platform

- **Kotlin + Jetpack Compose, Android only.** Cross-platform frameworks buy nothing here — there is no iOS requirement — and local peer-to-peer networking is precisely where they are weakest. Native gives first-class `NsdManager` access with no plugin risk.
- Portrait orientation only for v1.
- Distribution is **sideload / personal use**. No Play Console, no privacy policy, no data-safety declaration, no content rating. This is what makes it acceptable to use manufacturer names and third-party photographs in v1; if a public listing is ever pursued, the per-card licence metadata (below) is what makes that transition possible.

### Authority model

- **Host-authoritative.** Player One's device is the single source of truth: it shuffles, deals, adjudicates every comparison, and owns the score. Player Two is a thin client that sends only "I choose stat X" and renders the state pushed to it.
- Consequence: the two screens can never disagree, and the entire rules engine is plain Kotlin with no networking inside it.
- Accepted trade-off: the host holds the guest's hand in memory and could in principle inspect it. Irrelevant for two people in the same room; explicitly not defended against.

### Transport and discovery

- **NSD (mDNS) service advertisement plus a TCP socket.** No runtime permission prompts at all — only `INTERNET`, `ACCESS_NETWORK_STATE` and a multicast lock. This is a deliberate advantage over Nearby Connections, which would demand Bluetooth and location permissions that feel invasive for a card game.
- Both devices advertise **and** browse simultaneously, producing a symmetric lobby where everyone can see everyone.
- `NsdManager` resolve calls must be serialised — concurrent resolves are a known source of failure on Android.
- Known limitation: mDNS fails on guest networks and access points with client isolation. Mitigated by a manual connect-by-address/code fallback, which is a required part of v1 rather than a nicety.
- A `Transport` interface abstracts message send/receive. Production binds it to NSD+TCP; tests bind it to an in-memory pair.

### Lobby and invitations

- Advertised service records carry the player's display name and a per-launch instance UUID.
- Invitation flow: tap a peer → they see an accept/decline prompt → on accept, the inviter becomes Player One.
- **Simultaneous mutual invitations** resolve by comparing instance UUIDs; the lower UUID's invitation stands and the other is auto-cancelled. Without this rule two people tapping at once either deadlock or create two half-games.
- Invitations time out and are cancellable by the sender.
- The handshake exchanges app protocol version, deck id and a deck content hash. A mismatch is reported and the match refused **before** dealing.

### Game rules

- Deck of **30 cards**, dealt **15 to each player**, whole deck in play. Every match is therefore exactly 15 rounds.
- Each card carries a picture, a name, and values for the deck's five metrics.
- Each metric declares a **win direction** — `HIGH_WINS` or `LOW_WINS` — rendered on the card as an arrow. For Motorcycles: engine capacity, top speed and age are `HIGH_WINS`; dry weight and length are `LOW_WINS`. This is what stops a heavy tourer being a dead card and matches how real decks handle stats like 0–60 time.
- **Turn order alternates strictly.** Player One chooses on odd rounds, Player Two on even, so each gets seven or eight choices. This deliberately departs from physical Top Trumps: because hands are fixed at 15 and won cards leave play into a separate pile rather than returning to the hand, "winner chooses next" would not be self-correcting — a player on a streak could make fourteen of the fifteen choices while their opponent never picked once.
- **Ties are resolved interactively.** The same player whose turn it is chooses again from the remaining metrics; the tied metric is disabled in the UI. This recurses until a metric separates the cards.
- **Assumed fallback (see Further Notes):** if all five metrics tie, each player keeps their own card.
- **Reveal model:** while a round is live, only the contested metric is shown on the opponent's card. Once decided, the opponent's card is fully revealed — picture, name, all five stats — before both cards move to the winner's pile.
  - This is *forced* by the interactive tiebreak. Revealing the full card at first compare would let the chooser pick a tiebreak metric with perfect information and win every tie automatically.
  - It costs nothing strategically: each card is played exactly once and then leaves play permanently, so post-hoc revelation leaks no future information.
- **No drawn match is possible.** Fifteen rounds award two cards each, always to one player, so both scores are even and sum to 30. 15–15 cannot occur. No draw handling is required.
- Winner is the player holding the most cards at the end.

### Deck format and content

- A deck is a **self-contained folder** containing a deck manifest plus its images. The app enumerates available decks at launch, so adding a deck later is a content drop requiring no code change.
- The manifest declares: deck id and display name, the five metric definitions (key, display label, unit, win direction), and the 30 cards. Each card declares its name, image reference, five stat values, and image licence metadata (licence, author, source URL).
- Licence metadata is recorded from day one even though v1 is sideload-only — it costs nothing now and is the sole thing that makes a future public listing viable.
- Images are **Wikimedia Commons CC-licensed photographs** of the actual models, bundled in the APK at web resolution (~5MB for 30). Accuracy matters here in a way it wouldn't for a fantasy deck: half the appeal is recognising a Vincent Black Shadow, and image generators are unreliable at specific historical models.
- **Derived age:** the manifest stores the model `year` as a permanent fact. The card renders "Age — N years" computed at runtime, and the engine compares on the stored year (earlier year = greater age = wins under `HIGH_WINS`). The deck data therefore never needs annual maintenance and the displayed figure is never wrong.
- **Units (UK convention):** capacity in cc, top speed in mph, dry weight in kg, length in mm. Stored as-is with a unit label per metric — no conversion code anywhere.
- **Motorcycles deck roster** (30 models, 1923–2018, all internal-combustion):

  | # | Model | Year | # | Model | Year |
  |---|---|---|---|---|---|
  | 1 | BMW R32 | 1923 | 16 | Honda CBX1000 | 1978 |
  | 2 | Brough Superior SS100 | 1925 | 17 | Yamaha RD350 LC | 1980 |
  | 3 | Triumph Speed Twin | 1938 | 18 | Suzuki Katana GSX1100S | 1981 |
  | 4 | Vincent Black Shadow | 1948 | 19 | Suzuki GSX-R750 | 1985 |
  | 5 | Triumph Thunderbird 6T | 1949 | 20 | Yamaha VMAX | 1985 |
  | 6 | BSA Gold Star DBD34 | 1956 | 21 | Honda VFR750R RC30 | 1987 |
  | 7 | Velocette Venom | 1956 | 22 | Honda CBR900RR FireBlade | 1992 |
  | 8 | Harley-Davidson Sportster XL | 1957 | 23 | Ducati 916 | 1994 |
  | 9 | Triumph Bonneville T120 | 1959 | 24 | Yamaha YZF-R1 | 1998 |
  | 10 | Norton Commando 750 | 1968 | 25 | Suzuki Hayabusa GSX1300R | 1999 |
  | 11 | Honda CB750 Four | 1969 | 26 | Honda Gold Wing GL1800 | 2001 |
  | 12 | Kawasaki H2 Mach IV 750 | 1972 | 27 | BMW R1200GS | 2004 |
  | 13 | Kawasaki Z1 900 | 1972 | 28 | KTM 1290 Super Duke R | 2014 |
  | 14 | Ducati 750 SS | 1974 | 29 | Kawasaki Ninja H2 | 2015 |
  | 15 | Moto Guzzi 850 Le Mans | 1976 | 30 | Ducati Panigale V4 | 2018 |

  The roster is a **decision**; the five stat values per card are **not yet specified** and must be sourced and cited from published figures during implementation, not invented. Electric motorcycles are deliberately excluded — an engine-capacity metric is meaningless for them.

### Interruption handling

- `FLAG_KEEP_SCREEN_ON` for the duration of a match. One line, and it removes the single most common cause of a dropped socket.
- On disconnect the host retains the match state and both devices show a **60-second grace countdown** naming the absent player. The guest re-resolves the same NSD service and resumes using a session token issued at handshake.
- After 60 seconds the match is abandoned and both devices return to the lobby with an explanation.
- A deliberate quit sends an explicit leave message so the opponent sees "X left the game" rather than a pointless countdown.
- Match state is held in memory only; surviving an app kill is out of scope.

### Solo practice mode

- Runs the identical rules engine with a local opponent in place of the remote one, driven through the same session interface. Not a parallel implementation.
- Opponent strategy: select the metric on which its current card ranks highest within the deck. Plausible without being unbeatable.
- Beyond being a feature, this is the primary manual-verification harness for the rules — the full loop including tiebreaks is exercisable on one device with no network.

### History and persistence

- Local persistence on device only. Nothing leaves the phone; no analytics, no telemetry, no network calls beyond the peer socket.
- Persisted per completed match: timestamp, deck, opponent display name, final score, and the cards won.
- Derived views: head-to-head record per opponent name, overall record and win rate, and most-won cards.
- Opponent identity is display name only — there are no accounts, so two different people using the same name are indistinguishable. Accepted.

### Presentation

- Card flip on reveal and cards sliding into the winner's pile. Compose animation only, no game engine.
- Sound effects for stat selection, card flip, round win, round loss, match victory and match defeat, with a persistent mute toggle. Sourced or generated as short clips; no background music in v1.
- `design/assets.csv` in the repo describes a fantasy creature deck and is **superseded** by this PRD. Its structure — a per-asset manifest with role, type, description and ratio — is worth retaining as the pattern for the Motorcycles asset list.

## Testing Decisions

### What makes a good test here

Tests assert on **externally observable game behaviour**: what state each player's device would render, and what the final outcome is. They do not assert on internal call sequences, private structure, or the shape of intermediate objects. A test that breaks when the engine is refactored without any change in what a player sees is a bad test.

Tests must be deterministic. Shuffling and dealing take an injected seed so that any match is exactly reproducible.

Tests run on the JVM. No emulator, no instrumentation, no Wi-Fi, no second device.

### The seam

**One seam: a pair of `MatchSession` instances communicating over an in-memory `Transport`.**

A `MatchSession` owns the rules engine and exchanges typed messages through the `Transport` interface. Production binds that interface to NSD+TCP; tests bind two sessions directly to each other in memory.

This single seam covers dealing, metric comparison, win direction, interactive tiebreaks, alternating turns, the reveal model, win piles, scoring, end-of-match — *and* the protocol, session lifecycle, reconnection and version/deck mismatch handling. A complete fifteen-round two-player match is a plain unit test.

Choosing one high seam over separate engine and transport seams is deliberate: the join between rules and protocol is exactly where host-authoritative games break, and testing them apart would leave that join uncovered while doubling the mocked boundaries.

The pure rules engine remains directly reachable beneath the seam for focused tests of dense logic — tiebreak recursion, win-direction comparison, age derivation — but **is never mocked**. There is exactly one test double in the codebase: the in-memory `Transport`.

### What is tested at that seam

- A full match from deal to result, asserting the winner and that both piles sum to 30.
- Win direction: higher value wins on `HIGH_WINS` metrics, lower wins on `LOW_WINS`.
- Age comparison derives correctly from stored year, including that the result is independent of the current date.
- Tiebreak: a tie prompts the same player to choose again; the tied metric is unavailable; a second tie prompts again; the round always resolves.
- The all-five-tie fallback behaves as specified.
- Turn alternation holds across fifteen rounds regardless of who wins.
- Reveal: the opponent's non-contested stats are absent from the state visible to a player mid-round, and present once the round is decided. This is a behavioural assertion about information leakage, and it protects the tiebreak's integrity.
- Deal is a partition — 15 and 15, no duplicates, no omissions — and is reproducible from a seed.
- Handshake rejects mismatched protocol version, deck id or deck content hash before any card is dealt.
- Disconnect within the grace window resumes the match at the correct round with scores intact; disconnect beyond it abandons cleanly.
- Deliberate quit is distinguishable from a dropped connection.
- Solo mode reaches a valid completed match through the same session interface.
- Deck manifest validation: exactly 30 cards, exactly 5 metrics, every card carries every metric, every metric declares a unit and a direction, every image reference resolves.

### Prior art

None — the repo is empty. These are the first tests in the codebase and they establish the pattern: plain JVM tests, one in-memory double, seeded determinism, assertions on player-visible state. Later decks and features are expected to be testable without adding new seams.

Compose UI, `NsdManager` behaviour, real socket handling, animation and audio are verified manually on device. They sit above the seam by design.

## Out of Scope

- **iOS or any non-Android platform.** The stack choice forecloses this without a rewrite; that was accepted deliberately.
- **Google Play distribution.** No signing pipeline, privacy policy, data-safety form or content rating in v1.
- **Play over the internet.** Same-Wi-Fi only. No relay server, no cloud, no NAT traversal, no room codes for remote play.
- **More than two players.**
- **Accounts, profiles, friends lists, cloud sync, leaderboards.**
- **Downloadable or in-app-purchasable deck packs.** The folder format supports adding decks; fetching them from a network does not exist.
- **A deck editor or any in-app deck authoring.** New decks are authored by hand outside the app.
- **Additional decks beyond Motorcycles.** The multi-deck architecture and picker exist and ship; only one deck populates them.
- **Cryptographically hiding the guest's hand from the host.** Explicitly rejected as disproportionate.
- **Surviving an app kill or device restart mid-match.** The 60-second grace window covers realistic interruptions; durable match persistence does not exist. Note that *completed match history* is persisted — this exclusion is about resuming an in-progress game.
- **Background music.** Sound effects only.
- **Landscape or tablet-optimised layouts.**
- **Localisation.** English only, UK units only, no unit toggle.
- **Accessibility beyond Compose defaults.** Content descriptions should be supplied where cheap, but no formal accessibility target is set.
- **Adverts, analytics, telemetry or crash reporting.**

## Further Notes

### Open question

**The all-five-metrics-tie fallback is an assumption, not a confirmed decision.** The PRD specifies that each player keeps their own card. This can only fire if two cards have identical values on all five metrics, which is impossible with thirty distinct real motorcycles — but the engine needs defined behaviour and the test suite asserts on it. Worth a one-line confirmation before implementation; alternatives are "the chooser wins" or "the defender wins".

### Scope warning

All four optional areas were taken into v1: solo practice, animations, sound effects, and match history. This is a substantially larger v1 than the core loop. In particular **match history is an independent vertical slice** — it pulls in a persistence layer and a stats screen that share nothing with the game engine or the networking.

Recommended slicing order for `/to-wbs`, so that something playable exists as early as possible:

1. **Rules engine and session seam** — dealing, comparison, tiebreaks, scoring, the in-memory transport, and the test suite. No UI. Nothing is playable, but everything downstream depends on it and it is where the subtle bugs live.
2. **Deck format and Motorcycles content** — manifest schema, validation, and the thirty cards with sourced stats and images. Content-heavy and largely independent of code, so it can run in parallel with slice 1.
3. **Solo mode with real UI** — card rendering, stat selection, reveal, win pile, end of match. First genuinely playable build, on one device, with no networking risk anywhere near it.
4. **Networking** — NSD discovery, symmetric lobby, invitations, handshake, and the two-device match. The highest-risk slice, deliberately attempted only once the rules and UI are known-good so that failures are unambiguously network failures.
5. **Reconnection** — keep-screen-on, grace window, resume, clean quit.
6. **Polish** — animations and sound.
7. **Match history** — persistence and stats screens.

### Risks

- **mDNS reliability is the principal technical risk.** It is defeated by client isolation, some mesh systems, and VPNs active on either device. The manual-address fallback is therefore load-bearing, not optional, and should be built alongside discovery rather than deferred.
- **Deck content is the principal effort risk.** Thirty cards × five sourced-and-cited stats, plus thirty licence-checked photographs, is real research work that is easy to underestimate and is on the critical path to a playable build.
- **Published motorcycle figures disagree between sources** — particularly dry weight (dry vs kerb vs wet) and top speed (claimed vs tested). Each deck should nominate a convention and record the source per card, or the game will assert things enthusiasts will dispute.

### Deferred but anticipated

The deck folder format is designed so that a second theme — the "Fast Cars" example from the original brief — requires no application change. Nothing in v1 should hard-code motorcycle vocabulary into the engine or the UI; metric labels, units and directions all come from the manifest.
