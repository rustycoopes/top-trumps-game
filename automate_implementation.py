#!/usr/bin/env python3
"""
Orchestrate sequential issue implementation using Claude Code.

Usage:
  python automate_implementation.py issues_config.json
  python automate_implementation.py --issues 123 456 789

Each issue gets a fresh interactive Claude session where you can:
- Respond to any questions Claude asks
- Run additional skills or commands
- Enable remote control
- Work through the implementation naturally

The script monitors GitHub for closure and moves to the next issue.
"""

#import typing_extensions
import json
import subprocess
import sys
import time
import argparse
from pathlib import Path
from datetime import datetime


def load_config(config_path: str) -> dict:
    """Load issues from JSON config file."""
    path = Path(config_path)
    if not path.exists():
        print(f"Error: Config file not found: {config_path}")
        sys.exit(1)

    with open(path) as f:
        return json.load(f)


def check_issue_closed(issue_number: int, repo: str = "organize-me") -> bool:
    """Check if a GitHub issue is closed using gh CLI."""
    try:
        result = subprocess.run(
            ["gh", "issue", "view", str(issue_number), "-R", f"rustycoopes/{repo}", "--json", "state"],
            capture_output=True,
            text=True,
            timeout=10
        )
        if result.returncode == 0:
            data = json.loads(result.stdout)
            print(f"Issue #{issue_number} state: {data.get('state')}")
            return data.get("state") == "CLOSED"
    except Exception as e:
        print(f"Warning: Could not check issue status: {e}")
    return False


def wait_for_issue_closure(issue_number: int, repo: str = "organize-me", timeout_minutes: int = 120) -> bool:
    """
    Poll for issue closure after session ends.

    Args:
        issue_number: GitHub issue number
        repo: Repository name
        timeout_minutes: Max time to wait before giving up

    Returns:
        True if issue closed, False if timeout
    """
    print(f"\n[INFO] Monitoring issue #{issue_number} for closure...")

    start_time = time.time()
    timeout_seconds = timeout_minutes * 60
    check_interval = 15  # seconds between checks

    while (time.time() - start_time) < timeout_seconds:
        if check_issue_closed(issue_number, repo):
            print(f"✓ Issue #{issue_number} is CLOSED")
            return True

        elapsed = int(time.time() - start_time)
        print(f"  ({elapsed}s elapsed, still open...)", end="\r")
        time.sleep(check_interval)

    print(f"\n⚠ Timeout waiting for issue #{issue_number} to close after {timeout_minutes} minutes")
    return False


def run_implementation(issue_number: str, auto_mode: bool = False) -> bool:
    """
    Spawn an interactive Claude session for this issue.

    Args:
        issue_number: GitHub issue number
        auto_mode: If True, enable Claude auto mode

    Returns:
        True if session completed successfully, False otherwisecalude
    """
    print(f"\n{'='*70}")
    print(f"STARTING: Issue #{issue_number}")
    if auto_mode:
        print(f"(Auto Mode Enabled)")
    print(f"{'='*70}\n")

    try:
            # Spawn interactive Claude session with the to-implementation skill
        cmd = ["claude"]
        if auto_mode:
            cmd.append("--permission-mode")
            cmd.append("auto")
        cmd.append("/to-implementation")
        cmd.append(f"{issue_number}")
        
        cmdString = f'claude "/to-implementation {issue_number}" --permission-mode auto --remote-control claudecode{issue_number}'
        print(cmdString)
        # print(f"[DEBUG] Running: {cmd}")


        process = subprocess.Popen(
            cmdString,
            shell=True,
            # Don't capture I/O - let it be fully interactive in the terminal
            stdin=None,
            stdout=None,
            stderr=None,
        )

        # Wait for the session to complete (user closes it)
        return_code = process.wait()

        if return_code == 0:
            print(f"\n[INFO] Session for issue #{issue_number} completed successfully")
            return True
        else:
            print(f"\n[WARNING] Session for issue #{issue_number} exited with code {return_code}")
            return False

    except FileNotFoundError:
        print("\n[ERROR] Claude CLI not found. Make sure Claude Code is installed and in your PATH.")
        return False
    except Exception as e:
        print(f"\n[ERROR] Failed to spawn Claude session: {e}")
        return False


