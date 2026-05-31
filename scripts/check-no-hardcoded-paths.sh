#!/usr/bin/env sh
# scripts/check-no-hardcoded-paths.sh
#
# Portability gate (rf2-1ppe4, child of the rf2-v6wyb portability EPIC).
#
# Root-cause LOCK for the portability scrub (rf2-q4sah, #2509): fail CI on
# any tracked file that reintroduces environment-specific hardcoding —
#
#   (1) the literal token `miket` (the maintainer's home-dir / username),
#       ANYWHERE in a tracked file; and
#   (2) a personal/home absolute path whose user component names a real
#       person — `/Users/<name>/`, `/home/<name>/`, or a drive-letter
#       `C:/Users/<name>/` (forward- OR back-slashed) — for any <name> in
#       the explicit PERSONAL_NAMES list below.
#
# It deliberately does NOT key on Windows path SHAPE (drive letters,
# backslashes) — those are legitimate cross-platform test coverage. The
# scrub re-rooted every such fixture onto the sanctioned NEUTRAL
# placeholders (`me` / `my-app` / `myapp` / `proj` / `u`), which carry no
# personal name and therefore never match. The gate keys purely on the
# PERSONAL name token, so neutral placeholders pass by construction.
#
# Cross-platform: POSIX sh. Runs identically on the Linux CI runner, on
# macOS, and under Git Bash on Windows. No bashisms (`[[`, arrays, `<<<`).
# The maintainer base is Mac/Linux; the CI runner is ubuntu-latest.
#
# Scope: TRACKED files only, via `git grep`. Gitignored trees
# (`ai/`, `site/`, `node_modules/`, `.shadow-cljs/`, `target/`, the staged
# `docs/spec` + `docs/migration` copies) are excluded for free because
# `git grep` never sees them. The one tracked tree we skip explicitly is
# `.beads/` — the issue-tracker data legitimately records bead prose that
# mentions `miket` (e.g. this bead's own description).
#
# Exit: 0 = clean; 1 = at least one violation (per-hit message printed).

set -eu

# ----------------------------------------------------------------------------
# PERSONAL_NAMES — path-component names that name a real person and so must
# NOT appear in a tracked absolute path. Space-separated, lower-case.
#
# Keep this list small and explicit. Add a name here only when it is a real
# maintainer/contributor home-dir component that could leak into a path.
# The NEUTRAL placeholders (`me`, `my-app`, `myapp`, `proj`, `u`) are
# deliberately absent — they are the sanctioned stand-ins and must pass.
# ----------------------------------------------------------------------------
PERSONAL_NAMES="mike miket"

# ----------------------------------------------------------------------------
# ALLOWLIST — tracked files that legitimately contain `miket` or a
# personal-named path BY DESIGN. Each entry is explicit and justified; this
# is a reviewed list, not a silent skip. Matched verbatim against the
# repo-relative, forward-slashed paths `git grep` emits.
#
# TWO TIERS (see bead rf2-1ppe4 NOTES):
#
#   PERMANENT — redaction test fixtures. They MUST carry user-named paths
#   to prove the scrubbing/redaction logic actually redacts them; replacing
#   the names with placeholders would defeat the test.
#     - skills/shared/tests/fixtures/02-redaction.md
#     - skills/shared/tests/retro_protocol_test.clj
#     - tools/machines-viz/test/day8/re_frame2_machines_viz/share_cljs_test.cljs
#
#   TEMPORARY — local worktree-guard + git-hook tooling that encodes the
#   maintainer's mayor/worktree layout (`C:/Users/miket/...`). Local
#   workflow tooling, never shipped. Allowlisted ONLY until rf2-lalk6
#   de-personalises the guard scripts; their entries become
#   unnecessary-but-harmless then and may be removed.
#     - CLAUDE.md
#     - scripts/assert-worker-worktree.ps1
#     - scripts/git-hooks/README.md
#     - scripts/git-hooks/lib/check-mayor-commit-boundary.sh
#     - scripts/git-hooks/pre-commit
#
# THIS script itself is allowlisted: it names `mike`/`miket` in the
# PERSONAL_NAMES list and in this comment by necessity.
#     - scripts/check-no-hardcoded-paths.sh
# ----------------------------------------------------------------------------
is_allowlisted() {
  case "$1" in
    # PERMANENT — redaction fixtures (prove scrubbing; names are the test input).
    skills/shared/tests/fixtures/02-redaction.md) return 0 ;;
    skills/shared/tests/retro_protocol_test.clj) return 0 ;;
    tools/machines-viz/test/day8/re_frame2_machines_viz/share_cljs_test.cljs) return 0 ;;
    # TEMPORARY — local guard + hook tooling (de-personalised by rf2-lalk6 later).
    CLAUDE.md) return 0 ;;
    scripts/assert-worker-worktree.ps1) return 0 ;;
    scripts/git-hooks/README.md) return 0 ;;
    scripts/git-hooks/lib/check-mayor-commit-boundary.sh) return 0 ;;
    scripts/git-hooks/pre-commit) return 0 ;;
    # This gate script (carries the PERSONAL_NAMES list + this comment).
    scripts/check-no-hardcoded-paths.sh) return 0 ;;
    *) return 1 ;;
  esac
}

