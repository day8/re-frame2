#!/usr/bin/env sh
# scripts/git-hooks/lib/check-beads-boundary.sh
#
# Shared classifier for the STALE WORKER-SNAPSHOT guard (rf2-ia8o7).
#
# Sourced by two consumers, so both enforce one rule from one place:
#
#   1. scripts/git-hooks/pre-commit — refuses a commit staging beads
#      DATABASE paths from any NON-PRIMARY (worker) worktree.
#   2. scripts/check-beads-pr-boundary.sh — the CI arm; refuses a pull
#      request whose diff carries the same paths.
#
# THE FAILURE MODE THIS EXISTS TO STOP
#
#   `bd` auto-stages the full-database JSONL export in EVERY checkout. A
#   worker worktree therefore carries a snapshot of the tracker as it stood
#   when that worktree was created. Committing it TIME-TRAVELS the tracker:
#   beads closed since the snapshot reopen, beads filed since it vanish.
#   PR #6677 landed exactly that — 135 insertions / 136 deletions of pure
#   collateral (rf2-oe9xi reopened, rf2-ncm8j removed, memories replaced).
#
#   The tracker database is the MAYOR checkout's to commit. Worker PRs carry
#   code, spec, docs and tests — never the tracker database.
#
# WHY NOT `git update-index --skip-worktree`?
#
#   rf2-ia8o7 originally proposed setting `--skip-worktree` on
#   `.beads/issues.jsonl` in every new worker checkout, so the auto-export
#   could never be staged at all. Measured, that is STRICTLY WORSE than
#   refusing the commit:
#
#     skip-worktree hides the local edit from `git status` (clean tree), but
#     `git pull --rebase` still refuses to advance over it —
#       "error: Your local changes to the following files would be
#        overwritten by merge: .beads/issues.jsonl ... Aborting"
#     — leaving HEAD frozen with NOTHING in `git status` to explain why. The
#     worktree then silently rots at a stale base. This repo has already been
#     bitten by that exact silent-pull-abort shape.
#
#   A loud refusal at commit time, naming the file and the remedy, is the
#   better trade: it fires only when it must, and it says why. The remedy
#   text below therefore teaches explicit staging, NOT skip-worktree.
#
# ALLOW-LIST, NOT DENY-LIST
#
#   The permitted set is the small, human-authored beads CONFIG surface;
#   everything else under `.beads/` is database-derived and refused. An
#   allow-list covers artefacts that do not exist yet (`.beads/events.jsonl`
#   when events-export is enabled, `.beads/dolt/**`, `.beads/*.db`) without
#   anyone having to remember to extend a deny-list — the same reasoning as
#   the sibling lib/check-mayor-commit-boundary.sh.
#
# This file is a pure shell library (no `set -e`, no global state mutation)
# so scripts/git-hooks/test-pre-commit.sh can drive it with synthetic stdin
# and assert against stdout / stderr / exit.
#
# Cross-platform: POSIX sh; runs under Git Bash on Windows, macOS, Linux.
# No bashisms (`[[`, arrays, `<<<`).

# ---------------------------------------------------------------------------
# Path classification.
# ---------------------------------------------------------------------------

# rf2_beads_is_permitted_path PATH
#
# Returns 0 (permitted) when PATH is outside `.beads/` altogether, or is one
# of the human-authored beads config/doc files a worker may legitimately
# change. Returns 1 (refused) for every other `.beads/` path — the
# database-derived artefacts.
#
# Paths are matched verbatim against what git emits from
# `git diff --cached --name-only` / `git diff --name-only` (forward slashes,
# repo-relative, no leading `./`).
rf2_beads_is_permitted_path() {
  case "$1" in
    .beads/README.md)   return 0 ;;
    .beads/config.yaml) return 0 ;;
    .beads/.gitignore)  return 0 ;;
    .beads/hooks/*)     return 0 ;;
    .beads/*)           return 1 ;;
    *)                  return 0 ;;
  esac
}

# ---------------------------------------------------------------------------
# Worktree derivation.
#
# Mirrors scripts/assert-worker-worktree.sh: the MAYOR checkout is the
# repository's PRIMARY worktree — the first entry of
# `git worktree list --porcelain`, which git always lists first. That is the
# marker-free, portable way to tell the primary checkout from linked (worker)
# worktrees. RF2_MAYOR_ROOT overrides it, same escape hatch as the guard.
#
# (The two implementations are deliberately independent: assert-worker-worktree.sh
# is a standalone pre-edit guard that must keep working even from a partial
# checkout, and it already has a PowerShell sibling carrying the same rule.
# Keep the derivation in step if either changes.)
# ---------------------------------------------------------------------------

# rf2_beads_normalize_path PATH — absolute, forward-slashed, no trailing
# slash, lower-cased, so comparisons are stable across Git Bash / macOS /
# Linux and their case-insensitive filesystems.
rf2_beads_normalize_path() {
  _rf2b_p="$1"
  if [ -d "$_rf2b_p" ]; then
    _rf2b_p=$(cd "$_rf2b_p" 2>/dev/null && pwd) || _rf2b_p="$1"
  fi
  _rf2b_p=$(printf '%s' "$_rf2b_p" | tr '\\' '/' | tr 'A-Z' 'a-z')
  case "$_rf2b_p" in
    */) [ "$_rf2b_p" != "/" ] && _rf2b_p=${_rf2b_p%/} ;;
  esac
  printf '%s' "$_rf2b_p"
}

