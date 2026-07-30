# Next issue to work on

## Selected pick

**#17 — Slice 1.7: Sidebar shell + placeholder pages**

**Priority tier:** `enhancement` (the top occupied tier)

## Why this one

### Tier selection
The helper returned 16 Todo Slice 1 issues across three tiers:

- **`bug`** — empty.
- **`enhancement`** — #17 and #23.
- **`future-enhancement`** — #29–#42 (14 issues).

Priority is strict across tiers (bug → enhancement → future-enhancement). With no
bugs open, the choice is made entirely *within* the `enhancement` tier, between #17
and #23. Everything in `future-enhancement` is out of contention for this run.

### Choosing within the enhancement tier
The deciding factor is dependency/unblocking order:

- **#23 (Playwright E2E suite) is blocked by #17.** Its "Blocked by" list is #15, #16,
  and #17. I verified #15 (CLOSED) and #16 (CLOSED) are done, but **#17 is still open
  (Todo)** — so #23 cannot cleanly start yet. On top of the formal block, #23's own
  acceptance criteria require asserting "sidebar nav order/presence across at least two
  authenticated routes," which is exactly the shell that #17 builds. Starting #23 first
  would mean testing UI that doesn't exist.

- **#17 is ready to start.** Its only "Blocked by" is #12 (Slice 1.2 auth), which I
  confirmed is CLOSED / Done. Its own blocker being resolved plus it being the blocker
  of #23 makes #17 the unblocker and the correct foundational piece to land first.

- **#17 is also the more foundational piece** — a persistent authenticated app shell
  (sidebar + placeholder routes) that later slices hang their real content on, whereas
  #23 is verification layered on top of delivered features.

So #17 goes first: it is ready now, and it directly unblocks #23. #23 is the runner-up
and becomes the natural next pick once #17 lands.

## Caveat
None blocking. #17's dependency (#12) is already Done, so it is a clean start. No
Slice 1 issue is currently `In Progress` on the board.
