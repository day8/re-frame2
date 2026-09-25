#!/usr/bin/env sh
# scripts/git-hooks/lib/check-commit-attribution.sh
#
# ONE detector for the AI-ATTRIBUTION rule, sourced by BOTH arms so
# the local hook and the CI gate cannot drift apart:
#
#   1. scripts/git-hooks/commit-msg          — refuses the message being written.
#   2. scripts/check-commit-attribution.sh   — the CI arm; refuses a pull request
#                                              whose OWN commits carry one, and
#                                              (`--pr-body`) one whose BODY does.
#
# THE RULE. CLAUDE.md > Git Conventions: "No AI attribution in commits or PRs.
# ... Commit and PR text should read as the user's own work."
#
# WHY IT NEEDS A GUARD
#
#   The rule is not broken by carelessness. It is broken by a LIVE CONFLICT
#   BETWEEN TWO INSTRUCTION SOURCES. The agent harness injects a session-level
#   reminder telling the agent to END EVERY COMMIT MESSAGE with exactly these
#   trailers; the checked-in CLAUDE.md forbids them. Both reach every worker and
#   they contradict flatly, so without a check capable agents break the tie
#   BOTH WAYS — one declines the trailers citing CLAUDE.md, its sibling follows
#   the harness. That is a coin-flip, not a convention, and the offending
#   commits reach the trunk SILENTLY. A convention with standing
#   counterexamples in its own log is a convention eroding.
#
# WHAT IT MATCHES, AND WHY THE SET IS SMALL
#
#   Four shapes, all case-insensitive, all anchored at COLUMN 0:
#
#     `Claude-Session: <url>`                       the session trailer
#     `Co-Authored-By: ... @anthropic.com ...`      the co-author trailer
#     `<decoration> Generated with [Claude Code](<link>)`  the marker
#     `https://claude.ai/code/session_...`          the bare session URL
#
# A TRAILER IS A LINE THAT *IS* THE ATTRIBUTION; PROSE MERELY NAMES IT
#
#   That distinction is the whole of rule 3's and rule 4's shape. A bare
#   SUBSTRING test for rule 3, or a bare PREFIX test for rule 4, would refuse a
#   line that only MENTIONS a forbidden shape as though it carried one — and
#   the likeliest such line at column 0 is a worker's own statement that it
#   COMPLIED:
#
#     No Co-Authored-By: Claude and no Generated with [Claude Code] trailer,
#     in the commit message or in this description.
#
#   Every dispatch brief in this project tells the worker to decline the
#   trailers, so that sentence is written by design, and a guard that reddens
#   the PR for saying it fires on a PR per worker per wave. Nor can such a red
#   be cleared by fixing the body, because `test.yml` reads the body from the
#   FROZEN EVENT PAYLOAD; a re-run re-reads the old text for ever, and only a
#   new event (a push, or a close/reopen, which then leaves two check
#   generations in the rollup) can clear it.
#
#   THE TEST IS STRUCTURAL, NOT SENTIMENTAL. It does not look for a negation,
#   an "I declined" or any other phrasing — that is fragile and trivially
#   defeatable. It asks the one question that actually
#   separates the two: is this line the attribution, or a sentence about it?
#   `git interpret-trailers` recognises a trailer only as a WHOLE LINE, and
#   GitHub links a co-author only from a whole line, so a marker spliced into
#   the middle of a sentence attributes nothing to anyone. It is a quotation,
#   and quotations were always meant to be legal here.
#
#   RULES 1 AND 2 NEED NOTHING FROM THIS, and that asymmetry is deliberate
#   rather than an oversight. Both are keyed on a trailer TOKEN at column 0,
#   which is already precisely git's own definition of a trailer: a column-0
#   line reading `Claude-Session: ...`, or `Co-Authored-By: ...` carrying the
#   assistant's address, IS one however the rest of the sentence reads. The
#   compliance sentence never reaches rule 2 — `No Co-Authored-By: ...` fails
#   its prefix test for free. Widening what is already exact would only open a
#   hole.
#
#   That is the whole set, deliberately. This is a convention checker and they
#   metastasise: it is NOT a commit-message linter, it does not grade subject
#   length, mood, or trailer hygiene generally, and it does not object to
#   `Co-Authored-By:` naming a HUMAN, which is ordinary and correct git. Only
#   AI attribution — which is the only thing CLAUDE.md forbids.
#
#   THE CO-AUTHOR RULE MATCHES THE ADDRESS FAMILY, NOT THE NAME, and that is a
#   correctness point rather than a nicety. Every one of the 31 trailers in
#   this repository's log carries `@anthropic.com`, and the documented harness
#   default is `Claude <claude@anthropic.com>` — so the address is both
#   sufficient and precise. A name substring is neither: it refuses
#   `Co-Authored-By: Jean-Claude Martin <jcm@example.invalid>`, a human
#   colleague turned away for their own name.
#
#   The fourth shape is the same session URL as the first with no key in front
#   of it, which is how the harness writes it into a PULL REQUEST BODY. It
#   passes all three keyed rules untouched, so it needs a rule of its own.
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
# GIT'S OWN FURNITURE IS EXEMPT, AND IT IS NOT FREE
#
#   `#`-prefixed template comments are exempt outright, and the detector STOPS
#   READING at git's scissors line — `# ------------------------ >8 ---...` —
#   below which `git commit -v` writes the diff.
#
#   NEITHER FALLS OUT OF THE COLUMN-0 ANCHOR. A `#` line starts at column 0,
#   and rules 1, 2 and 4 are prefix tests that a `#` displaces for free, but
#   rule 3 admits decoration in front of the marker — so without the `#` arm
#   `# 🤖 Generated with [Claude Code](<link>)` would be refused. That matters:
#   `commit-msg` reads COMMIT_EDITMSG BEFORE git strips the comments and the
#   diff, so any `git commit -v` whose diff touches CLAUDE.md, this file or its
#   tests would be refused with `--no-verify` the only escape — a guard
#   punishing the commit that repairs it. Layer 10d pins both halves.
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
# SCOPE: COMMIT MESSAGES AND PULL REQUEST BODIES. CLAUDE.md's rule covers both,
# and the detector below is text-agnostic — it grades lines, not commits. A git
# hook cannot see a body, so the body is graded by the CI arm alone
# (`check-commit-attribution.sh --pr-body`), which reads it on stdin.
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

