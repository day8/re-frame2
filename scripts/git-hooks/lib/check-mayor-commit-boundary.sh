#!/usr/bin/env sh
# scripts/git-hooks/lib/check-mayor-commit-boundary.sh
#
# Library for the mayor pre-commit hook (rf2-ydl2p). Inspects a list of
# staged paths (newline-separated on stdin) and prints a refusal block to
# stderr if any of them sit outside the small allow-list of "mayor may
# commit" surfaces. Exit code:
#
#   0  no refused paths (commit may proceed)
#   1  one or more refused paths (commit must be aborted)
#
# This file is intentionally a pure shell library (no `set -e`, no global
# state mutations) so the test runner in
# `scripts/git-hooks/test-pre-commit.sh` can invoke it with synthetic
# stdin streams and assert against stdout / stderr / exit.
#
# Cross-platform: POSIX sh; runs under Git Bash on Windows, macOS, Linux.
# No bashisms (`[[`, arrays, `<<<`). Tested under `sh`, `bash`, `dash`.
#
# Discovery context: rf2-oswhk (#2136), worker acb252dc, 2026-05-25 — an
# initial commit attempt via PowerShell landed on the mayor checkout's
# local main because PowerShell `cwd` defaulted to the implementation
# directory rather than tracking a prior `cd`. Worker noticed + recovered,
# but the bogus commit could have carried real source diffs.
#
# Edit-time guard: scripts/assert-worker-worktree.ps1 (existing).
# Commit-time guard: this library + scripts/git-hooks/pre-commit (rf2-ydl2p).

# ----------------------------------------------------------------------------
# Permitted surfaces in mayor commits.
#
# The contract is allow-list, not deny-list: any staged path not matched
# below is refused.
#
#   - .beads/issues.jsonl                — bd closure / state edits
#   - MEMORY.md (root)                   — optional operator-memory file
#
# Gitignored paths cannot normally reach the staged diff at all (git
# excludes them before `git add` even sees them); a `git add --force`
# bypass would still be caught here because gitignore membership is not
# part of this allow-list. That is deliberate — if Mike force-adds
# something gitignored from the mayor, it is almost certainly a mistake.
# ----------------------------------------------------------------------------

# is_permitted_mayor_path PATH
#
# Returns 0 (permitted) if PATH is one of the small allow-list of paths
# the mayor checkout may commit. Returns 1 (refused) otherwise.
#
# Paths are matched verbatim against the strings git emits from
# `git diff --cached --name-only` (forward slashes, repo-relative, no
# leading `./`).
is_permitted_mayor_path() {
  case "$1" in
    .beads/issues.jsonl) return 0 ;;
    MEMORY.md)           return 0 ;;
    *)                   return 1 ;;
  esac
}

# check_mayor_commit_boundary
#
# Reads newline-separated staged paths from stdin. Prints a refusal block
# to stderr listing the refused paths if any are not in the allow-list.
# Returns 1 if any refused paths were seen, 0 otherwise.
check_mayor_commit_boundary() {
  staged_paths=$(cat)
  if [ -z "$staged_paths" ]; then
    # Empty commit (e.g. `git commit --allow-empty`). Nothing to refuse.
    return 0
  fi

  # Collect refused paths into a tmpfile so we can count and re-emit them
  # in the error block. Using a file (not a variable) keeps POSIX-sh
  # subshell semantics from eating our state in the read loop.
  refused_file=$(mktemp "${TMPDIR:-/tmp}/rf2-precommit-XXXXXX")
  # Best-effort cleanup; ignore failures (e.g. if mktemp returned a path
  # we somehow can't unlink).
  trap 'rm -f "$refused_file"' EXIT INT TERM HUP

  printf '%s\n' "$staged_paths" | while IFS= read -r p; do
    [ -z "$p" ] && continue
    if ! is_permitted_mayor_path "$p"; then
      printf '%s\n' "$p" >> "$refused_file"
    fi
  done

  if [ ! -s "$refused_file" ]; then
    rm -f "$refused_file"
    trap - EXIT INT TERM HUP
    return 0
  fi

  # At least one refused path. Emit the error block.
  printf '\n' >&2
  printf 'ERROR: mayor checkout cannot commit to worker-tracked surfaces.\n' >&2
  printf '\n' >&2
  printf '  Staged files in refused zones:\n' >&2
  while IFS= read -r p; do
    [ -n "$p" ] && printf '    %s\n' "$p" >&2
  done < "$refused_file"
  printf '\n' >&2
  printf '  Permitted in mayor commits:\n' >&2
  printf '    .beads/issues.jsonl  (bd closures)\n' >&2
  printf '    MEMORY.md            (operator-memory file, if present)\n' >&2
  printf '\n' >&2
  printf '  Any source / spec / test / doc / script change must originate\n' >&2
  printf '  from a worker worktree under\n' >&2
  printf '  C:\\Users\\miket\\code\\re-frame2-worktrees\\<descriptive>-<BEAD_ID>.\n' >&2
  printf '\n' >&2
  printf '  See docs/the-mayor-method/ for the worker dispatch contract.\n' >&2
  printf '\n' >&2

  rm -f "$refused_file"
  trap - EXIT INT TERM HUP
  return 1
}
