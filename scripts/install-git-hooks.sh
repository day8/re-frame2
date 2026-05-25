#!/usr/bin/env sh
# scripts/install-git-hooks.sh — install re-frame2 repo git hooks.
#
# Idempotent. Safe to run repeatedly. Co-exists with `bd hooks install`
# (beads-managed segments use their own BEGIN/END BEADS INTEGRATION
# markers; this script's segments use BEGIN/END re-frame2 markers and
# never touch beads segments).
#
# Installs:
#   - post-merge  (rf2-6jj3r) MCP-staleness advisory warning.
#   - pre-commit  (rf2-ydl2p) refuses commits in the MAYOR checkout that
#                 touch worker-tracked surfaces. Activation gated by a
#                 marker file at `<git-common-dir>/mayor-marker`, which
#                 this installer also drops (it lives in the mayor's git
#                 dir, so worker worktrees — which share hooks but not
#                 their per-worktree git dir — see a no-op hook).
#
# Usage:
#   scripts/install-git-hooks.sh           # install/refresh
#   scripts/install-git-hooks.sh --check   # exit 0 if installed & current, 1 otherwise
#
# Cross-platform: POSIX sh; runs under Git Bash on Windows, macOS, Linux.
# Windows operators who prefer PowerShell can use the sibling
# scripts/install-git-hooks.ps1.

set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
SRC_DIR="$SCRIPT_DIR/git-hooks"

# git stores hooks under <gitdir>/hooks; in worktrees that's the main
# repo's hook dir (worktrees share core.hooksPath by default), so use
# `git rev-parse --git-common-dir` to land in the right place.
COMMON_DIR=$(git -C "$REPO_ROOT" rev-parse --git-common-dir)
HOOKS_DIR="$COMMON_DIR/hooks"
mkdir -p "$HOOKS_DIR"

# Marker pair per hook (must match what the hook source files carry).
# `hook_markers <hook_name>` echoes `BEGIN<TAB>END`; consumers split on TAB.
hook_markers() {
  case "$1" in
    post-merge)
      printf '# --- BEGIN re-frame2 MCP-staleness check (rf2-6jj3r) ---\t# --- END re-frame2 MCP-staleness check (rf2-6jj3r) ---\n'
      ;;
    pre-commit)
      printf '# --- BEGIN re-frame2 mayor commit boundary (rf2-ydl2p) ---\t# --- END re-frame2 mayor commit boundary (rf2-ydl2p) ---\n'
      ;;
    *)
      printf 'install-git-hooks: unknown hook name: %s\n' "$1" >&2
      return 1
      ;;
  esac
}

