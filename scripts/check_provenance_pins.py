#!/usr/bin/env python3
"""Every cited AUTHORED head in the Fresco evidence corpus must be accompanied
by a RESOLVABLE LANDED SHA.

This repo rebase-merges, which mints a new SHA for every commit on the branch.
A page that pins a measurement to the SHA it was authored at is therefore
stranded the moment its own PR merges: the authored object is reachable from no
ref, so it is not in a fresh clone at all, and the page's central claim — here
is the tree this was measured on — fails without saying so.  It still looks
pinned.

WHAT IS ENFORCED, and deliberately not the stricter thing.  The rule is
accompaniment, NOT "no authored heads" and NOT "every SHA must be on main".
Pages legitimately retain the authored head — it is the true provenance of the
run, and a blob table beside it pins the instrument when the anchor cannot be
pinned.  A gate that red-flagged those pages would punish the careful ones and
be routed around within a week.  So a block may cite any number of stranded
heads; what it may not do is leave the reader with no resolvable anchor at all.
Concretely: within one block, if any cited pin is not an ancestor of
`origin/main`, then some other pin in that same block must be.

A TABLE ROW IS A BLOCK OF ITS OWN, because a paragraph-sized scope fails open
there.  A record table is one unbroken run of non-blank lines, so under it every
row would share a single scope and any one landed hash would answer for all of
them — a wrong pin in the operative row would go unreported as long as some
other row cited something that had landed.  That is precisely backwards from
where the risk lives: on a pre-registration page the hash that matters most is
the one in the table, beside a landed one, so the guard would be blind in the
field whose wrongness costs most.  Accompaniment is therefore scoped to the row
that makes the claim.  A row ends where the NEXT row begins and not at the
newline, because this corpus wraps a long cell across source lines and the
anchor rescuing a head is routinely on the continuation.  Prose is judged by
paragraph — a paragraph is one scope, which is what keeps "authored at X … it
landed on main as Y" sentences passing.

A BLOCKQUOTE IS NOT ONE BLOCK EITHER: the same fail-open would otherwise sit
in the container the corpus actually writes its provenance notes in.  Inside a
callout a paragraph break is a lone `>`, which does not strip to empty, so a
boundary reading the raw line never sees it and a multi-paragraph callout would
be ONE scope from its opening line to the blank line that ends the whole
callout — any single landed hash in it answering for every pin in it.  That is
the expensive half, because a one-pin plant is the natural non-vacuity control
for this gate, so the defect would read as the gate working.  Both boundaries
therefore read the line with its quote markers stripped: quoting a paragraph
changes nothing about how it is judged, and a pin in its own callout paragraph
is judged on its own.

FAILURE DIRECTION — this gate fails toward REFUSAL, never toward silence, and
one asymmetry forces it.  The stranded pins are precisely the objects a fresh
clone does NOT have, so from inside any checkout "git has never heard of this
token" and "this token is a content digest" are the same observation, and only
one of them is safe.  Resolvability therefore cannot decide the population:
a checker that classified by "does git know it" would call every stranded pin a
digest and pass a corpus made entirely of broken pins, silently, in CI, which is
the exact defect this gate exists to catch.  So the population is decided
SYNTACTICALLY, from how the page writes the token, and every unresolved token is
counted as NOT LANDED.  A token this script cannot classify is a FINDING, not a
pass.  Green means every cited pin was positively shown to be an ancestor of
`origin/main`, or positively shown to share a block with one.

SEPARATING COMMITS FROM DIGESTS is the hard half, and it is why the above
matters.  Of the hex tokens in this corpus roughly three in five are not commits
at all — SHA-1 blob hashes in the instrument tables, SHA-256 document digests,
FNV hashes of rendered output.  A checker that treats those as pins reds on
hashes forever and gets disabled.  They are separated by CONTEXT, never by a
roster of known digests (which rots) and never by asking git (see above):

  * NEAREST WORD WINS in the token's left context, which runs back through the
    end of the previous line because this corpus wraps its prose — "spine blob
    `x`" and "the previous blob was\n`x`" say digest, "Producing commit | `x`",
    "landed on main as `x`" and "authored at `x` on `worker/…`" say pin.
    Nearest rather than any-match, because a sentence about a commit routinely
    ends by naming a blob and vice versa, and the word beside the token is the
    one describing it.  That reach STOPS AT THE START OF THE TOKEN'S OWN ROW,
    the extraction half of the row scope: a "| Blob hash | … |" row above
    would otherwise repaint the row below it as a digest, no citation would be
    created, and the row scope below would never get to adjudicate what was
    never extracted.
    A FILE PATH in a code span counts as a digest word: "|
    `lane.cljs` | `x` |" and "`front/codec.cljs` is `x`" are how a blob is
    written when no noun is spare.  A branch is not a path — `worker/x` and
    `origin/main` carry no extension — so they stay commit words.
  * APPOSITION to the right, but only within a dozen characters, for the "the
    `x` blob" ordering.  Kept short on purpose: "Producing commit `x`. The blob
    table below…" must stay a pin, and a wide right window would flip it.
  * the enclosing TABLE'S HEADER — a `| file | blob |` table is a blob table
    top to bottom, which covers the rows whose cells name no file at all.
  * LENGTH, the one fact that needs no vocabulary: this repository's object
    format is SHA-1 (`git rev-parse --show-object-format`), so a hex run longer
    than 40 characters cannot be an object id here at all, and every 64-char
    token in the corpus is a SHA-256 document digest.

When none of those speaks, a token IN A CODE SPAN is treated as a PIN.  That is
the failure direction again: an unclassifiable token costs a finding, and the
cost of the opposite default is a corpus of broken pins that reports success.  A
handful of genuinely ambiguous lines are reported for that reason — "at
`e145597127` the gate reads …" is a sentence a reader cannot classify either,
which is the finding.

BARE HEX, OUTSIDE A CODE SPAN, IS READ TOO, but only when the vocabulary above
positively calls it a commit.  Reading code spans alone would fail open against
the very rule at the top of this file: `Authored at deadbeef00 on worker/x.`
would extract nothing, so a page could cite an authored head in explicit commit
prose, omit the backticks, and pass `--changed-since` without the accompaniment
rule ever running.  Backticks are a convention here, not a guarantee, and
dropping them is ordinary formatting drift.

The default flips at that boundary, and deliberately.  Inside a code span the
writer has already said "this is an id" and only the KIND is open, so silence
means pin.  In open prose nothing separates an unremarked hex run from a version
string or a large decimal, so silence means NOT a citation, and arbitrary bare
hex and bare decimals stay invisible.  The narrow reading is what keeps the
whole idea affordable: it makes a handful of bare citations visible rather than
a column of noise.

A FOREIGN COMMIT IS DECLARED, NEVER INFERRED.  Some rows cite a commit of
another repository — a benchmark this programme did not write, an upstream
library — and no SHA of THIS repository belongs in them, so there is no
accompaniment to add and the rule above has nothing to say about them.  The gate
cannot guess that: "at commit `x`" reads identically whoever owns `x`.  What it
honours is a citation the writer TYPED as foreign — the displayed token beside a
canonical GitHub commit permalink, `https://github.com/<owner>/<repo>/commit/`
followed by THE SAME full forty-hex SHA, in the same row or paragraph as the
token it declares.  Same scope as accompaniment, and for the same reason: a
reader must find the declaration where the claim is made, not three screens
away.

It is the TYPING that makes this safe, and prose is deliberately no part of it.
Words — "foreign", "upstream", "belongs to another repository" — would be the
first vocabulary in this file that turns the gate OFF for a token rather than
saying what the token is, and a magic phrase is launderable by anyone who learns
it.  A permalink is not: it names a repository mechanically, it binds the FULL
SHA (an abbreviation is meaningful only inside one object database, so it is
refused), and a reader can follow it.  A token sitting beside the words "foreign
repository" and nothing else is still a finding, and that is what distinguishes
this from sniffing prose.

Two boundaries keep it from becoming an exemption mechanism.  A permalink naming
THIS repository's own origin declares nothing and takes the ordinary local path,
so a stranded local head cannot be laundered through a GitHub-shaped link;
identity comes from `git remote get-url origin`, a config read, and when there is
no GitHub origin to compare against no permalink is honoured at all — the
refusal direction again.

Both halves of that boundary are drawn POSITIVELY.  A candidate link is the
WHOLE run of URL the page wrote, matched in full against the canonical shape,
rather than a good-looking prefix followed by a lookahead listing what must not
come after it — because such a list is only as complete as its author's
imagination, and `…/commit/<sha>%3Fdiff=split` walks straight through one.  An
identity is a NORMALISED `owner/repo`, case-folded and stripped of git's `.git`
alias on both sides of the comparison, rather than the two raw strings —
because `day8/re-frame2.GIT` is this repository however unequal it looks, and
reading it as somebody else's would launder a local head.  Both put the
unanticipated case on the refusal side rather than the exemption side, which
is where everything else in this file sits.

And a foreign citation is not dropped from the
population: it is counted and listed separately under `--verbose` so that green
cannot mean silently ignored, and it may NOT stand in as the landed anchor for a
local head beside it, because it is not in this object database at all.

Nothing here reaches the network, and nothing here is a roster of blessed
repositories.  The link records an existence its author confirmed once while
writing it; CI re-reads the declaration, never the host.

WHAT THIS DOES NOT DO: it never re-pins.  Recovering the landed SHA restores
the PATCH, not the TREE — where the rebase did not preserve every blob the
commit contributed, the landed commit is not what was measured, and re-pinning
to it swaps an unresolvable pin for a resolvable but WRONG one, which fails
silently.  A checker may REPORT; a human decides.

THE REPOSITORY IS PART OF WHAT IS CHECKED.  Everything above reasons about the
corpus; none of it asks whether this checkout can answer the question being put
to it, and an unanswerable question must not be read as a clean answer.  A
`git diff` that fails leaves empty stdout, which would read as "no page
changed" — an exit 0 over changed pages while another process holds the
object store.  A clone truncated at a shallow boundary is worse still, because
nothing fails at all: `merge-base --is-ancestor` answers NO for every commit
past the boundary, so the whole corpus would read as stranded pins.  A verdict
of that kind is more dangerous than a crash, since a reader would conclude a
CORRECT fix had failed and might re-pin what was never broken.

So a non-zero exit is an ANSWER in exactly two places — `rev-parse --verify
--quiet` reporting "no such object", and `merge-base --is-ancestor` reporting
"no" — and both are parsed for the specific status that means it, never for
"not zero".  Anywhere else, a command that fails raises `Unusable` and the run
exits 2.  Silent substitution counts as failing open too: a failed merge base
does not quietly re-base the comparison on the ref itself, and a failed
`--show-toplevel` does not continue against the current directory.  Answering a different question is not a safer outcome than
answering none — it is an unsafer one, because it looks like an answer.

WHAT A RUN SAYS IT DID, on every run and not only under `--verbose`.  A gate
that inspected nothing and exited 0 is indistinguishable from one that inspected
everything and found nothing, unless it says which.  Two ways reach a vacuous
green:

  * `--changed-since` sees a NEW page only once git has been told about it.
    `git diff <base> -- <root>` reads the working tree, so an uncommitted edit to
    a page already tracked IS inspected.  What is invisible is an UNTRACKED
    file: adding pages under the corpus root and running the gate before
    staging them inspects zero files.  `git add` is enough; a commit is not
    required, and the message says so.
  * A pin that is not landed but shares its scope with one that is passes, by
    design — that is the accompaniment rule working — so a stranded SHA planted
    beside an existing citation reads exit 0, which looks like the gate not
    reaching the file.

So every run prints ONE line naming the pages it opened, the pins it
classified, how many were absorbed by an anchor rather than reported, and how
many findings it raised.  Zero pages is then a sentence rather than a silence.
`--verbose` adds the absorbed pins one per line, each beside the landed pin that
answered for it, which is the diagnostic a planted-SHA control needs.

HOW IT IS ARMED.  The blocking gate is `--changed-since`, which holds only the
pages a change touches to the rule: re-pinning is a judgement each pin needs
individually, and touching a page is the moment its provenance is cheapest to
fix.  The bare full-corpus run is the audit.

Usage:
    python scripts/check_provenance_pins.py [--root DIR] [--verbose]
    python scripts/check_provenance_pins.py --changed-since origin/main
    python scripts/check_provenance_pins.py --self-test [--verbose]

Exit codes:
    0  every cited pin is landed or shares its block — its table row, when it
       sits in one — with a landed one.  Read the inventory line beside it: a
       0 exit over 0 pages is not a verdict on anything
    1  findings — a human decides each; this tool never re-pins
    2  the check could not run — an absent corpus, an unresolvable baseline or
       ref, or a repository that cannot answer: a shallow clone, an object
       store a command could not read, no git repository at all.  Never 0 and
       never 1: a gate that cannot run must not report success for work it
       never did, and must not be mistaken for a page needing repair either.
"""

