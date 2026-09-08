#!/usr/bin/env sh
# scripts/beads-checkpoint.sh — commit the beads tracker without losing state.
#
# PRIMARY implementation; scripts/beads-checkpoint.ps1 is the Windows
# PowerShell sibling with an identical contract.
#
# THE FAULT THIS EXISTS TO STOP (rf2-51uz1)
#
#   CLAUDE.md mandates `git checkout HEAD -- .beads` before every pull, and it
#   is right to: an uncommitted `.beads/issues.jsonl` makes `git pull` abort,
#   silently freezing HEAD at a stale base. But the JSONL is a full-database
#   EXPORT. If a `bd close` or `bd create` happened after the last
#   export-commit, that checkout reverts the export to its pre-close state —
#   and a checkpoint that then commits (or re-imports) the working file writes
#   the revert back over the database. The close simply evaporates. The
#   doctrine that prevents one fault performs the other.
#
#   OBSERVED, not hypothetical: rf2-5e8zv was reopened exactly this way, and
#   commit e80786e007 on main records three more closes reverted by re-import
#   and re-closed by hand.
#
# THE FIX, IN TWO WORDS: EXPORT FIRST. A checkpoint asks the Dolt database what
# the tracker says (`bd export`) instead of trusting whatever is sitting in the
# working tree. A reverted file can then never be committed over newer database
# state, because the file is regenerated before it is read.
#
# THE SECOND FAULT (rf2-rjqtj): EXPORT FIRST IS NOT ENOUGH ON ITS OWN.
#
#   Exporting first is right when the database is strictly ahead of Git. It is
#   wrong when Git is ahead in places — and Git can be, because a second writer
#   exists: the merged-PR audit commits issue rows straight to Git, and a `git
#   pull` brings other checkouts' rows in the same way. When both sides move,
#   they can diverge at the SAME ROW COUNT, one row for one row. The row-count
#   floor below then sees 1938 == 1938 and waves the export through, and the
#   commit deletes the Git-only rows and reverts the newer Git statuses.
#
#   OBSERVED, not hypothetical: commit 667c744dc875 dropped rf2-3jw04,
#   rf2-jv36i and rf2-lhdp0 and reverted rf2-2rtt6.52/.63 exactly this way.
#
#   So the export is now compared to HEAD by issue id, `updated_at` and
#   `status` before it is allowed to overwrite anything — see `git_only_facts`.
#   EQUAL COUNTS ARE NOT EQUALITY.
#
# THE THIRD BLIND SPOT (rf2-cve7): BOTH GUARDS ABOVE ONLY SEE ISSUES.
#
#   The floor counts rows, which the issue rows dominate, and the divergence
#   guard reads `"_type":"issue"` and skips everything else. So the memory rows
#   — the `bd remember` store — are unguarded by construction.
#
#   OBSERVED, not hypothetical: on 2026-09-08, 210 memory keys vanished from
#   the live store (1167 -> 957) and nothing said a word. `bd stats` reports
#   ISSUES ONLY and read a healthy 1099 straight through it; the export was
#   90.8% of HEAD's rows, over the floor. Two substantive memories went with the
#   retention cull that took the other 204, including a standing operator
#   preference, and both had to be recovered by hand from an older checkpoint.
#
#   `memory_facts` now reconciles the two populations against HEAD by KEY SET.
#   Unlike the two guards above it WARNS AND DOES NOT REFUSE — see the call site
#   for why that constraint is deliberate.
#
# USAGE
#
#   sh scripts/beads-checkpoint.sh [-m MESSAGE]
#       Re-export the tracker from the database, sanity-check the result, and
#       commit `.beads/issues.jsonl` if it changed. This is the checkpoint —
#       run it BEFORE `git checkout HEAD -- .beads` and the pull, never after.
#       The commit carries the rows that changed and nothing else: `bd export`
#       does not fix the order of the memory rows, so the file is written in
#       minimal-diff order first (rf2-51uz1.1, `minimal_diff_rewrite` below).
#
#   sh scripts/beads-checkpoint.sh --pre-pull
#       Ask whether clearing `.beads` would discard tracker state that HEAD
#       does not carry. Exit 0 = the checkout is safe; exit 1 = checkpoint
#       first, and it says so. Cheap enough to run every tick.
#
# WHAT IT DELIBERATELY DOES NOT DO: pull, push, import, or touch any beads path
# other than `.beads/issues.jsonl`. `.beads/metadata.json` is database-derived
# too, but the mayor pre-commit boundary (rf2-ydl2p) permits only the tracker
# and MEMORY.md, so committing it here would be refused; it stays a manual
# call. This is an operator helper, not a gate: nothing in CI runs it.
#
# Cross-platform: POSIX sh; runs under Git Bash on Windows, macOS, Linux.
# No bashisms (`[[`, arrays, `<<<`).

