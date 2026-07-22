#!/usr/bin/env sh
# scripts/check-beads-pr-boundary.sh
#
# CI arm of the STALE WORKER-SNAPSHOT guard (rf2-ia8o7). Fails a pull
# request whose diff carries the beads DATABASE — the artefact `bd`
# auto-stages in every checkout and that a worker branch must never ship.
# See scripts/git-hooks/lib/check-beads-boundary.sh for the diagnosis; this
# script only chooses WHAT to classify. Both sides share one classifier so
# the local hook and the CI gate can never drift apart.
#
# ENFORCEMENT SHAPE
#
#   pull_request  -> enforced. Every PR, no path filter, no branch filter:
#                    the tracker database has no business in ANY PR, and a
#                    branch-name filter would be trivially sidestepped.
#   anything else -> passes with an explanatory line. Pushes to main ARE the
#                    mayor's checkpoint flow; blocking them would break the
#                    very thing the durable fix depends on.
#
# The base ref is supplied by the caller rather than assumed, and a missing
# one FAILS CLOSED: a gate that cannot see the diff certifies nothing.
#
# WHAT IT DIFFS: THE BRANCH DELTA, NOT TWO TREE ENDPOINTS (rf2-5z20y)
#
#   The comparison runs from `git merge-base BASE HEAD` to HEAD — the changes
#   this branch INTRODUCED — never from BASE's tip to HEAD.
#
#   A two-endpoint `git diff BASE HEAD` reports every path where the two trees
#   differ, including paths only BASE moved. In this repository that is not a
#   corner case: the mayor checkpoints `.beads/issues.jsonl` to main on
#   essentially every loop tick, so any worker branch that forked before the
#   last checkpoint would be told it had committed tracker contamination it
#   never touched — a false RED with the wrong remedy attached, on most open
#   branches, within minutes of this gate being wired. Reproduced on real
#   history: branch bf8eb01b vs origin/main 1233189c reports
#   `.beads/issues.jsonl` two-dot and reports nothing from the merge base.
#
#   Endpoint-only good/bad fixtures cannot see this defect, which is why it
#   shipped green. The regression test is a DIVERGED-HISTORY SEQUENCE — fork,
#   advance the base with a beads-only checkpoint, then assert — in
#   scripts/git-hooks/test-pre-commit.sh (layer 5).
#
#   An unresolvable merge base FAILS CLOSED like a missing base ref. The usual
#   cause is a shallow clone that does not contain the branch point; on GitHub
#   Actions use `actions/checkout` with `fetch-depth: 0`.
#
# WHY `--no-renames` (rf2-ajbgq)
#
#   Git detects renames by default and `--name-only` then reports the
#   DESTINATION alone. An exact rename out of the protected tree —
#   `.beads/issues.jsonl` -> `tracker-snapshot.jsonl` — would therefore reach
#   the classifier as `tracker-snapshot.jsonl` only: outside `.beads/**`,
#   permitted, exit 0, while merging the PR deletes the tracker database from
#   its canonical location.
#
#   `--no-renames` presents both endpoints (a deletion plus an addition), so
#   the shared classifier refuses the protected OLD path with no new rules.
#   The same Git behaviour bit changed-surface discovery (rf2-vxgfnd.137) and
#   has the same one-flag fix. A rename endpoint is pinned in layer 5 of
#   scripts/git-hooks/test-pre-commit.sh; restoring plain `--name-only` reds it.
#
# Usage:
#   sh scripts/check-beads-pr-boundary.sh [BASE_REF]
#
#   BASE_REF resolution: argument, else $BEADS_BOUNDARY_BASE_REF. Pass the
#   BASE BRANCH, not a precomputed branch point — the merge base is this
#   script's job. Run it locally to pre-flight a PR:
#     sh scripts/check-beads-pr-boundary.sh origin/main
#
# Cross-platform: POSIX sh. Runs identically on the ubuntu CI runner, on
# macOS, and under Git Bash on Windows. No bashisms.

set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
LIB="$SCRIPT_DIR/git-hooks/lib/check-beads-boundary.sh"

if [ ! -f "$LIB" ]; then
  printf 'check-beads-pr-boundary: missing classifier library at %s\n' "$LIB" >&2
  exit 1
fi
# shellcheck source=git-hooks/lib/check-beads-boundary.sh
. "$LIB"

EVENT="${GITHUB_EVENT_NAME:-pull_request}"
if [ "$EVENT" != "pull_request" ]; then
  printf 'Event is "%s", not a pull request: the beads boundary is not enforced here.\n' "$EVENT"
  printf 'Pushes to main are the mayor checkpoint flow and carry the tracker by design.\n'
  exit 0
fi

BASE="${1:-${BEADS_BOUNDARY_BASE_REF:-}}"
if [ -z "$BASE" ]; then
  printf 'check-beads-pr-boundary: no base ref.\n' >&2
  printf 'Pass one as an argument or set BEADS_BOUNDARY_BASE_REF. Failing closed:\n' >&2
  printf 'a gate that cannot see the diff certifies nothing.\n' >&2
  exit 1
fi

if ! git rev-parse --verify --quiet "$BASE^{commit}" >/dev/null; then
  printf 'check-beads-pr-boundary: base ref "%s" does not resolve to a commit.\n' "$BASE" >&2
  printf 'Failing closed rather than passing vacuously.\n' >&2
  exit 1
fi

# rf2-5z20y — the BRANCH DELTA, from the branch point. See the header: a
# two-endpoint `git diff "$BASE" HEAD` blames a branch for whatever the base
# moved after it forked, which here means the mayor's tracker checkpoints.
BRANCH_POINT=$(git merge-base "$BASE" HEAD 2>/dev/null) || BRANCH_POINT=""
if [ -z "$BRANCH_POINT" ]; then
  printf 'check-beads-pr-boundary: no merge base between "%s" and HEAD.\n' "$BASE" >&2
  printf 'Usually a shallow clone that does not contain the branch point —\n' >&2
  printf 'on GitHub Actions use actions/checkout with fetch-depth: 0.\n' >&2
  printf 'Failing closed: a gate that cannot see the branch delta certifies nothing.\n' >&2
  exit 1
fi

changed=$(git diff --no-renames --name-only "$BRANCH_POINT" HEAD)

if printf '%s\n' "$changed" | check_beads_boundary ci; then
  printf 'No beads-database paths in this PR diff.\n'
  exit 0
fi
exit 1