# rf2_attribution_is_tool_link WORD
#
# Returns 0 when WORD carries a URL whose HOST is the tool's own, 1 otherwise.
# WORD is the already-lower-cased final blank-separated word of a line, so it
# arrives wearing whatever punctuation the surrounding text lent it — the
# markdown marker's closing `)`, a sentence's full stop.
#
# THIS ISOLATES THE HOST, WHICH IS THE WHOLE POINT. Rule 3's tail anchor means
# "ends on the tool's own link", and a URL's PATH is not its host, so a test
# asking whether the word CONTAINS `claude` or `anthropic` anywhere would refuse
# an ordinary citation:
#
#      Generated with [Claude Code] was declined per https://github.com/day8/re-frame2/blob/main/CLAUDE.md.
#
#   — a link to the very rule the sentence is complying with, refused because
# the FILENAME of that rule is `CLAUDE.md`, while the same sentence citing
# `README.md` passes. That is the mention-versus-attribution false positive
# again, reached through the tail rather than the head, and linking the rule
# rather than naming its file is the natural thing to write.
#
# So: strip the scheme, take the authority, drop userinfo and port, and compare
# the host itself. Subdomains of the tool's hosts count; a host that merely
# ENDS on one of them does not, because the dot is the boundary — otherwise
# `claude.com.example.invalid` would read as the tool's.
rf2_attribution_is_tool_link() {
  _rf2a_host="$1"

  case "$_rf2a_host" in
    *://*) ;;
    *) return 1 ;;
  esac

  _rf2a_host=${_rf2a_host#*://}   # the authority onwards
  _rf2a_host=${_rf2a_host%%/*}    # up to the path,
  _rf2a_host=${_rf2a_host%%\?*}   # or the query, where there is no path,
  _rf2a_host=${_rf2a_host%%#*}    # or the fragment.
  _rf2a_host=${_rf2a_host#*@}     # userinfo goes BEFORE the port: it may hold a colon
  _rf2a_host=${_rf2a_host%%:*}    # the port

  # A host ends on a letter or a digit; anything after that belongs to the
  # prose. This is what unwraps the markdown marker's `…claude-code)`.
  while :; do
    case "$_rf2a_host" in
      ''|*[a-z0-9]) break ;;
      *) _rf2a_host=${_rf2a_host%?} ;;
    esac
  done

  case "$_rf2a_host" in
    claude.com|*.claude.com) return 0 ;;
    claude.ai|*.claude.ai) return 0 ;;
    anthropic.com|*.anthropic.com) return 0 ;;
  esac

  return 1
}

# rf2_attribution_is_offending_line LINE
#
# Returns 0 when LINE is an AI-attribution line, 1 otherwise.
#
# TRAILING WHITESPACE IS TRIMMED BEFORE THE RULES RUN, which matters because
# rules 3 and 4 anchor the END of the line as well as the start. A trailing CR
# (a CRLF commit-message file on Windows) is already stripped upstream by
# rf2_attribution_offending_lines; trailing blanks are stripped here.
rf2_attribution_is_offending_line() {
  _rf2a_line="$1"

  # Indented, or one of git's own `#` template comments -> exempt. See
  # "COLUMN 0 IS THE ESCAPE HATCH" and "GIT'S OWN FURNITURE" above. The `#`
  # arm is load-bearing for rule 3 alone: the other three are prefix tests a
  # `#` already displaces.
  case "$_rf2a_line" in
    ' '*|'	'*|'#'*) return 1 ;;
  esac

  _rf2a_low=$(printf '%s' "$_rf2a_line" | tr 'A-Z' 'a-z')

  # Trailing blanks go before the whole-line anchors in rules 3 and 4, so a
  # reflowed or hand-wrapped body cannot walk a real trailer past an
  # end-of-line test with one space. (A trailing CR is already stripped
  # upstream by rf2_attribution_offending_lines.)
  while :; do
    case "$_rf2a_low" in
      *' '|*'	') _rf2a_low=${_rf2a_low%?} ;;
      *) break ;;
    esac
  done

  # 1. The session trailer. Unambiguous on its key alone.
  case "$_rf2a_low" in
    claude-session:*) return 0 ;;
  esac

  # 2. The co-author trailer, but only when the ADDRESS is the assistant's.
  #    `Co-Authored-By: <a colleague>` is ordinary git and stays permitted —
  #    including a colleague whose NAME contains "claude", which is why this
  #    tests the address family rather than a name substring.
  case "$_rf2a_low" in
    co-authored-by:*)
      case "$_rf2a_low" in
        *@anthropic.com*) return 0 ;;
      esac
      ;;
  esac

  # 3. The generated-with marker — the line that IS the marker, never a line
  #    that names it. Two anchors, and neither alone is enough:
  #
  #      - NOTHING BUT DECORATION BEFORE IT. The harness writes the marker
  #        behind a robot emoji; a human writes it behind words. Words in
  #        front make the line a sentence, so the head must carry no letters.
  #      - THE LINE ENDS ON THE TOOL'S OWN LINK, i.e. the last blank-separated
  #        word is the `[Claude Code](https://claude.com/claude-code)` link
  #        itself. Anything else after it is prose, and a marker with prose
  #        after it attributes nothing.
  #
  #    So `… Generated with [Claude Code](…)` is refused and `Generated with
  #    [Claude Code] was declined` is not.
  #
  #    THE SECOND ANCHOR TESTS FOR A LINK, NOT FOR THE WORD "CLAUDE". A test
  #    for the word would refuse an ordinary column-0 compliance sentence that
  #    merely happens to END on such a word,
  #
  #      Generated with [Claude Code] was declined per CLAUDE.md.
  #
  #    whose final word `CLAUDE.md.` is a FILENAME. That line carries no link
  #    and attributes nothing to anybody; it is the same mention-versus-
  #    attribution false positive, one rewording away from
  #    `No Generated with [Claude Code] trailer was added.`, which ends on
  #    `added.`. So the discriminator is structural on BOTH halves — no letters
  #    in front, and a URL at the end whose host is the tool's; deliberately
  #    NOT a list of negations ("No", "declined", "not"), which is trivially
  #    defeatable.
  #
  #    AND "WHOSE HOST IS THE TOOL'S" MEANS THE HOST. A word test behind a
  #    `://` test would still let a citation URL whose PATH carries `claude`
  #    read as the tool's own link:
  #
  #      Generated with [Claude Code] was declined per https://github.com/day8/re-frame2/blob/main/CLAUDE.md.
  #
  #    would be refused while the same sentence citing `README.md` passes, on
  #    nothing but a word in the path. Linking the rule rather than naming its
  #    file is the natural way to cite it — and this repository's rule file is
  #    literally called CLAUDE.md — so `rf2_attribution_is_tool_link` above
  #    parses the tail as a URL and compares the HOST.
  case "$_rf2a_low" in
    *'generated with'*)
      case "${_rf2a_low%%generated with*}" in
        *[a-z]*) ;;
        *)
          _rf2a_tail=${_rf2a_low##* }
          if rf2_attribution_is_tool_link "$_rf2a_tail"; then
            return 0
          fi
          ;;
      esac
      ;;
  esac

  # 4. The bare session URL — rule 1's URL with no key in front of it, which is
  #    the shape the harness writes into a pull request body. WHOLE LINE: the
  #    URL alone, because that is how the harness writes it. A line that opens
  #    with the URL and then keeps talking is a sentence about it.
  case "$_rf2a_low" in
    https://claude.ai/code/session_*)
      case "$_rf2a_low" in
        *' '*|*'	'*) ;;
        *) return 0 ;;
      esac
      ;;
  esac

  return 1
}

# rf2_attribution_offending_lines
#
# Reads a commit message, a pull request body, or any text from stdin; prints
# every offending line to stdout, one per line, with any trailing CR stripped
# so the listing reads cleanly on Windows. Always returns 0 — the CALLER
# decides what an offence means, which is what lets the CI arm accumulate hits
# across many commits before it prints anything.
#
# READING STOPS AT GIT'S SCISSORS LINE. Below it sits the diff `git commit -v`
# appends, whose `+` lines are not the author's text at all — and a commit that
# EDITS this guard adds the forbidden trailers there by construction.
rf2_attribution_offending_lines() {
  while IFS= read -r _rf2a_l || [ -n "$_rf2a_l" ]; do
    _rf2a_l=$(printf '%s' "$_rf2a_l" | tr -d '\r')
    # Matched on the scissors PAYLOAD rather than the whole line, so a
    # non-default `core.commentChar` cannot smuggle the tail past it.
    case "$_rf2a_l" in
      *'------------------------ >8 ------------------------') break ;;
    esac
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
# prints the didactic refusal block to stderr. CONTEXT is `commit` (default),
# `ci` or `pr`, and selects the headline and the remedy stanza — the diagnosis
# is identical, the fix differs by which text you are holding.
rf2_attribution_refusal() {
  _rf2a_context="${1:-commit}"
  _rf2a_listing=$(cat)

  printf '\n' >&2
  case "$_rf2a_context" in
    pr)
      printf 'ERROR: AI attribution in a pull request body.\n' >&2
      printf '\n' >&2
      printf '  Attribution lines in this pull request body:\n' >&2
      ;;
    ci)
      printf 'ERROR: AI attribution in a commit message.\n' >&2
      printf '\n' >&2
      printf '  Attribution lines in the commits this PR introduces:\n' >&2
      ;;
    *)
      printf 'ERROR: AI attribution in a commit message.\n' >&2
      printf '\n' >&2
      printf '  Attribution lines in this commit message:\n' >&2
      ;;
  esac
  printf '%s\n' "$_rf2a_listing" | while IFS= read -r _rf2a_l; do
    [ -n "$_rf2a_l" ] && printf '    %s\n' "$_rf2a_l" >&2
  done
  printf '\n' >&2
  printf '  CLAUDE.md > Git Conventions: "No AI attribution in commits or PRs.\n' >&2
  printf '  ... Commit and PR text should read as the user'"'"'s own work."\n' >&2
  printf '\n' >&2
  printf '  YOUR AGENT HARNESS SAYS THE OPPOSITE, and that is why this guard\n' >&2
  printf '  exists (rf2-2e8f). A session-level reminder tells agents to end every\n' >&2
  printf '  commit message AND every PR body with these trailers; the checked-in\n' >&2
  printf '  CLAUDE.md forbids them. THE CHECKED-IN FILE WINS. Three commits\n' >&2
  printf '  reached main while the tie was being broken at random.\n' >&2
  printf '\n' >&2
  # THE PUSH IS NAMED IN PROSE RATHER THAN SPELLED AS A COMMAND, deliberately.
  # The safe force-push flag is `--force-with-<the retired view-lifetime word>`,
  # and `scripts/check_view_lifetime_residue.py` is a repo-wide ratchet held at
  # ZERO over tracked file content — it matches that word as a bare token, which
  # is exactly what a git flag name makes it. Spelling the flag out would red
  # the ratchet, so do not "helpfully" spell it.
  case "$_rf2a_context" in
    pr)
      printf '  Fix — drop those lines from the body:\n' >&2
      printf '    gh pr edit <number> --body-file <file>\n' >&2
      printf '\n' >&2
      printf '  Nothing else in the body needs to change.\n' >&2
      ;;
    ci)
      printf '  Fix — reword the messages on your OWN branch, then force-push it:\n' >&2
      printf '    git rebase -i <base>     # reword each commit named above\n' >&2
      printf '\n' >&2
      printf '  Only your branch. Never rewrite main.\n' >&2
      ;;
    *)
      printf '  Fix — drop those lines from the message and commit again.\n' >&2
      printf '  If you are AMENDING: git commit --amend\n' >&2
      ;;
  esac
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
# Reads a commit message (CONTEXT `commit`, the default, or `ci`) or a pull
# request body (CONTEXT `pr`) from stdin. Returns 0 when clean; otherwise
# prints the refusal block to stderr and returns 1.
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