set -eu

TRACKER=".beads/issues.jsonl"

MODE="checkpoint"
MESSAGE="chore(beads): checkpoint"

usage() {
  printf 'usage: sh scripts/beads-checkpoint.sh [-m MESSAGE]\n'
  printf '       sh scripts/beads-checkpoint.sh --pre-pull\n'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --pre-pull)  MODE="pre-pull"; shift ;;
    -m|--message) MESSAGE="${2:-}"; shift 2 ;;
    -m=*|--message=*) MESSAGE="${1#*=}"; shift ;;
    -h|--help)   usage; exit 0 ;;
    *)
      printf 'beads-checkpoint: unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

die() {
  printf 'beads-checkpoint: %s\n' "$1" >&2
  exit 1
}

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$REPO_ROOT"

# ---------------------------------------------------------------------------
# The tracker database is the MAYOR checkout's to commit (rf2-ia8o7). Derive
# the primary worktree the same way the pre-commit guard does, and reuse its
# library so there is one rule in one place. If the library is missing (a
# partial checkout), skip the check rather than refuse — the pre-commit hook
# and the CI arm are the enforcement; this is a convenience.
#
# Only the COMMITTING arm is gated. --pre-pull is a read-only question —
# "would clearing `.beads` discard tracker state?" — that every worktree
# legitimately asks before its own pull, and it must keep answering from
# worker worktrees (rf2-fifk0).
# ---------------------------------------------------------------------------
BOUNDARY_LIB="$REPO_ROOT/scripts/git-hooks/lib/check-beads-boundary.sh"
if [ "$MODE" = "checkpoint" ] && [ -f "$BOUNDARY_LIB" ]; then
  # shellcheck source=git-hooks/lib/check-beads-boundary.sh
  . "$BOUNDARY_LIB"
  if ! rf2_beads_in_primary_worktree; then
    die "this is a linked (worker) worktree; the tracker database is the mayor checkout's to commit."
  fi
fi

# ---------------------------------------------------------------------------
# Helpers.
# ---------------------------------------------------------------------------

TMP_EXPORT=""
TMP_HEAD=""
TMP_ORDERED=""
TMP_A=""
TMP_B=""
cleanup() {
  rm -f "$TMP_EXPORT" "$TMP_HEAD" "$TMP_ORDERED" "$TMP_A" "$TMP_B" 2>/dev/null || true
}
trap cleanup EXIT INT TERM HUP

rows() {
  # Line count of a file, or 0 if it is absent.
  [ -f "$1" ] || { printf '0'; return 0; }
  awk 'END{print NR}' "$1"
}

# head_copy PATH [REF] — write REF's copy of the tracker to PATH; REF defaults
# to HEAD. Empty file if the path does not exist there (a first-ever checkpoint,
# or an unborn branch).
#
# THE REF ARGUMENT IS THE FIX (rf2-cve7, merged-PR audit of #9524), not an
# ergonomic flourish. The caller below prints a commit oid as a RECOVERY
# REFERENCE, and a helper that re-resolves `HEAD` for itself makes that oid a
# SEPARATE READ of a moving branch: a commit landing in the shared checkout
# between the two reads leaves the guard comparing commit A's bytes while
# printing commit B's oid, and B never carried the values the operator is told
# to recover. Adjacent calls are not one snapshot — the drift reproduces
# deterministically with a single commit in the gap. Pass the resolved oid and
# the two cannot disagree.
head_copy() {
  git show "${2:-HEAD}:$TRACKER" > "$1" 2>/dev/null || : > "$1"
}

