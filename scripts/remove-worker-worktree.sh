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
#   5. Re-snapshot the canary and FAIL LOUDLY if the signature moved.
#   Steps 2 and 4 are never chained into one command: the point is that the
#   removal runs against a tree with no live reparse point left in it.
#
# USAGE
#   sh scripts/remove-worker-worktree.sh <worktree-path> [<worktree-path>...]
#   sh scripts/remove-worker-worktree.sh --dry-run <worktree-path>
#   sh scripts/remove-worker-worktree.sh --self-test
#
#   --force              force-remove a dirty worktree, DELETING whatever it
#                        holds. You have looked; you are sure.
#   --force-disposable   force-remove ONLY when every untracked path is build
#                        or gate output; refuse the tree otherwise. This is the
#                        flag a bulk hygiene sweep wants.
#   --husk       the paths given are HUSKS of worktrees of this repository —
#                git already gutted them and left the files. Only then will an
#                unregistered path be disarmed and classified; see below. Pass
#                it alone, with the paths you have identified.
#   --dry-run    report what would be disarmed/removed; change nothing
#   --self-test  prove the disarm against a throwaway link in a temp dir
#   --mayor-root <path> / RF2_MAYOR_ROOT   override mayor-root derivation
#
# CONTRACT (preserved exactly across both implementations) — stdout lines:
#   MAYOR_ROOT=<absolute path>
#   CANARY_BEFORE=<path> <signature>        (one per real mayor node_modules)
#   DISARMED=<link path> -> <target>        (one per link unlinked)
#   NO_LINKS=<worktree path>                (when the worktree carried none)
#   HUSK_PROVENANCE=<path> <what identified it>
#   REMOVED=<worktree path>
#   CANARY_AFTER=<path> <signature>
#   OK: worktree removal complete.
#   Exit 0 on success, 1 on failure, 2 on usage error, 3 on a PARTIAL removal
#   (below). A moved canary prints CANARY_FAILED to stderr with the npm ci
#   recovery command.
#
#   The exit codes split on WHAT THE CALLER SHOULD DO NEXT, which is the only
#   thing a caller can act on:
#     0  done.
#     1  refused or failed, and NOTHING was destroyed — this script is either
#        still the right tool (retry when the lock clears; --force-disposable
#        for build output; a human for [KEEP] paths) or the wrong one for a
#        path it declines to identify.
#     2  usage error.
#     3  PARTIAL — the tree is already gutted and deregistered. This script
#        can do nothing more with it; see REMOVE_PARTIAL below. 3 wins over 1
#        when both occur in one run, because it is the outcome needing a
#        different remedy. Exit 3 is a CLAIM ABOUT PROVENANCE and is only ever
#        reached when that provenance is established — never from a predicate
#        that merely failed to recognise something.
#
#   A <signature> is `<immediate-entries>/<recursive-files>/<sentinels-present>`,
#   e.g. `103/2933/2`. The recursive count and the sentinels are load-bearing:
#   the immediate-entry count alone cannot see files vanishing from under
#   packages whose directories survive.
#
#   A FAILED removal is classified rather than guessed at (rf2-p0m6m). Nine
#   worktrees were read as file-locked and waited out for two days; every one
#   was simply dirty, and no amount of waiting adds a flag. So:
#     REMOVE_REFUSED_DIRTY=  git refused; the paths are listed and tagged
#                            [build output] or [KEEP].
#     REMOVE_REFUSED_KEEP=   --force-disposable stopped at unreviewed work.
#     REMOVE_FAILED=         the tree is CLEAN and INTACT, so this really is a
#                            file lock and really is worth retrying.
#     REMOVE_PARTIAL=        a HUSK (rf2-k3j2w) — see below. Exit 3.
#   --dry-run reports the same partition up front as WOULD_NEED_FORCE= (all
#   build output, sweepable) or WOULD_REFUSE_KEEP= (needs a human first).
#
#   THE FOURTH OUTCOME: A PARTIAL REMOVAL (rf2-k3j2w). `git worktree remove`
#   is not atomic on Windows. It can deregister the worktree and delete its
#   `.git`, then hit a file lock on the directory itself and stop — leaving a
#   HUSK: every source file still on disk, no worktree behind it. The state is
#   genuinely hard to see, which is why it earned a name here:
#     - `git worktree list` does not mention it — already deregistered;
#     - `git worktree prune` does not clear it — prune drops ADMIN records for
#       directories that vanished, and here the opposite happened;
#     - `git -C <husk> status --porcelain` FAILS and prints nothing, which the
#       clean/dirty split above read as "tree is CLEAN" and therefore as
#       REMOVE_FAILED, "safe to retry once it clears". Retrying is futile: the
#       registered-worktree check refuses it, forever;
#     - to a human `ls` it is indistinguishable from a live worktree.
#   And it is not a near-miss. A husk kills a running gate exactly as a
#   completed removal does — "the removal failed" is NOT "the worker was
#   unaffected". Both entry points report it: a removal that fails this way,
#   and a later invocation handed a path that is already a husk.
#
#   AND WHAT A HUSK IS *NOT*: MERELY AN UNREGISTERED DIRECTORY (rf2-k3j2w,
#   second pass). "git does not list it and it has no `.git`" is true of a
#   husk — and equally true of an ordinary project, a mistyped argument, and a
#   directory that has never been anything else. The first version of the
#   later-invocation path read that negative as proof. Handed a temp directory
#   created seconds earlier it unlinked any node_modules inside it, declared
#   it a DESTROYED TREE and recommended `rm -rf`: a destructive script
#   confidently recommending the destruction of something it had misidentified.
#   Reproduced on demand, `--dry-run` against a fresh empty directory, exit 3.
#
#   So the two entry points are no longer symmetric, because their EVIDENCE is
#   not symmetric:
#     - SAME INVOCATION — we called `git worktree remove` on a REGISTERED
#       worktree and watched it fail. Provenance is known first-hand, so the
#       classification stays automatic.
#     - LATER INVOCATION — a path handed to us cold. Refused unless the caller
#       says `--husk` AND the directory still holds a KILOBYTE of content this
#       repository recognises as its own. Both are required. An acknowledgement
#       is carried by a mistyped path exactly as willingly as by the right one,
#       so the flag alone does not license the delete; and the content test is
#       a read of git's own object database rather than a guess about names,
#       shapes or locations. Bytes rather than a file count, because tiny files
#       coincide — a throwaway `package.json` reading `{}` matched a blob of
#       ours on the first run. `HUSK_PROVENANCE=` prints what was established.
#   A REFUSAL TOUCHES NOTHING — no disarm, no advice, no delete. Every one of
#   those acts presumes the identification that has just failed.
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
FORCE_DISPOSABLE=0
DRY_RUN=0
SELF_TEST=0
HUSK=0
MAYOR_ROOT="${RF2_MAYOR_ROOT:-}"

