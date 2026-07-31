#!/usr/bin/env sh
# scripts/remove-worker-worktree.sh — remove a worker worktree WITHOUT
# deleting through its node_modules junction (POSIX).
#
# PRIMARY implementation; scripts/remove-worker-worktree.ps1 is the Windows
# PowerShell sibling with an identical contract.
#
# WHY THIS SCRIPT EXISTS (rf2-rxkht — second recorded occurrence).
#   A worker worktree cannot compile without a node_modules, so the
#   established convention is to point `<worktree>/implementation/node_modules`
#   at the mayor checkout's REAL node_modules — a directory junction on
#   Windows, a symlink elsewhere. `git worktree remove` then walks the tree it
#   is deleting, follows that reparse point, and deletes the TARGET's
#   contents: the mayor checkout's real node_modules is emptied and every
#   local build in the repo breaks until `npm ci` restores it. Measured on
#   Windows 11, against a throwaway junction pointing at a dummy directory:
#
#     git worktree remove --force <wt>   target 5 entries -> 0   (deletes through)
#     rm -rf <junction>                  target 5 entries -> 0   (deletes through)
#     rm <junction>          (no -r)     target 5 entries -> 5   (unlinks only)
#
#   The hazard was written down twice — in the hygiene procedure and in an
#   agent memory — and recurred anyway. Prose does not disarm a junction, so
#   the guard lives here, in the tooling the hygiene path has to run through.
#
# WHAT IT DOES, in the one order that is safe:
#   1. Snapshot every real node_modules in the MAYOR checkout (the canary).
#   2. Find every node_modules under the worktree that is a LINK and unlink
#      the LINK ONLY — never a recursive delete, which is the thing that
#      deletes through.
#   3. Verify each link is actually gone before continuing.
#   4. THEN `git worktree remove`.
#   5. Re-snapshot the canary and FAIL LOUDLY if any count dropped.
#   Steps 2 and 4 are never chained into one command: the point is that the
#   removal runs against a tree with no live reparse point left in it.
#
# USAGE
#   sh scripts/remove-worker-worktree.sh <worktree-path> [<worktree-path>...]
#   sh scripts/remove-worker-worktree.sh --dry-run <worktree-path>
#   sh scripts/remove-worker-worktree.sh --self-test
#
#   --force      pass --force to `git worktree remove` (dirty worktree)
#   --dry-run    report what would be disarmed/removed; change nothing
#   --self-test  prove the disarm against a throwaway link in a temp dir
#   --mayor-root <path> / RF2_MAYOR_ROOT   override mayor-root derivation
#
# CONTRACT (preserved exactly across both implementations) — stdout lines:
#   MAYOR_ROOT=<absolute path>
#   CANARY_BEFORE=<path> <entry-count>      (one per real mayor node_modules)
#   DISARMED=<link path> -> <target>        (one per link unlinked)
#   NO_LINKS=<worktree path>                (when the worktree carried none)
#   REMOVED=<worktree path>
#   CANARY_AFTER=<path> <entry-count>
#   OK: worktree removal complete.
#   Exit 0 on success, 1 on failure, 2 on usage error. A dropped canary count
#   prints CANARY_FAILED to stderr with the npm ci recovery command.
#
# Cross-platform: POSIX sh; runs under Git Bash on Windows, macOS, Linux.
# No bashisms (`[[`, arrays, `<<<`).
#
# NOTE for Git Bash: MSYS reports a Windows directory junction as a symlink,
# so `[ -L ]` detects one and a non-recursive `rm` unlinks it without
# touching the target — both verified on Windows 11. If a future MSYS build
# changes that, the disarm's post-condition check fails loudly rather than
# proceeding to a removal that would delete through.

set -eu

# ---------------------------------------------------------------------------
# Args.
# ---------------------------------------------------------------------------
FORCE=0
DRY_RUN=0
SELF_TEST=0
MAYOR_ROOT="${RF2_MAYOR_ROOT:-}"

WORK_DIR=$(mktemp -d 2>/dev/null) || {
  printf 'remove-worker-worktree: could not create a temp dir.\n' >&2
  exit 1
}
# shellcheck disable=SC2064  # expand WORK_DIR now, on purpose.
trap "rm -rf '$WORK_DIR' 2>/dev/null || true" EXIT
TARGETS_FILE="$WORK_DIR/targets"
: > "$TARGETS_FILE"

