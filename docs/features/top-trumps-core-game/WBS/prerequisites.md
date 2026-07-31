# Prerequisites — everything to prepare or install before Slice 0

> Part of the `top-trumps-core-game` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

This is not a vertical slice — it delivers nothing playable. It is the checklist of hardware, tooling, accounts and content that the slices assume already exist. Sorted so it sorts before `slice-0-*` alphabetically; nothing here is blocked by anything else.

## Hardware

- [ ] **Two physical Android phones, minimum**, on the same Wi-Fi. Slice 0 states this outright: an emulator cannot substitute — it sits behind NAT on `10.0.2.x` and mDNS multicast never crosses it, so neither an emulator-and-phone nor two emulators will ever discover each other. This blocks Slices 0, 4, 5 and 6 entirely.
- [ ] **Ideally three or more**, spanning Android 13, 14 and 15 — Slice 0 asks for "as many... as the household can supply," since NSD behaviour varies by OEM and version.
- [ ] **The single oldest/lowest-spec phone in the family**, specifically. Slice 6 says to test resilience on it ("OEM freeze timing varies enormously"), and Slice 7 says the same for animation jank ("invisible on a flagship").
- [ ] A way to sideload debug builds without the Play Store — USB + ADB, or "install unknown apps" enabled on each device.
- [ ] A home Wi-Fi network with no client isolation, for discovery testing.
- [ ] Optional but useful: access to a network that *does* defeat mDNS (client isolation, a mesh system, or an active VPN) to exercise the manual connect-by-address fallback Slice 4 calls load-bearing.

## Dev machine toolchain

- [ ] Android Studio (current stable), supporting `compileSdk 37`
- [ ] JDK 17
- [ ] Android SDK platforms/build-tools for API 26 (`minSdk`) through 37 (`compileSdk`)
- [ ] Kotlin 2.x plugin, plus `org.jetbrains.kotlin.plugin.compose`
- [ ] Gradle with Kotlin DSL support, ready for a version catalog and `build-logic` convention plugins (Slice 1 authors these, but Studio/Gradle must support them first)
- [ ] KSP (Room's annotation processor, needed by Slice 8)
- [ ] Library versions resolvable: `kotlinx-coroutines-core`, `kotlinx-serialization-json`, `kotlinx-datetime`, `androidx.annotation`, Coil 3, Room, and Turbine (test-only dependency)
- [ ] Layout Inspector / Compose compiler metrics — bundled with Studio, needed to verify recomposition counts in Slice 7

## Repo / CI setup

- [ ] A CI runner wired up (e.g. GitHub Actions) *before* Slice 1's acceptance criteria can actually be enforced — "adding `import android.util.Log` to `:core:rules` fails the build" and "a CI check rejects any `androidx.*` dependency in `:core:*`" both need a real pipeline, not just a developer's local Gradle run.
- [ ] `decks/` directory convention at repo root, ready to receive Slice 3's content via `assets.srcDir`.

## Image tooling (for Slice 3)

- [ ] A way to produce WebP, quality ~80, ~1080px long edge (e.g. `cwebp`, ImageMagick, or Squoosh) — the deck's 30 photographs must ship in this format, ≈120KB each, ≈3.6MB total.

## Content to source (Slice 3 — can start immediately, in parallel with everything else)

- [ ] Research roster of 30 real motorcycles, 1923–2018, internal-combustion only, per the PRD's deck-format roster
- [ ] Cited spec figures per card: engine capacity, top speed, model year, dry weight, length — sourced, never invented
- [ ] One CC-licensed Wikimedia Commons photograph per model, with licence, author and source URL recorded per card
- [ ] A nominated convention for weight (dry vs kerb vs wet) and top speed (claimed vs tested), to be recorded in the manifest — published figures disagree between sources

## Audio assets (for Slice 7)

- [ ] Six sound effects: stat selection, card flip, round win, round loss, match victory, match defeat — sourced or recorded, compatible with `SoundPool`

## Accounts / external services

- [ ] **None.** Worth stating explicitly: no backend, no analytics, no accounts, no Play Store listing are needed for any slice through Slice 8 — everything is a sideloaded APK talking peer-to-peer over local Wi-Fi. Don't set any of this up; it isn't on the critical path.

## Knowledge gates (not installable, but block work starting)

- [ ] Slice 0's four spike answers must be recorded before Slice 1 locks `targetSdk` and the manifest's permission set.
- [ ] Product sign-off on [TDD Open Question 3](../TDD.md#open-questions) (`CHOOSER_WINS` reversing the PRD's stated all-metrics-tie fallback) before Slice 2.
- [ ] Product decision on [TDD Open Question 4](../TDD.md#open-questions) (does the host stay host across a rematch, or does Player One rotate) before Slice 6.
