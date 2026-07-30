# Next-issue selection — Slice 1

## Pick

**#17 — Slice 1.7: Sidebar shell + placeholder pages**

## Priority tier

**`enhancement`** (the top occupied tier this run).

## Reasoning

### Tiers considered

The helper returned 16 `Todo` Slice 1 issues in three tiers:

- **`bug`** — empty. Nothing broken to fix, so this tier is out of contention.
- **`enhancement`** — the top non-empty tier, and therefore the only tier in contention:
  - #17 Sidebar shell + placeholder pages
  - #23 Slice 1.8: Automated E2E UX tests (Playwright)
- **`future-enhancement`** — 14 issues (#29–#42). Strictly lower priority than `enhancement`, so all excluded this run. Several are also "decide whether to…" product-decision items (e.g. #37 column-level encryption, #39 robots.txt/SEO, #42 authenticated-visitor-on-`/`) rather than clean code starts.

Per the strict tier order (bug → enhancement → future-enhancement), selection happens **within `enhancement`**, between #17 and #23.

### Why #17 beat #23

- **#17 unblocks #23.** Issue #23's own body lists its blockers as `#15, #16, #17`. #15 and #16 are already CLOSED, but **#17 is still open** — so #17 is the *only* remaining thing standing between the project and being able to start #23. Choosing the unblocker over the blocked is exactly the skill's tie-breaker (dependency-first), and this is the textbook case the skill even cites.
- **#17 is actually ready.** Its sole blocker, #12 (Slice 1.2 auth), is CLOSED, so there is nothing preventing it from starting now.
- **#23 is not ready.** It cannot cleanly start until #17 lands, so starting it now would mean building E2E tests for a sidebar/nav shell that doesn't exist yet.
- **#17 is foundational.** It establishes the persistent authenticated app shell (sidebar nav + placeholder routes `/dashboard`, `/upload`, `/processing`, `/logs`, `/prompt`, `/settings`, `/profile`) that later slices fill in and that the E2E suite is meant to assert against.

Not a close call — #17 is the clear pick: same tier as its only rival, ready to start, and a hard dependency of that rival.

### Caveat

None. #17 is a concrete build task (no product decision required) with all blockers closed. No Slice 1 issue is currently `In Progress` on the board.
