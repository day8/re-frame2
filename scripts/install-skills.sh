#!/usr/bin/env sh
# scripts/install-skills.sh — link this repo's skills into ~/.claude/skills.
#
# Deploys every `skills/<name>/` directory into `~/.claude/skills/<name>` BY
# LINK, not by copy, so the active skill Claude Code loads is the SAME FILE as
# the repo source — edits in either are immediately reflected in the other.
# This eliminates the stale-copy drift (rf2-901lr) that comes from a one-shot
# `cp -r`: a copy froze ~10 days behind the maintained repo and Claude Code
# loaded the stale skill.
#
# Link primitive per OS:
#   - macOS / Linux:        `ln -s` (POSIX symlink to the repo skill dir).
#   - Windows (Git Bash):   directory JUNCTION via PowerShell's
#                           `New-Item -ItemType Junction`. A junction needs NO
#                           admin / Developer Mode (unlike a Windows symlink),
#                           and Claude Code reads through it like a symlink.
#
# Idempotent: re-running re-links. If a target already points at this repo's
# skill dir, it is left alone. If a target is a link to a DIFFERENT source, it
# is re-pointed. If a target is a real directory (a stale COPY — the very bug
# this fixes), the installer WARNS and refuses to clobber it unless --force is
# given, so a user's local edits to a copied skill are never silently lost.
#
# Usage:
#   scripts/install-skills.sh                 # link all skills (skip+warn on copies)
#   scripts/install-skills.sh --force         # replace stale COPY dirs with links too
#   scripts/install-skills.sh --check         # exit 0 if all linked & current, 1 otherwise
#   scripts/install-skills.sh --target DIR    # link into DIR instead of ~/.claude/skills
#                                             # (used by the test harness; never needs admin)
#
# Cross-platform: POSIX sh. Runs under Git Bash on Windows, macOS, Linux.
# No bashisms ([[ ]], arrays, <<<). Windows operators who prefer pure
# PowerShell can use the sibling scripts/install-skills.ps1 (identical behaviour).

set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
SKILLS_SRC="$REPO_ROOT/skills"

# Derive the install target WITHOUT hardcoding a home or username. $HOME is
# set on macOS/Linux and under Git Bash on Windows; fall back to $USERPROFILE
# for the rare shell that exports only the Windows variable.
HOME_DIR="${HOME:-${USERPROFILE:-}}"

MODE="install"
FORCE=0
TARGET_DIR=""

while [ $# -gt 0 ]; do
  case "$1" in
    --check)  MODE="check" ;;
    --force)  FORCE=1 ;;
    --target)
      shift
      [ $# -gt 0 ] || { printf 'install-skills: --target needs a directory argument\n' >&2; exit 2; }
      TARGET_DIR="$1"
      ;;
    *)
      printf 'install-skills: unknown argument: %s\n' "$1" >&2
      exit 2
      ;;
  esac
  shift
done

if [ -z "$TARGET_DIR" ]; then
  if [ -z "$HOME_DIR" ]; then
    printf 'install-skills: neither $HOME nor $USERPROFILE is set; pass --target DIR\n' >&2
    exit 2
  fi
  TARGET_DIR="$HOME_DIR/.claude/skills"
fi

if [ ! -d "$SKILLS_SRC" ]; then
  printf 'install-skills: no skills directory at %s\n' "$SKILLS_SRC" >&2
  exit 1
fi

# Detect Windows (Git Bash / MSYS / Cygwin) so we pick junction over symlink.
is_windows() {
  case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*) return 0 ;;
    *) return 1 ;;
  esac
}

# Resolve a path to its real location for comparison. `cd && pwd -P` is the
# most portable way (no readlink -f on macOS by default). Echoes the resolved
# path, or the input unchanged if it does not resolve to a directory.
resolve_dir() {
  if [ -d "$1" ]; then
    (cd "$1" 2>/dev/null && pwd -P) || printf '%s' "$1"
  else
    printf '%s' "$1"
  fi
}

# Create the link `dst` -> `src` using the right primitive for the OS.
make_link() {
  src="$1"
  dst="$2"
  if is_windows; then
    # Junction: no admin needed. PowerShell is present on every supported
    # Windows. Pass native (back-slashed) paths via `cygpath -w` so the
    # Windows API accepts them.
    win_src=$(cygpath -w "$src" 2>/dev/null || printf '%s' "$src")
    win_dst=$(cygpath -w "$dst" 2>/dev/null || printf '%s' "$dst")
    powershell.exe -NoProfile -NonInteractive -Command \
      "New-Item -ItemType Junction -Path '$win_dst' -Target '$win_src' | Out-Null" \
      >/dev/null
  else
    ln -s "$src" "$dst"
  fi
}

