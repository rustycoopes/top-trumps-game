#!/usr/bin/env python3
"""List Todo issues in this repo's GitHub Project, grouped by feature track then slice then priority tier.

The skill uses this to get a deterministic shortlist so it doesn't re-derive the gathering,
slice-ordering, and tiering logic on every run. It intentionally does NOT make the final pick —
the ordering *within* a tier (and across tracks, if more than one has ready work) is a judgment
call that the model makes after reading the candidate issue bodies.

A "track" is either:
  - the legacy Platform Restructure track: issues carrying `framework-refactor` +
    `restructure-rN` (restructure-r1, restructure-r2, ...) — track key "restructure".
  - a new-style feature track (per the docs/features/<feature-slug>/ workflow): issues carrying
    a `<feature-slug>` label plus a `slice-N` label — track key is the feature-slug.

Within a track, work is preferred by slice number, lowest first — earlier slices are the
foundation later ones build on, so finish them first. Within a single slice, issues are bucketed
by priority tier.

The GitHub Project board is auto-detected: the repo's owner (from `gh repo view`) is used to
list that owner's projects, and the one whose title normalizes (lowercase, alphanumeric-only)
to a match against the repo name is used. Pass --owner/--project to skip detection and target
a specific board directly.

Usage:
    python todo_issues.py                                # every track, every slice
    python todo_issues.py --feature restructure           # legacy track only
    python todo_issues.py --feature prompt-versioning     # one new-style feature track
    python todo_issues.py --feature prompt-versioning --slice 2
    python todo_issues.py --status "In Progress"
    python todo_issues.py --owner rustycoopes --project 2 # skip auto-detection

Priority tiers (highest first): bug > enhancement > future-enhancement > (other/untiered)
Output: JSON on stdout:
    {"status": ..., "total": ..., "tracks": [
        {"feature": "restructure"|"<feature-slug>",
         "slices": [ {"slice": ..., "number": ..., "total": ..., "tiers": {...}}, ... ]},
        ...
    ]}
`tracks` and each track's `slices` are ordered lowest slice number first. Each candidate:
{"number", "title", "labels"}.
"""
import argparse
import json
import re
import subprocess
import sys

# Highest priority first. An issue is placed in the first tier whose label it carries.
TIER_ORDER = ["bug", "enhancement", "future-enhancement"]

RESTRUCTURE_SCOPE_LABEL = "framework-refactor"
RESTRUCTURE_SLICE_RE = re.compile(r"^restructure-r(\d+)$")
NEW_SLICE_RE = re.compile(r"^slice-(\d+)$")

# Labels that are never a feature-slug, even though they aren't a slice/tier label either.
NON_FEATURE_LABELS = {
    "bug", "documentation", "duplicate", "enhancement", "good first issue", "help wanted",
    "invalid", "question", "wontfix", "prerequisites", "future-enhancement", "russ",
    "manual-task", "modelsuggested", "intake", RESTRUCTURE_SCOPE_LABEL,
}


def _normalize(name):
    return re.sub(r"[^a-z0-9]", "", name.lower())


def _run_gh(args):
    try:
        out = subprocess.run(
            ["gh"] + args,
            capture_output=True, text=True, check=True,
            encoding="utf-8", errors="replace",
        ).stdout
    except FileNotFoundError:
        sys.exit("error: `gh` CLI not found on PATH.")
    except subprocess.CalledProcessError as e:
        sys.exit(f"error: gh {' '.join(args)} failed:\n{e.stderr}")
    return out