from __future__ import annotations

import argparse
import io
import os
import re
import subprocess
import sys
from typing import Dict, Iterable, List, NamedTuple, Optional, Sequence, Tuple

DEFAULT_ROOT = "docs/design/fresco"
BASELINE_REF = "origin/main"

# A hex run long enough to be a git object id.  7 is git's historical minimum
# abbreviation; 64 admits a full SHA-256.
_BARE_HEX = re.compile(r"^[0-9a-f]{7,64}$")

# Inline code spans.  This corpus writes pins and digests in a code span by
# convention, but a convention is not a guarantee — see _BARE_PROSE_HEX.
_CODE_SPAN = re.compile(r"`([^`\n]{1,200})`")

# A provenance id written WITHOUT backticks.  Extraction reading code spans only
# would yield nothing at all for `Authored at deadbeef00 on worker/x.`: a page
# could cite an authored head in completely explicit commit prose, omit the
# backticks, and sail through `--changed-since` without the accompaniment rule
# ever running.  That is ordinary formatting drift, not camouflage, and silence
# is the one direction this gate must never fail in.
#
# So bare hex is read, but ONLY when the writer's own vocabulary classifies it
# as a commit — the same nearest-word-wins machinery `classify` already runs,
# with the fail-toward-refusal default switched off.  Inside a code span the
# writer has said "this is an id" and only the KIND is in question, so silence
# there still means pin; in open prose nothing distinguishes an unremarked hex
# run from a version string or a large decimal, so silence means not a citation.
# Arbitrary bare hex and bare decimals stay invisible, which is what keeps this
# checker off the digests and off the disable list.
#
# The boundaries carry the rest of that load.  A run may not touch an
# alphanumeric, `_`, `/`, `\`, `#` or `-` on either side, and may not FOLLOW a
# `.` — which is what keeps the "0999999" inside "0.0999999 ms" out, and every
# version string with it — while a `.` AFTER it is allowed as sentence
# punctuation unless a word character follows.
_BARE_PROSE_HEX = re.compile(
    r"(?<![0-9A-Za-z._/\\#-])([0-9a-f]{7,64})(?![0-9A-Za-z_/\\-])(?!\.[0-9A-Za-z])"
)

# Separators that appear INSIDE a single span carrying more than one id:
# `a=b` (an authored=landed mapping), `sha:path` (a rev-parse argument),
# `a / b` (a row of sibling blobs).
_SPAN_SPLIT = re.compile(r"[=/:,\s]+")

# Left-context vocabulary.  These decide what the WRITER said the token is, and
# they are read no further back than the claim the token stands in — its own
# paragraph, or its own table row — so a neighbouring ROW cannot repaint it.
_DIGEST_WORDS = re.compile(
    r"blob|digest|sha-?256|fnv|checksum|hash", re.I
)
_COMMIT_WORDS = re.compile(
    # `ancestor OF`, not bare `ancestor`: the provenance usage is "is not an
    # ancestor of `main`", whereas a file has "pre-migration ancestors" and
    # that must not repaint the blob beside it as a pin.
    r"commit|authored|authoring|landed|committed|rebase|merge-base|ancestor of|"
    r"\bhead\b|pinned|\bpin\b|patch-id|rev-list|PR #|worker/|origin/main|"
    r"\bbranch\b|\bstamp",
    re.I,
)

# A code span that is WHOLLY a file path.  Whole-span, so `performance.now()`
# and `page.evaluate` do not match; alphabetic extension, so the bead ids this
# corpus is full of (`rf2-2rtt6.15`) do not either; and no extension on
# `worker/…` or `origin/main`, which must stay commit words.
_PATH_SPAN = re.compile(r"^[\w./~…*-]+\.[a-z]{2,5}$")

# This repository's object format is SHA-1, so no id here exceeds 40 hex
# characters.  Overridden from `git rev-parse --show-object-format`.
DEFAULT_MAX_ID_LEN = 40

# A CANONICAL GitHub commit permalink, and nothing looser.  This is the only
# form that declares a token foreign, so the boundary is drawn POSITIVELY: a
# candidate is the WHOLE run of URL the page wrote, and it is honoured only if
# that entire run is the canonical shape.
#
# Drawing it the other way round — match a good-looking prefix anywhere, then a
# lookahead listing the characters that must not follow it — relies on a suffix
# blacklist, which is only ever as complete as the imagination of whoever wrote
# it: `…/commit/<sha>%3Fdiff=split`, `<sha>:garbage` and `<sha>&diff=split` each
# carry a valid prefix past such a lookahead.  Whole-run matching inverts the
# default: a character nobody thought of is INSIDE the candidate, the candidate
# then fails to match in full, and the token takes the ordinary local path.  The
# unanticipated case costs a refusal instead of an exemption, which is the only
# direction this file is allowed to be wrong in.
#
# So the parts that remain load-bearing are what the run must BE, not what it
# must avoid: HTTPS and `github.com` verbatim, so `www.`, `http://` and an ssh
# remote all refuse; `/commit/`; and a FULL forty-hex lowercase SHA, because an
# abbreviation is meaningful only inside a particular object database and cannot
# carry a claim about another one.
_CANONICAL_COMMIT_URL = re.compile(
    r"https://github\.com"
    r"/([A-Za-z0-9][A-Za-z0-9._-]*)"
    r"/([A-Za-z0-9][A-Za-z0-9._-]*)"
    r"/commit/([0-9a-f]{40})"
)

