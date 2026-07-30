"""Starts this service locally for development: `uvicorn --reload`, plus the CSS watcher
(`scripts/build_css.py --watch`) once this app has adopted one — see organize-me's own
`scripts/build_css.py` for the Tailwind/chrome-package build pipeline to copy when this app gains
its own templates/static assets. This is the conventional per-app dev-server entrypoint every
hosted app owns (see organize-me's
docs/adr/local-dev-environment-launcher-orchestration-boundary.md) — organize-me's own
`scripts/local_dev.py` invokes this file directly, never a hardcoded run command. Port comes from
the `PORT` environment variable, the same convention `scripts/local_dev.py` relies on to invoke
this and every other app's `scripts/dev.py` uniformly.

Always runs the CSS watcher once `scripts/build_css.py` exists — no `--no-css-watch` escape hatch
(a developer who wants uvicorn alone can just run it directly instead of via this script).

Usage:
    uv run python scripts/dev.py            # PORT defaults to 8000
    PORT=8010 uv run python scripts/dev.py
"""

import os
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BUILD_CSS_SCRIPT = REPO_ROOT / "scripts" / "build_css.py"


def main() -> None:
    port = os.environ.get("PORT", "8000")

    uvicorn_cmd = [sys.executable, "-m", "uvicorn", "app.main:app", "--reload", "--port", port]
    processes = [("uvicorn", subprocess.Popen(uvicorn_cmd, cwd=REPO_ROOT))]

    # This freshly-scaffolded app has no CSS/chrome build pipeline yet - skip the watcher rather
    # than fail outright, so `scripts/dev.py` still runs before that pipeline exists. Once this
    # app adds scripts/build_css.py (copy an existing hosted app's), it's picked up automatically
    # here, matching every other app's scripts/dev.py shape.
    if BUILD_CSS_SCRIPT.exists():
        css_cmd = [sys.executable, str(BUILD_CSS_SCRIPT), "--watch"]
        processes.append(("css-watch", subprocess.Popen(css_cmd, cwd=REPO_ROOT)))

    try:
        while all(p.poll() is None for _, p in processes):
            time.sleep(0.5)
        for label, p in processes:
            code = p.poll()
            if code is not None:
                print(f"[{label}] exited with code {code}", file=sys.stderr)
    except KeyboardInterrupt:
        # Expected way to stop this script; not a failure.
        pass
    finally:
        for _, p in processes:
            if p.poll() is None:
                p.terminate()
        for _, p in processes:
            try:
                p.wait(timeout=10)
            except subprocess.TimeoutExpired:
                p.kill()


if __name__ == "__main__":
    main()
