# A foreground service for match duration

**Status:** Proposed
**Date:** 2026-07-30
**Feature:** [`top-trumps-core-game`](../features/top-trumps-core-game/TDD.md)

## Context

The PRD states that `FLAG_KEEP_SCREEN_ON` "removes the single most common cause of a dropped socket", backed by a 60-second grace window.

That is factually wrong about which cause is most common. Keep-screen-on prevents the *display timeout* — the least likely interruption in a game the user is actively looking at. The dominant interruption is **backgrounding**, and this app actively invites it: the PRD's own content pitch is that players want to learn about the machines, so "let me look that Vincent up" is a designed-for behaviour. Notification shade, incoming call, camera, app switch — keep-screen-on addresses none of them.

What actually happens when the app is backgrounded:

- The process becomes cached and is then frozen by the cgroup freezer. AOSP debounce is in the minutes; **OEM ROMs (Samsung, Xiaomi, OPPO, Huawei) freeze within seconds** and several will kill outright.
- A frozen process runs no code. The heartbeat stops. The grace countdown stops.
- The peer sees a dead player within ~6 seconds and starts its own countdown.
- Recent Android versions actively reset TCP sockets belonging to frozen UIDs, so the socket cannot be assumed to survive even if the process thaws in time.

Doze and App Standby are *not* relevant at this timescale — they need screen-off and stationary for tens of minutes.

## Decision

Run a **`connectedDevice` foreground service for the duration of a match**. Started from the foreground when the match begins, `startForeground()` within 5 seconds, stopped the instant the match ends. Not used in the lobby, and not in solo mode.

Keep-screen-on is retained but demoted to what it actually is — a display-timeout fix — and scoped to the match screen via `DisposableEffect` on `LocalView` rather than the whole Activity.

**Reconnect remains the primary designed path regardless**, because no foreground service defeats OEM task-killers or swipe-from-recents. To make it fast, the host's port and session token ride the invite payload so the guest can re-dial directly with no NSD re-resolve in the common case (~200ms rather than ~3s), falling back to discovery only if the direct dial fails.

Consequent to this, `MatchSession` is held in an **application-scoped holder whose lifetime mirrors the service**, exposed to the UI by a thin Activity-scoped ViewModel that only forwards state and intents.

## Alternatives considered

**Keep-screen-on alone, as the PRD specifies.** One line, zero permissions, no notification. Rejected because user story 57 — "a brief interruption should not end the match" — would fail on the first day of real use. It is roughly a 10% solution to the stated problem.

**No service; make reconnect the normal flow.** Defensible, and reconnect must be built anyway. Rejected as the *primary* strategy because it makes the rarest, hardest-to-test path also the most frequently exercised one: every glance at another app would trigger a full drop-detect, countdown, re-dial and resync. Correctness aside, it would feel broken.

**`specialUse` foreground service type.** Rejected — it requires Play Console justification, which is irrelevant for a sideloaded app, and `connectedDevice` is the semantically correct type regardless.

## Consequences

**Easier:** story 57 becomes achievable rather than aspirational; reconnect becomes a rare fallback rather than the hot path, so its inevitable rough edges matter less; the notification ("Top Trumps — playing Amy, round 7 of 15") is arguably a feature on a family device.

**Cost is genuinely low:** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` and `CHANGE_NETWORK_STATE` are all **normal** permissions with no prompt. The `connectedDevice` type requires the app to hold one of a documented permission set to justify it, and `CHANGE_NETWORK_STATE` qualifies. `POST_NOTIFICATIONS` *is* a runtime permission, but **the service runs whether or not it is granted** — denial only hides the notification. So the PRD's "no runtime prompts at all" promise survives intact.

**Harder:** an extra ~80 lines and a service lifecycle to keep in step with match lifecycle; a foreground service started from the background is prohibited (API 31+), which constrains where the match can be started from; `targetSdk 34+` makes foreground service types mandatory and enforced, so the type must be declared correctly.

**Does not save us from:** swipe-away-from-recents on many OEMs, or aggressive OEM battery managers. Those kill the process regardless. Reconnect covers them.