# minimal_diff_rewrite EXPORT HEAD_COPY OUT — write EXPORT's rows to OUT, but
# emit every row HEAD already carries in HEAD's order first, and only then the
# rows that are genuinely new, in export order.
#
# WHY (rf2-51uz1.1). `same_content` below already stops a reorder-ONLY export
# from becoming a commit. It does nothing for the normal case: one real row
# changed, so the checkpoint commits — and the raw export carries every
# unrelated memory reorder along with it. Measured on the first real checkpoint
# after this helper landed: 211 additions / 208 deletions staged, of which 200
# added rows were byte-identical to 200 removed rows. Pure relocation. Eleven
# added and eight removed lines were the actual tracker change, buried.
#
# The output is the export's row MULTISET exactly — no row is invented, dropped
# or edited, so `bd import` sees the same database either way. Only the line
# ORDER differs, and JSONL row order carries no meaning to the importer. What it
# buys is a diff that is exactly (rows HEAD had and the export does not) plus
# (rows the export has and HEAD does not): no relocation lines at all, from the
# first checkpoint onward.
#
# CRLF is stripped so a Windows checkout's `git checkout HEAD -- .beads` copy
# still matches the LF rows `bd export` emits — the same reason `same_content`
# strips it. Output is LF, byte-for-byte the export's own rows.
minimal_diff_rewrite() {
  awk '
    FNR == NR { sub(/\r$/, ""); cnt[$0]++; order[++n] = $0; next }
    { sub(/\r$/, ""); if (cnt[$0] > 0) { cnt[$0]--; print } }
    END {
      for (i = 1; i <= n; i++) {
        line = order[i]
        if (cnt[line] > 0) { cnt[line]--; print line }
      }
    }
  ' "$1" "$2" > "$3"
}

# same_content FILE_A FILE_B — 0 when the two files carry the SAME SET of rows,
# regardless of order.
#
# Order matters here because `bd export` does not fix the order of the trailing
# `_type":"memory"` rows: two exports of an unchanged database differ by
# reordering alone (measured: 2396 issue rows byte-identical, 222 memory rows
# reordered). Comparing sorted forms keeps a checkpoint from committing a
# few-hundred-line diff that says nothing, while still noticing a memory that
# was added, removed or edited.
#
# Line endings are stripped first. On a Windows checkout (`core.autocrlf=true`)
# `git checkout HEAD -- .beads` writes the tracker back with CRLF while
# `git show HEAD:` and `bd export` both emit LF, so a byte comparison would
# call every line different and --pre-pull would warn every single time. An
# advisory that always fires is an advisory nobody reads.
same_content() {
  TMP_A=$(mktemp "${TMPDIR:-/tmp}/rf2-bdchk-a-XXXXXX")
  TMP_B=$(mktemp "${TMPDIR:-/tmp}/rf2-bdchk-b-XXXXXX")
  tr -d '\r' < "$1" | LC_ALL=C sort > "$TMP_A"
  tr -d '\r' < "$2" | LC_ALL=C sort > "$TMP_B"
  cmp -s "$TMP_A" "$TMP_B"
}