# Where the run ENDS, and this is the only judgement the character set makes.
# It ends where the RENDERED link ends: whitespace, a backtick, a table pipe, an
# angle bracket, a double quote, or the `)`/`]` that closes markdown's own link
# syntax — every one of them a place a reader's link genuinely stops, and none
# of them legal in the canonical shape above, so no true permalink is cut short.
# Everything else continues the run: `%`, `?`, `#`, `&`, `:`, `=` and whatever
# somebody appends next, which is exactly why none of it can be smuggled past
# the match.
_URL_RUN = re.compile(r"https://[^\s`|<>\")\]]*")

# A renderer trims trailing sentence punctuation off an autolink, and so does
# this, so a link may end a sentence.  Only `.` and `,`: they are what this
# corpus writes, and every further character left in the run is one more thing
# the match refuses rather than forgives.
_URL_SENTENCE_PUNCTUATION = ".,"

# git's `.git` alias suffix, in ANY case.  `day8/re-frame2.GIT` and
# `day8/re-frame2` are one repository, and comparing the two spellings as
# strings would let a stranded local head be recorded as a commit of
# "day8/re-frame2.GIT" and pass as somebody else's business.
_DOT_GIT = re.compile(r"\.git$", re.I)

# This repository's own identity, read out of `origin`.  Covers the three forms
# a clone can carry it in, case-insensitively, because a remote spelled
# `git@github.com:Day8/RE-Frame2.GIT` is still this repository; anything that is
# not a GitHub remote yields no identity at all, and then no permalink is
# honoured.  The `.git` is not stripped here but in `repo_identity`, so that one
# function is the only place an identity is ever minted.
_ORIGIN_URL = re.compile(
    r"^(?:https://|ssh://git@|git@)github\.com[:/]([^/]+)/([^/]+?)/?$", re.I
)

# A provenance anchor whose author promised to fill it after the merge and did
# not.  Unambiguous, so it is reported on sight.
_UNFILLED_ANCHOR = re.compile(r"\(\s*filled on merge", re.I)

_FENCE = re.compile(r"^\s*(?:```|~~~)")

# The start of a table row, which is where one accompaniment scope ends and the
# next begins.  Deliberately only the OPENING pipe: a continuation line carries
# no pipe of its own, so it stays with the row it belongs to.
_TABLE_ROW = re.compile(r"^\s*\|")


class Citation(NamedTuple):
    path: str
    line: int
    # The scope accompaniment is judged in: a paragraph, or a single table row.
    # Not called `block` because a table is one block and many scopes.
    scope: int
    token: str
    reason: str  # why it was read as a pin rather than a digest
    # `owner/repo` when a canonical permalink in this same scope declares the
    # token a commit of ANOTHER repository.  Such a citation is neither judged
    # by the accompaniment rule nor able to satisfy it — it is not in this
    # object database at all — but it stays in the population and is reported.
    foreign: Optional[str] = None


class Finding(NamedTuple):
    path: str
    line: int
    token: str
    status: str
    detail: str


class Absorbed(NamedTuple):
    """A pin that is NOT landed and is NOT a finding, because a landed pin in
    its own scope answered for it.

    RECORDING THIS CHANGES NO VERDICT.  A stranded head beside a landed one is
    exactly what accompaniment permits — the block still leaves the reader a
    resolvable anchor, and a gate that red-flagged those pages would punish the
    careful ones.  But a run that never SAYS the absorption happened misleads:
    a stranded SHA planted in the natural place — beside an existing citation,
    where SHAs already live — reads exit 0, which looks like the gate not
    reaching the file.  It does reach it.  The plant was answered by its
    neighbour, and the neighbour is printed beside it under `--verbose` and
    counted on every run.

    It is also the number that says how much of a green run is load-bearing:
    a pass over these pages means the anchors held, not that nothing was
    stranded.
    """

    path: str
    line: int
    token: str
    status: str  # STRANDED or UNRESOLVABLE — never LANDED
    anchor: str  # the landed pin in the same scope that answered for it
    anchor_line: int


class Verdict(NamedTuple):
    """What `classify` made of one hex token.

    `spoken` records whether the WRITER's own words decided it — a vocabulary
    word, a file path, a table header — as against the fail-toward-refusal
    default.  Only the bare-prose reader consults it, and it is the whole of
    what keeps that reader narrow: outside a code span, a token nobody called a
    commit is not a citation.
    """

    is_pin: bool
    reason: str
    spoken: bool


# --------------------------------------------------------------------------
# Extraction
# --------------------------------------------------------------------------


def _split_span(inner: str, max_id_len: int) -> List[str]:
    """Yield every bare hex id inside one code span."""
    out: List[str] = []
    for part in _SPAN_SPLIT.split(inner.strip()):
        # An abbreviated id trails an ellipsis, written both as the single
        # character and as three dots; `rstrip` covers each.
        part = part.strip().strip("*").strip("()[]").rstrip(".").rstrip("…")
        if not _BARE_HEX.match(part):
            continue
        if len(part) > max_id_len:
            # Longer than any object id this repository can mint, so it is a
            # digest of something else — the rendered documents are stamped
            # with SHA-256.  This one needs no vocabulary to decide.
            continue
        out.append(part)
    return out


def _mask_code_spans(line: str) -> str:
    """The line with every code span blanked out, OFFSETS PRESERVED.

    The bare-prose reader runs over this so it cannot re-read a token the span
    reader already took, while `classify` still sees the real line — the left
    context needs the surrounding spans intact to spot the file path that marks
    a blob.
    """
    return _CODE_SPAN.sub(lambda m: " " * (m.end() - m.start()), line)


def _strip_quote(line: str) -> str:
    """Drop leading blockquote markers; the corpus writes whole blob tables
    inside `>` callouts."""
    return re.sub(r"^\s*(?:>\s?)+", "", line)


def _table_header_is_digest(lines: Sequence[str], index: int) -> bool:
    """True when the token's line sits in a table whose header names a digest.

    Walks up from the row to the nearest `|---|` delimiter and reads the line
    above it.  A `| file | blob |` table is a blob table for its whole height,
    which is what covers rows naming no file at all ("| coldmount views | `x` |").
    """
    i = index
    while i >= 0:
        line = _strip_quote(lines[i])
        if not line.strip() or not line.lstrip().startswith("|"):
            return False
        if re.match(r"^\s*\|[\s:|-]+\|\s*$", line):
            header = _strip_quote(lines[i - 1]) if i >= 1 else ""
            return bool(_DIGEST_WORDS.search(header))
        i -= 1
    return False


_LEFT_LINES = 2
_RIGHT_APPOSITION = 14


def _left_context(lines: Sequence[str], index: int, span_start: int) -> str:
    """The token's left context: everything before it on its own line, plus up
    to two whole lines above, STOPPING AT THE START OF ITS OWN TABLE ROW.

    It has to cross lines: this corpus hard-wraps at about eighty columns, so
    the word describing a token routinely sits on the line above it, and a run
    of "`file.cljs`\\n`hash`, `file.cljs`\\n`hash`" puts it two lines up.  It
    stops at a blank line, because that is a different block and a different
    claim.

    IT STOPS AT A PRIOR ROW FOR THE SAME REASON: scoping accompaniment by row
    guards adjudication, and this guards EXTRACTION.  Reading whatever the rows
    above happen to say, a "| Blob hash | … |" row one line up would repaint the
    next row's token as a digest and no citation would be created at all —
    `evaluate` would never see it, and the row scope would have nothing to
    enforce.  A digest word describes
    the cell it stands in; the row below makes its own claim.  So the context
    may reach the line that OPENS the token's row — the corpus wraps a long cell
    across source lines and the word describing the token is routinely up
    there — and it may not reach past it.

    WHOLE lines, never a character slice.  Cutting the context mid-line can cut
    a code span in half, after which the surviving backtick re-pairs with the
    wrong partner and the file path that would have identified the token as a
    blob stops being visible, so a column of blob hashes would be reported as
    unresolvable pins.
    """
    own = _strip_quote(lines[index][:span_start])
    if _TABLE_ROW.match(_strip_quote(lines[index])):
        # The token's own line opens the row, so everything above it is a
        # previous row's claim.
        return own
    parts = [own]
    i = index - 1
    taken = 0
    while taken < _LEFT_LINES and i >= 0 and lines[i].strip():
        parts.append(_strip_quote(lines[i]))
        taken += 1
        if _TABLE_ROW.match(_strip_quote(lines[i])):
            # That line opened the row this token wrapped out of; the row ends
            # here going up.
            break
        i -= 1
    return " ".join(reversed(parts))