# Build the extended-regexp alternation for personal-named paths. A name is
# a violation only as the USER COMPONENT of an absolute path root:
#
#     /Users/<name>/    /home/<name>/    <drive>:[\\/]+Users[\\/]+<name>
#
# Both `/` and `\` separators are accepted on the Windows drive-letter form
# so a `C:\Users\mike\...` leak is caught as readily as `C:/Users/mike/...`.
# The trailing `[\\/]` ensures we match the name as a full path component
# (so `mike` does not match inside `mikennedy`).
names_alt=""
for n in $PERSONAL_NAMES; do
  if [ -z "$names_alt" ]; then
    names_alt="$n"
  else
    names_alt="$names_alt|$n"
  fi
done
path_pattern="(/Users/|/home/|[A-Za-z]:[\\/]+Users[\\/]+)($names_alt)[\\/]"

# Two detection patterns:
#   1. literal `miket` anywhere (the maintainer username/home token).
#   2. a personal-named absolute path root (any PERSONAL_NAMES entry).
# `git grep -I` skips binary files; `-n` gives line numbers; `-E` extended
# regexp. `:!.beads` excludes the tracked issue-tracker data.
miket_hits=$(git grep -I -n -E 'miket' -- ':!.beads' || true)
path_hits=$(git grep -I -n -E "$path_pattern" -- ':!.beads' || true)

# Merge, drop allowlisted files, and report. Each `git grep` line is
# `path:lineno:content`; the path is everything up to the first colon.
violations=0
report_hits() {
  reason="$1"
  hits="$2"
  [ -z "$hits" ] && return 0
  printf '%s\n' "$hits" | while IFS= read -r line; do
    [ -z "$line" ] && continue
    file=${line%%:*}
    if is_allowlisted "$file"; then
      continue
    fi
    printf 'VIOLATION (%s): %s\n' "$reason" "$line"
  done
}

# Run reporting in a way that lets us both PRINT every hit and COUNT
# non-allowlisted ones. The while-subshell cannot mutate `violations`, so
# we capture the printed report and count its lines.
miket_report=$(report_hits "literal 'miket'" "$miket_hits")
path_report=$(report_hits "personal-named path" "$path_hits")

if [ -n "$miket_report" ]; then
  printf '%s\n' "$miket_report"
  violations=$((violations + $(printf '%s\n' "$miket_report" | grep -c '^VIOLATION')))
fi
if [ -n "$path_report" ]; then
  printf '%s\n' "$path_report"
  violations=$((violations + $(printf '%s\n' "$path_report" | grep -c '^VIOLATION')))
fi

if [ "$violations" -ne 0 ]; then
  printf '\n'
  printf 'Portability gate FAILED: %s hardcoded personal/home path violation(s).\n' "$violations" >&2
  printf 'Use the neutral placeholders instead (me / my-app / myapp / proj / u),\n' >&2
  printf 'or — if the path is a deliberate redaction fixture or local guard tool —\n' >&2
  printf 'add it to the explicit ALLOWLIST in scripts/check-no-hardcoded-paths.sh\n' >&2
  printf 'with a justification. See bead rf2-1ppe4.\n' >&2
  exit 1
fi

printf 'Portability gate OK: no hardcoded personal/home paths in tracked files.\n'
exit 0
