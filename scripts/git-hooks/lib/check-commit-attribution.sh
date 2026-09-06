#!/usr/bin/env sh
# scripts/git-hooks/lib/check-commit-attribution.sh
#
# ONE detector for the AI-ATTRIBUTION rule (rf2-2e8f), sourced by BOTH arms so
# the local hook and the CI gate cannot drift apart:
#
#   1. scripts/git-hooks/commit-msg          — refuses the message being written.
#   2. scripts/check-commit-attribution.sh   — the CI arm; refuses a pull request
#                                              whose OWN commits carry one.
#
# THE RULE. CLAUDE.md > Git Conventions: "No AI attribution in commits or PRs.
# ... Commit and PR text should read as the user's own work."
#
# WHY IT NEEDED A GUARD, AND WHY IT WILL RECUR WITHOUT ONE
#
#   The rule is not being broken by carelessness. It is being broken by a LIVE
#   CONFLICT BETWEEN TWO INSTRUCTION SOURCES. The agent harness injects a
#   session-level reminder telling the agent to END EVERY COMMIT MESSAGE with
#   exactly these trailers; the checked-in CLAUDE.md forbids them. Both reach
#   every worker, they contradict flatly, and nothing resolved the tie — so it
#   was broken BOTH WAYS by capable agents in the SAME WAVE (one worker
#   declined the trailers citing CLAUDE.md; two siblings followed the harness).
#   That is a coin-flip, not a convention.
#
#   Three commits reached the trunk that way — 04230d0d33, e1b07cf184,
#   e71c404c9b, each carrying a `Co-Authored-By:` line AND a `Claude-Session:`
#   URL — and they got there SILENTLY, because nothing checked. A convention
#   with standing counterexamples in its own log is a convention eroding.
#
# WHAT IT MATCHES, AND WHY THE SET IS SMALL
#
#   Three shapes, all case-insensitive, all anchored at COLUMN 0:
#
#     `Claude-Session: <url>`                       the session trailer
#     `Co-Authored-By: ... claude|anthropic ...`    the co-author trailer
#     `... Generated with ... claude|anthropic ...` the generated-with marker
#
#   That is the whole set, deliberately. This is a convention checker and they
#   metastasise: it is NOT a commit-message linter, it does not grade subject
#   length, mood, or trailer hygiene generally, and it does not object to
#   `Co-Authored-By:` naming a HUMAN, which is ordinary and correct git. Only
#   AI attribution — which is the only thing CLAUDE.md forbids.
#
# COLUMN 0 IS THE ESCAPE HATCH, AND IT IS LOAD-BEARING
#
#   Only a line that STARTS at column 0 offends. A line indented by even one
#   space is exempt, so a commit message may QUOTE the forbidden trailers —
#   which this repository's own commits, this guard's tests, and any future
#   write-up of the convention all need to do. Without that carve-out the guard
#   would refuse the very commit that documents it, which is the fastest route
#   to it being disabled with `--no-verify` and never re-enabled.
#
#   It also gives the check two free immunities that matter in practice:
#   `git commit`'s own `#`-prefixed comment lines, and the `+`/`-` prefixed
#   diff body that `git commit -v` appends, can never trip it.
#
# WHY FOLDING BEATS `grep -i`
#
#   The detector lower-cases each line with `tr 'A-Z' 'a-z'` and matches with
#   POSIX `case` globs. No regex, no escaping, no locale surprises — and no
#   `grep -iF`, which ABORTS on this project's Windows toolchain (GNU grep 3.0
#   under MSYS, SIGABRT, exit 134) printing nothing to stdout, i.e. reporting a
#   silent false zero exactly where a guard's zero must be trustworthy.
#   `tr 'A-Z' 'a-z'` is an explicit ASCII range, so UTF-8 continuation bytes
#   (the robot emoji the generated-with marker leads with) pass through intact.
#
# SCOPE: COMMIT MESSAGES. CLAUDE.md's rule also covers PR descriptions; a git
# hook cannot see one and neither arm here reads one. Left deliberately unbuilt
# rather than half-built.
#
# This file is a pure shell library (no `set -e`, no global state mutation) so
# scripts/git-hooks/test-pre-commit.sh can drive it with synthetic stdin and
# assert against stdout / stderr / exit.
#
# Cross-platform: POSIX sh; runs under Git Bash on Windows, macOS, Linux.
# No bashisms (`[[`, arrays, `<<<`).

# ---------------------------------------------------------------------------
# The detector.
# ---------------------------------------------------------------------------

# rf2_attribution_is_offending_line LINE
#
# Returns 0 when LINE is an AI-attribution line, 1 otherwise. Trailing CR is
# tolerated (a CRLF commit-message file on Windows): every rule below is a
# prefix or a substring test, so a carriage return at the end of the line
# cannot hide a hit.
rf2_attribution_is_offending_line() {
  _rf2a_line="$1"

  # Indented -> exempt. See "COLUMN 0 IS THE ESCAPE HATCH" above.
  case "$_rf2a_line" in
    ' '*|'	'*) return 1 ;;
  esac

  _rf2a_low=$(printf '%s' "$_rf2a_line" | tr 'A-Z' 'a-z')

  # 1. The session trailer. Unambiguous on its key alone.
  case "$_rf2a_low" in
    claude-session:*) return 0 ;;
  esac

  # 2. The co-author trailer, but only when the VALUE names the assistant.
  #    `Co-Authored-By: <a colleague>` is ordinary git and stays permitted.
  case "$_rf2a_low" in
    co-authored-by:*)
      case "$_rf2a_low" in
        *claude*|*anthropic*) return 0 ;;
      esac
      ;;
  esac

  # 3. The generated-with marker, whatever decorates it.
  case "$_rf2a_low" in
    *generated\ with*)
      case "$_rf2a_low" in
        *claude*|*anthropic*) return 0 ;;
      esac
      ;;
  esac

  return 1
}