# Absolute path to this script. --self-test invokes it as a subprocess to prove
# what the MAIN LOOP does, and a relative $0 would not survive the fixtures.
SCRIPT_SELF="$0"
if _ss_dir=$(cd "$(dirname "$0")" 2>/dev/null && pwd); then
  SCRIPT_SELF="$_ss_dir/$(basename "$0")"
fi

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
    --force)             FORCE=1; shift ;;
    --force-disposable)  FORCE_DISPOSABLE=1; shift ;;
    --husk)      HUSK=1; shift ;;
    --dry-run)   DRY_RUN=1; shift ;;
    --self-test) SELF_TEST=1; shift ;;
    --mayor-root)   MAYOR_ROOT="${2:-}"; shift 2 ;;
    --mayor-root=*) MAYOR_ROOT="${1#*=}"; shift ;;
    -h|--help)
      # The whole leading comment block, however long it grows — a hardcoded
      # line range silently truncates the contract the first time an edit
      # pushes it past the end.
      awk 'NR==1 {next} /^#/ {print; next} {exit}' "$0"
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

# Paths inside a node_modules whose disappearance is damage on its own, whatever
# the counts say. Kept to two: one directory every install has, one package this
# repo cannot build without.
NM_SENTINELS='.bin shadow-cljs/package.json'

# A node_modules HEALTH SIGNATURE: <immediate-entries>/<recursive-files>/<sentinels>.
# Prints the word MISSING when the directory is not there at all — MISSING
# against a real "before" is itself a canary failure.
#
# The immediate-entry count alone was the original canary and it is half-blind:
# a partial recursive delete can empty the files *under* every package while
# leaving all the top-level package directories standing, so before == after
# reports healthy over material damage. The recursive file count sees exactly
# that shape, and the sentinels see a targeted loss two counts could coincide
# on. All three are cheap — 2933 files in 0.08s over this repo's mayor
# node_modules — so there is no reason to settle for the blind one.
signature() {
  if [ ! -d "$1" ]; then
    printf 'MISSING'
    return
  fi
  _sig_entries=$(find "$1" -mindepth 1 -maxdepth 1 2>/dev/null | wc -l | tr -d ' ')
  _sig_files=$(find "$1" -type f 2>/dev/null | wc -l | tr -d ' ')
  _sig_sentinels=0
  for _sig_s in $NM_SENTINELS; do
    if [ -e "$1/$_sig_s" ]; then
      _sig_sentinels=$((_sig_sentinels + 1))
    fi
  done
  printf '%s/%s/%s' "$_sig_entries" "$_sig_files" "$_sig_sentinels"
}

# What `git worktree remove` will refuse over: modified or untracked files.
# Empty output means clean. This is the discriminator behind a failed removal
# (rf2-p0m6m) — see report_remove_failure.
#
# ONLY MEANINGFUL ON AN INTACT WORKTREE. On a husk the git call fails, prints
# nothing, and this returns empty — identical to clean. Every caller therefore
# checks worktree_is_intact FIRST.
worktree_dirt() {
  git -C "$1" status --porcelain 2>/dev/null || true
}

# Is there still a git worktree behind this directory?
#
# The one honest discriminator for a HUSK (rf2-k3j2w): a partially failed
# `git worktree remove` deletes the worktree's `.git` before it deletes the
# files, so a husk's files survive with nothing behind them. Verified against
# two husks found sitting in a worktree parent on 2026-08-05: `ls` shows a full
# checkout, `rev-parse --git-dir` exits 128, `git worktree list` has never
# heard of them and `git worktree prune` reports nothing to do.
#
# Note this is a READ of git's own state, not a guess about who is using the
# tree. Whether an AGENT is still alive is not knowable from here and this
# script does not try — see the reaping rule in docs/the-mayor-method.
#
# BOTH halves are load-bearing. `rev-parse` alone walks UP the directory tree,
# so a husk sitting inside another checkout would answer with THAT repo and
# read as intact; the `-e` catches it, because a linked worktree always has a
# `.git` file at its root and a husk is exactly the tree that lost one. And
# `-e` alone would accept a dangling or truncated `.git`. Neither is compared
# against a path shape: under Git Bash `git` prints `C:/Users/...` where `pwd`
# prints `/c/Users/...`, and a textual compare of the two never matches.
worktree_is_intact() {
  [ -e "$1/.git" ] && git -C "$1" rev-parse --git-dir >/dev/null 2>&1
}

# Does this directory hold content that belongs to THIS repository?
#
# THE POSITIVE HALF of husk identification (rf2-k3j2w). "Unregistered, and no
# `.git`" is a pair of NEGATIVES, and every ordinary directory on the machine
# satisfies both. What actually distinguishes a husk is what it still CONTAINS:
# the files git had checked out and did not get to delete.
#
# So this is a read of the repository's own object database, not a guess about
# names, shapes or locations. Each top-level tracked FILE of the mayor's HEAD
# that also exists in the candidate is hashed with the attributes git itself
# would apply (`hash-object --path`, which is why a CRLF checkout still
# matches), and the resulting blob is offered back to `cat-file -e`. A hit
# means this repository has, at some commit, stored exactly those bytes under
# exactly that name.
#
# `cat-file -e` rather than a compare against HEAD's blob is deliberate: a husk
# was checked out at whatever commit its worker was on, and half its files will
# differ from mayor HEAD. Any HISTORICAL version counts, which is what makes an
# unrelated project score zero while a husk scores most of the roster.
#
# A MATCH COUNT IS NOT THE MEASURE; MATCHED BYTES ARE. A tiny file is not
# evidence of anything, because tiny files coincide: the first version of this
# counted files, and a throwaway directory holding a three-byte `package.json`
# reading `{}` scored a hit against this repository, because `{}` is a blob
# every second project has committed at some point. The empty blob is worse
# still — it exists in every repository there is.
#
# So the criterion is a VOLUME of recognised content: at least
# HUSK_PROVENANCE_MIN_BYTES of bytes this repository has stored under those
# exact names. The floor is a stated one rather than a derived one, and it is
# cheap to be wrong in either direction: any real checkout clears it on its
# first substantial file (a wild husk found in the worktree parent matched 8
# files and tens of kilobytes), while a husk stripped down to less than a
# kilobyte of recognisable content is refused — and a directory with nothing
# recognisable left in it is an ordinary directory, which the caller can delete
# without this script's help.
HUSK_PROVENANCE_MIN_BYTES=1024