# git_only_facts EXPORT HEAD_COPY REMEDY_PATH — print one line per tracker fact
# HEAD carries that the fresh export does not, and write the rows that would
# repair the database to REMEDY_PATH. No output means the export is a safe
# superset of HEAD and the checkpoint may proceed.
#
# THE FAULT THIS EXISTS TO STOP (rf2-rjqtj): see the second fault at the top of
# this file. Row counts are a floor, not an equality test.
#
# Three classes, all of them "Git knows something Dolt does not":
#
#   GONE    an issue id at HEAD that the export has no row for at all. The
#           commit would DELETE that bead.
#   REVERT  an id in both, where HEAD's `updated_at` is strictly NEWER than the
#           export's. The commit would revert it to an older status.
#   AMBIG   an id in both carrying the SAME `updated_at` but a DIFFERENT
#           `status`. Neither side can be called newer, so neither may be
#           chosen automatically.
#
# The opposite direction — ids only the export has, rows the export has newer —
# is the normal forward motion of a checkpoint and is deliberately not reported.
#
# WHY `status` AND `updated_at`, NOT JUST THE ID SET: an id-set comparison
# proves presence, nothing more. Confirmed in the field: an interrupted Dolt
# generational GC reverted a bead's close and five note appends while every id
# stayed intact. Presence is not state.
#
# THE ID IS THE STABLE BEAD ID (`rf2-…`), NEVER A ROW UUID. `bd` regenerates row
# and comment UUIDs on re-import, so a UUID-keyed diff reports phantom losses —
# it flagged three beads that existed and were closed. `"id":"` occurs up to
# eight times in a single row because every comment carries one; only the FIRST
# occurrence is the bead's, because `bd export` writes `_type` and `id` at the
# front of the row. A row whose id cannot be read is REPORTED rather than
# skipped: a guard that silently stops guarding is the bug being fixed here.
#
# Rows are compared only when both sides carry a non-empty `updated_at`. Without
# timestamps there is no basis on which to call either side newer, and inventing
# one would turn every ordinary close into a refusal.
#
# REMEDY_PATH receives the GONE and REVERT rows exactly as HEAD holds them, so
# `bd import` of that file is the whole recovery — the bead's own verified,
# bounded mechanism: it created the three missing ids, updated exactly the two
# newer Git rows, skipped the stale ones, and preserved every newer Dolt row and
# cursor. AMBIG rows are deliberately left out; an import cannot adjudicate them.
#
# REMEDY_PATH reaches awk through the ENVIRONMENT, not through `-v`: awk
# processes escape sequences in a `-v` value, so a Windows-shaped TMPDIR
# (`C:\Users\…`) would arrive with its backslashes eaten and the rows would land
# somewhere other than the path the message names. ENVIRON is taken verbatim.
git_only_facts() {
  RF2_BDCHK_REMEDY="$3" awk -v cap=20 '
    function jval(line, key,   pfx, re) {
      pfx = "\"" key "\":\""
      re  = pfx "[^\"]*\""
      if (match(line, re)) {
        return substr(line, RSTART + length(pfx), RLENGTH - length(pfx) - 1)
      }
      return ""
    }
    { sub(/\r$/, "") }
    index($0, "\"_type\":\"issue\"") == 0 { next }
    {
      id = jval($0, "id")
      st = jval($0, "status")
      up = jval($0, "updated_at")
    }
    # First file: the fresh export. (FNR == NR is sound here because the caller
    # has already refused a zero-row export, so file 1 is never empty.)
    FNR == NR {
      if (id == "") { xbad++; next }
      xseen[id] = 1; xst[id] = st; xup[id] = up
      next
    }
    # Second file: HEAD.
    {
      if (id == "") { hbad++; next }
      if (!(id in xseen)) {
        if (++ngone <= cap) {
          printf "  GONE    %s  would be DELETED (HEAD: status=%s updated_at=%s)\n", id, st, up
        }
        print $0 > ENVIRON["RF2_BDCHK_REMEDY"]
        next
      }
      if (up != "" && xup[id] != "" && up > xup[id]) {
        if (++nrev <= cap) {
          printf "  REVERT  %s  HEAD status=%s updated_at=%s -> export status=%s updated_at=%s\n", \
                 id, st, up, xst[id], xup[id]
        }
        print $0 > ENVIRON["RF2_BDCHK_REMEDY"]
        next
      }
      if (up != "" && up == xup[id] && st != xst[id]) {
        if (++namb <= cap) {
          printf "  AMBIG   %s  same updated_at=%s but HEAD status=%s, export status=%s\n", \
                 id, up, st, xst[id]
        }
      }
    }
    END {
      if (ngone > cap) printf "  ... and %d more that would be DELETED\n", ngone - cap
      if (nrev  > cap) printf "  ... and %d more that would be REVERTED\n", nrev - cap
      if (namb  > cap) printf "  ... and %d more ambiguous rows\n", namb - cap
      if (xbad > 0) {
        printf "  UNREADABLE  %d issue rows in the fresh export carry no readable id\n", xbad
      }
      if (hbad > 0) {
        printf "  UNREADABLE  %d issue rows at HEAD carry no readable id\n", hbad
      }
    }
  ' "$1" "$2"
}

