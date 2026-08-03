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

# head_copy PATH — write HEAD's copy of the tracker to PATH. Empty file if the
# path does not exist at HEAD (a first-ever checkpoint).
head_copy() {
  git show "HEAD:$TRACKER" > "$1" 2>/dev/null || : > "$1"
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
  printf '  Checkpoint first, then clear, then pull:\n\n' >&2
  printf '      sh scripts/beads-checkpoint.sh\n' >&2
  printf '      git checkout HEAD -- .beads\n' >&2
  printf '      git pull --rebase\n\n' >&2
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

TMP_HEAD=$(mktemp "${TMPDIR:-/tmp}/rf2-bdchk-head-XXXXXX")
head_copy "$TMP_HEAD"

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
