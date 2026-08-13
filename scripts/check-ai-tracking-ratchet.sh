#!/usr/bin/env sh
# scripts/check-ai-tracking-ratchet.sh
#
# Tracked-`ai/` RATCHET (rf2-lsp1i, fixed by rf2-ow7dz).
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
# THE RULE — a SET comparison against the last accepted state:
#
#     { tracked under ai/ now } \ { tracked under ai/ at BASE }  non-empty
#         =>  FAIL, naming every path in that difference
#     otherwise                                        =>  PASS
#
# WHY A SET AND NOT A COUNT (rf2-ow7dz). The first cut of this gate compared
# COUNTS against a committed snapshot file. That is a static ceiling, not a
# ratchet, and it leaked in three ways once the count had fallen: a later
# increase back toward the stale ceiling passed; an equal-count replacement
# (remove one, add one) passed and admitted a brand-new path invisibly; and
# after the set reached zero the stale ceiling still permitted additions
# indefinitely. A set difference has none of those holes — it does not care
# what the counts are, only whether a path is tracked now that was not
# tracked before.
#
# A DECREASE MUST PASS, and does: removals only ever shrink the current set,
# so the difference stays empty. S7's removals are never blocked by this
# gate.
#
# THE BASELINE TIGHTENS AUTOMATICALLY, with no maintenance step. BASE is
# read from git, not from a file, so the instant a removal is accepted the
# smaller set IS the baseline for everything that follows. There is nothing
# to refresh and nothing that can go stale. When the last tracked ai/ file
# goes, BASE is empty and the gate retires itself into "any addition fails".
#
# NOT AN ALLOWLIST — and this file enumerates nothing. Mike ruled that
# CLAUDE.md states policy and that `git ls-files ai/` is the roster, never a
# list in a file. Reading BASE from git honours that literally: the previous
# snapshot file was the last remaining list-in-a-file, and it is gone. No
# tree is named here, blessed here, or excluded here.
#
# THERE IS NO IN-BAND ESCAPE HATCH, by choice. A count ceiling could be
# raised by editing a file; a git-derived base cannot, which is exactly what
# makes it leak-proof. Since the END STATE is zero, a genuinely intended new
# tracked ai/ file is a policy exception rather than routine work: it needs
# Mike, and it lands by an explicit admin merge over a red gate. That is a
# real cost, and it is the intended one — the alternative is a lever that a
# force-add can pull on its own.
#
# BASE resolution. The base is read from AI_RATCHET_BASE_REF, defaulting to
# `HEAD^` only when it is unset or empty. CI sets it to the EVENT's accepted
# base (rf2-7hq4l) and never lets the default stand in for a push:
#   * pull_request: github.event.pull_request.base.sha, the base-branch tip.
#     This equals the merge commit's first parent, so HEAD^ was already sound
#     here; the workflow passes it explicitly so both shapes share one rule.
#   * push to main: github.event.before, the tip main pointed at before the
#     push. HEAD^ is NOT that tip on a MULTI-COMMIT push — it is the push's own
#     second-to-last commit — so a path first tracked in an earlier commit of
#     the push and retained at the tip would sit on both sides of a HEAD^ diff
#     and escape. Comparing against the accepted base names it instead.
# `HEAD^` stays the default for LOCAL and manual runs, where the parent is a
# sensible base and there is no event to consult. If the base cannot be
# resolved (root commit, or an over-shallow clone) the gate FAILS rather than
# passing: a guard that cannot see its base must not certify anything.
#
# THE BASE IS PEELED TO `^{commit}`, AND THAT IS LOAD-BEARING (rf2-uol6). The
# first cut resolved it with `git rev-parse --verify --quiet "$base_ref"`, which
# looks fail-closed and is not: for a FULL 40-hex sha git echoes the string back
# with exit 0 WITHOUT consulting the object store, so an absent base passed the
# guard. `git ls-tree` on it then failed — into a pipeline, whose POSIX status is
# the LAST command's (`sort`, exit 0), so `set -e` never fired either. Both
# leaks composed into a false green: an empty base list, an empty diff, and
# "END STATE reached" printed over a base that was never read. Peeling forces
# the lookup, and the ls-tree below is run UNPIPED with its status checked.
#
# That is what makes a SHALLOW CI checkout safe. portability.yml checks out with
# fetch-depth: 2 (holding HEAD^ for the manual-dispatch fallback) and fetches the
# accepted base as a single `--depth=1` commit when it is deeper — the gate needs
# the base's TREE, never its ancestry. A fetch that misses the base now reds.
#
# Cross-platform: POSIX sh. No bashisms (`[[`, arrays, `<<<`, process
# substitution). Runs identically on the ubuntu-latest CI runner, on macOS,
# and under Git Bash on Windows. Invoked as `sh scripts/...` so it needs no
# executable bit on the runner.
#
# Exit: 0 = clean; 1 = a path is tracked under ai/ that was not tracked at
# BASE, or BASE could not be resolved.