def classify(
    lines: Sequence[str], index: int, span_start: int, span_end: int
) -> Verdict:
    """Decide whether one hex token is a cited PIN.

    Nearest signal in the left context wins; then a tight right apposition;
    then the enclosing table's header; and silence means PIN.
    """
    context = _left_context(lines, index, span_start)

    # (end offset, verdict) — the LAST signal to end is the nearest one.
    signals: List[Tuple[int, bool, str]] = []
    for m in _DIGEST_WORDS.finditer(context):
        signals.append((m.end(), False, "digest word %r nearest on the left" % m.group(0)))
    for m in _COMMIT_WORDS.finditer(context):
        signals.append((m.end(), True, "commit word %r nearest on the left" % m.group(0)))
    for m in _CODE_SPAN.finditer(context):
        if _PATH_SPAN.match(m.group(1).strip()):
            signals.append(
                (m.end(), False, "file path `%s` nearest on the left" % m.group(1).strip())
            )
    if signals:
        # Nearest wins; on a tie the PIN reading wins, which is the failure
        # direction in miniature.  "the commit hash `x`" ends both words at the
        # same column, and calling that a digest would lose a real pin
        # silently, whereas calling it a pin costs at worst one finding.
        signals.sort(key=lambda s: (s[0], s[1]))
        _, is_pin, reason = signals[-1]
        return Verdict(is_pin, reason, True)

    # The ORIGINAL line, not a quote-stripped one: `span_end` is an offset into
    # the line as read, and stripping a `> ` prefix first would slide the
    # window two characters past the apposition it exists to see.
    right = lines[index][span_end : span_end + _RIGHT_APPOSITION]
    if _DIGEST_WORDS.search(right):
        return Verdict(False, "digest word in apposition to the right", True)
    if _table_header_is_digest(lines, index):
        return Verdict(False, "table header names a digest", True)
    return Verdict(True, "unclassified — read as a pin (fail toward refusal)", False)


def repo_identity(owner: str, name: str) -> str:
    """`owner/repo`, normalised so that two spellings of one repository compare
    equal.

    Case-folded, because GitHub is case-insensitive about both halves, and with
    git's `.git` alias stripped, because git is.  This is the ONLY place an
    identity is minted, so both sides of the comparison in `scan_file` — the
    permalink's repository and this checkout's own origin — are normalised by
    construction.  Were they not, `day8/re-frame2.GIT` would compare unequal to
    `day8/re-frame2` and carry a local head off as foreign.
    """
    return "%s/%s" % (owner.lower(), _DOT_GIT.sub("", name).lower())


def github_identity(url: str) -> Optional[str]:
    """This checkout's own normalised `owner/repo`, or None when `origin` is not
    a GitHub remote."""
    match = _ORIGIN_URL.match(url.strip())
    return repo_identity(match.group(1), match.group(2)) if match else None


def commit_permalinks(line: str) -> List[Tuple[str, str]]:
    """Every canonical GitHub commit permalink on one line, as
    `(normalised owner/repo, full sha)`.

    Each candidate is the whole URL run as the page wrote it, trimmed only of
    the sentence punctuation a renderer would trim, and then matched IN FULL.  A
    query, a fragment, percent-encoded material, a deeper path or any other
    trailing matter is left inside the candidate, so the match fails and the URL
    declares nothing — it is not the canonical commit page.

    A `.git` repository is refused for the same reason, in any case: a clone URL
    with a path glued onto it is not a page a reader can open.  That refusal and
    the normalisation in `repo_identity` are two independent answers to the same
    laundering, and both are here deliberately, because what a miss costs is a
    stranded head of THIS repository reported as somebody else's business.
    """
    out: List[Tuple[str, str]] = []
    for run in _URL_RUN.finditer(line):
        match = _CANONICAL_COMMIT_URL.fullmatch(
            run.group(0).rstrip(_URL_SENTENCE_PUNCTUATION)
        )
        if match and not _DOT_GIT.search(match.group(2)):
            out.append((repo_identity(match.group(1), match.group(2)), match.group(3)))
    return out


def scan_file(
    path: str,
    text: str,
    max_id_len: int = DEFAULT_MAX_ID_LEN,
    local_repo: Optional[str] = None,
) -> Tuple[List[Citation], List[Finding]]:
    """Read one page's citations.

    `local_repo` is this repository's own identity as `github_identity` mints
    it — normalised, so it compares equal to any spelling of our own origin a
    permalink can carry.  Without it no permalink can be told from a link to
    ourselves, so none is honoured and every token takes the local path — the
    refusal direction, and the reason it is not defaulted to something
    convenient.
    """
    lines = text.splitlines()
    citations: List[Citation] = []
    anchors: List[Finding] = []
    # scope -> {full sha: owner/repo} declared by a canonical permalink there.
    declared: Dict[int, Dict[str, str]] = {}
    in_fence = False
    scope = 0
    for i, line in enumerate(lines):
        if not _strip_quote(line).strip():
            # Read RAW, this boundary could not see a paragraph break inside a
            # CALLOUT: the break is written as a lone `>`, whose `.strip()` is
            # `">"` and so never empty.  A multi-paragraph blockquote would
            # then be ONE scope from its opening line to the blank line that
            # ends the whole callout, and any single landed hash in it would
            # answer for every pin in it — the same fail-open the row boundary
            # below closes, in the one container `_strip_quote`'s own docstring
            # says this corpus uses.  Stripping first is what makes the two
            # boundaries agree about what a blockquote is.
            #
            # It can only ever SPLIT a scope and never merge two — the stripped
            # test fires everywhere the raw one would, plus on quote-marker-only
            # lines — so it cannot widen what accompaniment forgives.  The
            # counterweight is that a quoted paragraph must still be ONE scope:
            # quoting prose may not change its verdict, or this would trade a
            # fail-open for a corpus-wide fail-closed.
            scope += 1
            continue
        if _FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            # Fenced blocks are reproduction commands.  Their SHAs are
            # arguments to an example, not the page's own provenance.
            continue
        if _TABLE_ROW.match(_strip_quote(line)):
            # A row makes its own claim, so it may accompany only itself.
            # The scope opens here and runs to the next row rather
            # than to the newline, because a wrapped cell continues on lines
            # that open no pipe and the anchor is often down there.
            scope += 1
        for repo, sha in commit_permalinks(line):
            # Collected AFTER the fence test on purpose: a permalink inside a
            # reproduction command is an argument to an example, exactly as its
            # SHAs are, and must not declare anything about the page's own
            # citations.
            declared.setdefault(scope, {})[sha] = repo
        if _UNFILLED_ANCHOR.search(line):
            anchors.append(
                Finding(
                    path,
                    i + 1,
                    "",
                    "UNFILLED",
                    "provenance anchor still says '(filled on merge …)' — "
                    "it was never filled",
                )
            )
        for match in _CODE_SPAN.finditer(line):
            for token in _split_span(match.group(1), max_id_len):
                verdict = classify(lines, i, match.start(), match.end())
                if verdict.is_pin:
                    citations.append(Citation(path, i + 1, scope, token, verdict.reason))
        # Then the same line with its code spans blanked out, so a token cannot
        # be read twice, and with the default reading switched off.
        for match in _BARE_PROSE_HEX.finditer(_mask_code_spans(line)):
            token = match.group(1)
            if len(token) > max_id_len:
                continue
            verdict = classify(lines, i, match.start(), match.end())
            if verdict.is_pin and verdict.spoken:
                citations.append(
                    Citation(path, i + 1, scope, token, verdict.reason + ", uncoded")
                )

    # Only now, with every scope's declarations in hand, because the permalink
    # routinely follows the token it declares.  A declaration binds ONE exact
    # SHA, which is what bounds its blast radius to the token it names.
    if local_repo:
        for index, citation in enumerate(citations):
            repo = declared.get(citation.scope, {}).get(citation.token)
            if repo and repo != local_repo:
                citations[index] = citation._replace(foreign=repo)
    return citations, anchors


# --------------------------------------------------------------------------
# Git
# --------------------------------------------------------------------------


class Unusable(Exception):
    """The repository cannot answer the question this gate asks.

    Distinct from a finding, and it must stay distinct.  A finding is a fact
    about the corpus; this is the absence of any fact at all, and the two exit
    differently — 1 against 2 — so that a red pipeline cannot be read as a page
    needing repair when what it means is that nothing was checked.
    """