# Is `path` a link that already points at `want_src`?
points_at() {
  path="$1"
  want_src="$2"
  # A POSIX symlink: compare readlink target (resolved) to the wanted source.
  if [ -L "$path" ]; then
    [ "$(resolve_dir "$path")" = "$(resolve_dir "$want_src")" ]
    return
  fi
  # A Windows junction is NOT seen as a symlink by `test -L` under Git Bash,
  # but it IS a directory that resolves (via `pwd -P`) to its target. If the
  # resolved real path equals the source's real path, it is our link.
  if is_windows && [ -d "$path" ]; then
    [ "$(resolve_dir "$path")" = "$(resolve_dir "$want_src")" ]
    return
  fi
  return 1
}

# Is `path` a real (non-link) directory — i.e. a stale COPY we must not clobber
# without --force?
#   POSIX: a dir that is not a symlink.
#   Windows: a dir that is neither a symlink NOR a reparse point (junction). A
#     junction pointing ELSEWHERE (not at us — that case is caught by
#     `points_at` first) is still a link, not a copy, so it is safe to
#     re-point without --force. We detect a reparse point as a directory whose
#     real path (resolved through `pwd -P`) differs from its own literal path.
is_real_copy() {
  path="$1"
  [ -d "$path" ] || return 1
  if [ -L "$path" ]; then
    return 1   # a symlink, not a real copy
  fi
  if is_windows; then
    literal=$(cd "$(dirname "$path")" 2>/dev/null && printf '%s/%s' "$(pwd -P)" "$(basename "$path")")
    real=$(resolve_dir "$path")
    if [ "$real" != "$literal" ]; then
      return 1   # a junction (redirects elsewhere) — a link, not a copy
    fi
  fi
  return 0
}

mkdir -p "$TARGET_DIR"

rc=0
linked=0
skipped=0

# Iterate every skills/<name> directory. `shared/` is included: it carries the
# retro-protocol security doc + tests that re-frame2-pair-retro loads, so it
# must be linked too. The README index lists eight USER-facing skills; we link
# whatever dirs exist (audit ALL of skills/, per the bead).
for entry in "$SKILLS_SRC"/*/; do
  [ -d "$entry" ] || continue
  name=$(basename "$entry")
  src=$(resolve_dir "$entry")
  dst="$TARGET_DIR/$name"

  if points_at "$dst" "$src"; then
    [ "$MODE" = "install" ] && printf 'install-skills: %s already linked -> %s\n' "$name" "$src"
    continue
  fi

  if [ "$MODE" = "check" ]; then
    if [ -e "$dst" ]; then
      printf 'install-skills: %s present but not linked to this repo (%s)\n' "$name" "$dst" >&2
    else
      printf 'install-skills: %s not installed (%s)\n' "$name" "$dst" >&2
    fi
    rc=1
    continue
  fi

  # install mode
  if is_real_copy "$dst"; then
    if [ "$FORCE" -eq 0 ]; then
      printf 'install-skills: WARNING %s is a real directory (a COPY), not a link.\n' "$dst" >&2
      printf '                Refusing to replace it — your local edits would be lost.\n' >&2
      printf '                Re-run with --force to replace this copy with a link to the repo:\n' >&2
      printf '                  scripts/install-skills.sh --force\n' >&2
      skipped=$((skipped + 1))
      continue
    fi
    rm -rf "$dst"
  elif [ -e "$dst" ] || [ -L "$dst" ]; then
    # A link (broken, or pointing elsewhere) — safe to drop and re-point.
    rm -rf "$dst"
  fi

  make_link "$src" "$dst"
  printf 'install-skills: linked %s -> %s\n' "$name" "$src"
  linked=$((linked + 1))
done

if [ "$MODE" = "check" ]; then
  if [ "$rc" -ne 0 ]; then
    printf '\nRun scripts/install-skills.sh to (re)link.\n' >&2
  else
    printf 'install-skills: all skills linked and current.\n'
  fi
  exit "$rc"
fi

printf '\ninstall-skills: linked %s, skipped %s (copies left intact; use --force to replace).\n' \
  "$linked" "$skipped"
printf 'install-skills: target %s now mirrors %s by link.\n' "$TARGET_DIR" "$SKILLS_SRC"
[ "$skipped" -gt 0 ] && exit 1
exit 0