# memory_facts EXPORT HEAD_COPY — print the memory-reconciliation body when the
# fresh export accounts for FEWER `bd remember` keys than HEAD, or when either
# file carries rows that are neither an issue nor a memory. Prints NOTHING when
# both populations reconcile, which is every ordinary checkpoint.
#
# THE FAULT THIS EXISTS TO STOP (rf2-cve7). On 2026-09-08, 210 memory keys
# vanished from the live store (1167 -> 957) and NO INSTRUMENT SAID A WORD.
# `bd stats` read a healthy `Total Issues: 1099` straight through it, because it
# reports ISSUES ONLY, and every guard above inherited the same blind spot:
#
#   * the row-count floor is dominated by issue rows, so a memory-only deletion
#     slides under it. Measured on the real event: 2073 rows against HEAD's
#     2283 is 90.8% — over the 90% floor, so it waves the export through.
#   * `git_only_facts` reads `"_type":"issue"` rows and skips every other line,
#     so memories are outside its remit by construction.
#
# WHY THIS IS NOT THE "SUM TO THE ROW COUNT" CHECK THE BEAD ASKED FOR, and the
# distinction is the whole point rather than a quibble. Counting the two
# populations and checking they sum to the file's row count is an INTERNAL
# WELL-FORMEDNESS identity: it holds trivially whenever every row is one of the
# two known types, so it is SILENT ON BOTH SIDES of the event it was proposed to
# catch — 1116 + 1167 == 2283 before, 1116 + 957 == 2073 after. A population
# that shrinks is only visible against a BASELINE, and HEAD's committed export
# is exactly that baseline, already in hand for the guards above. So the sum is
# kept (it catches a row of an unknown `_type`, which is cheap to notice and
# would otherwise be invisible) and the LOSS DETECTOR is the key-set comparison.
#
# KEY SETS, NOT COUNTS. A cull that deletes 210 keys and adds 210 leaves the
# count flat while losing 210 memories, so the count is the same kind of floor
# the row count already was. The key set has no such hole.
#
# THE KEY IS READ FROM THE `key` FIELD, never grepped for. `bd export` writes a
# memory row as exactly `_type`, `key`, `value`, so the FIRST `"key":"` is the
# row's own; a later one inside `value` is JSON-escaped (`\"key\":\"`) and
# cannot match. This is the same identity-field discipline `git_only_facts`
# documents for `"id":"` — and the bead recorded the cost of ignoring it: a
# `git grep -F` for a deleted key "found" it in a commit that does not carry the
# memory at all, because it matched the BEAD'S OWN PROSE naming the key.
#
# (FNR == NR is sound here for the same reason it is in `git_only_facts`: the
# caller has already refused a zero-row export, so file 1 is never empty.)
memory_facts() {
  awk -v cap=10 '
    function jval(line, key,   pfx, re) {
      pfx = "\"" key "\":\""
      re  = pfx "[^\"]*\""
      if (match(line, re)) {
        return substr(line, RSTART + length(pfx), RLENGTH - length(pfx) - 1)
      }
      return ""
    }
    { sub(/\r$/, "") }
    # First file: the fresh export.
    FNR == NR {
      xrows++
      if (index($0, "\"_type\":\"issue\"")  > 0) { xiss++; next }
      if (index($0, "\"_type\":\"memory\"") > 0) {
        xmem++
        k = jval($0, "key")
        if (k == "") { xbad++; next }
        xkey[k] = 1
        next
      }
      xoth++
      next
    }
    # Second file: HEAD.
    {
      hrows++
      if (index($0, "\"_type\":\"issue\"")  > 0) { hiss++; next }
      if (index($0, "\"_type\":\"memory\"") > 0) {
        hmem++
        k = jval($0, "key")
        if (k == "") { hbad++; next }
        if (!(k in xkey)) { if (++ngone <= cap) lost[ngone] = k }
        next
      }
      hoth++
    }
    END {
      if (ngone == 0 && xoth == 0 && hoth == 0 && xbad == 0 && hbad == 0) { exit 0 }
      printf "  export  %d rows = %d issues + %d memories\n", xrows, xiss, xmem
      printf "  HEAD    %d rows = %d issues + %d memories\n", hrows, hiss, hmem
      if (ngone > 0) {
        printf "\n  %d memory key(s) at HEAD are ABSENT from the fresh export:\n", ngone
        n = (ngone < cap ? ngone : cap)
        for (i = 1; i <= n; i++) { printf "      %s\n", lost[i] }
        if (ngone > cap) { printf "      ... and %d more\n", ngone - cap }
      }
      if (xoth > 0) {
        printf "\n  %d row(s) in the fresh export are neither an issue nor a memory,\n", xoth
        printf "  so the two populations do NOT sum to the export row count.\n"
      }
      if (hoth > 0) {
        printf "\n  %d row(s) at HEAD are neither an issue nor a memory.\n", hoth
      }
      if (xbad > 0) {
        printf "\n  UNREADABLE  %d memory rows in the fresh export carry no readable key\n", xbad
      }
      if (hbad > 0) {
        printf "\n  UNREADABLE  %d memory rows at HEAD carry no readable key\n", hbad
      }
    }
  ' "$1" "$2"
}

