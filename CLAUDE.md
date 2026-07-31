# CLAUDE.md

Guidance for working in this repo.

## What this is

A local, peer-to-peer Top Trumps card game for Android. No accounts, no server, no
internet dependency beyond same-Wi-Fi peer discovery (NSD/mDNS) and a direct TCP socket
between two phones. See `docs/features/top-trumps-core-game/PRD.md` and `TDD.md` for the
full design.

## Planning docs and workflow

- `docs/features/<feature-slug>/PRD.md` — product requirements
- `docs/features/<feature-slug>/TDD.md` — technical design, module structure, open questions
- `docs/features/<feature-slug>/WBS/slice-N-*.md` — one file per vertical slice: what to
  build, design notes, acceptance criteria, testing approach
- `docs/adr/` — architecture decision records, one per non-obvious design call

Slices become GitHub issues via `/to-issues`, get worked via `/to-implementation`, and pick
order is decided via `/next-issue`. Don't re-slice or re-scope a WBS file from inside
`/to-implementation` — that's `/to-wbs`'s job; flag it back if a slice looks wrong instead of
improvising around it.

## Branch naming

- `slice-N-<short-name>` for a WBS slice's implementation (e.g. `slice-1-walking-skeleton`)
- `fix/<short-name>` for a bug fix not tied to a slice
- `spike/<short-name>` for throwaway exploration that is **not** intended to merge into
  `main` (e.g. Slice 0's NSD platform spike) — push for backup/reference, no PR required

## Module structure (from Slice 1 onward)

Six Gradle modules, per the TDD's module-structure ADR:

| Module | Plugin | Android? |
|---|---|---|
| `:core:rules`, `:core:decks`, `:core:session`, `:core:ai` | `kotlin("jvm")` | Never |
| `:platform:net`, `:feature:history`, `:app` | Android | Yes |

`:core:*` may depend only on `kotlinx-coroutines-core`, `kotlinx-serialization-json`,
`kotlinx-datetime` and `androidx.annotation` — enforced by CI. Adding `import android.*`
anywhere in `:core:*` should fail the build.

**Toolchain:** `minSdk 26`, `targetSdk 35` (deliberately held back — see TDD §1), `compileSdk`
tracks the newest installed platform, JVM target 17, Kotlin 2.x, Gradle Kotlin DSL.

## Testing

Primary seam (per the TDD): a pair of `MatchSession` instances over an in-memory
`Transport`. Plain JVM tests, no emulator, no instrumentation. `NsdManager` behaviour,
Compose UI, animations, audio and foreground-service lifecycle are manual, on two physical
devices — **an emulator and a phone will never discover each other over NSD.**

## Commits

Imperative mood, focused on why over what. Co-author trailer:
`Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`.