# Prints "<hits> <candidates> <matched-bytes> <first-matching-file>".
repo_content_matches() {
  _rcm_dir="$1"
  _rcm_hits=0
  _rcm_total=0
  _rcm_bytes=0
  _rcm_first=""
  : > "$WORK_DIR/toplevel"
  git -C "$MAYOR_ROOT" ls-tree HEAD 2>/dev/null \
    | awk -F'\t' '{split($1, meta, " "); if (meta[2] == "blob") print $2}' \
    >> "$WORK_DIR/toplevel" 2>/dev/null || true
  while IFS= read -r _rcm_p; do
    [ -n "$_rcm_p" ] || continue
    _rcm_total=$((_rcm_total + 1))
    [ -f "$_rcm_dir/$_rcm_p" ] || continue
    [ -s "$_rcm_dir/$_rcm_p" ] || continue
    _rcm_h=$(git -C "$MAYOR_ROOT" hash-object --path "$_rcm_p" -- "$_rcm_dir/$_rcm_p" 2>/dev/null) || continue
    [ -n "$_rcm_h" ] || continue
    git -C "$MAYOR_ROOT" cat-file -e "$_rcm_h" 2>/dev/null || continue
    _rcm_sz=$(wc -c < "$_rcm_dir/$_rcm_p" 2>/dev/null | tr -d ' ') || _rcm_sz=0
    [ -n "$_rcm_sz" ] || _rcm_sz=0
    _rcm_hits=$((_rcm_hits + 1))
    _rcm_bytes=$((_rcm_bytes + _rcm_sz))
    [ -n "$_rcm_first" ] || _rcm_first="$_rcm_p"
  done < "$WORK_DIR/toplevel"
  printf '%s %s %s %s' "$_rcm_hits" "$_rcm_total" "$_rcm_bytes" "$_rcm_first"
}

# Is one `git status --porcelain` line safe to delete unreviewed?
#
# Only UNTRACKED (`??`) build and gate output qualifies. A modified tracked
# file never does, whatever its path: three worktrees were carrying uncommitted
# source and doc edits when this was written, and a blanket force would have
# taken them silently. Neither does a hand-written note — band-ymi6j held four
# `ladder-*.md` analysis files for an OPEN bead. A worktree that will not reap
# is clutter; deleting somebody's unreviewed work is not.
#
# The patterns are shape-based rather than a roster of names on purpose: four
# different log directories (`logs/`, `bench-logs/`, `.gate-logs/`, `.wtlogs/`)
# turned up across nine worktrees, because every worker invents its own, and a
# roster would have missed two of them. Anything unmatched is NOT disposable —
# the rule fails closed, so the cost of a gap is a tree that needs a human
# glance, never a deletion.
is_disposable_line() {
  case "$1" in
    '?? '*) _idl_path=${1#?? } ;;
    *)      return 1 ;;
  esac
  case "$_idl_path" in
    *logs/|out/|*/out/|.shadow-cljs/|*/.shadow-cljs/|*.log) return 0 ;;
    *) return 1 ;;
  esac
}

# The dirt lines that are NOT disposable. Empty output means everything present
# is build or gate output and the tree can be swept without a human reading it.
undisposable_lines() {
  printf '%s\n' "$1" | while IFS= read -r _ul_line; do
    [ -n "$_ul_line" ] || continue
    is_disposable_line "$_ul_line" || printf '%s\n' "$_ul_line"
  done
}

# Each dirt line tagged with the decision, so a reader sees WHY a tree was
# swept or refused instead of re-applying the rule by eye.
annotate_dirt() {
  printf '%s\n' "$1" | while IFS= read -r _ad_line; do
    [ -n "$_ad_line" ] || continue
    if is_disposable_line "$_ad_line"; then
      printf '  [build output] %s\n' "$_ad_line"
    else
      printf '  [KEEP]         %s\n' "$_ad_line"
    fi
  done
}