MODE="install"
if [ $# -gt 0 ] && [ "$1" = "--check" ]; then
  MODE="check"
fi

install_hook() {
  hook_name="$1"
  src="$SRC_DIR/$hook_name"
  dst="$HOOKS_DIR/$hook_name"

  # Resolve per-hook markers.
  marker_line=$(hook_markers "$hook_name") || return 1
  BEGIN_MARK=$(printf '%s' "$marker_line" | awk -F'\t' '{print $1}')
  END_MARK=$(printf '%s' "$marker_line" | awk -F'\t' '{print $2}')

  if [ ! -f "$src" ]; then
    printf 'install-git-hooks: missing source %s\n' "$src" >&2
    return 1
  fi

  # Extract just the marker block from src (POSIX sed; portable).
  block=$(sed -n "/$BEGIN_MARK/,/$END_MARK/p" "$src")
  if [ -z "$block" ]; then
    printf 'install-git-hooks: source %s has no marker block\n' "$src" >&2
    return 1
  fi

  if [ ! -f "$dst" ]; then
    case "$MODE" in
      check)
        printf 'install-git-hooks: %s missing\n' "$dst" >&2
        return 1
        ;;
      install)
        # fresh file: shebang + block.
        {
          printf '#!/usr/bin/env sh\n'
          printf '# %s — managed in part by scripts/install-git-hooks.sh\n' "$hook_name"
          printf '\n'
          printf '%s\n' "$block"
        } > "$dst"
        chmod +x "$dst"
        printf 'install-git-hooks: installed %s\n' "$dst"
        return 0
        ;;
    esac
  fi

  # Existing file: replace our block in place if present, else append.
  if grep -Fq "$BEGIN_MARK" "$dst"; then
    # Replace block between markers.
    current=$(sed -n "/$BEGIN_MARK/,/$END_MARK/p" "$dst")
    if [ "$current" = "$block" ]; then
      [ "$MODE" = "install" ] && printf 'install-git-hooks: %s up to date\n' "$dst"
      return 0
    fi
    case "$MODE" in
      check)
        printf 'install-git-hooks: %s block out of date\n' "$dst" >&2
        return 1
        ;;
      install)
        tmp=$(mktemp "${TMPDIR:-/tmp}/rf2-hook-XXXXXX")
        block_file=$(mktemp "${TMPDIR:-/tmp}/rf2-hook-block-XXXXXX")
        printf '%s\n' "$block" > "$block_file"
        # Strip the existing block (between markers, inclusive), then
        # append the fresh block. Two passes keeps the logic clear and
        # portable across BSD/GNU sed.
        sed "/$BEGIN_MARK/,/$END_MARK/d" "$dst" > "$tmp"
        cat "$block_file" >> "$tmp"
        mv "$tmp" "$dst"
        rm -f "$block_file"
        chmod +x "$dst"
        printf 'install-git-hooks: refreshed %s\n' "$dst"
        return 0
        ;;
    esac
  fi

  # No marker yet — append.
  case "$MODE" in
    check)
      printf 'install-git-hooks: %s block missing\n' "$dst" >&2
      return 1
      ;;
    install)
      {
        printf '\n'
        printf '%s\n' "$block"
      } >> "$dst"
      chmod +x "$dst"
      printf 'install-git-hooks: appended block to %s\n' "$dst"
      return 0
      ;;
  esac
}

install_mayor_marker() {
  # Always drop the marker into <common-dir>/mayor-marker. The marker is
  # the activation gate for the pre-commit hook (rf2-ydl2p). It lives in
  # the mayor's per-worktree git dir (== common dir for the primary
  # worktree); worker worktrees have their own per-worktree git dir at
  # <common>/worktrees/<name> and therefore see no marker -> hook no-ops.
  marker_path="$COMMON_DIR/mayor-marker"
  marker_content='re-frame2 mayor checkout marker (rf2-ydl2p).
Presence of this file in <git-dir> activates the pre-commit hook that
refuses commits to worker-tracked surfaces. Managed by
scripts/install-git-hooks.sh; safe to delete (the hook then no-ops and
this checkout behaves like a worker worktree from the hook'\''s POV).
'

  if [ ! -f "$marker_path" ]; then
    case "$MODE" in
      check)
        printf 'install-git-hooks: mayor-marker missing at %s\n' "$marker_path" >&2
        return 1
        ;;
      install)
        printf '%s' "$marker_content" > "$marker_path"
        printf 'install-git-hooks: installed mayor-marker at %s\n' "$marker_path"
        return 0
        ;;
    esac
  fi

  current=$(cat "$marker_path")
  expected=$(printf '%s' "$marker_content")
  if [ "$current" = "$expected" ]; then
    [ "$MODE" = "install" ] && printf 'install-git-hooks: mayor-marker up to date\n'
    return 0
  fi

  case "$MODE" in
    check)
      printf 'install-git-hooks: mayor-marker content drifted at %s\n' "$marker_path" >&2
      return 1
      ;;
    install)
      printf '%s' "$marker_content" > "$marker_path"
      printf 'install-git-hooks: refreshed mayor-marker at %s\n' "$marker_path"
      return 0
      ;;
  esac
}

rc=0
install_hook post-merge || rc=$?
install_hook pre-commit || rc=$?
install_mayor_marker || rc=$?

if [ "$MODE" = "check" ] && [ "$rc" -ne 0 ]; then
  printf '\nRun scripts/install-git-hooks.sh to install.\n' >&2
fi

exit "$rc"