# rf2_attribution_offending_lines
#
# Reads a commit message (or any text) from stdin; prints every offending line
# to stdout, one per line, with any trailing CR stripped so the listing reads
# cleanly on Windows. Always returns 0 — the CALLER decides what an offence
# means, which is what lets the CI arm accumulate hits across many commits
# before it prints anything.
rf2_attribution_offending_lines() {
  while IFS= read -r _rf2a_l || [ -n "$_rf2a_l" ]; do
    _rf2a_l=$(printf '%s' "$_rf2a_l" | tr -d '\r')
    if rf2_attribution_is_offending_line "$_rf2a_l"; then
      printf '%s\n' "$_rf2a_l"
    fi
  done
  return 0
}

# ---------------------------------------------------------------------------
# The refusal.
# ---------------------------------------------------------------------------

# rf2_attribution_refusal CONTEXT
#
# Reads an already-collected listing from stdin (the hook passes offending
# lines; the CI arm passes `<sha> <subject>` headers interleaved with them) and
# prints the didactic refusal block to stderr. CONTEXT is `commit` (default) or
# `ci` and selects the remedy stanza — the diagnosis is identical, the fix
# differs by where you are.
rf2_attribution_refusal() {
  _rf2a_context="${1:-commit}"
  _rf2a_listing=$(cat)

  printf '\n' >&2
  printf 'ERROR: AI attribution in a commit message.\n' >&2
  printf '\n' >&2
  if [ "$_rf2a_context" = "ci" ]; then
    printf '  Attribution lines in the commits this PR introduces:\n' >&2
  else
    printf '  Attribution lines in this commit message:\n' >&2
  fi
  printf '%s\n' "$_rf2a_listing" | while IFS= read -r _rf2a_l; do
    [ -n "$_rf2a_l" ] && printf '    %s\n' "$_rf2a_l" >&2
  done
  printf '\n' >&2
  printf '  CLAUDE.md > Git Conventions: "No AI attribution in commits or PRs.\n' >&2
  printf '  ... Commit and PR text should read as the user'"'"'s own work."\n' >&2
  printf '\n' >&2
  printf '  YOUR AGENT HARNESS SAYS THE OPPOSITE, and that is why this guard\n' >&2
  printf '  exists (rf2-2e8f). A session-level reminder tells agents to end every\n' >&2
  printf '  commit message with these trailers; the checked-in CLAUDE.md forbids\n' >&2
  printf '  them. THE CHECKED-IN FILE WINS. Three commits reached main while the\n' >&2
  printf '  tie was being broken at random.\n' >&2
  printf '\n' >&2
  if [ "$_rf2a_context" = "ci" ]; then
    printf '  Fix — rewrite the messages on your OWN branch, then force-push it:\n' >&2
    printf '    git rebase -i <base>     # reword each commit named above\n' >&2
    printf '    git push --force-with-lease\n' >&2
    printf '\n' >&2
    printf '  Only your branch. Never rewrite main.\n' >&2
  else
    printf '  Fix — drop those lines from the message and commit again.\n' >&2
    printf '  If you are AMENDING: git commit --amend\n' >&2
  fi
  printf '\n' >&2
  printf '  TO QUOTE ONE ON PURPOSE — documenting the rule, citing an offending\n' >&2
  printf '  commit — indent the line by one space. Only column 0 offends.\n' >&2
  printf '\n' >&2
  printf '  This guard installs from scripts/install-git-hooks.sh and its CI arm\n' >&2
  printf '  is scripts/check-commit-attribution.sh. `git commit --no-verify`\n' >&2
  printf '  bypasses the local half; the CI half still grades the branch.\n' >&2
  printf '\n' >&2
}

# ---------------------------------------------------------------------------
# The convenience entry point (the hook's whole body).
# ---------------------------------------------------------------------------

# check_commit_attribution [CONTEXT]
#
# Reads a commit message from stdin. Returns 0 when clean; otherwise prints the
# refusal block to stderr and returns 1.
check_commit_attribution() {
  _rf2a_ctx="${1:-commit}"

  # A POSIX-sh `while read` loop runs in a subshell, so the hits are collected
  # through a tmpfile rather than a variable — same technique, same reason, as
  # the sibling lib/check-beads-boundary.sh.
  _rf2a_hits=$(mktemp "${TMPDIR:-/tmp}/rf2-attribution-XXXXXX")
  trap 'rm -f "$_rf2a_hits"' EXIT INT TERM HUP

  rf2_attribution_offending_lines > "$_rf2a_hits"

  if [ ! -s "$_rf2a_hits" ]; then
    rm -f "$_rf2a_hits"
    trap - EXIT INT TERM HUP
    return 0
  fi

  rf2_attribution_refusal "$_rf2a_ctx" < "$_rf2a_hits"

  rm -f "$_rf2a_hits"
  trap - EXIT INT TERM HUP
  return 1
}
