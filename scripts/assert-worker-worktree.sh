#!/usr/bin/env sh
# scripts/assert-worker-worktree.sh — worker worktree edit guard (POSIX).
#
# PRIMARY implementation; scripts/assert-worker-worktree.ps1 is the Windows
# PowerShell sibling with an identical contract.
#
# CONTRACT (preserved exactly across both implementations):
#   - On success, prints these lines to stdout, in order:
#       WORKTREE_ROOT=<absolute git toplevel>
#       BRANCH=<current branch>          (omitted only if detached HEAD)
#       OK: worker worktree guard passed.
#     and exits 0.
#   - REFUSES (non-zero exit, message to stderr) when the current git
#     checkout is the MAYOR checkout (the repository's primary worktree).
#   - REFUSES when the current git checkout is not a worker worktree under
#     the worktree parent directory.
#   - REFUSES when an --expected-root / -ExpectedRoot is given and the
#     current git root does not match it.
#
# Background workers run this FIRST, before any edit, and report the
# printed WORKTREE_ROOT.
#
# CROSS-PLATFORM ROOT DETECTION (no hardcoded maintainer paths):
#   - MAYOR ROOT is derived as the repository's PRIMARY worktree — the
#     first entry of `git worktree list --porcelain`, which git always
#     lists first. This is the marker-free, portable way to distinguish
#     the primary checkout from linked (worker) worktrees, and mirrors
#     the mayor-marker pattern the git hooks use (see
#     scripts/git-hooks/README.md).
#   - WORKTREE PARENT defaults to `<dir-containing-mayor>/re-frame2-worktrees`,
#     derived from the mayor root's own location — never a baked-in path.
#   - Both are overridable via the explicit escape hatches
#     RF2_MAYOR_ROOT and RF2_WORKTREE_PARENT (env), or the
#     --mayor-root / --worktree-parent flags.
#
# Cross-platform: POSIX sh; runs under Git Bash on Windows, macOS, Linux.
# No bashisms (`[[`, arrays, `<<<`).

set -eu

# ---------------------------------------------------------------------------
# Args. Env vars provide defaults; flags override env.
# ---------------------------------------------------------------------------
EXPECTED_ROOT=""
WORKTREE_PARENT="${RF2_WORKTREE_PARENT:-}"
MAYOR_ROOT="${RF2_MAYOR_ROOT:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --expected-root)   EXPECTED_ROOT="${2:-}"; shift 2 ;;
    --expected-root=*) EXPECTED_ROOT="${1#*=}"; shift ;;
    --worktree-parent)   WORKTREE_PARENT="${2:-}"; shift 2 ;;
    --worktree-parent=*) WORKTREE_PARENT="${1#*=}"; shift ;;
    --mayor-root)   MAYOR_ROOT="${2:-}"; shift 2 ;;
    --mayor-root=*) MAYOR_ROOT="${1#*=}"; shift ;;
    *)
      printf 'assert-worker-worktree: unknown argument: %s\n' "$1" >&2
      exit 2
      ;;
  esac
done

# ---------------------------------------------------------------------------
# Helpers.
# ---------------------------------------------------------------------------

die() {
  printf '%s\n' "$1" >&2
  exit 1
}

# Normalise a path to an absolute, forward-slashed form with no trailing
# slash, so comparisons are stable across Git Bash / macOS / Linux. We do
# NOT require the path to exist (the worktree parent may legitimately be a
# notional sibling that only ever holds linked worktrees), but if it does
# exist we canonicalise via `cd` + `pwd` to resolve `.`/`..`/symlinks.
normalize_path() {
  _np_in="$1"
  if [ -d "$_np_in" ]; then
    _np_in=$(cd "$_np_in" 2>/dev/null && pwd) || _np_in="$1"
  fi
  # Forward-slash and strip a single trailing slash (but not a bare "/").
  _np_in=$(printf '%s' "$_np_in" | tr '\\' '/')
  case "$_np_in" in
    */) [ "$_np_in" != "/" ] && _np_in=${_np_in%/} ;;
  esac
  printf '%s' "$_np_in"
}

