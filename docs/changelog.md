# Changelog

## [Unreleased]

- 2026-07-31 — [#1](https://github.com/rustycoopes/top-trumps-game/issues/1) Slice 0 — NSD and platform spike: confirmed no runtime permission prompt for NSD discovery on Android 13/14, no `MulticastLock` needed, auto-rename format (`"<name> (2)"`), and `resolveService` concurrency behaviour. Local Network Protection (API 36) remains untested pending hardware. See [slice-0-nsd-platform-spike.md § Delivered](features/top-trumps-core-game/WBS/slice-0-nsd-platform-spike.md#delivered).
- 2026-07-31 — [#2](https://github.com/rustycoopes/top-trumps-game/issues/2) Slice 1 — Walking skeleton: stood up the six-module structure, structurally-redacted `PlayerView`/`OpponentCardView` types, a synchronous `RulesEngine`, deck manifest loading through `DeckSource`, a `MatchSession` (host + guest) over `LoopbackTransport` round-tripping a real JSON codec, a trivial AI opponent, and an ugly Compose screen — one round of Top Trumps played solo, end to end, verified on a physical device. See [slice-1-walking-skeleton.md § Delivered](features/top-trumps-core-game/WBS/slice-1-walking-skeleton.md#delivered).
