# Next Slice 1 issue to pick up

## Selection: Issue #43 — "POST /auth/login returns bare 204 (relies on client-side JS to redirect)"

Label: `bug`
Status: Todo
URL: https://github.com/rustycoopes/organize-me/issues/43

## Why this one

**1. It is the only open bug in Slice 1, and bugs outrank everything else.**
The Slice 1 Todo column contains exactly one bug (#43), two feature enhancements
(#17 Sidebar shell, #23 Playwright E2E), and a stack of `future-enhancement`
items (#29–#42). The project's own triage convention is
bugs → enhancements → future-enhancements. #43 sits at the top of that ordering
with no competition in the bug tier.

**2. It is a genuine correctness defect, not cosmetic.**
`POST /api/v1/auth/login` returns a bare `204 No Content` and only *appears* to
work because login.html runs `window.location.href = '/profile'` after the
`fetch()` resolves. Any non-fetch / JS-disabled consumer of the endpoint is left
stranded on /login with no navigation. The endpoint is effectively incorrect on
its own; it is masked by client JS.

**3. It is the same class of defect as an already-fixed, higher-severity bug.**
It was split out of #27 (the Google sign-in hang that never returned to the app).
#27 was fixed by making the callback issue a proper 302; #43 is the identical
latent shape on the email/password path. Closing it makes the auth surface
consistent and finishes the job started in #27 rather than leaving a known twin
defect behind.

**4. It is small, well-scoped, and low-risk.**
The fix is a bounded decision: make `/auth/login` return a `302 → /profile` on
success (mirroring the #27 fix), or explicitly document the 204-plus-JS contract.
Either way it is a short change to `app/api/v1/auth.py` (and possibly
login.html), touching the auth core that Slices 1.2–1.5 depend on — worth getting
right before more UI (Slice 1.7) is layered on top.

## Honest caveat

The reporter tagged #43 "Not urgent — works today via the JS path; filing so it's
tracked rather than lost." So in real user-impact terms it is a low-severity bug.
If the intent is to keep building the core product feature-first and defer the
latent bug, the strongest alternative is:

- **#17 — Slice 1.7: Sidebar shell + placeholder pages** — the next sequential
  core feature slice in the roadmap (1.1–1.6 are all Done). It is the primary
  product-build enhancement and the natural next step to keep Slice 1 moving,
  ahead of #23 (E2E test automation, which is more valuable once 1.7 exists) and
  ahead of every `future-enhancement`.

## Recommendation

Pick up **#43** now: it is the only bug in Slice 1, it is a real correctness gap
that mirrors an already-fixed twin (#27), and it is a small, low-risk fix to the
auth core. Follow it immediately with **#17 (Slice 1.7)** as the next feature slice.