class Git:
    """Object-status oracle.  Answers exactly one question per token, and
    answers UNRESOLVABLE rather than guessing.

    A NON-ZERO EXIT IS AN ANSWER IN EXACTLY TWO PLACES HERE, and everywhere else
    it is a refusal.  `rev-parse --verify --quiet` reports "no such object"
    through its status, and `merge-base --is-ancestor` reports "no" through its
    status; git documents both, and both are parsed positively — the specific
    code that means no, never merely "not zero".  Every other call asks a
    question git can only succeed at or fail to answer, and those raise
    `Unusable`.

    The distinction matters because reading a failure as an answer turns a
    `git diff` that could not run into "no page changed", and a clone truncated
    at a shallow boundary into a corpus of stranded pins.  Both would produce a
    confident verdict from a question the repository never answered.
    """

    def __init__(self, repo: str, baseline: str = BASELINE_REF) -> None:
        self.repo = repo
        self.baseline = baseline
        self._cache: Dict[str, str] = {}

    def _run(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["git", "-C", self.repo, *args],
            capture_output=True,
            text=True,
        )

    def _answer(self, *args: str, why: str) -> str:
        """stdout of a command whose failure means the repository could not
        answer — never that the answer was no.  git's own words are carried
        into the refusal, because the operator needs them to tell a locked
        object store from a missing one."""
        result = self._run(*args)
        if result.returncode != 0:
            raise Unusable(
                "`git %s` failed (exit %d): %s\n%s"
                % (
                    " ".join(args),
                    result.returncode,
                    result.stderr.strip() or "(no message)",
                    why,
                )
            )
        return result.stdout

    def assert_usable(self) -> None:
        """Refuse a repository that cannot support a verdict, BEFORE one is formed.

        SHALLOWNESS is what forces this check to exist, and it is worse than any
        crash.  `merge-base --is-ancestor` answers NO for every commit past a
        shallow boundary, so a truncated clone does not fail — it reports a
        corpus made entirely of stranded pins.  A reader trusting that would
        conclude a CORRECT fix had failed and might "repair" pins
        that were never broken — and the header above says why that is the worst
        outcome available: re-pinning swaps an unresolvable pin for a resolvable
        WRONG one, which fails silently forever after.

        Parsed positively: "false" is the only answer that lets a run proceed.
        The probe doubles as the is-this-a-repository check, since git exits
        non-zero for it outside one.
        """
        shallow = self._answer(
            "rev-parse",
            "--is-shallow-repository",
            why="This gate decides every verdict by reading ancestry, so it "
            "cannot run outside a git repository.",
        ).strip()
        if shallow == "false":
            return
        if shallow == "true":
            raise Unusable(
                "this is a SHALLOW clone. `merge-base --is-ancestor` answers NO "
                "for every commit past the shallow boundary, so every pin here "
                "would read as stranded and the verdict would be fiction rather "
                "than a finding.\nRun `git fetch --unshallow --no-tags origin "
                "main`. (CI checks out with fetch-depth: 0 and is unaffected.)"
            )
        raise Unusable(
            "`git rev-parse --is-shallow-repository` answered %r, which is "
            "neither \"true\" nor \"false\", so whether ancestry can be trusted "
            "in this checkout is unknown." % shallow
        )

    def baseline_exists(self) -> bool:
        # Deliberately tolerant: the exit status IS the question, and the one
        # caller turns False into a refusal, so a failure of any kind here
        # already lands on rc=2.
        return self._run("rev-parse", "--verify", "--quiet", self.baseline).returncode == 0

    def rev_exists(self, ref: str) -> bool:
        # Tolerant for the same reason, and fail-closed for the same reason:
        # False is a refusal at the only call site.
        return self._run("rev-parse", "--verify", "--quiet", ref).returncode == 0

    def changed_markdown(self, since: str, root: str) -> set:
        """Corpus pages this branch touches, against the merge base with `since`
        so that commits landing on the baseline meanwhile are not attributed
        here.

        BOTH COMMANDS MUST SUCCEED.  A failing `git diff` leaves stdout empty,
        the empty set would read as "no page under the corpus root changed",
        and the driver would exit 0 having verified nothing — which is what a
        `git diff` does while another process holds this repository.

        THERE IS NO MERGE-BASE FALLBACK.  Comparing against the baseline itself
        would answer a DIFFERENT question — "what differs from the baseline"
        instead of "what this branch changed" — and answer it silently, which
        is the same defect in a quieter register.  Nor is there a case it could
        serve: `since` has already been proved to resolve by the time this
        runs, so a failure here means the two histories share no ancestor or
        the repository cannot be read, and neither of those is a set of pages
        to check.  A substituted question is not a safer answer than none; it
        is an unsafer one, because it looks like an answer.
        """
        base = self._answer(
            "merge-base",
            since,
            "HEAD",
            why="Without a merge base between HEAD and %r there is no basis to "
            "compare against, so the set of pages to check is unknown." % since,
        ).strip()
        if not base:
            raise Unusable(
                "`git merge-base %s HEAD` succeeded but named no commit, so the "
                "basis to compare against is unknown." % since
            )
        diff = self._answer(
            "diff",
            "--name-only",
            "--diff-filter=d",
            base,
            "--",
            root,
            why="The set of pages this branch changed is therefore unknown. An "
            "empty answer here is indistinguishable from 'nothing changed', "
            "so it is refused rather than read as one.",
        )
        return {
            line.strip()
            for line in diff.splitlines()
            if line.strip().endswith(".md")
        }

    def origin_repo(self) -> Optional[str]:
        """This repository's own `owner/repo`, from `origin`.

        A config read, so it stays offline and needs no allowlist: the only
        repository this gate has an opinion about is itself, and it holds a
        permalink naming itself to the ordinary local path.  None when there is
        no GitHub origin, and then no permalink is honoured at all.

        THE ONE TOLERANT RETURN CODE IN THIS CLASS, and it is tolerant
        because it is provably fail-closed rather than because failure is
        unlikely.  Losing the identity honours NO permalink, which turns every
        declared-foreign citation back into an ordinary local one; that can only
        ADD findings, never remove one.  A failure here cannot manufacture a
        green run, so refusing on it would trade a safe outcome for a noisier
        one.  It is also all but unreachable: a checkout with no `origin` has no
        `origin/main` either, and the baseline guard refuses first.
        """
        result = self._run("remote", "get-url", "origin")
        if result.returncode != 0:
            return None
        return github_identity(result.stdout)

    def max_id_len(self) -> int:
        """Hex length of a full object id here — 40 for SHA-1, 64 for SHA-256.
        Anything longer in the corpus is a digest of something that is not a
        git object, and needs no vocabulary to be excluded.

        Read positively, because that exclusion rests on the answer being real.
        An unanswered probe falling through to 40 would, in a SHA-256
        repository, silently drop every full-length citation in the corpus
        from the population — a fail-open of exactly the shape above, arriving
        as a shorter list rather than as an error.
        """
        fmt = self._answer(
            "rev-parse",
            "--show-object-format",
            why="The object format decides which hex runs can be object ids "
            "here at all, so the population cannot be settled without it.",
        ).strip()
        if fmt == "sha256":
            return 64
        if fmt == "sha1":
            return DEFAULT_MAX_ID_LEN
        raise Unusable(
            "`git rev-parse --show-object-format` answered %r, which is neither "
            "\"sha1\" nor \"sha256\", so the longest hex run that could be an "
            "object id here is unknown." % fmt
        )

    def status(self, token: str) -> str:
        """LANDED | STRANDED | UNRESOLVABLE.

        Both exit statuses read here are ANSWERS, and both are parsed
        positively so that a failure cannot pass for one.  `rev-parse --verify
        --quiet` exits 1 for "no such object"; `merge-base --is-ancestor` exits
        0 for yes and 1 for no, and git documents that "errors are signaled by a
        non-zero status that is not 1".  Anything else from either is the
        repository failing to answer.

        Filing such a token as STRANDED — which is what reading "not zero"
        would do — would be a finding invented out of a failed process, and on
        a shallow clone that is every pin in the corpus.
        """
        if token in self._cache:
            return self._cache[token]
        resolved = self._run("rev-parse", "--verify", "--quiet", token + "^{commit}")
        if resolved.returncode == 1:
            value = "UNRESOLVABLE"
        elif resolved.returncode != 0:
            raise Unusable(
                "`git rev-parse --verify --quiet %s^{commit}` failed (exit %d): "
                "%s\nThat is neither a commit nor 'no such object', so whether "
                "this token is a pin at all is unknown."
                % (token, resolved.returncode, resolved.stderr.strip() or "(no message)")
            )
        else:
            ancestry = self._run("merge-base", "--is-ancestor", token, self.baseline)
            if ancestry.returncode == 0:
                value = "LANDED"
            elif ancestry.returncode == 1:
                value = "STRANDED"
            else:
                raise Unusable(
                    "`git merge-base --is-ancestor %s %s` failed (exit %d): %s\n"
                    "git signals errors here with a status that is not 1, so "
                    "this is not the answer 'no' — whether %s landed is unknown."
                    % (
                        token,
                        self.baseline,
                        ancestry.returncode,
                        ancestry.stderr.strip() or "(no message)",
                        token,
                    )
                )
        self._cache[token] = value
        return value


# --------------------------------------------------------------------------
# The rule
# --------------------------------------------------------------------------