def resolve_project(owner, project):
    """Return (owner, project_number). Auto-detects whichever of owner/project wasn't passed
    explicitly by matching the current repo's name against the current owner's project titles."""
    if owner is None:
        repo_json = json.loads(_run_gh(["repo", "view", "--json", "owner,name"]))
        owner = repo_json["owner"]["login"]
        repo_name = repo_json["name"]
    else:
        repo_name = None

    if project is not None:
        return owner, project

    if repo_name is None:
        sys.exit("error: --project requires either an explicit --owner or a repo to infer one from.")

    projects = json.loads(_run_gh(["project", "list", "--owner", owner, "--format", "json"])).get("projects", [])
    target = _normalize(repo_name)
    matches = [p for p in projects if _normalize(p["title"]) == target
               or target.startswith(_normalize(p["title"])) or _normalize(p["title"]).startswith(target)]
    if len(matches) == 1:
        return owner, str(matches[0]["number"])
    if not matches:
        titles = ", ".join(f"{p['title']!r} (#{p['number']})" for p in projects) or "none"
        sys.exit(f"error: no GitHub Project for owner {owner!r} matches repo {repo_name!r}. "
                  f"Available: {titles}. Pass --project to specify one explicitly.")
    numbers = ", ".join(f"#{p['number']} {p['title']!r}" for p in matches)
    sys.exit(f"error: multiple GitHub Projects for owner {owner!r} match repo {repo_name!r}: "
              f"{numbers}. Pass --project to disambiguate.")


def fetch_items(owner, project):
    out = _run_gh(["project", "item-list", project, "--owner", owner, "--format", "json", "--limit", "300"])
    return json.loads(out).get("items", [])


def classify(labels):
    for tier in TIER_ORDER:
        if tier in labels:
            return tier
    return "other"


def track_and_slice(labels):
    """Return (track_key, slice_number) for an issue's labels, or (None, None) if it belongs to
    no recognized track."""
    label_set = set(labels)

    if RESTRUCTURE_SCOPE_LABEL in label_set:
        nums = [int(m.group(1)) for l in labels if (m := RESTRUCTURE_SLICE_RE.match(l))]
        if nums:
            return "restructure", min(nums)
        return None, None

    slice_nums = [int(m.group(1)) for l in labels if (m := NEW_SLICE_RE.match(l))]
    if not slice_nums:
        return None, None
    feature_slugs = [l for l in labels if l not in NON_FEATURE_LABELS and not NEW_SLICE_RE.match(l)]
    if len(feature_slugs) != 1:
        # Ambiguous or missing feature-slug label — can't place it in a track.
        return None, None
    return feature_slugs[0], min(slice_nums)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--feature", default=None,
                    help="restrict to one track: a feature-slug, or 'restructure' for the legacy "
                         "track; default: all tracks")
    ap.add_argument("--slice", type=int, default=None,
                    help="restrict to one slice number within the selected --feature")
    ap.add_argument("--status", default="Todo",
                    help="project Status value to filter by (default: Todo)")
    ap.add_argument("--owner", default=None,
                    help="GitHub Project owner; default: auto-detect from `gh repo view`")
    ap.add_argument("--project", default=None,
                    help="GitHub Project number; default: auto-detect by matching repo name "
                         "against the owner's project titles")
    args = ap.parse_args()

    owner, project = resolve_project(args.owner, args.project)

    # track -> slice number -> {tier: [candidates]}
    by_track = {}
    for item in fetch_items(owner, project):
        content = item.get("content") or {}
        number = content.get("number")
        if number is None:  # draft items with no linked issue
            continue
        if (item.get("status") or "") != args.status:
            continue
        labels = item.get("labels") or []
        feature, snum = track_and_slice(labels)
        if feature is None:
            continue
        if args.feature is not None and args.feature != feature:
            continue
        if args.slice is not None and args.slice != snum:
            continue
        tiers = by_track.setdefault(feature, {}).setdefault(snum, {t: [] for t in TIER_ORDER + ["other"]})
        tiers[classify(labels)].append({
            "number": number,
            "title": content.get("title", ""),
            "labels": labels,
        })

    tracks = []
    for feature in sorted(by_track):
        slices_for_feature = by_track[feature]
        slices = []
        for snum in sorted(slices_for_feature):
            tiers = slices_for_feature[snum]
            for t in tiers:
                tiers[t].sort(key=lambda c: c["number"])
            slice_label = f"restructure-r{snum}" if feature == "restructure" else f"slice-{snum}"
            slices.append({
                "slice": slice_label,
                "number": snum,
                "total": sum(len(v) for v in tiers.values()),
                "tiers": tiers,
            })
        tracks.append({
            "feature": feature,
            "slices": slices,
        })

    print(json.dumps({
        "status": args.status,
        "total": sum(s["total"] for t in tracks for s in t["slices"]),
        "tracks": tracks,
    }, indent=2))


if __name__ == "__main__":
    main()
