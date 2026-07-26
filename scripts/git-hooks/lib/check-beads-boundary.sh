#!/usr/bin/env sh
# scripts/git-hooks/lib/check-beads-boundary.sh
#
# Two checks over the same file, asking different questions of it:
#
#   check_beads_boundary   — WHO may commit the beads database
#                            (the STALE WORKER-SNAPSHOT guard, rf2-ia8o7)
#   check_beads_truncation — WHAT is in it when they do
#                            (the TRUNCATION FLOOR, rf2-or8te)
#
# The boundary check is sourced by two consumers, so both enforce one rule
# from one place:
#
#   1. scripts/git-hooks/pre-commit — refuses a commit staging beads
#      DATABASE paths from any NON-PRIMARY (worker) worktree.
#   2. scripts/check-beads-pr-boundary.sh — the CI arm; refuses a pull
#      request whose diff carries the same paths.
#
# The truncation floor is sourced by the pre-commit hook alone, and runs in
# EVERY worktree including the primary. Its own section below says why.
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

# ---------------------------------------------------------------------------
# The truncation floor (rf2-or8te).
#
# THE SAME FILE, A DIFFERENT QUESTION. Everything above decides WHO may commit
# the beads database and says nothing about WHAT is in it. Twice now the answer
# to "what" has been "nothing":
#
#     2026-06-10  7aea52459   7172 rows deleted
#     2026-07-26  4d8042d80d  2573 rows deleted
#
# Both were a plain `git add` of the JSONL caught mid-rewrite, and both came
# from the MAYOR checkout — where committing the tracker is the intended flow
# and the boundary check above correctly no-ops. Afterwards working tree, index
# and HEAD were all empty and `git status` was CLEAN, so nothing on screen said
# anything was wrong.
#
# WHY HERE. scripts/beads-checkpoint.sh already refuses this: it re-exports from
# Dolt rather than trusting the working file, refuses an empty export outright,
# and refuses anything below 90% of HEAD. That guard is sound and unchanged.
# Neither commit went through it. So the floor is repeated at the one place no
# committer can route around, with the checkpoint's own thresholds.
#
# WHY REFUSING IS SAFE. An empty export is a REGENERATION event, not a
# data-loss one. The Dolt database is the source of truth and was never at risk
# — `bd list` worked throughout the 2026-07-26 incident and one checkpoint
# rebuilt 2576 rows over a HEAD of 0. Restoring an older export from git
# history would instead TIME-TRAVEL the tracker, reopening beads closed since
# and vanishing beads filed since. The remedy text says so.
#
# WHY IT DOES NOT BLOCK A GENUINE MASS DELETE. It does refuse one — and names
# the escape. That mirrors the checkpoint script's own answer, "refuse and say
# so; a genuine mass delete is rare enough to commit by hand": a deliberate
# `bd gc` is rare and worth one extra flag, and an emptied export is neither
# rare nor deliberate.
#
# SCOPE. This is a guard for a failure that is silent and has already happened,
# twice. It is deliberately not extended to the CI arm: a mayor checkpoint goes
# to main directly and never appears in a pull-request diff, so neither
# incident could have been seen there.
# ---------------------------------------------------------------------------

RF2_BEADS_TRACKER=".beads/issues.jsonl"

# rf2_beads_rows PREFIX — row count of the tracker at a `git show` prefix:
# `:` reads the INDEX (what is about to be committed), `HEAD:` the last commit.
# Prints 0 when the path is absent there, which is also the honest answer for a
# staged deletion.
rf2_beads_rows() {
  git show "$1$RF2_BEADS_TRACKER" 2>/dev/null | awk 'END{print NR}'
}

# check_beads_truncation
#
# Reads newline-separated staged paths from stdin. When the tracker is among
# them, compares the INDEX row count against HEAD's and refuses a commit that
# would lose more than a tenth of the tracker, printing a didactic block to
# stderr. Returns 0 in every other case — including a first-ever add, since a
# HEAD that carries no rows can lose none.
check_beads_truncation() {
  _rf2t_seen=0
  while IFS= read -r _rf2t_p; do
    if [ "$_rf2t_p" = "$RF2_BEADS_TRACKER" ]; then
      _rf2t_seen=1
    fi
  done
  [ "$_rf2t_seen" = "1" ] || return 0

  _rf2t_new=$(rf2_beads_rows ':')
  _rf2t_old=$(rf2_beads_rows 'HEAD:')

  # ONE condition covers both incidents: with HEAD non-empty, a staged 0 is
  # just the extreme of the same shrink. Same arithmetic as the checkpoint
  # script's line 236, deliberately — two spellings of one floor would drift.
  [ "$_rf2t_old" -gt 0 ] || return 0
  [ $((_rf2t_new * 10)) -lt $((_rf2t_old * 9)) ] || return 0

  printf '\n' >&2
  printf 'ERROR: refusing to commit a TRUNCATED beads tracker.\n' >&2
  printf '\n' >&2
  printf '  %s\n' "$RF2_BEADS_TRACKER" >&2
  printf '    staged: %s rows\n' "$_rf2t_new" >&2
  printf '    HEAD:   %s rows\n' "$_rf2t_old" >&2
  printf '\n' >&2
  printf '  More than a tenth of the tracker would disappear in this commit.\n' >&2
  printf '  An empty export has reached main twice by exactly this route — a\n' >&2
  printf '  plain `git add` catching the JSONL mid-rewrite (2026-06-10 7aea52459,\n' >&2
  printf '  2026-07-26 4d8042d80d) — and both times `git status` was clean\n' >&2
  printf '  afterwards, so nothing said so.\n' >&2
  printf '\n' >&2
  if [ "$_rf2t_new" -eq 0 ]; then
    printf '  An empty export is a REGENERATION event, not a data-loss one. The\n' >&2
    printf '  Dolt database is the source of truth and is untouched by this. Do NOT\n' >&2
    printf '  restore an older export from git history: that TIME-TRAVELS the\n' >&2
    printf '  tracker, reopening beads closed since and vanishing beads filed since.\n' >&2
    printf '\n' >&2
  fi
  printf '  Fix — drop the staged copy and re-export from the database:\n' >&2
  printf '    git restore --staged %s\n' "$RF2_BEADS_TRACKER" >&2
  printf '    sh scripts/beads-checkpoint.sh\n' >&2
  printf '\n' >&2
  printf '  If the shrink is GENUINE — a deliberate `bd gc`, a mass delete you\n' >&2
  printf '  meant — this guard is not the arbiter. Inspect it with `bd status`,\n' >&2
  printf '  then commit by hand:\n' >&2
  printf '    git commit --no-verify\n' >&2
  printf '\n' >&2
  printf '  See CLAUDE.md > Beads durability, and scripts/beads-checkpoint.sh,\n' >&2
  printf '  which applies this same floor to its own re-export.\n' >&2
  printf '\n' >&2

  return 1
}
