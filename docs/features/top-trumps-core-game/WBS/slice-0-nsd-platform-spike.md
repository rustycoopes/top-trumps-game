# Slice 0 — NSD and platform spike

> Part of the `top-trumps-core-game` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

**Delivers:** A definitive yes/no on whether peer discovery works with zero runtime permission prompts — the claim the entire product premise rests on.

## What to build

A **throwaway** app. Nothing from this slice ships; it exists to answer a question before any architecture is committed.

A single screen that registers an `_toptrumps._tcp` service under a name you can set, browses for others, lists what it finds, and resolves one on tap. The manifest declares **only** `INTERNET` and `ACCESS_NETWORK_STATE` — nothing else, deliberately, because the point is to discover whether anything else is demanded.

Run it on **two physical devices** across as many of Android 13, 14 and 15 as the household can supply, with `targetSdk` set to the value the real app intends to ship.

Answer four questions and write the answers down:

1. Does registration and discovery work with no runtime prompt? In particular, is `NEARBY_WIFI_DEVICES` demanded on API 33+?
2. Does discovery still work with no `MulticastLock` held? The TDD asserts it is unnecessary for `NsdManager`; confirm it.
3. What is the current status of **Local Network Protection**? Has it moved from opt-in to enforced on any shipping release?
4. Does `resolveService` fail with `FAILURE_ALREADY_ACTIVE` under concurrent calls on these devices, and does it recover?

While the app is running, also confirm the two behaviours most likely to waste a day later: that `serviceType` sometimes comes back from `onServiceFound` with a **trailing dot**, and that a name collision triggers Android's **auto-rename**.

## Design notes

The reasoning behind each question, and the hedge if question 1 fails, is in [TDD Open Questions 1–2](../TDD.md#open-questions). The lobby design that depends on the answers is [TDD §8](../TDD.md#8-networking-on-the-device) and the [instance-name ADR](../../../adr/top-trumps-core-game-discovery-instance-name.md).

If `NEARBY_WIFI_DEVICES` turns out to be required, the hedge is to declare it with `usesPermissionFlags="neverForLocation"` — one prompt, no location implication, survivable. But the PRD's Solution section currently promises "no accounts, no sign-in, no internet connection, no typing in codes" as a differentiator, and that copy would need revisiting.

**An emulator cannot substitute for a device here.** The emulator sits behind NAT on `10.0.2.x` with its own network stack and mDNS multicast does not cross it — an emulator and a phone will never discover each other, and neither will two emulators. Two physical devices are mandatory.

## Blocked by

None — can start immediately.

## Acceptance criteria

- [x] A throwaway app registers and discovers `_toptrumps._tcp` on two physical devices on the same Wi-Fi
- [x] The manifest contains only `INTERNET` and `ACCESS_NETWORK_STATE`, and no runtime permission dialog appears — **or** the exact permission demanded is recorded
- [x] Discovery is confirmed working with no `MulticastLock` held
- [ ] Current Local Network Protection status is recorded, with the Android version it applies to — **partial:** no API 36 device was available to test; no data either way
- [x] Behaviour of concurrent `resolveService` calls on these devices is recorded
- [x] Whether `serviceType` returns with a trailing dot is recorded per device
- [x] Auto-rename behaviour on name collision is observed and its format noted (as evidence it must not be parsed)
- [x] Findings are written into [TDD Open Questions](../TDD.md#open-questions), and the PRD's permission claim is confirmed or corrected

## Testing

No automated tests — this is a manual spike and the deliverable is knowledge, not code. The app is deleted afterwards.

The one thing to be rigorous about is recording **which device and which Android version** produced each observation. NSD behaviour varies by OEM and version, and an undated, unattributed note will be worthless in three months when something misbehaves.

## Delivered

- **Issue:** [#1](https://github.com/rustycoopes/top-trumps-game/issues/1)
- **Branch:** `spike/slice-0-nsd` (pushed for backup/reference only — per this slice's own "nothing ships" rule, never merged into `main`)
- **Date:** 2026-07-31
- **Devices:** Samsung Galaxy S21 Ultra, `SM-G998U`, Android 13 (API 33); Samsung Galaxy Tab S7 FE, `SM-T733`, Android 14 (API 34)

Built the throwaway single-Activity Views app exactly as specified (Gradle 8.9 / AGP 8.6.1 / Kotlin 1.9.24, `compileSdk`/`targetSdk 35`, `minSdk 26` — `compileSdk` deliberately pinned to 35 rather than the real product's 37, to minimise build-tooling risk on a one-off app). One real bug surfaced and fixed during bring-up: the manifest declared `Theme.Material.Light` but `MainActivity` extends `AppCompatActivity`, which requires a `Theme.AppCompat` descendant — crashed on launch with `IllegalStateException` until switched to `Theme.AppCompat.Light.NoActionBar`.

All four spike questions plus both extra checks were run on both physical devices:

1. **No runtime permission prompt on either device**, confirmed both visually and via `adb shell dumpsys package` (only `INTERNET`/`ACCESS_NETWORK_STATE` ever attached). `NEARBY_WIFI_DEVICES` is not demanded on API 33 or 34. **PRD's no-permissions claim confirmed**, no correction needed.
2. **No `MulticastLock` held** at any point; discovery worked regardless.
3. **Local Network Protection: unresolved.** Neither test device runs Android 16/API 36 — no data either way. Remains open until API 36 hardware is available.
4. **Concurrent `resolveService`:** on the S21 Ultra (API 33), only the first of several back-to-back calls succeeds; the rest fail with `FAILURE_ALREADY_ACTIVE` (`errorCode=3`). Confirmed **not a permanent wedge** — a later solo resolve for the same service succeeded. The Tab S7 FE (API 34) accepted all concurrent calls with no failures — a real cross-version difference, recorded but not acted on (the design already avoids relying on concurrent resolves).
5. **Trailing dot:** confirmed on both devices — `onServiceFound` reports `serviceType = "_toptrumps._tcp."`.
6. **Auto-rename:** confirmed by registering the same name (`"Spike"`) on both devices — the second registrant's `onServiceRegistered` callback returned `actualServiceName = "Spike (2)"`. Format is `"<name> (<n>)"`.

Full findings written into [TDD Open Questions 1 and 2](../TDD.md#open-questions). No deviations from the acceptance criteria other than the LNP item, which is a hardware-availability gap rather than a spike failure.
