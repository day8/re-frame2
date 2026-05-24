#!/usr/bin/env sh
# scripts/install-git-hooks.sh — install re-frame2 repo git hooks (rf2-6jj3r).
#
# Idempotent. Safe to run repeatedly. Co-exists with `bd hooks install`
# (beads-managed segments use their own BEGIN/END BEADS INTEGRATION
# markers; this script's segments use BEGIN/END re-frame2 markers and
# never touch beads segments).
#
# Today this installs only `post-merge`, which warns when a `git pull`
# brings down MCP-source changes that invalidate the local
# tools/re-frame2-pair-mcp/out/server.js binary. Future re-frame2 repo
# hooks register here.
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
HOOKS_DIR=$(git -C "$REPO_ROOT" rev-parse --git-common-dir)/hooks
mkdir -p "$HOOKS_DIR"

# Marker pair (must match what the hook source files carry).
BEGIN_MARK='# --- BEGIN re-frame2 MCP-staleness check (rf2-6jj3r) ---'
END_MARK='# --- END re-frame2 MCP-staleness check (rf2-6jj3r) ---'

MODE="install"
if [ $# -gt 0 ] && [ "$1" = "--check" ]; then
  MODE="check"
fi

install_hook() {
  hook_name="$1"
  src="$SRC_DIR/$hook_name"
  dst="$HOOKS_DIR/$hook_name"

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

rc=0
install_hook post-merge || rc=$?

if [ "$MODE" = "check" ] && [ "$rc" -ne 0 ]; then
  printf '\nRun scripts/install-git-hooks.sh to install.\n' >&2
fi

exit "$rc"
