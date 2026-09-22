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
#                                             # (for a scratch target; never needs admin)
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

# Keep Windows junction semantics in one implementation. Passing paths as
# arguments to -File also handles apostrophes without interpolating shell data
# into PowerShell source.
if is_windows; then
  win_script=$(cygpath -w "$SCRIPT_DIR/install-skills.ps1")
  win_target=$(cygpath -w "$TARGET_DIR")
  set -- -Target "$win_target"
  [ "$MODE" != "check" ] || set -- "$@" -Check
  [ "$FORCE" -eq 0 ] || set -- "$@" -Force
  exec powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass \
    -File "$win_script" "$@"
fi

# Resolve POSIX symlinks without depending on readlink -f (absent on macOS).
# `pwd -P` yields the PHYSICAL path, so a symlink in an ANCESTOR resolves too
# and the overlap guard below cannot be bypassed by aliasing the checkout above
# skills/ (rf2-7bwh1). Windows never reaches here - it execs install-skills.ps1
# above, whose Resolve-RealDir walks ancestors to get the same property.
resolve_dir() {
  if [ -d "$1" ]; then
    (cd "$1" 2>/dev/null && pwd -P) || printf '%s' "$1"
  else
    printf '%s' "$1"
  fi
}

points_at() {
  [ -L "$1" ] && [ "$(resolve_dir "$1")" = "$2" ]
}

# Refuse source/destination overlap before --force can remove source skills.
if [ "$(resolve_dir "$TARGET_DIR")" = "$(resolve_dir "$SKILLS_SRC")" ]; then
  printf "install-skills: target is this checkout's skills source; choose a separate destination (%s)\n" "$TARGET_DIR" >&2
  exit 1
fi

[ "$MODE" != "install" ] || mkdir -p "$TARGET_DIR"

rc=0
linked=0
skipped=0

# Iterate every skills/<name> directory. Each skill is self-contained; we
# link whatever dirs exist (audit ALL of skills/, per the bead).
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
  if [ -e "$dst" ] && [ ! -L "$dst" ]; then
    if [ "$FORCE" -eq 0 ]; then
      printf 'install-skills: WARNING %s is a non-link file or directory (a COPY).\n' "$dst" >&2
      printf '                Refusing to replace it — your local edits would be lost.\n' >&2
      printf '                Re-run with --force to replace this copy with a link to the repo:\n' >&2
      printf '                  scripts/install-skills.sh --force\n' >&2
      skipped=$((skipped + 1))
      continue
    fi
    rm -rf "$dst"
  elif [ -e "$dst" ] || [ -L "$dst" ]; then
    # A link (broken, or pointing elsewhere) — safe to drop and re-point.
    rm -f "$dst"
  fi

  ln -s "$src" "$dst"
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
