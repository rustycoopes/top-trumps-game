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

- [ ] A throwaway app registers and discovers `_toptrumps._tcp` on two physical devices on the same Wi-Fi
- [ ] The manifest contains only `INTERNET` and `ACCESS_NETWORK_STATE`, and no runtime permission dialog appears — **or** the exact permission demanded is recorded
- [ ] Discovery is confirmed working with no `MulticastLock` held
- [ ] Current Local Network Protection status is recorded, with the Android version it applies to
- [ ] Behaviour of concurrent `resolveService` calls on these devices is recorded
- [ ] Whether `serviceType` returns with a trailing dot is recorded per device
- [ ] Auto-rename behaviour on name collision is observed and its format noted (as evidence it must not be parsed)
- [ ] Findings are written into [TDD Open Questions](../TDD.md#open-questions), and the PRD's permission claim is confirmed or corrected

## Testing

No automated tests — this is a manual spike and the deliverable is knowledge, not code. The app is deleted afterwards.

The one thing to be rigorous about is recording **which device and which Android version** produced each observation. NSD behaviour varies by OEM and version, and an undated, unattributed note will be worthless in three months when something misbehaves.