def evaluate(citations: Iterable[Citation], git: Git) -> Tuple[List[Finding], List[Absorbed]]:
    """Apply the accompaniment rule, one scope at a time.

    Returns what it OBJECTED to and what it PASSED OVER — the findings, and the
    pins that were not landed but were answered by an anchor in their own scope.
    The second half reports only; it changes no verdict.  A rule that silently
    forgives is indistinguishable from a rule that never ran.

    A scope is a prose paragraph, or a SINGLE TABLE ROW — not the whole table,
    and not the whole blockquote: a paragraph inside a callout is a paragraph,
    and quoting one does not merge it into its neighbours.  Those are the two
    shapes this corpus writes provenance in, and they are the scope in which a
    reader actually finds the fallback: a repaired pin puts the landed SHA in
    the same table cell or the same sentence as the head it rescues, never
    merely somewhere in the same table.

    Scoping the table by row matters because a table is one unbroken run of
    non-blank lines, so judging it whole would let any single landed hash
    answer for every row around it, and a wrong pin in the operative row would
    go unreported — on the pages this guards, that row is the one that matters
    most.  A row accompanies itself and nothing else.
    """
    per_scope: Dict[Tuple[str, int], List[Citation]] = {}
    for c in citations:
        if c.foreign:
            # A commit of another repository, declared by a canonical permalink
            # in this same scope.  There is no local anchor to add for it, so
            # the rule has nothing to say — and it may not answer for a local
            # head beside it either: a reader following it lands in a different
            # object database, which is no anchor for this tree at all.
            continue
        per_scope.setdefault((c.path, c.scope), []).append(c)

    findings: List[Finding] = []
    absorbed: List[Absorbed] = []
    for (_path, _scope), group in sorted(per_scope.items()):
        statuses = {c.token: git.status(c.token) for c in group}
        landed = [c for c in group if statuses[c.token] == "LANDED"]
        if landed:
            # The anchor a reader meets FIRST, so the line number printed beside
            # an absorbed pin is one they can go and look at.
            anchor = min(landed, key=lambda c: (c.line, c.token))
            for c in group:
                if statuses[c.token] != "LANDED":
                    absorbed.append(
                        Absorbed(
                            c.path,
                            c.line,
                            c.token,
                            statuses[c.token],
                            anchor.token,
                            anchor.line,
                        )
                    )
            continue
        for c in group:
            status = statuses[c.token]
            if status == "LANDED":
                continue
            if status == "STRANDED":
                detail = (
                    "authored head — resolves here but is an ancestor of no "
                    "remote ref, so it is absent from a fresh clone"
                )
            else:
                detail = (
                    "resolves to no commit in this checkout — either an "
                    "authored head this clone never had, or a token read as a "
                    "pin because nothing on its line said otherwise (%s)"
                    % c.reason
                )
            findings.append(Finding(c.path, c.line, c.token, status, detail))
    return findings, absorbed


# --------------------------------------------------------------------------
# Driver
# --------------------------------------------------------------------------


def iter_markdown(root: str) -> List[str]:
    out: List[str] = []
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in sorted(filenames):
            if name.endswith(".md"):
                out.append(os.path.join(dirpath, name).replace(os.sep, "/"))
    return sorted(out)


def _plural(count: int, noun: str) -> str:
    return "%d %s%s" % (count, noun, "" if count == 1 else "s")


def _inventory(
    stream,
    files: Sequence[str],
    citations: Sequence[Citation],
    absorbed: Sequence[Absorbed],
    findings: Sequence[Finding],
    git: Git,
) -> None:
    """One line, on EVERY run, naming what this run actually opened.

    Not `--verbose`-gated, and that is the point of it.  Both scheduled callers
    — the fast-PR spine and docs.yml — pass `--verbose`, so the reader a
    silent success would fool is someone running the command by hand, without
    the flag, and reading a silent exit 0 as a verdict.  A summary nobody sees
    in the mode people use is not a summary.

    One line and not a table, because this gate walks ONE root: the sibling
    `check_doc_slugs.py` prints a per-root inventory precisely because it has
    many roots to drop one out of, and copying its shape here would print a
    wall to say a single number.  The counts are the reach.
    """
    counts: Dict[str, int] = {}
    foreign = 0
    for c in citations:
        if c.foreign:
            foreign += 1
            continue
        status = git.status(c.token)
        counts[status] = counts.get(status, 0) + 1
    stream.write(
        "check_provenance_pins: %s inspected, %s — %d landed, %d stranded, "
        "%d unresolvable, %d foreign; %d accompanied in scope; %s.\n"
        % (
            _plural(len(files), "page"),
            _plural(len(citations), "cited pin"),
            counts.get("LANDED", 0),
            counts.get("STRANDED", 0),
            counts.get("UNRESOLVABLE", 0),
            foreign,
            len(absorbed),
            _plural(len(findings), "finding"),
        )
    )


def _refuse(stream, exc: Unusable) -> int:
    """One shape for every refusal, and one exit code for all of them: 2, never
    1 and never 0.  A findings-failure says the corpus is wrong; this says the
    check did not run, and the two must never be confused for one another."""
    stream.write("check_provenance_pins: %s\n" % exc)
    return 2


def check(
    repo: str,
    root: str,
    verbose: bool,
    stream,
    changed_since: Optional[str] = None,
    git: Optional[Git] = None,
) -> int:
    """Drive the corpus and return an exit code.

    The oracle is a parameter — as it is for `evaluate` — so the
    self-test can present the repository states this gate must refuse without
    staging one on disk: an object store another process is holding, and a
    clone truncated at a shallow boundary.  Neither can be produced by a fixture
    on the filesystem, and both are the reason this function has a refusal path
    at all.
    """
    try:
        return _check(repo, root, verbose, stream, changed_since, git or Git(repo))
    except Unusable as exc:
        return _refuse(stream, exc)


def _check(
    repo: str,
    root: str,
    verbose: bool,
    stream,
    changed_since: Optional[str],
    git: Git,
) -> int:
    root_abs = os.path.join(repo, root)
    if not os.path.isdir(root_abs):
        stream.write(
            "check_provenance_pins: corpus root %r does not exist — refusing to "
            "report success for work not done.\n" % root
        )
        return 2

    files = iter_markdown(root_abs)
    if not files:
        stream.write(
            "check_provenance_pins: no markdown under %r — refusing to report "
            "success for work not done.\n" % root
        )
        return 2

    # Before any verdict is formed: can this repository support one?  Every
    # answer below is ancestry, and a truncated clone gets ancestry WRONG rather
    # than refusing it, so there is no later failure to notice.
    git.assert_usable()

    if changed_since is not None:
        if not git.rev_exists(changed_since):
            stream.write(
                "check_provenance_pins: --changed-since %r does not resolve, so "
                "the set of pages to check is unknown. Refusing.\n" % changed_since
            )
            return 2
        touched = git.changed_markdown(changed_since, root)
        files = [f for f in files if os.path.relpath(f, repo).replace(os.sep, "/") in touched]
        if not files:
            # UNCONDITIONAL, and the most important thing this script prints.
            # Exit 0 here says nothing about the corpus; it says the corpus was
            # never opened, and silence would make the two identical.
            #
            # The remedy names STAGING rather than committing, because
            # `git diff <base> -- <root>` reads the working
            # tree, so an edit to a page git already tracks is inspected without
            # any commit at all.  Only a file git has never been told about is
            # invisible, and `git add` alone is enough to end that.
            stream.write(
                "check_provenance_pins: 0 pages inspected — no page under %s "
                "changed since %s, so NOTHING was checked and this exit 0 is "
                "not a verdict on the corpus.\n"
                "  If you expected pages here: a file git has not been told "
                "about yet is invisible to `--changed-since`. `git add` it (a "
                "commit is not needed) and run again. Edits to already-tracked "
                "pages are read from the working tree and need neither.\n"
                % (root, changed_since)
            )
            return 0

    if not git.baseline_exists():
        stream.write(
            "check_provenance_pins: %r does not resolve. The accompaniment rule "
            "is defined against it, so without it every verdict would be "
            "vacuous. Run `git fetch origin main` (CI needs fetch-depth: 0).\n"
            % BASELINE_REF
        )
        return 2

    max_id_len = git.max_id_len()
    local_repo = git.origin_repo()
    citations: List[Citation] = []
    findings: List[Finding] = []
    for path in files:
        with io.open(path, encoding="utf-8") as handle:
            text = handle.read()
        rel = os.path.relpath(path, repo).replace(os.sep, "/")
        cites, anchors = scan_file(rel, text, max_id_len, local_repo)
        citations.extend(cites)
        findings.extend(anchors)

    rule_findings, absorbed = evaluate(citations, git)
    findings.extend(rule_findings)
    findings.sort(key=lambda f: (f.path, f.line, f.token))

    _inventory(stream, files, citations, absorbed, findings, git)

    if verbose:
        # The two classes where a green run could otherwise mean "quietly
        # forgiven", listed rather than merely subtracted.  An ABSORBED pin is
        # the one a planted control lands on: naming the anchor that answered
        # for it is what tells a worker their plant was neutralised by its
        # neighbour rather than missed by the gate.
        for a in sorted(absorbed):
            stream.write(
                "  accompanied: %s:%d  %s [%s] — not a finding: `%s` on line %d "
                "is in the same block and is an ancestor of %s\n"
                % (a.path, a.line, a.token, a.status, a.anchor, a.anchor_line, BASELINE_REF)
            )
        for c in sorted(c for c in citations if c.foreign):
            stream.write(
                "  foreign: %s:%d  %s declared a commit of %s\n"
                % (c.path, c.line, c.token, c.foreign)
            )

    if not findings:
        return 0

    stream.write(
        "\ncheck_provenance_pins: %d finding(s). Every cited authored head must "
        "be accompanied, in its own block — its own table ROW, when it sits in "
        "a table — by a SHA that is an ancestor of %s.\n"
        "This tool does NOT re-pin: recovering the landed SHA restores the patch "
        "and not necessarily the measured tree, so a human decides each "
        "one.\n\n" % (len(findings), BASELINE_REF)
    )
    for f in findings:
        label = ("%s " % f.token) if f.token else ""
        stream.write("  %s:%d  %s[%s]\n      %s\n" % (f.path, f.line, label, f.status, f.detail))
    stream.write("\n")
    return 1


