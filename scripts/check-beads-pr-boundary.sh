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
# Usage:
#   sh scripts/check-beads-pr-boundary.sh [BASE_REF]
#
#   BASE_REF resolution: argument, else $BEADS_BOUNDARY_BASE_REF. Run it
#   locally against your branch point to pre-flight a PR:
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

changed=$(git diff --name-only "$BASE" HEAD)

if printf '%s\n' "$changed" | check_beads_boundary ci; then
  printf 'No beads-database paths in this PR diff.\n'
  exit 0
fi
exit 1
