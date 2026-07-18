#!/usr/bin/env sh
# scripts/check-ai-tracking-ratchet.sh
#
# Tracked-`ai/` RATCHET (rf2-lsp1i).
#
# Mike ruled (2026-07-18): "nothing under /ai should be tracked" is the END
# STATE. Files stay tracked as long as they are needed; rf2-vxgfnd.99.1
# removes the remaining trees at re-frame.ui S7. rf2-4lv73 (#6368) states
# that policy as prose in CLAUDE.md and .gitignore.
#
# PROSE DOES NOT STOP A FORCE-ADD. This script is the mechanism.
#
# WHY IT EXISTS — a realised failure, not a hypothetical. On 2026-07-18 a
# force-added ai/decisions/ dossier carried a literal personal worktree path
# into CI. scripts/check-no-hardcoded-paths.sh then failed ON MAIN, and
# because CI evaluates the PR *merge commit*, every open PR went red for
# about an hour. Cost: six worker dispatches. Four workers independently
# named the tracking as the root cause. This gate is the upstream lock for
# that leak class: it fires at the moment of the force-add, on the PR that
# does it, instead of downstream on everyone else's branch.
#
# THE RULE — one number, compared one way:
#
#     count(git ls-files ai/)  >  count(baseline)   =>  FAIL
#     anything else                                 =>  PASS
#
# A DECREASE MUST PASS. S7's removals must never be blocked by this gate,
# so the comparison is deliberately one-directional. The number can only
# fall over time, which means the gate needs no upkeep and RETIRES ITSELF
# at zero — when the last tracked ai/ file goes, the baseline is empty and
# any addition at all fails.
#
# NOT AN ALLOWLIST. The baseline file is a machine-generated SNAPSHOT of
# `git ls-files ai/`, kept only so that a failure can NAME the offending
# paths (a bare count mismatch is not actionable). It is NOT a list of
# trees that are permitted to be tracked, and nothing here decides policy
# from its contents: the pass/fail decision is the COUNT comparison alone.
# Mike ruled that CLAUDE.md states policy only and that git state is the
# roster — an enumerating guard would reintroduce exactly what that ruling
# forbids, so this file enumerates nothing of its own.
#
# A LEGITIMATE ADDITION IS STILL POSSIBLE — it just has to be deliberate.
# Refresh the baseline in the same PR (see REFRESH below) and the addition
# lands as a reviewed, visible one-line diff instead of a silent force-add.
# Note that .gitignore un-ignores /ai/extended-context/**, so a file added
# there needs no `git add -f` and will trip this gate; that is the intent —
# the gate makes the growth visible, not impossible.
#
# REFRESH (run from the repo root, on a branch, as a reviewed change):
#     git ls-files ai/ | LC_ALL=C sort > scripts/ai-tracked-baseline.txt
#
# Cross-platform: POSIX sh. No bashisms (`[[`, arrays, `<<<`, process
# substitution). Runs identically on the ubuntu-latest CI runner, on macOS,
# and under Git Bash on Windows. Invoked as `sh scripts/...` so it needs no
# executable bit on the runner.
#
# Exit: 0 = clean; 1 = the tracked-ai/ count increased.

set -eu

# Run from the repo root regardless of the caller's working directory —
# several CI jobs set `defaults.run.working-directory`, and `git ls-files
# ai/` is relative to the cwd.
repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

BASELINE_FILE="scripts/ai-tracked-baseline.txt"

if [ ! -f "$BASELINE_FILE" ]; then
  printf 'ai/ tracking ratchet FAILED: baseline file %s is missing.\n' "$BASELINE_FILE" >&2
  printf 'Regenerate it with:  git ls-files ai/ | LC_ALL=C sort > %s\n' "$BASELINE_FILE" >&2
  exit 1
fi

# Temp files: `comm` needs two sorted FILES, and process substitution is a
# bashism. Clean up on every exit path.
current_list=$(mktemp)
added_list=$(mktemp)
removed_list=$(mktemp)
cleanup() {
  rm -f "$current_list" "$added_list" "$removed_list"
}
trap cleanup EXIT

# `git ls-files` already emits sorted paths; sort again under LC_ALL=C so
# the ordering matches the baseline byte-for-byte on every locale.
git ls-files ai/ | LC_ALL=C sort > "$current_list"

current_count=$(wc -l < "$current_list" | tr -d ' ')
baseline_count=$(wc -l < "$BASELINE_FILE" | tr -d ' ')

# Set difference in both directions, for the failure message and for the
# stale-baseline advisory. `comm -13` = lines only in the second file
# (added since the baseline); `comm -23` = only in the first (removed).
LC_ALL=C comm -13 "$BASELINE_FILE" "$current_list" > "$added_list"
LC_ALL=C comm -23 "$BASELINE_FILE" "$current_list" > "$removed_list"

if [ "$current_count" -gt "$baseline_count" ]; then
  printf 'ai/ tracking ratchet FAILED: tracked files under ai/ rose from %s to %s.\n' \
    "$baseline_count" "$current_count" >&2
  printf '\n' >&2
  printf 'Tracked under ai/ but NOT in the baseline:\n' >&2
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    printf '  ADDED: %s\n' "$p" >&2
  done < "$added_list"
  printf '\n' >&2
  printf 'Nothing under /ai/ should be tracked (Mike, 2026-07-18) — that is the\n' >&2
  printf 'END STATE, and the count is only allowed to fall on the way there.\n' >&2
  printf 'A force-added ai/ file has already taken every open PR red once.\n' >&2
  printf '\n' >&2
  printf 'To fix: git rm --cached <path> on the file(s) above (they stay on disk;\n' >&2
  printf '/ai/ is gitignored). If the addition is genuinely intended, refresh the\n' >&2
  printf 'baseline in this same PR so the growth is reviewed:\n' >&2
  printf '  git ls-files ai/ | LC_ALL=C sort > %s\n' "$BASELINE_FILE" >&2
  printf 'See bead rf2-lsp1i.\n' >&2
  exit 1
fi

# PASS. A decrease is the expected direction of travel (rf2-vxgfnd.99.1
# removes the remaining trees at S7), so say so and note that the baseline
# can be lowered to keep the ratchet tight.
if [ "$current_count" -lt "$baseline_count" ]; then
  printf 'ai/ tracking ratchet OK: tracked files under ai/ FELL from %s to %s.\n' \
    "$baseline_count" "$current_count"
  printf 'Baseline is now slack. Tighten it in this PR (optional, never required):\n'
  printf '  git ls-files ai/ | LC_ALL=C sort > %s\n' "$BASELINE_FILE"
  exit 0
fi

# Equal counts. A pure rename (one path out, one in) lands here; the count
# rule passes it, but the baseline is stale, so say which paths moved.
if [ -s "$added_list" ] || [ -s "$removed_list" ]; then
  printf 'ai/ tracking ratchet OK: count unchanged at %s, but the baseline is stale.\n' \
    "$current_count"
  printf 'Refresh it:  git ls-files ai/ | LC_ALL=C sort > %s\n' "$BASELINE_FILE"
  exit 0
fi

printf 'ai/ tracking ratchet OK: %s tracked file(s) under ai/, matching the baseline.\n' \
  "$current_count"
exit 0