# ---------------------------------------------------------------------------
# --pre-pull: would clearing `.beads` throw tracker state away?
# ---------------------------------------------------------------------------
if [ "$MODE" = "pre-pull" ]; then
  if [ ! -f "$TRACKER" ]; then
    exit 0
  fi
  TMP_HEAD=$(mktemp "${TMPDIR:-/tmp}/rf2-bdchk-head-XXXXXX")
  head_copy "$TMP_HEAD"
  if same_content "$TRACKER" "$TMP_HEAD"; then
    exit 0
  fi
  work_rows=$(rows "$TRACKER")
  head_rows=$(rows "$TMP_HEAD")
  printf '\n[re-frame2] the working tracker export is AHEAD of HEAD.\n' >&2
  printf '  working %s rows, HEAD %s rows, in %s\n\n' "$work_rows" "$head_rows" "$TRACKER" >&2
  printf '  `git checkout HEAD -- .beads` here would revert it, and the next\n' >&2
  printf '  checkpoint would write that revert back over the database — the\n' >&2
  printf '  rf2-51uz1 fault, which has silently reopened closed beads before.\n\n' >&2
  # The third step is a fetch-then-rebase pair, not `git pull --rebase`
  # (rf2-9m1n4): a pull rebases onto FETCH_HEAD, a scratch file any concurrent
  # git process in the same checkout rewrites, so naming remote and branch
  # constrains only the fetch half. See CLAUDE.md's Beads durability section.
  # The .ps1 sibling prints the same pair in PowerShell's own checked idiom,
  # because `&&` does not parse in Windows PowerShell 5.x; that divergence is
  # deliberate.
  printf '  Checkpoint first, then clear, then update:\n\n' >&2
  printf '      sh scripts/beads-checkpoint.sh\n' >&2
  printf '      git checkout HEAD -- .beads\n' >&2
  printf '      git fetch origin main && git rebase origin/main\n\n' >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# checkpoint: export from the database, verify, commit.
# ---------------------------------------------------------------------------
command -v bd >/dev/null 2>&1 \
  || die "bd is not on PATH; a checkpoint must re-export from the database, so it cannot proceed."

TMP_EXPORT=$(mktemp "${TMPDIR:-/tmp}/rf2-bdchk-export-XXXXXX")
# Redirect rather than `bd export -o`: a shell redirection needs no path
# translation, so this works identically under Git Bash and on Unix.
#
# --include-memories is load-bearing (rf2-fifk0). bd v1.1.2 made the bare
# export EXCLUDE the `bd remember` memory rows that v1.0.3 always carried, so
# a flagless checkpoint would silently drop every one of them — caught only
# because the shrink floor below refused the memory-less export against HEAD.
# The tracker commits whole: issues AND memories.
bd export --include-memories > "$TMP_EXPORT" 2>/dev/null \
  || die "bd export failed; leaving $TRACKER untouched."

# THE BASELINE SNAPSHOT. Resolved ONCE, and resolved FIRST — every read of the
# tracker-at-HEAD below goes through this one oid, so the bytes this checkpoint
# compares against and the oid it prints are the same object by construction.
#
# WHY IT IS PRINTED AT ALL (rf2-cve7, merged-PR audit of #9520). The memory
# warning below prints it as a RECOVERY REFERENCE, and this script COMMITS the
# fresh export a hundred lines later. Printing `HEAD` there is worse than
# useless: by the time an operator reads the warning and pastes the command,
# `HEAD` IS the checkpoint that just removed those rows, so the lookup exits 0
# and prints nothing — succeeding, and recovering nothing. A full commit oid is
# content-addressed and immutable, so it keeps naming this exact tree after the
# checkpoint commits and after any number of later commits land on top.
#
# WHY IT IS RESOLVED BEFORE THE COPY, AND PASSED IN (rf2-cve7, merged-PR audit
# of #9524). The first version of this captured the oid immediately AFTER
# `head_copy "$TMP_HEAD"`, on the reasoning that adjacent statements cannot
# drift. They can: those were two separate reads of a moving branch, and this is
# the mayor's SHARED checkout, where a second checkpoint or an ordinary commit
# can land in the gap. When one does, the guard compares commit A's bytes and
# prints commit B's oid — and B never contained the values the message tells the
# operator to recover, so the printed lookup exits 0 and prints nothing. That is
# the same reassuring failure the #9520 fix set out to remove, reached one step
# further along. Moving the `rev-parse` up while leaving `head_copy` reading
# `HEAD` for itself would REVERSE that race, not close it — both reads have to
# come from this one oid, which is why it is an argument and not a comment.
#
# Empty on an unborn branch (a first-ever checkpoint), where there is no
# baseline to recover from and the recovery paragraph is skipped; `head_copy`
# then falls back to `HEAD`, which fails the same way and yields the same empty
# comparison file.
BASELINE_COMMIT=$(git rev-parse --verify HEAD 2>/dev/null || printf '')

TMP_HEAD=$(mktemp "${TMPDIR:-/tmp}/rf2-bdchk-head-XXXXXX")
head_copy "$TMP_HEAD" "${BASELINE_COMMIT:-HEAD}"

export_rows=$(rows "$TMP_EXPORT")
head_rows=$(rows "$TMP_HEAD")