set -eu

# Run from the repo root regardless of the caller's working directory —
# several CI jobs set `defaults.run.working-directory`, and `git ls-files
# ai/` is relative to the cwd.
repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

base_ref=${AI_RATCHET_BASE_REF:-HEAD^}

# `--verify --quiet` prints nothing and exits non-zero on an unresolvable ref.
# The `^{commit}` peel is REQUIRED, not decorative: without it a full 40-hex sha
# resolves to itself even when the object is missing from a shallow clone (see
# the header). Guard the assignment so `set -e` does not abort before the
# message.
base_sha=$(git rev-parse --verify --quiet "$base_ref^{commit}" 2>/dev/null) || base_sha=''

if [ -z "$base_sha" ]; then
  printf 'ai/ tracking ratchet FAILED: cannot resolve base ref %s.\n' "$base_ref" >&2
  printf '\n' >&2
  printf 'This gate compares the tracked ai/ set against the last accepted\n' >&2
  printf 'state, so without a base it cannot certify anything and will not\n' >&2
  printf 'pass by default. Usual causes: a depth-1 shallow clone (CI checks\n' >&2
  printf 'out with fetch-depth: 2, which holds HEAD^); an accepted base that\n' >&2
  printf 'is deeper than that and was never fetched (CI fetches it as a\n' >&2
  printf 'single --depth=1 commit — see .github/workflows/portability.yml);\n' >&2
  printf 'or a root commit with no parent.\n' >&2
  printf 'Set AI_RATCHET_BASE_REF to name the base explicitly.\n' >&2
  exit 1
fi

# Temp files: `comm` needs two sorted FILES, and process substitution is a
# bashism. Clean up on every exit path.
current_list=$(mktemp)
base_list=$(mktemp)
base_raw=$(mktemp)
added_list=$(mktemp)
removed_list=$(mktemp)
# `cleanup` IS reachable: `trap cleanup EXIT` below invokes it, and a POSIX
# EXIT trap fires on every `exit` in this script. ShellCheck cannot see that.
# Its reachability analysis models the EXIT trap as the script's fall-through
# ending, and every path here ends in an explicit `exit`, so it concludes the
# body is dead. Removing it would leak four temp files per run. Both codes
# are listed because the diagnostic was renumbered: pre-0.10 shellcheck (what
# the ubuntu runner ships) reports SC2317 on the `rm`, 0.10+ reports SC2329
# on the function. Listing both keeps this lint-clean on either version.
# shellcheck disable=SC2317,SC2329
cleanup() {
  rm -f "$current_list" "$base_list" "$base_raw" "$added_list" "$removed_list"
}
trap cleanup EXIT