def process_issues(issues: list[int], repo: str = "organize-me", skip_wait: bool = False, auto_mode: bool = False):
    """
    Process a list of issues sequentially.

    Args:
        issues: List of issue numbers
        repo: Repository name
        skip_wait: If True, don't wait for issue closure before moving to next
        auto_mode: If True, enable Claude auto mode for each session

EXAMPLE : python .\automate_implementation.py --issues 1, 2, 3python .\automate_implementation.py --issues 17, 18 --repo doc-library


    """
    total = len(issues)
    print(f"\n{'='*70}")
    print(f"AUTOMATION: Sequential Issue Implementation")
    print(f"{'='*70}")
    print(f"Processing {total} issue(s): {issues}")
    print(f"Repository: rustycoopes/{repo}")
    if auto_mode:
        print(f"Mode: AUTO")
    print(f"Started: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"{'='*70}\n")

    completed = []
    failed = []

    for idx, issue_num in enumerate(issues, 1):
        print(f"\n[{idx}/{total}] Processing issue #{issue_num}...")

        # Run the implementation session
        session_ok = run_implementation(f"rustycoopes/{repo}#{issue_num}", auto_mode=auto_mode)

        if not session_ok:
            print(f"✗ Session failed for issue #{issue_num}")
            failed.append(issue_num)
            continue

        # Monitor for closure
        if skip_wait:
            print("[INFO] Skipping closure check (--skip-wait flag)")
            completed.append(issue_num)
        else:
            if wait_for_issue_closure(issue_num, repo):
                completed.append(issue_num)
            else:
                # Even if it times out waiting, mark as attempted
                completed.append(issue_num)
                print("(You can verify closure manually)")

        # Brief pause before next issue
        if idx < total:
            print(f"\n[INFO] Moving to next issue in 5 seconds...")
            time.sleep(5)

    # Summary
    print(f"\n\n{'='*70}")
    print("SUMMARY")
    print(f"{'='*70}")
    print(f"Completed: {len(completed)}/{total}")
    if completed:
        print(f"  ✓ Issues: {', '.join(f'#{i}' for i in completed)}")
    if failed:
        print(f"Failed: {len(failed)}/{total}")
        print(f"  ✗ Issues: {', '.join(f'#{i}' for i in failed)}")
    print(f"Finished: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"{'='*70}\n")


def main():
    parser = argparse.ArgumentParser(
        description="Automate sequential issue implementation with Claude Code"
    )
    parser.add_argument(
        "config",
        nargs="?",
        help="Path to JSON config file with issue list"
    )
    parser.add_argument(
        "--issues",
        type=int,
        nargs="+",
        help="Issue numbers to process (alternative to config file)"
    )
    parser.add_argument(
        "--repo",
        default="organize-me",
        help="Repository name (default: organize-me)"
    )
    parser.add_argument(
        "--skip-wait",
        action="store_true",
        help="Don't wait for issue closure before moving to next"
    )
    parser.add_argument(
        "--auto",
        action="store_true",
        help="Enable Claude auto mode for each session"
    )

    args = parser.parse_args()

    # Get issues from either config file or command-line args
    if args.issues:
        issues = args.issues
    elif args.config:
        config = load_config(args.config)
        issues = config.get("issues", [])
        if not issues:
            print("Error: No 'issues' array found in config file")
            sys.exit(1)
    else:
        parser.print_help()
        sys.exit(1)

    if not issues:
        print("Error: No issues to process")
        sys.exit(1)

    try:
        process_issues(issues, repo=args.repo, skip_wait=args.skip_wait, auto_mode=args.auto)
    except KeyboardInterrupt:
        print("\n\n[INFO] Interrupted by user")
        sys.exit(130)


if __name__ == "__main__":
    main()