# TRUNCATION GUARD. A `git add` that catches the JSONL mid-rewrite has landed
# an empty export on main before (incident 2026-06-10, commit 7aea52459), and
# an export that loses a tenth of the tracker is a bug, not a checkpoint.
# Refuse and say so; a genuine mass delete is rare enough to commit by hand.
[ "$export_rows" -gt 0 ] \
  || die "bd export produced 0 rows; refusing to checkpoint. $TRACKER is untouched."
if [ "$head_rows" -gt 0 ] && [ $((export_rows * 10)) -lt $((head_rows * 9)) ]; then
  printf 'beads-checkpoint: export has %s rows, HEAD has %s — more than a tenth of the\n' \
    "$export_rows" "$head_rows" >&2
  printf '  tracker would disappear. Refusing to checkpoint; %s is untouched.\n' "$TRACKER" >&2
  printf '  Inspect with `bd status`, then commit by hand if the shrink is genuine.\n' >&2
  exit 1
fi

# DIVERGENCE GUARD (rf2-rjqtj). The floor above answers "is the export big
# enough?". It cannot answer "does the export still contain what HEAD contains?"
# — and at equal counts it has already said yes to an export that did not.
# Nothing has been written yet, so a refusal here leaves the tracker exactly as
# it was found.
REMEDY="${TMPDIR:-/tmp}/rf2-beads-git-only-$$.jsonl"
rm -f "$REMEDY"
FACTS=$(git_only_facts "$TMP_EXPORT" "$TMP_HEAD" "$REMEDY")
if [ -n "$FACTS" ]; then
  printf 'beads-checkpoint: HEAD carries tracker facts the fresh export does NOT.\n' >&2
  printf '  export %s rows, HEAD %s rows.' "$export_rows" "$head_rows" >&2
  if [ "$export_rows" = "$head_rows" ]; then
    printf ' EQUAL COUNTS ARE NOT EQUALITY:\n' >&2
    printf '  commit 667c744dc875 passed this floor at 1938 == 1938 and still deleted three\n' >&2
    printf '  issues and reverted two closes, because Git and Dolt had diverged one for one.\n' >&2
  else
    printf '\n' >&2
  fi
  printf '\n%s\n\n' "$FACTS" >&2
  printf '  Committing this export would lose exactly those facts, so it was NOT committed.\n' >&2
  printf '  %s is UNTOUCHED.\n\n' "$TRACKER" >&2
  if [ -s "$REMEDY" ]; then
    printf '  To teach the database what Git already knows, then checkpoint again:\n\n' >&2
    printf '      bd import %s\n' "$REMEDY" >&2
    printf '      sh scripts/beads-checkpoint.sh\n\n' >&2
    printf '  That file holds only the rows above, as HEAD holds them; `bd import` is\n' >&2
    printf '  timestamp-safe, so it creates what is missing, updates what is genuinely\n' >&2
    printf '  newer, and skips the rest. Newer database rows are preserved.\n\n' >&2
  else
    rm -f "$REMEDY"
  fi
  printf '  If the loss is DELIBERATE (a `bd delete`, a `bd gc`, an AMBIG row you have\n' >&2
  printf '  adjudicated), take the export by hand and commit it yourself:\n\n' >&2
  printf '      bd export --include-memories > %s\n' "$TRACKER" >&2
  printf '      git add -- %s && git commit -m "chore(beads): ..."\n\n' "$TRACKER" >&2
  exit 1
fi
rm -f "$REMEDY"