# rf2_beads_in_primary_worktree
#
# Returns 0 when the current git checkout IS the primary (mayor) worktree —
# where committing tracker state is the intended flow, so this guard must
# stay out of the way. Returns 1 when it is a linked (worker) worktree.
#
# Fails OPEN (treats the checkout as primary) if the derivation is
# impossible, with a one-line warning: a guard that cannot decide must not
# brick every commit in the repository. The CI arm is the backstop.
rf2_beads_in_primary_worktree() {
  _rf2b_root=$(git rev-parse --show-toplevel 2>/dev/null) || _rf2b_root=""
  if [ -z "$_rf2b_root" ]; then
    printf 'warning: beads boundary guard could not locate the git root; skipping.\n' >&2
    return 0
  fi

  _rf2b_primary="${RF2_MAYOR_ROOT:-}"
  if [ -z "$_rf2b_primary" ]; then
    _rf2b_primary=$(git worktree list --porcelain 2>/dev/null \
      | awk '/^worktree /{print substr($0, 10); exit}')
  fi
  if [ -z "$_rf2b_primary" ]; then
    printf 'warning: beads boundary guard could not determine the primary worktree; skipping.\n' >&2
    printf 'warning: set RF2_MAYOR_ROOT to the mayor checkout to restore the guard.\n' >&2
    return 0
  fi

  [ "$(rf2_beads_normalize_path "$_rf2b_root")" = "$(rf2_beads_normalize_path "$_rf2b_primary")" ]
}

# ---------------------------------------------------------------------------
# The check.
# ---------------------------------------------------------------------------

# check_beads_boundary [CONTEXT]
#
# Reads newline-separated paths from stdin. Prints a didactic refusal block
# to stderr naming every refused path; returns 1 if any were refused, 0
# otherwise. CONTEXT is `commit` (default) or `ci` and selects the remedy
# stanza — the diagnosis is identical, the fix differs by where you are.
check_beads_boundary() {
  _rf2b_context="${1:-commit}"
  _rf2b_paths=$(cat)
  if [ -z "$_rf2b_paths" ]; then
    return 0
  fi

  # Collect refused paths in a tmpfile: a POSIX-sh `while read` loop runs in
  # a subshell, so accumulating into a variable would lose the state.
  _rf2b_refused=$(mktemp "${TMPDIR:-/tmp}/rf2-beads-boundary-XXXXXX")
  trap 'rm -f "$_rf2b_refused"' EXIT INT TERM HUP

  printf '%s\n' "$_rf2b_paths" | while IFS= read -r p; do
    [ -z "$p" ] && continue
    if ! rf2_beads_is_permitted_path "$p"; then
      printf '%s\n' "$p" >> "$_rf2b_refused"
    fi
  done

  if [ ! -s "$_rf2b_refused" ]; then
    rm -f "$_rf2b_refused"
    trap - EXIT INT TERM HUP
    return 0
  fi

  printf '\n' >&2
  printf 'ERROR: refusing the beads DATABASE outside the mayor checkout.\n' >&2
  printf '\n' >&2
  if [ "$_rf2b_context" = "ci" ]; then
    printf '  Beads-database paths in this PR diff:\n' >&2
  else
    printf '  Staged beads-database paths:\n' >&2
  fi
  while IFS= read -r p; do
    [ -n "$p" ] && printf '    %s\n' "$p" >&2
  done < "$_rf2b_refused"
  printf '\n' >&2
  printf '  This is the STALE WORKER-SNAPSHOT failure mode (rf2-ia8o7).\n' >&2
  printf '  `bd` auto-stages the full-database JSONL export in EVERY checkout,\n' >&2
  printf '  so a worker worktree carries the tracker as it stood when that\n' >&2
  printf '  worktree was created. Committing it TIME-TRAVELS the tracker: beads\n' >&2
  printf '  closed since the snapshot reopen, beads filed since it vanish.\n' >&2
  printf '\n' >&2
  if [ "$_rf2b_context" = "ci" ]; then
    printf '  Fix — take the beads paths out of this branch, then force-push:\n' >&2
    printf '    git rebase -i <base>        # drop or edit the offending commit\n' >&2
    printf '    git checkout origin/main -- .beads && git commit --amend\n' >&2
  else
    printf '  Fix — drop the stale snapshot, then commit again:\n' >&2
    printf '    git restore --staged .beads\n' >&2
    printf '    git checkout HEAD -- .beads\n' >&2
  fi
  printf '\n' >&2
  printf '  To stop it recurring: stage explicit paths. `git add -A` and\n' >&2
  printf '  `git commit -a` sweep up the bd auto-export every time.\n' >&2
  printf '\n' >&2
  printf '  Tracker state is the MAYOR checkout to commit. Worker branches carry\n' >&2
  printf '  code, spec, docs and tests — never the tracker database.\n' >&2
  printf '\n' >&2
  printf '  Human-authored beads config IS permitted here:\n' >&2
  printf '    .beads/README.md  .beads/config.yaml  .beads/.gitignore  .beads/hooks/**\n' >&2
  printf '\n' >&2
  printf '  Merge-side rule: never resolve a .beads conflict with --theirs/--ours.\n' >&2
  printf '  See CLAUDE.md > Beads durability.\n' >&2
  printf '\n' >&2

  rm -f "$_rf2b_refused"
  trap - EXIT INT TERM HUP
  return 1
}
