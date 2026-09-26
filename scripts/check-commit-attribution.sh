#!/usr/bin/env sh
# scripts/check-commit-attribution.sh
#
# CI arm of the AI-ATTRIBUTION guard. Fails a pull request whose OWN
# COMMITS carry AI attribution in their messages, or whose BODY carries it —
# the `Co-Authored-By:` / `Claude-Session:` / generated-with / bare session-URL
# shapes CLAUDE.md > Git Conventions forbids — and one whose own commits are
# RECORDED as the assistant, author or committer `noreply@anthropic.com`. See
# scripts/git-hooks/lib/check-commit-attribution.sh for the diagnosis and the
# matched set; this script only chooses WHICH TEXT to grade. Every arm shares
# one detector so the local hook and the CI gate can never drift apart.
#
# THE RANGE, WHICH IS THE ONLY REAL DESIGN QUESTION HERE
#
#   `git merge-base BASE HEAD`..HEAD — the commits this branch INTRODUCED.
#   Never all of history, and never a two-endpoint comparison.
#
#   All of history is not an option, and not for a stylistic reason: three
#   commits carrying these trailers are ancestors of main
#   (04230d0d33, e1b07cf184, e71c404c9b), and trunk history is not rewritten —
#   rewriting published trunk history is a force-push across every live
#   worktree. A gate that grades all of history would therefore red every
#   pull request in the repository, for ever, over commits nobody in that PR
#   wrote. The branch delta grades exactly the commits its author can still
#   fix, and the three sit BEHIND every merge base, so the trunk stays green
#   with no allow-list, no baseline file and nothing to trim. So do the trunk
#   commits already recorded as the assistant, which are accepted as history
#   in the same way.
#
#   Pass the BASE BRANCH, not a precomputed branch point — the merge base is
#   this script's job. The reasoning is the sibling guard's
#   (scripts/check-beads-pr-boundary.sh): a
#   two-endpoint `git log BASE..HEAD` is not the same set once BASE moves, and
#   in this repository BASE moves constantly.
#
# HOW IT DEGRADES: CLOSED, LOUDLY, AND THAT DICTATES WHERE IT CAN RUN
#
#   A missing base ref, an unresolvable one, or no merge base — the usual
#   cause being a SHALLOW CLONE that does not contain the branch point — all
#   fail closed. A gate that cannot see the range certifies nothing, and a
#   commit-message gate is unusually exposed to the alternative: with no range
#   it inspects nothing, finds nothing, and reports the same silent zero as a
#   clean branch. That is the vacuous pass this whole guard exists to end.
#
#   So this MUST run in a job checked out with `fetch-depth: 0`. It cannot
#   live in test.yml's `verify-skill-mcp-drift` ("Repo invariant checks"),
#   whose checkout is the default shallow one and which is deliberately kept
#   cheap: there it would fail closed on every PR, and "fixing" that by
#   letting it pass on a missing range would install exactly the vacuous
#   checker it is meant to replace. `beads-pr-boundary` is the job whose
#   checkout, event policy and branch-point semantics this script matches
#   line for line.
#
# ENFORCEMENT SHAPE
#
#   pull_request  -> enforced. Every PR, no path filter, no branch filter.
#   anything else -> passes with an explanatory line. A push to main carries
#                    commits that are history; refusing one there
#                    blocks the trunk over a rewrite decision that is the
#                    operator's, not this gate's.
#
# Usage:
#   sh scripts/check-commit-attribution.sh [BASE_REF]
#   sh scripts/check-commit-attribution.sh --pr-body   < body
#
#   BASE_REF resolution: argument, else $COMMIT_ATTRIBUTION_BASE_REF. Run it
#   locally to pre-flight a branch:
#     sh scripts/check-commit-attribution.sh origin/main
#
#   `--pr-body` grades a PULL REQUEST BODY read from stdin instead of a commit
#   range — the surface a git hook cannot see. See its block below.
#
# Cross-platform: POSIX sh. Runs identically on the ubuntu CI runner, on
# macOS, and under Git Bash on Windows. No bashisms.

set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
LIB="$SCRIPT_DIR/git-hooks/lib/check-commit-attribution.sh"

if [ ! -f "$LIB" ]; then
  printf 'check-commit-attribution: missing detector library at %s\n' "$LIB" >&2
  exit 1
fi
# shellcheck source=git-hooks/lib/check-commit-attribution.sh
. "$LIB"

EVENT="${GITHUB_EVENT_NAME:-pull_request}"
if [ "$EVENT" != "pull_request" ]; then
  printf 'Event is "%s", not a pull request: the attribution guard is not enforced here.\n' "$EVENT"
  printf 'Commits already on a branch history are an operator rewrite decision, not a gate.\n'
  exit 0
fi