# MEMORY RECONCILIATION (rf2-cve7). Every guard above is blind to the memory
# rows: the floor is diluted by the issue rows and the divergence guard reads
# only `"_type":"issue"`. This one counts the two populations separately and
# names the keys HEAD holds that the export does not.
#
# IT WARNS. IT DOES NOT REFUSE, and that is a deliberate design constraint
# rather than an unfinished one. This is the single shared tool the mayor runs
# several times an hour, and a false positive that aborted it would halt the
# whole dispatch loop — which is precisely why the reconciliation sat unbuilt as
# "a ruling and not a dispatch" while 210 keys went missing in silence. A
# warning delivers the entire value of the guard (the event becomes one loud
# block instead of nothing at all) with that risk removed. There is deliberately
# no --strict mode, no override flag and no config key: the whole deliverable is
# one unmissable warning, and a knob would be machinery guarding a warning.
#
# It also runs AFTER the divergence guard on purpose, so it speaks only when the
# checkpoint is genuinely about to commit and can say so truthfully.
MEMORY_FACTS=$(memory_facts "$TMP_EXPORT" "$TMP_HEAD")
if [ -n "$MEMORY_FACTS" ]; then
  printf '\n' >&2
  printf 'beads-checkpoint: ***** MEMORY RECONCILIATION FAILED (rf2-cve7) *****\n' >&2
  printf '  The tracker'"'"'s `bd remember` rows do not reconcile against HEAD.\n\n' >&2
  printf '%s\n' "$MEMORY_FACTS" >&2
  printf '\n  `bd stats` reports ISSUES ONLY, and the row-count floor above is dominated\n' >&2
  printf '  by issue rows, so a memory-only deletion passes both in silence. That is\n' >&2
  printf '  exactly how 210 keys disappeared on 2026-09-08 with nothing on screen.\n' >&2
  printf '\n  THIS IS A WARNING, NOT A REFUSAL — the checkpoint continues and commits\n' >&2
  printf '  the export. The database is the source of truth, and this may well be a\n' >&2
  printf '  deliberate `bd forget` or a retention cull. If it is NOT, the rows are not\n' >&2
  if [ -n "$BASELINE_COMMIT" ]; then
    printf '  lost. The commit this export was compared against still carries every\n' >&2
    printf '  one of them, and this is the lookup:\n' >&2
    printf '\n      git show %s:%s \\\n' "$BASELINE_COMMIT" "$TRACKER" >&2
    printf '        | jq -r --arg k "<key>" '"'"'select(._type=="memory" and .key==$k)|.value'"'"'\n' >&2
    printf '\n  THAT COMMIT IS SPELLED OUT RATHER THAN `HEAD` ON PURPOSE. This checkpoint\n' >&2
    printf '  commits the export a moment from now, so by the time you read this `HEAD`\n' >&2
    printf '  is the commit that REMOVED the rows: the same lookup against it would exit\n' >&2
    printf '  0 and print nothing — succeeding, and recovering nothing. A full commit oid\n' >&2
    printf '  is immutable, so it stays valid after this commit and every later one.\n' >&2
    printf '\n  Older checkpoints are NOT a general fallback — one made before a key was\n' >&2
    printf '  created does not carry it. To find the commit where any single key changed:\n' >&2
    printf '\n      git log -S'"'"'"key":"<key>"'"'"' -- %s\n' "$TRACKER" >&2
  else
    printf '  lost — but this is a first-ever checkpoint with no commit behind it, so\n' >&2
    printf '  there is no baseline to recover from. The database is the only copy.\n' >&2
  fi
  printf '\n  Select on `.key`. A bare grep for the key matches rows that merely MENTION\n' >&2
  printf '  it — bead prose naming a deleted key has already been mistaken for the\n' >&2
  printf '  memory itself (rf2-cve7, CLAUDE.md instrument item (f)).\n\n' >&2
fi

# The export is trustworthy — it is now the working tracker. From here on the
# working file cannot be a stale revert, whatever it was a moment ago.
#
# It is written in MINIMAL-DIFF order (rf2-51uz1.1) rather than raw export
# order, so the staged ledger shows the rows that changed and nothing else. The
# row-count check is the safety net: the rewrite must reproduce the export's
# rows exactly, and if it ever does not, the raw export wins. Losing a row to a
# cosmetic reordering would be a far worse bug than the churn it removes.
TMP_ORDERED=$(mktemp "${TMPDIR:-/tmp}/rf2-bdchk-ordered-XXXXXX")
minimal_diff_rewrite "$TMP_EXPORT" "$TMP_HEAD" "$TMP_ORDERED"
if [ "$(rows "$TMP_ORDERED")" = "$export_rows" ]; then
  cp -f "$TMP_ORDERED" "$TRACKER"
else
  printf 'beads-checkpoint: minimal-diff rewrite produced %s rows for a %s-row export;\n' \
    "$(rows "$TMP_ORDERED")" "$export_rows" >&2
  printf '  committing the raw export instead (order churn, but no lost rows).\n' >&2
  cp -f "$TMP_EXPORT" "$TRACKER"
fi

if same_content "$TRACKER" "$TMP_HEAD"; then
  printf 'beads-checkpoint: nothing to checkpoint (%s rows, unchanged).\n' "$export_rows"
  exit 0
fi

# Explicit pathspec, both to `git add` and to `git commit`: anything else the
# operator had staged stays staged, and nothing else is swept in.
git add -- "$TRACKER"
git commit -q -m "$MESSAGE" -- "$TRACKER"
printf 'beads-checkpoint: committed %s (%s rows, HEAD had %s).\n' \
  "$TRACKER" "$export_rows" "$head_rows"