count_lines() {
  printf '%s\n' "$1" | wc -l | tr -d ' '
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

# --self-test ONLY. Make a link at $1 pointing at $2, by whatever means this
# platform offers: `ln -s` is the POSIX answer, and under Git Bash it silently
# COPIES unless MSYS=winsymlinks is set, so fall back to a native junction via
# PowerShell — which needs Windows-shaped paths, hence cygpath. Returns
# non-zero when neither yields a link, so the caller SKIPS that assertion
# rather than claiming a pass it did not earn.
make_test_link() {
  _mtl_link="$1"
  _mtl_target="$2"
  ln -s "$_mtl_target" "$_mtl_link" 2>/dev/null || true
  if [ ! -L "$_mtl_link" ]; then
    rm -rf "$_mtl_link" 2>/dev/null || true
    if command -v powershell >/dev/null 2>&1 && command -v cygpath >/dev/null 2>&1; then
      powershell -NoProfile -Command \
        "New-Item -ItemType Junction -Path '$(cygpath -w "$_mtl_link")' -Target '$(cygpath -w "$_mtl_target")' | Out-Null" \
        >/dev/null 2>&1 || true
    fi
  fi
  [ -L "$_mtl_link" ]
}

# Disarm every node_modules link under one worktree, before anything recursive
# runs over it.
#
# Extracted from the main loop so the HUSK path gets the identical protection
# (rf2-k3j2w). A husk left behind by a bare `git worktree remove` — one that
# never went through this script — can still hold an ARMED junction, and the
# only remedy left for a husk is a plain `rm -rf`, which is precisely the
# recursive delete that empties the target. Disarming before we recommend that
# delete is the difference between advice and a loaded gun.
disarm_worktree_links() {
  _dwl_wt="$1"
  find_node_modules "$_dwl_wt" > "$WORK_DIR/wt-nm"
  _dwl_found=0
  while IFS= read -r nm; do
    [ -n "$nm" ] || continue
    if [ ! -L "$nm" ]; then
      continue
    fi
    _dwl_found=1
    link_target=$(readlink "$nm" 2>/dev/null || printf '<unresolved>')
    if [ "$DRY_RUN" -eq 1 ]; then
      printf 'WOULD_DISARM=%s -> %s\n' "$nm" "$link_target"
      continue
    fi
    disarm_link "$nm" \
      || die "Failed to unlink $nm — it is still present. ABORTING before 'git worktree remove', which would delete THROUGH it into $link_target."
    printf 'DISARMED=%s -> %s\n' "$nm" "$link_target"
  done < "$WORK_DIR/wt-nm"
  if [ "$_dwl_found" -eq 0 ]; then
    printf 'NO_LINKS=%s\n' "$_dwl_wt"
  fi
}

# Say WHY the removal failed instead of guessing (rf2-p0m6m).
#
# `git worktree remove` has two failure modes with OPPOSITE remedies:
#
#   dirty  fatal: '<wt>' contains modified or untracked files, use --force
#          to delete it — a refusal, non-destructive, and NO amount of waiting
#          clears it, because nothing is going to delete the worker's leftover
#          gate log for you.
#   lock   the tree is clean but a live process still holds a handle under it
#          (Windows shadow-cljs/Node) — genuinely transient; retry later.
#
# This script used to report both as "safe to retry once the lock clears".
# Nine worktrees were then read as locked and waited out across two days and
# a full session of other workers; every one of them was simply dirty, holding
# a single untracked `logs/`, `bench-logs/`, `PRBODY.md` or `*-exit.txt` the
# worker left behind. Telling the two apart is the whole fix.
# A partially removed worktree — a HUSK. Says what was already done TO the
# tree, and why this script is the wrong tool from here on (rf2-k3j2w).
report_husk() {
  printf 'REMOVE_PARTIAL=%s\n' "$1" >&2
  printf 'The worktree is already GUTTED: git deregistered it and deleted its .git,\n' >&2
  printf 'then could not delete the directory — a file lock taken mid-removal. The\n' >&2
  printf 'files you can still see have no worktree behind them.\n' >&2
  printf 'THIS IS A DESTROYED TREE, NOT AN UNTOUCHED ONE. If an agent was using it,\n' >&2
  printf 'its build or gate run has already been broken; a partial removal kills a\n' >&2
  printf 'running gate exactly as a complete one does (rf2-k3j2w).\n' >&2
  printf 'Retrying this script will not finish the job — there is no registered\n' >&2
  printf "worktree left to remove, and 'git worktree prune' has nothing to clear.\n" >&2
  printf 'Any node_modules link has been disarmed, so what remains is an ordinary\n' >&2
  printf 'directory delete, once whatever holds the lock has exited:\n' >&2
  printf '  rm -rf %s\n' "$1" >&2
}

# The refusals for a path this script declines to IDENTIFY (rf2-k3j2w, second
# pass). Each says what was established and what was not, and each is followed
# by nothing at all: no disarm, no classification, no delete advice.
report_refused_unidentified() {
  printf 'REFUSED_UNREGISTERED=%s\n' "$1" >&2
  printf '  registered as a worktree : no\n' >&2
  printf '  .git present             : no\n' >&2
  printf '  content from this repo   : %s\n' "$2" >&2
  printf 'The first two are true of a husk and equally true of an ordinary directory, a\n' >&2
  printf 'mistyped path and an unrelated project, so they identify nothing on their own.\n' >&2
  printf 'NOTHING HERE WAS TOUCHED.\n' >&2
  printf 'If you know this is the husk of a worktree of this repository — git gutted it\n' >&2
  printf 'and left the files standing — say so, and it will be disarmed and classified:\n' >&2
  printf '  sh scripts/remove-worker-worktree.sh --husk %s\n' "$1" >&2
}

report_refused_not_husk() {
  printf 'REFUSED_NOT_A_HUSK=%s\n' "$1" >&2
  # %s, not a bare format: a format string starting with `--` is read as an
  # end-of-options marker and printf fails, taking `set -e` with it.
  printf '%s\n' '--husk claims this is the husk of a worktree of this repository, but its' >&2
  printf 'contents do not come from one: %s.\n' "$2" >&2
  printf 'A mistyped path carries the flag exactly as willingly as the right one, so the\n' >&2
  printf 'acknowledgement does not overrule the evidence. NOTHING HERE WAS TOUCHED.\n' >&2
  printf 'If it really is a gutted worktree with nothing recognisable left in it, then it\n' >&2
  printf 'is an ordinary directory now and needs no special tool to delete.\n' >&2
}

report_remove_failure() {
  _rf_wt="$1"
  # A husk reads as CLEAN below — the git call fails and prints nothing — so
  # it has to be ruled out before the dirty/locked split runs (rf2-k3j2w).
  if ! worktree_is_intact "$_rf_wt"; then
    report_husk "$_rf_wt"
    PARTIAL=1
    return
  fi
  _rf_dirt=$(worktree_dirt "$_rf_wt")
  if [ -z "$_rf_dirt" ]; then
    printf 'REMOVE_FAILED=%s (tree is CLEAN and still INTACT, so this is a file lock: links are already disarmed; safe to retry once it clears)\n' "$_rf_wt" >&2
    return
  fi
  printf 'REMOVE_REFUSED_DIRTY=%s\n' "$_rf_wt" >&2
  annotate_dirt "$_rf_dirt" >&2
  printf 'git refuses a worktree holding modified or untracked files. RETRYING WILL NEVER\n' >&2
  printf 'CLEAR THIS — nothing is locked and waiting does not add a flag.\n' >&2
  if [ -z "$(undisposable_lines "$_rf_dirt")" ]; then
    printf 'All of it is build/gate output: --force-disposable will sweep the tree.\n' >&2
  else
    printf 'The [KEEP] paths are NOT build output — an uncommitted edit, a note, a draft.\n' >&2
    printf '%s\n' '--force-disposable will refuse them. Save or commit what matters first; only' >&2
    printf 'then is --force (which DELETES them) the right tool.\n' >&2
  fi
}

# ---------------------------------------------------------------------------
# --self-test — prove the disarm and the detector, rather than asserting them.
#
#   1. Builds a throwaway target with a known signature, links a node_modules
#      at it, disarms, and requires BOTH: the link is gone AND the target is
#      untouched.
#   2. Proves the canary SEES the damage the old immediate-entry count was
#      blind to: files vanish from under every package while each package
#      directory stays standing.
#   3. Proves the husk DETECTOR against three husk shapes.
#   4. Proves the later-invocation GUARD by invoking this script, for real,
#      against fixtures a wrong answer would destroy — the negative direction
#      first, because refusing is the behaviour that was missing.
#
# Never touches a real node_modules, and never this repository.
# ---------------------------------------------------------------------------
if [ "$SELF_TEST" -eq 1 ]; then
  ST_TARGET="$WORK_DIR/dummy-target"
  ST_LINK="$WORK_DIR/wt/implementation/node_modules"
  mkdir -p "$ST_TARGET" "$WORK_DIR/wt/implementation"
  for _i in 1 2 3 4 5; do
    printf 'canary\n' > "$ST_TARGET/f$_i.txt"
  done
  ST_BEFORE=$(signature "$ST_TARGET")

  # The link assertions SKIP on a platform that yields no link, but only THEY
  # do. This used to `exit 0` for the whole run, which quietly took the husk
  # detector and the later-invocation guard down with it — two suites that need
  # no link at all and have nothing to skip for.
  ST_LINK_OK=1
  ST_AFTER="$ST_BEFORE"
  if make_test_link "$ST_LINK" "$ST_TARGET"; then
    printf 'SELF_TEST target=%s signature_before=%s\n' "$ST_TARGET" "$ST_BEFORE"
    disarm_link "$ST_LINK" \
      || die "SELF_TEST=FAILED the link survived the disarm: $ST_LINK"
    ST_AFTER=$(signature "$ST_TARGET")
    printf 'SELF_TEST link_removed=yes signature_after=%s\n' "$ST_AFTER"
    if [ "$ST_AFTER" != "$ST_BEFORE" ]; then
      die "SELF_TEST=FAILED the disarm deleted THROUGH the link: $ST_BEFORE -> $ST_AFTER."
    fi
  else
    ST_LINK_OK=0
    printf 'SELF_TEST link_disarm=SKIPPED (this platform produced no link to test against; run scripts/remove-worker-worktree.ps1 -SelfTest on Windows)\n'
  fi

  # The nested-loss case. This is the shape a partial recursive delete leaves
  # behind, and the shape the old immediate-entry canary called healthy.
  ST_NESTED="$WORK_DIR/nested"
  mkdir -p "$ST_NESTED/pkg-a/lib" "$ST_NESTED/pkg-b/lib"
  printf 'x\n' > "$ST_NESTED/pkg-a/lib/index.js"
  printf 'x\n' > "$ST_NESTED/pkg-b/lib/index.js"
  ST_N_BEFORE=$(signature "$ST_NESTED")
  rm -f "$ST_NESTED/pkg-a/lib/index.js" "$ST_NESTED/pkg-b/lib/index.js"
  ST_N_AFTER=$(signature "$ST_NESTED")
  if [ "${ST_N_BEFORE%%/*}" != "${ST_N_AFTER%%/*}" ]; then
    die "SELF_TEST=FAILED the fixture's own top-level entry count moved (${ST_N_BEFORE%%/*} -> ${ST_N_AFTER%%/*}); it no longer tests what it claims."
  fi
  if [ "$ST_N_BEFORE" = "$ST_N_AFTER" ]; then
    die "SELF_TEST=FAILED the canary is blind to nested file loss: signature stayed $ST_N_BEFORE."
  fi
  printf 'SELF_TEST nested_loss top_level_entries_unchanged=%s signature %s -> %s\n' \
    "${ST_N_BEFORE%%/*}" "$ST_N_BEFORE" "$ST_N_AFTER"

  # The HUSK detector (rf2-k3j2w), in a throwaway repo; never touches this one.
  #
  # THREE fixtures, because worktree_is_intact has two clauses and each catches
  # a husk the other cannot see. Drop either clause and one fixture reds:
  #   outside  — a husk with no repo above it (the shape seen in the wild).
  #              `rev-parse` fails; this is the one whose dirt reads EMPTY, i.e.
  #              identical to a clean tree, which is how a gutted worktree came
  #              to be reported as a retryable file lock.
  #   nested   — a husk sitting inside another checkout. `rev-parse` WALKS UP,
  #              finds the enclosing repo and answers happily; only the missing
  #              `.git` gives it away.
  #   dangling — `.git` present but naming an admin dir that is gone. The `-e`
  #              check calls that intact; only `rev-parse` sees through it.
  ST_REPO="$WORK_DIR/husk-repo"
  mkdir -p "$ST_REPO"
  # GIT_DIR and friends are unset for the subshell: an inherited one (CI runners
  # and some hook contexts set them) would point `git init` and `git worktree
  # add` at the CALLER's repository instead of this throwaway.
  ( unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE
    cd "$ST_REPO" \
      && git init -q . \
      && git -c user.email=selftest@example.invalid -c user.name=selftest \
             commit -q --allow-empty -m init \
      && git worktree add -q "$WORK_DIR/husk-outside" -b husk-probe-outside \
      && git worktree add -q "$ST_REPO/husk-nested"   -b husk-probe-nested ) >/dev/null 2>&1 || true

  if [ -e "$WORK_DIR/husk-outside/.git" ] && [ -e "$ST_REPO/husk-nested/.git" ]; then
    for ST_WT in "$WORK_DIR/husk-outside" "$ST_REPO/husk-nested"; do
      worktree_is_intact "$ST_WT" \
        || die "SELF_TEST=FAILED a live worktree read as not-intact: $ST_WT"
      rm -f "$ST_WT/.git"   # precisely what a partial removal leaves behind
      if worktree_is_intact "$ST_WT"; then
        die "SELF_TEST=FAILED a husk read as INTACT: $ST_WT — it would be classified as a clean tree and a retryable lock."
      fi
    done
    if [ -n "$(worktree_dirt "$WORK_DIR/husk-outside")" ]; then
      die "SELF_TEST=FAILED the husk fixture does not reproduce the ambiguity it exists to test."
    fi

    # A THIRD shape, and the one that keeps the other clause honest: `.git` is
    # PRESENT but dangling — it names an admin directory that is gone. The `-e`
    # check calls that intact; only rev-parse sees through it.
    printf 'gitdir: %s\n' "$WORK_DIR/no-such-admin-dir" > "$WORK_DIR/husk-outside/.git"
    if worktree_is_intact "$WORK_DIR/husk-outside"; then
      die "SELF_TEST=FAILED a dangling .git read as INTACT; the -e check alone cannot see it."
    fi

    printf 'SELF_TEST husk detected=yes for all three shapes (outside a repo, where worktree_dirt reads EMPTY — the trap; nested inside one, where rev-parse walks up and is fooled; and a dangling .git, where the -e check is fooled)\n'
  else
    # NOT a SKIP. The link fixture above may legitimately skip — some platforms
    # cannot produce a link to test against. There is no platform where git can
    # run this script and yet cannot build a worktree in a temp directory, so a
    # husk fixture that will not build means the detector went untested, and an
    # untested detector must red rather than certify itself.
    die "SELF_TEST=FAILED could not build the throwaway husk fixtures under $ST_REPO, so the husk detector was never exercised."
  fi

  # ---- THE LATER-INVOCATION GUARD (rf2-k3j2w, second pass), END TO END ----
  #
  # Helper-level assertions cannot reach this one. What failed was not a
  # predicate but what the MAIN LOOP did with it: unlink a stranger's junction
  # and recommend `rm -rf` on a directory it had never seen. So these fixtures
  # go through REAL invocations of this script, against a throwaway repository,
  # and the assertions are on exit codes and output — including, for the
  # negative cases, that the fixture is still standing afterwards.
  ST_G_REPO="$WORK_DIR/guard-repo"
  ST_G_HUSK="$WORK_DIR/guard-husk"
  ST_G_LIVE="$WORK_DIR/guard-live"
  ST_G_PLAIN="$WORK_DIR/guard-plain"
  ST_G_TINY="$WORK_DIR/guard-tiny"
  ST_G_TARGET="$WORK_DIR/guard-target"
  ST_G_LINK="$ST_G_PLAIN/implementation/node_modules"
  ST_G_OUT="$WORK_DIR/guard-out"
  mkdir -p "$ST_G_REPO" "$ST_G_PLAIN/implementation" "$ST_G_TINY" "$ST_G_TARGET"
  for _i in 1 2 3 4 5; do
    printf 'canary\n' > "$ST_G_TARGET/f$_i.txt"
  done
  # A populated ordinary directory: real files, real names, none of them ours.
  printf 'from no repository at all\n' > "$ST_G_PLAIN/README.md"
  printf '{ "name": "somebody-elses-project" }\n' > "$ST_G_PLAIN/package.json"
  # And the NEAR MISS that a match COUNT could not see: an ordinary directory
  # holding one three-byte file that genuinely does match a blob of ours,
  # because `{}` is a blob half the projects on the machine have committed.
  printf '{}\n' > "$ST_G_TINY/tiny.json"
  ST_G_TGT_BEFORE=$(signature "$ST_G_TARGET")
  # probe.txt is deliberately over the byte floor and unique to this run ($$),
  # so the positive case has to earn its provenance rather than inherit it from
  # some file every repository holds; tiny.json is deliberately under it.
  ( unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE
    cd "$ST_G_REPO" \
      && git init -q . \
      && { _i=0
           while [ "$_i" -lt 64 ]; do
             printf 'provenance probe %s line %s\n' "$$" "$_i" >> probe.txt
             _i=$((_i + 1))
           done; } \
      && printf '{}\n' > tiny.json \
      && git add probe.txt tiny.json \
      && git -c user.email=selftest@example.invalid -c user.name=selftest \
             commit -q -m init \
      && git worktree add -q "$ST_G_HUSK" -b guard-probe-husk \
      && git worktree add -q "$ST_G_LIVE" -b guard-probe-live ) >/dev/null 2>&1 || true
  [ -e "$ST_G_HUSK/probe.txt" ] && [ -e "$ST_G_LIVE/.git" ] \
    || die "SELF_TEST=FAILED could not build the later-invocation fixtures under $ST_G_REPO, so the guard went untested."
  [ "$(wc -c < "$ST_G_HUSK/probe.txt" | tr -d ' ')" -gt "$HUSK_PROVENANCE_MIN_BYTES" ] \
    || die "SELF_TEST=FAILED the positive fixture does not clear the provenance byte floor, so it proves nothing."
  # Precisely what a partial removal leaves behind, in BOTH of its parts: the
  # `.git` file gone AND the worktree deregistered. Deleting the `.git` alone
  # leaves git still listing the path (as `prunable`), which is NOT the state
  # seen in the wild and would let this fixture pass against a roster check it
  # never really faced. `prune` is git's own deregistration and it leaves every
  # other file standing — verified: `gitdir file points to non-existent
  # location`, entry removed, contents untouched.
  rm -f "$ST_G_HUSK/.git"
  ( unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE
    git -C "$ST_G_REPO" worktree prune ) >/dev/null 2>&1 || true
  ST_G_LINK_OK=0
  if make_test_link "$ST_G_LINK" "$ST_G_TARGET"; then
    ST_G_LINK_OK=1
  fi

  # NEGATIVE 1 — an ordinary directory, unacknowledged. This is the case that
  # was reproduced against main: exit 3, "THIS IS A DESTROYED TREE", `rm -rf`.
  ST_G_RC=0
  sh "$SCRIPT_SELF" --mayor-root "$ST_G_REPO" "$ST_G_PLAIN" > "$ST_G_OUT" 2>&1 || ST_G_RC=$?
  [ "$ST_G_RC" -eq 1 ] \
    || die "SELF_TEST=FAILED an ordinary directory exited $ST_G_RC, want 1 (a refusal). Output: $(cat "$ST_G_OUT")"
  grep -q '^REFUSED_UNREGISTERED=' "$ST_G_OUT" \
    || die "SELF_TEST=FAILED an ordinary directory was not refused by name. Output: $(cat "$ST_G_OUT")"
  if grep -qE 'REMOVE_PARTIAL=|DESTROYED TREE|rm -rf ' "$ST_G_OUT"; then
    die "SELF_TEST=FAILED an ordinary directory was called a destroyed tree or handed a recursive delete. Output: $(cat "$ST_G_OUT")"
  fi
  if grep -q '^DISARMED=' "$ST_G_OUT"; then
    die "SELF_TEST=FAILED a refused directory had a link disarmed anyway. Output: $(cat "$ST_G_OUT")"
  fi
  [ -f "$ST_G_PLAIN/README.md" ] \
    || die "SELF_TEST=FAILED a refused directory lost files."
  if [ "$ST_G_LINK_OK" -eq 1 ]; then
    [ -L "$ST_G_LINK" ] \
      || die "SELF_TEST=FAILED a refused directory's link was removed: $ST_G_LINK"
    [ "$(signature "$ST_G_TARGET")" = "$ST_G_TGT_BEFORE" ] \
      || die "SELF_TEST=FAILED a refused directory's link target lost entries."
  fi

  # NEGATIVE 2 — the same ordinary directory WITH the acknowledgement. The flag
  # is what a mistyped path carries too, so it must not be sufficient on its own.
  ST_G_RC=0
  sh "$SCRIPT_SELF" --mayor-root "$ST_G_REPO" --husk "$ST_G_PLAIN" > "$ST_G_OUT" 2>&1 || ST_G_RC=$?
  [ "$ST_G_RC" -eq 1 ] \
    || die "SELF_TEST=FAILED --husk on an ordinary directory exited $ST_G_RC, want 1. Output: $(cat "$ST_G_OUT")"
  grep -q '^REFUSED_NOT_A_HUSK=' "$ST_G_OUT" \
    || die "SELF_TEST=FAILED --husk on an ordinary directory was not refused on the evidence. Output: $(cat "$ST_G_OUT")"
  if grep -qE '^DISARMED=|rm -rf ' "$ST_G_OUT"; then
    die "SELF_TEST=FAILED --husk alone was enough to disarm or condemn an ordinary directory. Output: $(cat "$ST_G_OUT")"
  fi
  if [ "$ST_G_LINK_OK" -eq 1 ]; then
    [ -L "$ST_G_LINK" ] \
      || die "SELF_TEST=FAILED --husk removed a refused directory's link."
    [ "$(signature "$ST_G_TARGET")" = "$ST_G_TGT_BEFORE" ] \
      || die "SELF_TEST=FAILED --husk deleted through a refused directory's link."
  fi

  # NEGATIVE 3 — an ordinary directory whose one tiny file DOES match a blob of
  # ours. This is the case a match count called provenance: found while proving
  # the guard, against a throwaway `package.json` holding `{}`.
  ST_G_RC=0
  sh "$SCRIPT_SELF" --mayor-root "$ST_G_REPO" --husk "$ST_G_TINY" > "$ST_G_OUT" 2>&1 || ST_G_RC=$?
  [ "$ST_G_RC" -eq 1 ] \
    || die "SELF_TEST=FAILED a lone tiny coincidental blob bought a husk classification (exit $ST_G_RC, want 1). Output: $(cat "$ST_G_OUT")"
  grep -q '^REFUSED_NOT_A_HUSK=' "$ST_G_OUT" \
    || die "SELF_TEST=FAILED the byte floor did not refuse a tiny coincidental match. Output: $(cat "$ST_G_OUT")"

  # NEGATIVE 4 — --husk sprayed at a LIVE registered worktree. The likeliest way
  # for the flag to be wrong is a caller applying it to a whole sweep list.
  ST_G_RC=0
  sh "$SCRIPT_SELF" --mayor-root "$ST_G_REPO" --husk "$ST_G_LIVE" > "$ST_G_OUT" 2>&1 || ST_G_RC=$?
  [ "$ST_G_RC" -eq 1 ] \
    || die "SELF_TEST=FAILED --husk on a registered worktree exited $ST_G_RC, want 1. Output: $(cat "$ST_G_OUT")"
  grep -q '^REFUSED_HUSK_IS_REGISTERED=' "$ST_G_OUT" \
    || die "SELF_TEST=FAILED --husk on a registered worktree was not refused. Output: $(cat "$ST_G_OUT")"
  [ -e "$ST_G_LIVE/.git" ] \
    || die "SELF_TEST=FAILED a live worktree was gutted by a --husk claim."

  # POSITIVE — a REAL husk, acknowledged. The guard must not have cost the one
  # case the husk path exists for: it is still classified, still disarmed first,
  # and still exits 3.
  ST_G_RC=0
  sh "$SCRIPT_SELF" --mayor-root "$ST_G_REPO" --husk "$ST_G_HUSK" > "$ST_G_OUT" 2>&1 || ST_G_RC=$?
  [ "$ST_G_RC" -eq 3 ] \
    || die "SELF_TEST=FAILED an acknowledged husk exited $ST_G_RC, want 3. Output: $(cat "$ST_G_OUT")"
  grep -q '^HUSK_PROVENANCE=' "$ST_G_OUT" \
    || die "SELF_TEST=FAILED a husk was classified without naming what identified it. Output: $(cat "$ST_G_OUT")"
  grep -q '^REMOVE_PARTIAL=' "$ST_G_OUT" \
    || die "SELF_TEST=FAILED an acknowledged husk was not reported as a partial removal. Output: $(cat "$ST_G_OUT")"

  printf 'SELF_TEST later_invocation guard=yes (ordinary dir REFUSED untouched; --husk alone REFUSED on the evidence; a lone tiny coincidental blob REFUSED by the byte floor; --husk on a live worktree REFUSED; a real husk still classified exit 3 with provenance named)\n'

  if [ "$ST_LINK_OK" -eq 0 ]; then
    # The verdict CI greps for is withheld, exactly as it was before the link
    # assertions were made skippable: everything else passed, but a run that
    # could not test the disarm has not proven the thing this script exists for.
    printf 'SELF_TEST=SKIPPED the disarm went untested on this platform (no link could be created); every other assertion passed.\n'
    exit 0
  fi

  printf 'SELF_TEST=PASSED link unlinked with its target intact (%s), the canary caught nested-only loss, a husk is not mistaken for a clean tree, and an unidentified directory is refused rather than condemned.\n' "$ST_AFTER"
  exit 0
fi

if [ ! -s "$TARGETS_FILE" ]; then
  printf 'usage: remove-worker-worktree.sh <worktree-path>... [--force|--force-disposable] [--dry-run]\n' >&2
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
# Every REAL (non-link) node_modules in the mayor checkout, with its health
# signature. A junction we failed to detect still shows up here as a drop.
# ---------------------------------------------------------------------------
CANARY_FILE="$WORK_DIR/canary"
: > "$CANARY_FILE"

find_node_modules "$MAYOR_ROOT" > "$WORK_DIR/mayor-nm"
while IFS= read -r nm; do
  [ -n "$nm" ] || continue
  if [ -L "$nm" ]; then
    continue   # a link in the MAYOR tree is not a canary
  fi
  printf '%s\t%s\n' "$(normalize_path "$nm")" "$(signature "$nm")" >> "$CANARY_FILE"
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
PARTIAL=0

while IFS= read -r raw_target; do
  [ -n "$raw_target" ] || continue
  WT=$(normalize_path "$raw_target")

  # Refuse the mayor checkout outright — removing it is never the intent, and
  # it is the tree the canary is protecting.
  if [ "$(to_lower "$WT")" = "$(to_lower "$MAYOR_ROOT")" ]; then
    die "Refusing to remove the MAYOR checkout: $WT"
  fi

  # --husk is a claim about a path git has ALREADY let go of. Applied to a
  # registered worktree it is simply false, and the likeliest way for it to
  # become false is a caller spraying the flag across a whole sweep list. So
  # the contradiction is refused rather than quietly ignored.
  if [ "$HUSK" -eq 1 ] && grep -Fxq "$(to_lower "$WT")" "$WORK_DIR/roster"; then
    printf 'REFUSED_HUSK_IS_REGISTERED=%s\n' "$WT" >&2
    printf 'git still lists this as a worktree, so it is not a husk. NOTHING WAS TOUCHED —\n' >&2
    printf 're-run without --husk to remove it normally. --husk is for paths git has\n' >&2
    printf 'already let go of; pass it alone, with the husks you have identified.\n' >&2
    FAILED=1
    continue
  fi

  # Refuse anything git does not know as a linked worktree. This script never
  # deletes a directory itself; if git will not remove it, neither will we.
  #
  # AND REFUSING IS THE DEFAULT HERE, because "git does not list it" is a fact
  # about git, not about the directory (rf2-k3j2w, second pass). A husk is
  # deregistered by definition — and so is every directory that was never a
  # worktree. The version that inferred a husk from that negative alone
  # disarmed and condemned an ordinary temp directory. Identification now needs
  # a positive: the caller's --husk AND content this repository recognises.
  if ! grep -Fxq "$(to_lower "$WT")" "$WORK_DIR/roster"; then
    if [ ! -d "$WT" ]; then
      printf 'REFUSED_UNREGISTERED=%s (no such directory)\n' "$WT" >&2
      printf "Nothing was touched. Check the path; 'git worktree list' shows what is registered.\n" >&2
      FAILED=1
      continue
    fi
    if worktree_is_intact "$WT"; then
      printf 'REFUSED_UNREGISTERED=%s\n' "$WT" >&2
      printf 'It has a working .git and git answers for it, so it is some OTHER checkout —\n' >&2
      printf 'neither a worktree of %s nor a husk. Nothing was touched.\n' "$MAYOR_ROOT" >&2
      FAILED=1
      continue
    fi
    PROV=$(repo_content_matches "$WT")
    PROV_HITS=${PROV%% *}
    PROV_REST=${PROV#* }
    PROV_TOTAL=${PROV_REST%% *}
    PROV_REST=${PROV_REST#* }
    PROV_BYTES=${PROV_REST%% *}
    PROV_FILE=${PROV_REST#* }
    PROV_SUMMARY="$PROV_HITS of $PROV_TOTAL top-level tracked files match a blob this repository knows, $PROV_BYTES bytes (floor $HUSK_PROVENANCE_MIN_BYTES)"
    if [ "$HUSK" -eq 0 ]; then
      report_refused_unidentified "$WT" "$PROV_SUMMARY"
      FAILED=1
      continue
    fi
    if [ "$PROV_BYTES" -lt "$HUSK_PROVENANCE_MIN_BYTES" ]; then
      report_refused_not_husk "$WT" "$PROV_SUMMARY"
      FAILED=1
      continue
    fi
    printf 'HUSK_PROVENANCE=%s %s (e.g. %s)\n' "$WT" "$PROV_SUMMARY" "$PROV_FILE"
    disarm_worktree_links "$WT"
    report_husk "$WT"
    PARTIAL=1
    continue
  fi

  # 1. Disarm every link, before anything recursive runs over the tree.
  disarm_worktree_links "$WT"

  # 2. Only now remove the worktree. Never chained with the disarm.
  wt_dirt=$(worktree_dirt "$WT")
  wt_keep=$(undisposable_lines "$wt_dirt")

  if [ "$DRY_RUN" -eq 1 ]; then
    # The survey a hygiene pass actually needs: which trees it can sweep, which
    # need a human, and why — decided BEFORE anything touches them.
    if [ -z "$wt_dirt" ]; then
      printf 'WOULD_REMOVE=%s\n' "$WT"
    elif [ -n "$wt_keep" ]; then
      printf 'WOULD_REFUSE_KEEP=%s (%s path(s) that are not build output)\n' "$WT" "$(count_lines "$wt_keep")"
      annotate_dirt "$wt_dirt"
    else
      printf 'WOULD_NEED_FORCE=%s (%s path(s), all build/gate output)\n' "$WT" "$(count_lines "$wt_dirt")"
      annotate_dirt "$wt_dirt"
      printf 'WOULD_REMOVE=%s\n' "$WT"
    fi
  elif [ "$FORCE_DISPOSABLE" -eq 1 ] && [ "$FORCE" -eq 0 ] && [ -n "$wt_keep" ]; then
    # The whole point of the flag: a bulk sweep stops at unreviewed work
    # instead of taking it. This is a refusal, so nothing has been deleted.
    printf 'REMOVE_REFUSED_KEEP=%s\n' "$WT" >&2
    annotate_dirt "$wt_dirt" >&2
    printf 'The [KEEP] paths are not build or gate output. --force-disposable will not\n' >&2
    printf 'delete them. Save or commit what matters, then use --force if you are certain\n' >&2
    printf 'the rest can go.\n' >&2
    FAILED=1
  elif [ "$FORCE" -eq 1 ] || [ "$FORCE_DISPOSABLE" -eq 1 ]; then
    if git -C "$MAYOR_ROOT" worktree remove --force "$WT"; then
      printf 'REMOVED=%s\n' "$WT"
    else
      report_remove_failure "$WT"
      FAILED=1
    fi
  else
    if git -C "$MAYOR_ROOT" worktree remove "$WT"; then
      printf 'REMOVED=%s\n' "$WT"
    else
      report_remove_failure "$WT"
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
  after=$(signature "$nm")
  printf 'CANARY_AFTER=%s %s\n' "$nm" "$after"
  if [ "$after" != "$before" ]; then
    CANARY_BAD=1
    printf 'CANARY_FAILED: %s went from %s to %s (entries/files/sentinels).\n' "$nm" "$before" "$after" >&2
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

# A partial removal outranks a plain failure: both are non-zero, but only one
# of them means "stop calling this script about that path" (rf2-k3j2w).
if [ "$PARTIAL" -ne 0 ]; then
  exit 3
fi

if [ "$FAILED" -ne 0 ]; then
  exit 1
fi

printf 'OK: worktree removal complete.\n'