# Lower-case for case-insensitive comparison (Windows/macOS default
# case-insensitive filesystems). `tr` is POSIX.
to_lower() {
  printf '%s' "$1" | tr 'A-Z' 'a-z'
}

# Parent directory of an absolute path (POSIX dirname), normalised.
parent_dir() {
  normalize_path "$(dirname "$1")"
}

# ---------------------------------------------------------------------------
# Locate the current git root.
# ---------------------------------------------------------------------------
GIT_ROOT_RAW=$(git rev-parse --show-toplevel 2>/dev/null) \
  || die "Not inside a git checkout. cd to the worker worktree before editing."
[ -n "$GIT_ROOT_RAW" ] \
  || die "Not inside a git checkout. cd to the worker worktree before editing."
GIT_ROOT=$(normalize_path "$GIT_ROOT_RAW")

# ---------------------------------------------------------------------------
# Derive the MAYOR ROOT (primary worktree) if not supplied via env/flag.
# `git worktree list --porcelain` always emits the primary worktree first;
# its `worktree <path>` line is the mayor checkout.
# ---------------------------------------------------------------------------
if [ -z "$MAYOR_ROOT" ]; then
  MAYOR_ROOT=$(git worktree list --porcelain 2>/dev/null \
    | awk '/^worktree /{print substr($0, 10); exit}') \
    || MAYOR_ROOT=""
  [ -n "$MAYOR_ROOT" ] \
    || die "Could not determine the mayor (primary) worktree via 'git worktree list'. Set RF2_MAYOR_ROOT or pass --mayor-root."
fi
MAYOR_ROOT=$(normalize_path "$MAYOR_ROOT")

# ---------------------------------------------------------------------------
# Derive the WORKTREE PARENT if not supplied: sibling of the mayor checkout
# named re-frame2-worktrees. Derived from the mayor root's own location, so
# no maintainer-specific absolute path is ever baked in.
# ---------------------------------------------------------------------------
if [ -z "$WORKTREE_PARENT" ]; then
  WORKTREE_PARENT="$(parent_dir "$MAYOR_ROOT")/re-frame2-worktrees"
fi
WORKTREE_PARENT=$(normalize_path "$WORKTREE_PARENT")

# ---------------------------------------------------------------------------
# Comparisons (case-insensitive; trailing-slash-stable).
# ---------------------------------------------------------------------------
git_root_cmp=$(to_lower "$GIT_ROOT")
mayor_cmp=$(to_lower "$MAYOR_ROOT")
parent_cmp=$(to_lower "$WORKTREE_PARENT")
parent_prefix="$parent_cmp/"

# Refuse the mayor checkout.
if [ "$git_root_cmp" = "$mayor_cmp" ]; then
  die "Refusing to edit from mayor checkout: $GIT_ROOT. Switch to a worker worktree under $WORKTREE_PARENT."
fi

# Refuse anything not under the worktree parent.
case "$git_root_cmp/" in
  "$parent_prefix"*) : ;;
  *) die "Refusing to edit outside worker worktree parent. git root: $GIT_ROOT; expected under: $WORKTREE_PARENT." ;;
esac

# Optional exact-root assertion.
if [ -n "$EXPECTED_ROOT" ]; then
  expected_cmp=$(to_lower "$(normalize_path "$EXPECTED_ROOT")")
  if [ "$git_root_cmp" != "$expected_cmp" ]; then
    die "Wrong worker worktree. git root: $GIT_ROOT; expected: $(normalize_path "$EXPECTED_ROOT")."
  fi
fi

# ---------------------------------------------------------------------------
# Success: emit the contract output.
# ---------------------------------------------------------------------------
BRANCH=$(git branch --show-current 2>/dev/null) || BRANCH=""

printf 'WORKTREE_ROOT=%s\n' "$GIT_ROOT"
if [ -n "$BRANCH" ]; then
  printf 'BRANCH=%s\n' "$BRANCH"
fi
printf 'OK: worker worktree guard passed.\n'