while [ $# -gt 0 ]; do
  case "$1" in
    --force)     FORCE=1; shift ;;
    --dry-run)   DRY_RUN=1; shift ;;
    --self-test) SELF_TEST=1; shift ;;
    --mayor-root)   MAYOR_ROOT="${2:-}"; shift 2 ;;
    --mayor-root=*) MAYOR_ROOT="${1#*=}"; shift ;;
    -h|--help)
      sed -n '2,60p' "$0"
      exit 0
      ;;
    -*)
      printf 'remove-worker-worktree: unknown argument: %s\n' "$1" >&2
      exit 2
      ;;
    *)
      printf '%s\n' "$1" >> "$TARGETS_FILE"
      shift
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

# Absolute, forward-slashed, no trailing slash — so comparisons are stable
# across Git Bash / macOS / Linux. Mirrors scripts/assert-worker-worktree.sh.
normalize_path() {
  _np_in="$1"
  if [ -d "$_np_in" ]; then
    _np_in=$(cd "$_np_in" 2>/dev/null && pwd) || _np_in="$1"
  fi
  # shellcheck disable=SC1003  # '\\' is a literal backslash for tr, not an escaped quote.
  _np_in=$(printf '%s' "$_np_in" | tr '\\' '/')
  case "$_np_in" in
    */) [ "$_np_in" != "/" ] && _np_in=${_np_in%/} ;;
  esac
  printf '%s' "$_np_in"
}

to_lower() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

# Entry count of a directory (dotfiles included). Prints a bare integer, or
# the word MISSING when the directory is not there at all — MISSING against a
# numeric "before" is itself a canary failure.
entry_count() {
  if [ -d "$1" ]; then
    find "$1" -mindepth 1 -maxdepth 1 2>/dev/null | wc -l | tr -d ' '
  else
    printf 'MISSING'
  fi
}

# Every node_modules under $1, WITHOUT descending into any of them (-prune):
# descending into a junction is the very walk this script exists to avoid,
# and descending into a real one is thousands of wasted stats.
#
# The scan is DEPTH-UNBOUNDED on purpose. A depth cap is the kind of guess
# that fails silently — a junction one level deeper than the cap is not
# disarmed, and the canary then only REPORTS the damage instead of
# preventing it. Pruning `.git` (never a home for a node_modules, and the
# entire cost of the walk: 41s unbounded vs 1.2s with it pruned, on this
# repo's mayor checkout) buys the whole tree for less than a depth-4 scan
# cost — and finds one node_modules a depth-4 scan missed.
find_node_modules() {
  find "$1" -name .git -prune -o -name node_modules -prune -print 2>/dev/null || true
}