# --------------------------------------------------------------------------
# Self-test
# --------------------------------------------------------------------------

# (label, lines, expected pin tokens)
_EXTRACTION_CASES: List[Tuple[str, List[str], List[str]]] = [
    (
        "blob table row keyed by a path is not a pin",
        ["| `lane.cljs` | `0642815dc234c1544d1f97bd9e1e4dd24365c027` |"],
        [],
    ),
    (
        "a non-digest table header leaves its rows as pins",
        [
            "| arm | anchor |",
            "|---|---|",
            "| narrow write | `0cba8181a7` |",
        ],
        ["0cba8181a7"],
    ),
    # No vocabulary either way, so both default to pins (fail toward refusal).
    (
        "authored=landed mapping yields both ids",
        ["The recovery table reads `0cf86fb580=24e8822d7f` for this page."],
        ["0cf86fb580", "24e8822d7f"],
    ),
    (
        "bare prose hex the writer calls a commit is a citation",
        ["Commit 08344cb500 was measured on one box."],
        ["08344cb500"],
    ),
    (
        "bare hex nobody calls a commit is not a citation",
        ["The bulk row settled at 5f2c8a1b3d across all ten turns."],
        [],
    ),
    (
        "a bare digest and a bare number are not citations",
        ["The instrument blob 0304f489bb held at 0.0999999 ms across ten turns."],
        [],
    ),
    # "commit" is right there, so only the `.` in front of `0999999` keeps it out.
    (
        "a bare decimal beside a commit word is not a citation",
        ["The commit's ten-turn aggregate resolved at 0.0999999 ms on the bulk row."],
        [],
    ),
    (
        "abbreviated id with an ellipsis still reads as one id",
        ["Producing commit `0642815dc2…` for the spine."],
        ["0642815dc2"],
    ),
    # The right-apposition window is measured on the raw line, `> ` included.
    (
        "right apposition survives a blockquote prefix",
        ["> The instrument is `a1d7005d74` blob for the run."],
        [],
    ),
    (
        "a blockquoted blob table is still a blob table",
        [
            "> | file | blob |",
            "> |---|---|",
            "> | coldmount views | `335a37bb09233121e83ca2dc9f6a0a9ef88e037c` |",
        ],
        [],
    ),
    # The left context stops at the start of the token's own row...
    (
        "a digest word in the row above does not repaint the next row",
        [
            "| Field | Value |",
            "|---|---|",
            "| Blob hash | `bbbbbbbbbb` |",
            "| Original freeze | `aaaaaaaaaa` |",
        ],
        ["aaaaaaaaaa"],
    ),
    # ...and still reaches the line that opened it when the cell wraps.
    (
        "a wrapped cell still reads the word that opened its own row",
        [
            "| Instrument blob | `0304f489bb`, and the follow-up",
            "`0642815dc2` for the same lane |",
        ],
        [],
    ),
]

# (label, lines, {token: status}, expected finding tokens)
_RULE_CASES: List[Tuple[str, List[str], Dict[str, str], List[str]]] = [
    # A row is its own scope, and a wrapped cell's continuation stays in it.
    (
        "a cell wrapped across source lines is still one row",
        [
            "| Producing commit | `aaaaaaaaaa` on `worker/x` — authored, and",
            "rebase-merged. It landed on main as **`bbbbbbbbbb`**. |",
            "| Orphan row | `cccccccccc` |",
        ],
        {"aaaaaaaaaa": "STRANDED", "bbbbbbbbbb": "LANDED", "cccccccccc": "STRANDED"},
        ["cccccccccc"],
    ),
    # Inside a callout a lone `>` is a paragraph break, and so a scope boundary.
    (
        "a landed hash in a SIBLING BLOCKQUOTE PARAGRAPH does not rescue the head",
        [
            "> Authored at `aaaaaaaaaa` on `worker/x`, before the rebase.",
            ">",
            "> A separate note: the roster closed at commit `bbbbbbbbbb`.",
        ],
        {"aaaaaaaaaa": "STRANDED", "bbbbbbbbbb": "LANDED"},
        ["aaaaaaaaaa"],
    ),
    (
        "accompaniment inside one blockquote paragraph still passes",
        [
            "> Authored at `aaaaaaaaaa` on `worker/x`, before the rebase; the",
            "> same patch landed on main as `bbbbbbbbbb`.",
        ],
        {"aaaaaaaaaa": "STRANDED", "bbbbbbbbbb": "LANDED"},
        [],
    ),
    (
        "a landed hash in a sibling row does not rescue it inside a callout",
        [
            "> | Original freeze | `bbbbbbbbbb`, registering all seven criteria |",
            "> | Pre-registration commit | `aaaaaaaaaa` — this is the hash to cite |",
        ],
        {"aaaaaaaaaa": "STRANDED", "bbbbbbbbbb": "LANDED"},
        ["aaaaaaaaaa"],
    ),
]


# --------------------------------------------------------------------------
# The foreign-citation witnesses
# --------------------------------------------------------------------------

_UPSTREAM = "krausest/js-framework-benchmark"
_HERE = "day8/re-frame2"
_FOREIGN_SHA = "247fafa22c1f2caeb4cad179aa64cf444398cbc7"
_LOCAL_SHA = "19a3710bc9604684ddbc7b2b72ec901dcc0f0ea7"
_PERMALINK = "https://github.com/%s/commit/%s"


class _ForeignCase(NamedTuple):
    """One witness, asserted at extraction, declaration and adjudication at
    once: "no finding" alone would also hold if the token had been dropped
    from the population altogether."""

    label: str
    lines: List[str]
    local: Optional[str]  # this repository's own origin identity
    status: Dict[str, str]  # what git says about each token
    pins: List[str]  # tokens extracted as citations, in order
    foreign: Dict[str, str]  # of those, the ones carrying a repository identity
    findings: List[str]  # what the accompaniment rule then reports


_FOREIGN_CASES: List[_ForeignCase] = [
    _ForeignCase(
        "a permalink may close a sentence, and may be a markdown link",
        [
            "| Benchmark revision | at commit **`%s`**, canonically at %s. |"
            % (_FOREIGN_SHA, _PERMALINK % (_UPSTREAM, _FOREIGN_SHA)),
            "| Restated | at commit **`%s`**, [the commit page](%s) |"
            % (_FOREIGN_SHA, _PERMALINK % (_UPSTREAM, _FOREIGN_SHA)),
        ],
        _HERE,
        {},
        [_FOREIGN_SHA, _FOREIGN_SHA],
        {_FOREIGN_SHA: _UPSTREAM},
        [],
    ),
    # The permalink binds the FULL sha; an abbreviation takes the local path.
    _ForeignCase(
        "an abbreviated token is not what the permalink bound",
        [
            "| Benchmark revision | at commit **`%s`**, canonically at %s |"
            % (_FOREIGN_SHA[:10], _PERMALINK % (_UPSTREAM, _FOREIGN_SHA)),
        ],
        _HERE,
        {},
        [_FOREIGN_SHA[:10]],
        {},
        [_FOREIGN_SHA[:10]],
    ),
    # The whole URL run must be canonical, so trailing material fails it rather
    # than being trimmed to a passing prefix; a `.git` repository is refused.
    _ForeignCase(
        "a decorated or `.GIT` permalink declares nothing",
        [
            "| A | at commit **`%s`**, at %s%%3Fdiff=split |"
            % (_FOREIGN_SHA, _PERMALINK % (_UPSTREAM, _FOREIGN_SHA)),
            "| B | at commit **`%s`**, at https://github.com/%s.GIT/commit/%s |"
            % (_FOREIGN_SHA, _UPSTREAM, _FOREIGN_SHA),
        ],
        _HERE,
        {},
        [_FOREIGN_SHA, _FOREIGN_SHA],
        {},
        [_FOREIGN_SHA, _FOREIGN_SHA],
    ),
    # Case-insensitively, or a stranded local head launders through github.com.
    _ForeignCase(
        "a permalink to our own origin takes the ordinary local path",
        [
            "| Authoring anchor | `%s` on `worker/x`, at %s |"
            % (_LOCAL_SHA, _PERMALINK % ("Day8/RE-Frame2", _LOCAL_SHA)),
        ],
        _HERE,
        {_LOCAL_SHA: "STRANDED"},
        [_LOCAL_SHA],
        {},
        [_LOCAL_SHA],
    ),
    _ForeignCase(
        "a declaration in a SIBLING ROW does not reach the row beside it",
        [
            "| Benchmark revision | canonically at %s |"
            % (_PERMALINK % (_UPSTREAM, _FOREIGN_SHA)),
            "| Restated | at commit **`%s`** |" % _FOREIGN_SHA,
        ],
        _HERE,
        {},
        [_FOREIGN_SHA],
        {},
        [_FOREIGN_SHA],
    ),
    _ForeignCase(
        "a permalink inside a fenced block declares nothing",
        [
            "| Benchmark revision | at commit **`%s`** |" % _FOREIGN_SHA,
            "```bash",
            "git fetch %s" % (_PERMALINK % (_UPSTREAM, _FOREIGN_SHA)),
            "```",
        ],
        _HERE,
        {},
        [_FOREIGN_SHA],
        {},
        [_FOREIGN_SHA],
    ),
    _ForeignCase(
        "with no GitHub origin to compare against, no permalink is honoured",
        [
            "| Benchmark revision | at commit **`%s`**, canonically at %s |"
            % (_FOREIGN_SHA, _PERMALINK % (_UPSTREAM, _FOREIGN_SHA)),
        ],
        None,
        {},
        [_FOREIGN_SHA],
        {},
        [_FOREIGN_SHA],
    ),
]