# Current state: the INDEX, so a staged force-add is caught before it is
# even committed. Base state: the tree at BASE. Both sorted under LC_ALL=C
# so `comm` sees the same collation on every locale.
#
# The base read is UNPIPED and its status checked. Piping it into `sort` hides
# the failure — a POSIX pipeline reports the LAST command's status — and the
# gate would then compare against an empty base and pass anything.
git ls-files ai/ | LC_ALL=C sort > "$current_list"
if ! git ls-tree -r --name-only "$base_sha" -- ai/ > "$base_raw" 2>/dev/null; then
  printf 'ai/ tracking ratchet FAILED: cannot read the tree at base %s (%s).\n' \
    "$base_ref" "$base_sha" >&2
  printf '\n' >&2
  printf 'The commit resolved but its tree could not be listed, so the base\n' >&2
  printf 'state is unknown and this gate will not certify anything. Usual\n' >&2
  printf 'cause: a partial clone missing that tree. Fetch the base commit\n' >&2
  printf 'in full, or set AI_RATCHET_BASE_REF to a base you hold.\n' >&2
  exit 1
fi
LC_ALL=C sort "$base_raw" > "$base_list"

current_count=$(wc -l < "$current_list" | tr -d ' ')
base_count=$(wc -l < "$base_list" | tr -d ' ')

# `comm -13` = lines only in the second file (tracked now, not at BASE — the
# additions this change makes). `comm -23` = only in the first (removals).
LC_ALL=C comm -13 "$base_list" "$current_list" > "$added_list"
LC_ALL=C comm -23 "$base_list" "$current_list" > "$removed_list"

if [ -s "$added_list" ]; then
  printf 'ai/ tracking ratchet FAILED: %s path(s) newly tracked under ai/.\n' \
    "$(wc -l < "$added_list" | tr -d ' ')" >&2
  printf 'Base %s tracked %s file(s) under ai/; this change tracks %s.\n' \
    "$base_ref" "$base_count" "$current_count" >&2
  printf '\n' >&2
  printf 'Tracked under ai/ now, but NOT tracked at the base:\n' >&2
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    printf '  ADDED: %s\n' "$p" >&2
  done < "$added_list"
  printf '\n' >&2
  printf 'Nothing under /ai/ should be tracked (Mike, 2026-07-18) — that is the\n' >&2
  printf 'END STATE, and the set is only allowed to shrink on the way there.\n' >&2
  printf 'A force-added ai/ file has already taken every open PR red once.\n' >&2
  printf '\n' >&2
  printf 'This fires on ANY newly tracked ai/ path, whatever the totals do —\n' >&2
  printf 'including a swap that leaves the count unchanged, and including an\n' >&2
  printf 'addition made after an earlier removal.\n' >&2
  printf '\n' >&2
  printf 'To fix: git rm --cached <path> on the file(s) above. They stay on\n' >&2
  printf 'disk; /ai/ is gitignored. If the addition is genuinely intended it\n' >&2
  printf 'is a policy exception for Mike, not a gate to edit around.\n' >&2
  printf 'See beads rf2-lsp1i and rf2-ow7dz.\n' >&2
  exit 1
fi

# PASS. A decrease is the expected direction of travel (rf2-vxgfnd.99.1
# removes the remaining trees at S7). Nothing to refresh: the next run reads
# its base from git and is already tight against this smaller set.
if [ -s "$removed_list" ]; then
  printf 'ai/ tracking ratchet OK: tracked files under ai/ FELL from %s to %s.\n' \
    "$base_count" "$current_count"
  printf 'Removed:\n'
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    printf '  REMOVED: %s\n' "$p"
  done < "$removed_list"
  printf 'The ratchet tightens automatically — %s is the new ceiling.\n' "$current_count"
  exit 0
fi

if [ "$current_count" -eq 0 ]; then
  printf 'ai/ tracking ratchet OK: nothing under ai/ is tracked. END STATE reached.\n'
  exit 0
fi

printf 'ai/ tracking ratchet OK: %s tracked file(s) under ai/, unchanged from base %s.\n' \
  "$current_count" "$base_ref"
exit 0
