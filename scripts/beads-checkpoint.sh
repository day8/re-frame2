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
# USAGE
#
#   sh scripts/beads-checkpoint.sh [-m MESSAGE]
#       Re-export the tracker from the database, sanity-check the result, and
#       commit `.beads/issues.jsonl` if it changed. This is the checkpoint —
#       run it BEFORE `git checkout HEAD -- .beads` and the pull, never after.
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
# ---------------------------------------------------------------------------
BOUNDARY_LIB="$REPO_ROOT/scripts/git-hooks/lib/check-beads-boundary.sh"
if [ -f "$BOUNDARY_LIB" ]; then
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
TMP_A=""
TMP_B=""
cleanup() { rm -f "$TMP_EXPORT" "$TMP_HEAD" "$TMP_A" "$TMP_B" 2>/dev/null || true; }
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
bd export > "$TMP_EXPORT" 2>/dev/null \
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

# The export is trustworthy — it is now the working tracker. From here on the
# working file cannot be a stale revert, whatever it was a moment ago.
cp -f "$TMP_EXPORT" "$TRACKER"

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