class _FakeGit(Git):
    def __init__(self, table: Dict[str, str]) -> None:  # noqa: D107
        self.table = table
        self._cache = {}

    def status(self, token: str) -> str:  # noqa: D102
        return self.table.get(token, "UNRESOLVABLE")


class _BusyGit(Git):
    """A repository where one subcommand fails, as `git diff` does while
    another process holds the object store — and only it: the guards upstream
    of it all pass."""

    def __init__(self, repo: str, failing: str) -> None:  # noqa: D107
        Git.__init__(self, repo)
        self.failing = failing

    def _run(self, *args: str) -> subprocess.CompletedProcess:  # noqa: D102
        if args and args[0] == self.failing:
            return subprocess.CompletedProcess(
                args, 128, "", "fatal: Unable to create 'index.lock': File exists.\n"
            )
        return Git._run(self, *args)


class _ChangedGit(Git):
    """A repository that reports an exact set of changed pages.  `HEAD` is the
    baseline so the run gets as far as its inventory without `origin/main`."""

    def __init__(self, repo: str, touched: Iterable[str]) -> None:  # noqa: D107
        Git.__init__(self, repo, "HEAD")
        self.touched = set(touched)

    def changed_markdown(self, since: str, root: str) -> set:  # noqa: D102
        return set(self.touched)


class _ShallowGit(Git):
    """A clone truncated at a shallow boundary."""

    def _run(self, *args: str) -> subprocess.CompletedProcess:  # noqa: D102
        if args[:2] == ("rev-parse", "--is-shallow-repository"):
            return subprocess.CompletedProcess(args, 0, "true\n", "")
        return Git._run(self, *args)


def self_test(verbose: bool, stream) -> int:
    results: List[Tuple[str, object, object]] = []  # (label, got, expected)

    for label, lines, expected in _EXTRACTION_CASES:
        cites, _ = scan_file("fixture.md", "\n".join(lines))
        results.append(("extraction [%s]" % label, [c.token for c in cites], expected))

    for label, lines, table, expected in _RULE_CASES:
        cites, _ = scan_file("fixture.md", "\n".join(lines))
        got = sorted(f.token for f in evaluate(cites, _FakeGit(table))[0])
        results.append(("rule [%s]" % label, got, sorted(expected)))

    # What the rule forgave is reported, beside its anchor, and is not a finding.
    cites, _ = scan_file(
        "fixture.md",
        "Authored at `cccccccccc`; the same patch landed on main as `bbbbbbbbbb`.",
    )
    rule_findings, absorbed = evaluate(cites, _FakeGit({"bbbbbbbbbb": "LANDED"}))
    results.append((
        "an unresolvable token accompanied in-block is reported as absorbed",
        (rule_findings, [(a.token, a.status, a.anchor) for a in absorbed]),
        ([], [("cccccccccc", "UNRESOLVABLE", "bbbbbbbbbb")]),
    ))

    for case in _FOREIGN_CASES:
        cites, _ = scan_file(
            "fixture.md", "\n".join(case.lines), DEFAULT_MAX_ID_LEN, case.local
        )
        got_findings = sorted(f.token for f in evaluate(cites, _FakeGit(case.status))[0])
        results.append((
            "foreign [%s]" % case.label,
            ([c.token for c in cites], {c.token: c.foreign for c in cites if c.foreign},
             got_findings),
            (case.pins, case.foreign, sorted(case.findings)),
        ))

    # The origin side is normalised by the same function as the permalink side.
    for url, expected in (
        ("git@GitHub.com:Day8/RE-Frame2.GIT", _HERE),
        ("/srv/mirrors/re-frame2.git", None),
    ):
        results.append(("origin identity of %r" % url, github_identity(url), expected))

    _, anchors = scan_file(
        "fixture.md",
        "| Landed anchor | *(filled on merge — a rebase mints a new SHA)* |",
    )
    results.append(
        ("unfilled anchor is a finding", [a.status for a in anchors], ["UNFILLED"])
    )

    # Every way this gate can fail to do its job exits 2, never 0 or 1.  The
    # repository states go through the oracle; `HEAD` as the ref keeps them
    # from passing on the baseline guard in a checkout with no `origin/main`.
    repo = _repo_root()
    for label, kwargs, root in (
        ("absent corpus root", {}, "no/such/corpus/root"),
        (
            "unresolvable --changed-since ref",
            {"changed_since": "refs/heads/no-such-ref-rf2-kqac1"},
            DEFAULT_ROOT,
        ),
        (
            "a `git diff` that could not run",
            {"changed_since": "HEAD", "git": _BusyGit(repo, "diff")},
            DEFAULT_ROOT,
        ),
        (
            "no merge base to compare --changed-since against",
            {"changed_since": "HEAD", "git": _BusyGit(repo, "merge-base")},
            DEFAULT_ROOT,
        ),
        ("a shallow clone", {"git": _ShallowGit(repo)}, DEFAULT_ROOT),
    ):
        results.append(
            ("%s refuses" % label, check(repo, root, False, _DevNull(), **kwargs), 2)
        )

    # What a run says it did, WITHOUT --verbose: the mode a hand-run uses.
    corpus = iter_markdown(os.path.join(repo, DEFAULT_ROOT))
    for label, touched, wanted in (
        (
            "a run that inspected nothing says so",
            [],
            ("0 pages inspected", "NOTHING was checked", "git add"),
        ),
        (
            "a run that inspected a page says how much it found",
            corpus[:1],
            ("check_provenance_pins: 1 page inspected,", "cited pin"),
        ),
    ):
        relative = [os.path.relpath(p, repo).replace(os.sep, "/") for p in touched]
        capture = _Capture()
        check(
            repo,
            DEFAULT_ROOT,
            False,
            capture,
            changed_since="HEAD",
            git=_ChangedGit(repo, relative),
        )
        said = capture.text()
        missing = [w for w in wanted if w not in said]
        results.append(("%s (it said %r)" % (label, said) if missing else label, missing, []))

    failures = 0
    for label, got, expected in results:
        if got != expected:
            failures += 1
            stream.write(
                "self-test FAIL: %s expected %r, got %r\n" % (label, expected, got)
            )
        elif verbose:
            stream.write("self-test PASS: %s\n" % label)
    if failures:
        stream.write("\n%d self-test failure(s).\n" % failures)
        return 1
    if verbose:
        stream.write("all %d self-tests passed.\n" % len(results))
    return 0


class _DevNull:
    def write(self, *_args, **_kwargs) -> int:
        return 0

    def flush(self) -> None:
        return None


class _Capture(_DevNull):
    """What a run said, so the self-test can assert on the text."""

    def __init__(self) -> None:  # noqa: D107
        self.parts: List[str] = []

    def write(self, text: str = "", *_args, **_kwargs) -> int:  # noqa: D102
        self.parts.append(text)
        return len(text)

    def text(self) -> str:  # noqa: D102
        return "".join(self.parts)


def _repo_root() -> str:
    """The working tree this run is about.

    There is no fallback to the current directory: it would be the same silent
    substitution a merge-base fallback would be.  When git cannot say where the
    repository is, such a run would continue against somewhere else and report
    whatever it found there — at best rc=2 by way of a missing corpus root,
    with a message about the corpus when the truth is about the repository,
    and a diagnostic that names the wrong thing is how the next fail-open gets
    missed.
    """
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True
    )
    if result.returncode != 0 or not result.stdout.strip():
        raise Unusable(
            "`git rev-parse --show-toplevel` failed (exit %d): %s\nThis gate "
            "reads ancestry from the repository it checks, so it must be run "
            "from inside that working tree." % (result.returncode, result.stderr.strip() or "(no message)")
        )
    return result.stdout.strip()


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=DEFAULT_ROOT, help="corpus root, repo-relative")
    parser.add_argument(
        "--changed-since",
        metavar="REF",
        help="check only corpus pages this branch touches against REF",
    )
    parser.add_argument("--self-test", action="store_true", dest="self_test")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv)

    # Findings carry em dashes and ellipses straight out of the corpus; a
    # Windows console defaults to cp1252 and would raise instead of reporting.
    stream = io.TextIOWrapper(
        sys.stderr.buffer, encoding="utf-8", errors="replace", line_buffering=True
    )

    try:
        if args.self_test:
            return self_test(args.verbose, stream)
        return check(_repo_root(), args.root, args.verbose, stream, args.changed_since)
    except Unusable as exc:
        # `check` catches its own so that the self-test can assert rc=2 by
        # calling it; this catches the one raised before there is a repo to
        # hand it, and gives it the identical shape and code.
        return _refuse(stream, exc)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