# ---------------------------------------------------------------------------
# The PR-BODY arm: `sh scripts/check-commit-attribution.sh --pr-body`, body on
# stdin. No range, no git — the body is just text, and the detector grades
# lines rather than commits.
#
# WHY IT NEEDS AN ARM AT ALL. CLAUDE.md forbids the trailers in "commits or
# PRs", and a git hook cannot see a body. The two shapes the harness writes
# there are the generated-with marker and a BARE session URL.
#
# THE BODY IS DATA, NEVER TEXT. The workflow puts it in an `env:` value and
# pipes that value in. `${{ github.event.pull_request.body }}` is expanded by
# the workflow renderer BEFORE any shell sees the script, so interpolating it
# into a `run:` body would splice author-controlled prose into the source.
#
# AN EMPTY BODY PASSES, and that is not a hole in the fail-closed policy above.
# A missing RANGE makes the commit arm inspect nothing while reporting the same
# silent zero as a clean branch; a missing BODY genuinely contains nothing to
# grade, and refusing one would red every pull request opened without prose.
if [ "${1:-}" = "--pr-body" ]; then
  if check_commit_attribution pr; then
    printf 'No AI attribution in the pull request body.\n'
    exit 0
  fi
  exit 1
fi

BASE="${1:-${COMMIT_ATTRIBUTION_BASE_REF:-}}"
if [ -z "$BASE" ]; then
  printf 'check-commit-attribution: no base ref.\n' >&2
  printf 'Pass one as an argument or set COMMIT_ATTRIBUTION_BASE_REF. Failing closed:\n' >&2
  printf 'a gate that cannot see the commit range certifies nothing.\n' >&2
  exit 1
fi

if ! git rev-parse --verify --quiet "$BASE^{commit}" >/dev/null; then
  printf 'check-commit-attribution: base ref "%s" does not resolve to a commit.\n' "$BASE" >&2
  printf 'Failing closed rather than passing vacuously.\n' >&2
  exit 1
fi

BRANCH_POINT=$(git merge-base "$BASE" HEAD 2>/dev/null) || BRANCH_POINT=""
if [ -z "$BRANCH_POINT" ]; then
  printf 'check-commit-attribution: no merge base between "%s" and HEAD.\n' "$BASE" >&2
  printf 'Usually a shallow clone that does not contain the branch point —\n' >&2
  printf 'on GitHub Actions use actions/checkout with fetch-depth: 0.\n' >&2
  printf 'Failing closed: a gate that cannot see the branch delta certifies nothing.\n' >&2
  exit 1
fi

# Collect through a tmpfile: the `while read` loop below runs in a subshell, so
# a variable would not survive it. Same technique, same reason, as the shared
# library and its sibling lib/check-beads-boundary.sh.
LISTING=$(mktemp "${TMPDIR:-/tmp}/rf2-attribution-ci-XXXXXX")
IDENTS=$(mktemp "${TMPDIR:-/tmp}/rf2-attribution-ci-ident-XXXXXX")
trap 'rm -f "$LISTING" "$IDENTS"' EXIT INT TERM HUP

COMMITS=$(git rev-list "$BRANCH_POINT..HEAD")
count=0
for sha in $COMMITS; do
  count=$((count + 1))
  header="$(git rev-parse --short=10 "$sha") $(git log -1 --format=%s "$sha")"
  hits=$(git log -1 --format=%B "$sha" | rf2_attribution_offending_lines)
  if [ -n "$hits" ]; then
    printf '%s\n' "$header" >> "$LISTING"
    printf '%s\n' "$hits" | while IFS= read -r l; do
      [ -n "$l" ] && printf '  %s\n' "$l" >> "$LISTING"
    done
  fi

  # The identity the commit is recorded under, graded apart from its text.
  # `%ae` / `%ce`, not the mailmapped `%aE` / `%cE`: the gate grades what the
  # commit records, not what a mailmap would display.
  author=$(git log -1 --format='%an <%ae>' "$sha")
  committer=$(git log -1 --format='%cn <%ce>' "$sha")
  ident_hits=""
  if rf2_attribution_is_offending_ident "$author"; then
    ident_hits="  author:    $author"
  fi
  if rf2_attribution_is_offending_ident "$committer"; then
    ident_hits="${ident_hits:+$ident_hits
}  committer: $committer"
  fi
  if [ -n "$ident_hits" ]; then
    printf '%s\n%s\n' "$header" "$ident_hits" >> "$IDENTS"
  fi
done

if [ ! -s "$LISTING" ] && [ ! -s "$IDENTS" ]; then
  printf 'No AI attribution in the messages, authors or committers of the %s commit(s) this branch introduces (from %s).\n' \
    "$count" "$(git rev-parse --short=10 "$BRANCH_POINT")"
  exit 0
fi

if [ -s "$LISTING" ]; then
  rf2_attribution_refusal ci < "$LISTING"
fi
if [ -s "$IDENTS" ]; then
  rf2_attribution_ident_refusal ci < "$IDENTS"
fi
exit 1