# THE DISARM. Remove a LINK, never its target.
#
# `rm` WITHOUT -r unlinks a symlink (and, under Git Bash, a Windows directory
# junction) and stops there. It is deliberately not `rm -rf`, which follows
# the reparse point and empties the target — the incident this script exists
# to prevent, reproduced in the measurements above.
#
# Returns non-zero if the link survives, so a platform where this technique
# stops working can never fall through to a removal that deletes through.
disarm_link() {
  _dl_path="$1"
  rm "$_dl_path" 2>/dev/null || true
  if [ -e "$_dl_path" ] || [ -L "$_dl_path" ]; then
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# --self-test — prove the disarm rather than asserting it.
#
# Builds a throwaway target directory with a known entry count, links a
# node_modules at it, disarms, and requires BOTH: the link is gone AND the
# target still has every entry. Never touches a real node_modules.
# ---------------------------------------------------------------------------
if [ "$SELF_TEST" -eq 1 ]; then
  ST_TARGET="$WORK_DIR/dummy-target"
  ST_LINK="$WORK_DIR/wt/implementation/node_modules"
  mkdir -p "$ST_TARGET" "$WORK_DIR/wt/implementation"
  for _i in 1 2 3 4 5; do
    printf 'canary\n' > "$ST_TARGET/f$_i.txt"
  done
  ST_BEFORE=$(entry_count "$ST_TARGET")

  # Make a link. `ln -s` is the POSIX answer; under Git Bash it silently
  # COPIES instead of linking unless MSYS=winsymlinks is set, so fall back to
  # a native junction via PowerShell — which needs Windows-shaped paths, hence
  # cygpath. If neither yields a link we SKIP rather than claim a pass we did
  # not earn.
  ln -s "$ST_TARGET" "$ST_LINK" 2>/dev/null || true
  if [ ! -L "$ST_LINK" ]; then
    rm -rf "$ST_LINK" 2>/dev/null || true
    if command -v powershell >/dev/null 2>&1 && command -v cygpath >/dev/null 2>&1; then
      powershell -NoProfile -Command \
        "New-Item -ItemType Junction -Path '$(cygpath -w "$ST_LINK")' -Target '$(cygpath -w "$ST_TARGET")' | Out-Null" \
        >/dev/null 2>&1 || true
    fi
  fi
  if [ ! -L "$ST_LINK" ]; then
    printf 'SELF_TEST=SKIPPED (this platform produced no link to test against)\n'
    printf 'Run scripts/remove-worker-worktree.ps1 -SelfTest on Windows instead.\n'
    exit 0
  fi

  printf 'SELF_TEST target=%s entries_before=%s\n' "$ST_TARGET" "$ST_BEFORE"
  disarm_link "$ST_LINK" \
    || die "SELF_TEST=FAILED the link survived the disarm: $ST_LINK"
  ST_AFTER=$(entry_count "$ST_TARGET")
  printf 'SELF_TEST link_removed=yes entries_after=%s\n' "$ST_AFTER"
  if [ "$ST_AFTER" != "$ST_BEFORE" ]; then
    die "SELF_TEST=FAILED the disarm deleted THROUGH the link: $ST_BEFORE -> $ST_AFTER entries."
  fi
  printf 'SELF_TEST=PASSED link unlinked, target intact (%s entries).\n' "$ST_AFTER"
  exit 0
fi

if [ ! -s "$TARGETS_FILE" ]; then
  printf 'usage: remove-worker-worktree.sh <worktree-path>... [--force] [--dry-run]\n' >&2
  printf '       remove-worker-worktree.sh --self-test\n' >&2
  exit 2
fi

# ---------------------------------------------------------------------------
# Derive the MAYOR ROOT — the repository's PRIMARY worktree, which
# `git worktree list --porcelain` always emits first. Same derivation as
# scripts/assert-worker-worktree.sh; no maintainer path is ever baked in.
# ---------------------------------------------------------------------------
if [ -z "$MAYOR_ROOT" ]; then
  MAYOR_ROOT=$(git worktree list --porcelain 2>/dev/null \
    | awk '/^worktree /{print substr($0, 10); exit}') || MAYOR_ROOT=""
  [ -n "$MAYOR_ROOT" ] \
    || die "Could not determine the mayor (primary) worktree via 'git worktree list'. Set RF2_MAYOR_ROOT or pass --mayor-root."
fi
MAYOR_ROOT=$(normalize_path "$MAYOR_ROOT")
[ -d "$MAYOR_ROOT" ] || die "Mayor root does not exist: $MAYOR_ROOT"
printf 'MAYOR_ROOT=%s\n' "$MAYOR_ROOT"

# ---------------------------------------------------------------------------
# CANARY, captured at runtime — never a committed number, which would rot.
# Every REAL (non-link) node_modules in the mayor checkout, with its entry
# count. A junction we failed to detect still shows up here as a drop.
# ---------------------------------------------------------------------------
CANARY_FILE="$WORK_DIR/canary"
: > "$CANARY_FILE"

find_node_modules "$MAYOR_ROOT" > "$WORK_DIR/mayor-nm"
while IFS= read -r nm; do
  [ -n "$nm" ] || continue
  if [ -L "$nm" ]; then
    continue   # a link in the MAYOR tree is not a canary
  fi
  printf '%s\t%s\n' "$(normalize_path "$nm")" "$(entry_count "$nm")" >> "$CANARY_FILE"
done < "$WORK_DIR/mayor-nm"

while IFS= read -r line; do
  [ -n "$line" ] || continue
  printf 'CANARY_BEFORE=%s %s\n' "${line%%	*}" "${line##*	}"
done < "$CANARY_FILE"

# The registered-worktree roster. Every entry goes through normalize_path,
# exactly as the caller's argument does — NOT a bare `tr` of git's output.
# Under Git Bash the two disagree: `git worktree list` prints Windows-shaped
# `C:/Users/...` while `pwd` prints the MSYS-translated `/c/Users/...`, so a
# textual compare never matches and every removal is refused as unregistered.
git -C "$MAYOR_ROOT" worktree list --porcelain 2>/dev/null \
  | awk '/^worktree /{print substr($0, 10)}' > "$WORK_DIR/roster-raw"
: > "$WORK_DIR/roster"
while IFS= read -r roster_line; do
  [ -n "$roster_line" ] || continue
  to_lower "$(normalize_path "$roster_line")" >> "$WORK_DIR/roster"
  printf '\n' >> "$WORK_DIR/roster"
done < "$WORK_DIR/roster-raw"

# ---------------------------------------------------------------------------
# Per worktree: disarm, verify, THEN remove.
# ---------------------------------------------------------------------------
FAILED=0

while IFS= read -r raw_target; do
  [ -n "$raw_target" ] || continue
  WT=$(normalize_path "$raw_target")

  # Refuse the mayor checkout outright — removing it is never the intent, and
  # it is the tree the canary is protecting.
  if [ "$(to_lower "$WT")" = "$(to_lower "$MAYOR_ROOT")" ]; then
    die "Refusing to remove the MAYOR checkout: $WT"
  fi

  # Refuse anything git does not know as a linked worktree. This script never
  # deletes a directory itself; if git will not remove it, neither will we.
  if ! grep -Fxq "$(to_lower "$WT")" "$WORK_DIR/roster"; then
    die "Not a registered worktree of $MAYOR_ROOT: $WT (run 'git worktree list'; 'git worktree prune' clears stale entries)."
  fi

  # 1. Disarm every link, before anything recursive runs over the tree.
  find_node_modules "$WT" > "$WORK_DIR/wt-nm"
  found_any=0
  while IFS= read -r nm; do
    [ -n "$nm" ] || continue
    if [ ! -L "$nm" ]; then
      continue
    fi
    found_any=1
    link_target=$(readlink "$nm" 2>/dev/null || printf '<unresolved>')
    if [ "$DRY_RUN" -eq 1 ]; then
      printf 'WOULD_DISARM=%s -> %s\n' "$nm" "$link_target"
      continue
    fi
    disarm_link "$nm" \
      || die "Failed to unlink $nm — it is still present. ABORTING before 'git worktree remove', which would delete THROUGH it into $link_target."
    printf 'DISARMED=%s -> %s\n' "$nm" "$link_target"
  done < "$WORK_DIR/wt-nm"
  if [ "$found_any" -eq 0 ]; then
    printf 'NO_LINKS=%s\n' "$WT"
  fi

  # 2. Only now remove the worktree. Never chained with the disarm.
  if [ "$DRY_RUN" -eq 1 ]; then
    printf 'WOULD_REMOVE=%s\n' "$WT"
  elif [ "$FORCE" -eq 1 ]; then
    if git -C "$MAYOR_ROOT" worktree remove --force "$WT"; then
      printf 'REMOVED=%s\n' "$WT"
    else
      # A Windows file lock (a shadow-cljs / Node process still holding the
      # tree) is the common cause and is harmless: the links are already
      # unlinked, so retrying later cannot delete through anything.
      printf 'REMOVE_FAILED=%s (links are already disarmed; safe to retry once the lock clears)\n' "$WT" >&2
      FAILED=1
    fi
  else
    if git -C "$MAYOR_ROOT" worktree remove "$WT"; then
      printf 'REMOVED=%s\n' "$WT"
    else
      printf 'REMOVE_FAILED=%s (links are already disarmed; safe to retry once the lock clears)\n' "$WT" >&2
      FAILED=1
    fi
  fi
done < "$TARGETS_FILE"

# ---------------------------------------------------------------------------
# Re-check the canary. A drop means something deleted through a link.
# ---------------------------------------------------------------------------
CANARY_BAD=0
while IFS= read -r line; do
  [ -n "$line" ] || continue
  nm="${line%%	*}"
  before="${line##*	}"
  after=$(entry_count "$nm")
  printf 'CANARY_AFTER=%s %s\n' "$nm" "$after"
  if [ "$after" != "$before" ]; then
    CANARY_BAD=1
    printf 'CANARY_FAILED: %s went from %s to %s entries.\n' "$nm" "$before" "$after" >&2
  fi
done < "$CANARY_FILE"

if [ "$CANARY_BAD" -eq 1 ]; then
  printf '\n' >&2
  printf 'A node_modules in the MAYOR checkout lost entries during this removal:\n' >&2
  printf 'something deleted THROUGH a link that was not disarmed. Recover with\n' >&2
  printf '  npm ci --prefix implementation\n' >&2
  printf 'run from %s, then re-check the counts before dispatching anything.\n' "$MAYOR_ROOT" >&2
  exit 1
fi

if [ "$FAILED" -ne 0 ]; then
  exit 1
fi

printf 'OK: worktree removal complete.\n'
