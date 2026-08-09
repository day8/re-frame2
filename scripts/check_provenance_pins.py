#!/usr/bin/env python3
"""Every cited AUTHORED head in the Hicasso evidence corpus must be accompanied
by a RESOLVABLE LANDED SHA.

This repo rebase-merges, which mints a new SHA for every commit on the branch.
A page that pins a measurement to the SHA it was authored at is therefore
stranded the moment its own PR merges: the authored object is reachable from no
ref, so it is not in a fresh clone at all, and the page's central claim — here
is the tree this was measured on — fails without saying so.  It still looks
pinned.  The census behind this gate (rf2-owq6p, PR #7616) found 44 such pins
over 79 occurrences on 24 pages, every one of them unreachable from any remote
ref.

WHAT IS ENFORCED, and deliberately not the stricter thing.  The rule is
accompaniment, NOT "no authored heads" and NOT "every SHA must be on main".
Pages legitimately retain the authored head — it is the true provenance of the
run, and a blob table beside it pins the instrument when the anchor cannot be
pinned.  A gate that red-flagged those pages would punish the careful ones and
be routed around within a week.  So a block may cite any number of stranded
heads; what it may not do is leave the reader with no resolvable anchor at all.
Concretely: within one block, if any cited pin is not an ancestor of
`origin/main`, then some other pin in that same block must be.

A TABLE ROW IS A BLOCK OF ITS OWN, because the paragraph-sized scope was a
fail-open (rf2-xlsh).  A record table is one unbroken run of non-blank lines, so
every row in it shared a single scope and any one landed hash answered for all
of them — a wrong pin in the operative row went unreported as long as some other
row cited something that had landed.  That is precisely backwards from where the
risk lives: on a pre-registration page the hash that matters most is the one in
the table, beside a landed one, so the guard was blind in the field whose
wrongness costs most.  Accompaniment is therefore scoped to the row that makes
the claim.  A row ends where the NEXT row begins and not at the newline, because
this corpus wraps a long cell across source lines and the anchor rescuing a head
is routinely on the continuation.  Prose is untouched — a paragraph remains one
scope, which is what keeps the census's repaired "authored at X … it landed on
main as Y" sentences passing.

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
    which is the other half of rf2-xlsh: a "| Blob hash | … |" row above
    otherwise repaints the row below it as a digest, no citation is created,
    and the row scope below never gets to adjudicate what was never extracted.
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
positively calls it a commit.  Reading code spans alone was a fail-open against
the very rule at the top of this file: `Authored at deadbeef00 on worker/x.`
extracted nothing, so a page could cite an authored head in explicit commit
prose, omit the backticks, and pass `--changed-since` without the accompaniment
rule ever running (rf2-kqac1).  Backticks are a convention here, not a
guarantee, and dropping them is ordinary formatting drift.

The default flips at that boundary, and deliberately.  Inside a code span the
writer has already said "this is an id" and only the KIND is open, so silence
means pin.  In open prose nothing separates an unremarked hex run from a version
string or a large decimal, so silence means NOT a citation, and arbitrary bare
hex and bare decimals stay invisible exactly as before.  The narrow reading is
what keeps the whole idea affordable: it costs the corpus a handful of newly
visible citations rather than a column of noise.

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
repository" and nothing else is still a finding — that is the sabotage case in
the self-test, and it is what distinguishes this from sniffing prose.

Two boundaries keep it from becoming an exemption mechanism.  A permalink naming
THIS repository's own origin declares nothing and takes the ordinary local path,
so a stranded local head cannot be laundered through a GitHub-shaped link;
identity comes from `git remote get-url origin`, a config read, and when there is
no GitHub origin to compare against no permalink is honoured at all — the
refusal direction again.  And a foreign citation is not dropped from the
population: it is counted and listed separately under `--verbose` so that green
cannot mean silently ignored, and it may NOT stand in as the landed anchor for a
local head beside it, because it is not in this object database at all.

Nothing here reaches the network, and nothing here is a roster of blessed
repositories.  The link records an existence its author confirmed once while
writing it; CI re-reads the declaration, never the host.

WHAT THIS DOES NOT DO: it never re-pins.  rf2-owq6p established that recovering
the landed SHA restores the PATCH, not the TREE — where the rebase did not
preserve every blob the commit contributed, the landed commit is not what was
measured, and re-pinning to it swaps an unresolvable pin for a resolvable but
WRONG one, which fails silently.  Two of eleven repaired pins were in exactly
that state and were annotated rather than re-pinned.  A checker may REPORT; a
human decides.

HOW IT IS ARMED.  The corpus is red today — the census repaired eleven pins and
left thirty-odd standing, deliberately, because re-pinning is a judgement each
one needs individually.  So the blocking gate is `--changed-since`, which holds
only the pages a change touches to the rule; the bare full-corpus run is the
audit, and it is expected to report findings until the backlog is worked off.
That split is what lets the gate exist at all today instead of after the repair,
and touching a page is the moment its provenance is cheapest to fix.

Usage:
    python scripts/check_provenance_pins.py [--root DIR] [--verbose]
    python scripts/check_provenance_pins.py --changed-since origin/main
    python scripts/check_provenance_pins.py --self-test [--verbose]

Exit codes:
    0  every cited pin is landed or shares its block — its table row, when it
       sits in one — with a landed one
    1  findings — a human decides each; this tool never re-pins
    2  the check could not run (absent corpus, unresolvable baseline, bad ref).
       Never 0: a gate that cannot run must not report success for work it
       never did.
"""

from __future__ import annotations

import argparse
import io
import os
import re
import subprocess
import sys
from typing import Dict, Iterable, List, NamedTuple, Optional, Sequence, Tuple

DEFAULT_ROOT = "docs/design/hicasso"
BASELINE_REF = "origin/main"

# A hex run long enough to be a git object id.  7 is git's historical minimum
# abbreviation; 64 admits a full SHA-256.
_BARE_HEX = re.compile(r"^[0-9a-f]{7,64}$")

# Inline code spans.  This corpus writes pins and digests in a code span by
# convention, but a convention is not a guarantee — see _BARE_PROSE_HEX.
_CODE_SPAN = re.compile(r"`([^`\n]{1,200})`")

# A provenance id written WITHOUT backticks.  Extraction used to read code spans
# only, so `Authored at deadbeef00 on worker/x.` yielded nothing at all: a page
# could cite an authored head in completely explicit commit prose, omit the
# backticks, and sail through `--changed-since` without the accompaniment rule
# ever running (rf2-kqac1).  That is ordinary formatting drift, not camouflage,
# and silence is the one direction this gate must never fail in.
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
# form that declares a token foreign, so every part of it is load-bearing:
#
#   * HTTPS and `github.com` verbatim.  `www.`, `http://` and an ssh remote all
#     fail to match and take the ordinary local path — refusal, not silence.
#   * a repository that does not end in `.git`, because
#     `github.com/day8/re-frame2.git/commit/…` would otherwise record an
#     identity that compares unequal to this repository's own and launder a
#     local head straight through the check below.
#   * `/commit/` and a FULL forty-hex lowercase SHA.  An abbreviation is
#     meaningful only inside a particular object database, so it cannot carry a
#     claim about another one.
#   * nothing after it: a trailing `/`, `?` or `#` means a query, a fragment or
#     a deeper path, none of which is the canonical commit page.  A `.` is
#     allowed only as sentence punctuation, the same boundary `_BARE_PROSE_HEX`
#     draws, so a link may end a sentence but `…/commit/<sha>.diff` may not.
_COMMIT_PERMALINK = re.compile(
    r"https://github\.com"
    r"/([A-Za-z0-9][A-Za-z0-9._-]*)"
    r"/([A-Za-z0-9][A-Za-z0-9._-]*)(?<!\.git)"
    r"/commit/([0-9a-f]{40})"
    r"(?![0-9A-Za-z/?#_-])(?!\.[0-9A-Za-z])"
)

# This repository's own identity, read out of `origin`.  Covers the three forms
# a clone can carry it in; anything else yields no identity at all, and then no
# permalink is honoured.
_ORIGIN_URL = re.compile(
    r"^(?:https://|ssh://git@|git@)github\.com[:/]([^/]+)/(.+?)(?:\.git)?/?$"
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
    # Not called `block` any more because a table is one block and many scopes.
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

    IT STOPS AT A PRIOR ROW FOR THE SAME REASON, and not stopping there was the
    second half of rf2-xlsh.  Scoping accompaniment by row fixed adjudication
    but left EXTRACTION reading whatever the rows above happened to say, so a
    "| Blob hash | … |" row one line up repainted the next row's token as a
    digest and no citation was created at all — `evaluate` never saw it, and the
    row scope it could not see had nothing to enforce.  A digest word describes
    the cell it stands in; the row below makes its own claim.  So the context
    may reach the line that OPENS the token's row — the corpus wraps a long cell
    across source lines and the word describing the token is routinely up
    there — and it may not reach past it.

    WHOLE lines, never a character slice.  Cutting the context mid-line can cut
    a code span in half, after which the surviving backtick re-pairs with the
    wrong partner and the file path that would have identified the token as a
    blob stops being visible — which is how the first cut of this function
    reported a column of blob hashes as unresolvable pins.
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


def github_identity(url: str) -> Optional[str]:
    """`owner/repo` for a GitHub remote URL, or None when it is not one."""
    match = _ORIGIN_URL.match(url.strip())
    return "%s/%s" % (match.group(1), match.group(2)) if match else None


def scan_file(
    path: str,
    text: str,
    max_id_len: int = DEFAULT_MAX_ID_LEN,
    local_repo: Optional[str] = None,
) -> Tuple[List[Citation], List[Finding]]:
    """Read one page's citations.

    `local_repo` is this repository's own `owner/repo`.  Without it no permalink
    can be told from a link to ourselves, so none is honoured and every token
    takes the local path — the refusal direction, and the reason it is not
    defaulted to something convenient.
    """
    lines = text.splitlines()
    citations: List[Citation] = []
    anchors: List[Finding] = []
    # scope -> {full sha: owner/repo} declared by a canonical permalink there.
    declared: Dict[int, Dict[str, str]] = {}
    in_fence = False
    scope = 0
    for i, line in enumerate(lines):
        if not line.strip():
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
            # A row makes its own claim, so it may accompany only itself
            # (rf2-xlsh).  The scope opens here and runs to the next row rather
            # than to the newline, because a wrapped cell continues on lines
            # that open no pipe and the anchor is often down there.
            scope += 1
        for link in _COMMIT_PERMALINK.finditer(line):
            # Collected AFTER the fence test on purpose: a permalink inside a
            # reproduction command is an argument to an example, exactly as its
            # SHAs are, and must not declare anything about the page's own
            # citations.
            declared.setdefault(scope, {})[link.group(3)] = "%s/%s" % (
                link.group(1),
                link.group(2),
            )
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
            if repo and repo.lower() != local_repo.lower():
                citations[index] = citation._replace(foreign=repo)
    return citations, anchors


# --------------------------------------------------------------------------
# Git
# --------------------------------------------------------------------------


class Git:
    """Object-status oracle.  Answers exactly one question per token, and
    answers UNRESOLVABLE rather than guessing."""

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

    def baseline_exists(self) -> bool:
        return self._run("rev-parse", "--verify", "--quiet", self.baseline).returncode == 0

    def rev_exists(self, ref: str) -> bool:
        return self._run("rev-parse", "--verify", "--quiet", ref).returncode == 0

    def changed_markdown(self, since: str, root: str) -> set:
        """Corpus pages this branch touches, against the merge base with `since`
        so that commits landing on the baseline meanwhile are not attributed
        here."""
        base = self._run("merge-base", since, "HEAD")
        ref = base.stdout.strip() if base.returncode == 0 and base.stdout.strip() else since
        diff = self._run("diff", "--name-only", "--diff-filter=d", ref, "--", root)
        return {
            line.strip()
            for line in diff.stdout.splitlines()
            if line.strip().endswith(".md")
        }

    def origin_repo(self) -> Optional[str]:
        """This repository's own `owner/repo`, from `origin`.

        A config read, so it stays offline and needs no allowlist: the only
        repository this gate has an opinion about is itself, and it holds a
        permalink naming itself to the ordinary local path.  None when there is
        no GitHub origin, and then no permalink is honoured at all.
        """
        result = self._run("remote", "get-url", "origin")
        if result.returncode != 0:
            return None
        return github_identity(result.stdout)

    def max_id_len(self) -> int:
        """Hex length of a full object id here — 40 for SHA-1, 64 for SHA-256.
        Anything longer in the corpus is a digest of something that is not a
        git object, and needs no vocabulary to be excluded."""
        result = self._run("rev-parse", "--show-object-format")
        return 64 if result.stdout.strip() == "sha256" else DEFAULT_MAX_ID_LEN

    def status(self, token: str) -> str:
        """LANDED | STRANDED | UNRESOLVABLE."""
        if token in self._cache:
            return self._cache[token]
        resolved = self._run("rev-parse", "--verify", "--quiet", token + "^{commit}")
        if resolved.returncode != 0:
            value = "UNRESOLVABLE"
        elif self._run("merge-base", "--is-ancestor", token, self.baseline).returncode == 0:
            value = "LANDED"
        else:
            value = "STRANDED"
        self._cache[token] = value
        return value


# --------------------------------------------------------------------------
# The rule
# --------------------------------------------------------------------------


def evaluate(citations: Iterable[Citation], git: Git) -> List[Finding]:
    """Apply the accompaniment rule, one scope at a time.

    A scope is a prose paragraph, or a SINGLE TABLE ROW — not the whole table.
    Those are the two shapes this corpus writes provenance in, and they are the
    scope in which a reader actually finds the fallback: the census's repairs
    all put the landed SHA in the same table cell or the same sentence as the
    head it rescues, never merely somewhere in the same table.

    Scoping the table by row is what closes rf2-xlsh.  A table is one unbroken
    run of non-blank lines, so judging it whole let any single landed hash
    answer for every row around it, and a wrong pin in the operative row went
    unreported — on the pages this guards, that row is the one that matters
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
    for (_path, _scope), group in sorted(per_scope.items()):
        statuses = {c.token: git.status(c.token) for c in group}
        landed = sorted({t for t, s in statuses.items() if s == "LANDED"})
        if landed:
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
    return findings


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


def check(
    repo: str, root: str, verbose: bool, stream, changed_since: Optional[str] = None
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

    git = Git(repo)

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
            if verbose:
                stream.write(
                    "check_provenance_pins: no page under %s changed since %s.\n"
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

    findings.extend(evaluate(citations, git))
    findings.sort(key=lambda f: (f.path, f.line, f.token))

    if verbose:
        foreign = [c for c in citations if c.foreign]
        counts: Dict[str, int] = {}
        for c in citations:
            if c.foreign:
                continue
            counts[git.status(c.token)] = counts.get(git.status(c.token), 0) + 1
        stream.write(
            "check_provenance_pins: %d files, %d cited pins "
            "(%d landed, %d stranded, %d unresolvable, %d foreign)\n"
            % (
                len(files),
                len(citations),
                counts.get("LANDED", 0),
                counts.get("STRANDED", 0),
                counts.get("UNRESOLVABLE", 0),
                len(foreign),
            )
        )
        # Listed, never merely subtracted: a foreign citation is exempt from the
        # accompaniment rule, so it is the one class of token where green could
        # otherwise mean "quietly ignored".
        for c in sorted(foreign):
            stream.write(
                "  foreign: %s:%d  %s declared a commit of %s\n"
                % (c.path, c.line, c.token, c.foreign)
            )

    if not findings:
        if verbose:
            stream.write(
                "check_provenance_pins: every cited pin is an ancestor of %s or "
                "shares its block — its table row, when it sits in one — with "
                "one.\n" % BASELINE_REF
            )
        return 0

    stream.write(
        "\ncheck_provenance_pins: %d finding(s). Every cited authored head must "
        "be accompanied, in its own block — its own table ROW, when it sits in "
        "a table — by a SHA that is an ancestor of %s.\n"
        "This tool does NOT re-pin: recovering the landed SHA restores the patch "
        "and not necessarily the measured tree, so a human decides each one "
        "(see rf2-owq6p).\n\n" % (len(findings), BASELINE_REF)
    )
    for f in findings:
        label = ("%s " % f.token) if f.token else ""
        stream.write("  %s:%d  %s[%s]\n      %s\n" % (f.path, f.line, label, f.status, f.detail))
    stream.write("\n")
    return 1


# --------------------------------------------------------------------------
# Self-test
# --------------------------------------------------------------------------

# Each case is (label, lines, expected pin tokens).  These pin the DEFECT KIND,
# not a count: every digest shape this corpus actually writes appears here, and
# so does the unclassifiable default.
_EXTRACTION_CASES: List[Tuple[str, List[str], List[str]]] = [
    (
        "producing-commit table row is a pin",
        ["| **Producing commit** | `08344cb500` on `worker/linkterm-cno31` |"],
        ["08344cb500"],
    ),
    (
        "authored-at prose is a pin",
        ["Authored at `0b482f385e` on `worker/coldmount-2rtt6-15`; if that"],
        ["0b482f385e"],
    ),
    (
        "landed-on-main prose is a pin",
        ["It landed on main as **`93ad80f097`** (same patch)."],
        ["93ad80f097"],
    ),
    (
        "blob word in left context is not a pin",
        ["**AFTER** - codec blob `0304f489bb`, instrument blob byte-identical"],
        [],
    ),
    (
        "blob table row keyed by a path is not a pin",
        ["| `lane.cljs` | `0642815dc234c1544d1f97bd9e1e4dd24365c027` |"],
        [],
    ),
    (
        "blob table header covers a row whose own cell is bare prose",
        [
            "| file | blob |",
            "|---|---|",
            "| coldmount views | `335a37bb09233121e83ca2dc9f6a0a9ef88e037c` |",
        ],
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
    (
        "sha-256 document digest is not a pin",
        ["The rendered document's SHA-256 digest `deadbeefcafe0123456789abcdef0123`"],
        [],
    ),
    (
        "authored=landed mapping yields both ids",
        ["The recovery table reads `0cf86fb580=24e8822d7f` for this page."],
        ["0cf86fb580", "24e8822d7f"],
    ),
    (
        "fenced reproduction commands are not citations",
        [
            "```bash",
            "git rev-parse f784ab0adb:$S   # 086d08e94089002c19e6b30cc901d03324b0f4cc",
            "```",
        ],
        [],
    ),
    (
        "a decimal number is not a citation",
        ["the ten-turn aggregate resolved at `0.0999999 ms`, the bulk row's floor"],
        [],
    ),
    # BARE PROVENANCE.  This case used to assert the opposite — that prose hex
    # outside a code span is never a citation — and that assertion pinned the
    # fail-open: explicit commit prose without backticks passed the gate
    # unexamined (rf2-kqac1).  It reads the other way now, and the four cases
    # under it hold the narrowness in place.
    (
        "bare prose hex the writer calls a commit is a citation",
        ["Commit 08344cb500 was measured on one box."],
        ["08344cb500"],
    ),
    (
        "bare prose hex the writer calls authored is a citation",
        ["Authored at deadbeef00 on worker/x."],
        ["deadbeef00"],
    ),
    (
        "bare hex nobody calls a commit is not a citation",
        ["The bulk row settled at 5f2c8a1b3d across all ten turns."],
        [],
    ),
    (
        "bare hex the writer calls a blob is not a citation",
        ["The instrument blob 0304f489bb is byte-identical to the last arm."],
        [],
    ),
    (
        # The boundary rule, not the vocabulary rule: "commit" is right there,
        # so only the `.` in front of `0999999` keeps the decimal out.
        "a bare decimal beside a commit word is not a citation",
        ["The commit's ten-turn aggregate resolved at 0.0999999 ms on the bulk row."],
        [],
    ),
    # THE FAILURE DIRECTION, pinned.  A token with no vocabulary either way is
    # read as a PIN, so it costs a finding rather than vanishing.
    (
        "unclassifiable token defaults to a pin",
        ["The run above was taken at `1234567890abcdef1234`."],
        ["1234567890abcdef1234"],
    ),
    (
        "abbreviated id with an ellipsis still reads as one id",
        ["Producing commit `0642815dc2…` for the spine."],
        ["0642815dc2"],
    ),
    # The right-apposition window is measured on the line AS READ.  Stripping
    # the `> ` first slid it two characters and lost the word it looks for,
    # which mattered because this corpus writes whole blob callouts in
    # blockquotes.
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
    # THE SIBLING DIGEST (rf2-xlsh, second half).  Scoping accompaniment by row
    # guards adjudication; this guards EXTRACTION, which ran first and so ran
    # unguarded.  The digest word in the row above used to reach down and
    # repaint the row below, and a token classified as a digest is never a
    # citation, so `evaluate` was handed nothing and the operative pin failed
    # open with the row scope already in place.  Both rows are asserted: the
    # digest must stay a digest, and the pin beside it must be seen.
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
    # The counterweight, and the reason the boundary is the row START and not
    # the newline: a long cell wraps across source lines, and the word
    # describing the token is up on the line that opened the row.  `0642815dc2`
    # has no vocabulary of its own, so it is a digest only if the context still
    # reaches its own row's first line — cut that and it defaults to a pin.
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
    (
        "a stranded head alone in its block is a finding",
        ["| Producing commit | `aaaaaaaaaa` on `worker/x` |"],
        {"aaaaaaaaaa": "STRANDED"},
        ["aaaaaaaaaa"],
    ),
    (
        "a retained stranded head accompanied in-block passes",
        [
            "| Producing commit | `aaaaaaaaaa` on `worker/x` — authored, and",
            "rebase-merged. It landed on main as **`bbbbbbbbbb`**. |",
        ],
        {"aaaaaaaaaa": "STRANDED", "bbbbbbbbbb": "LANDED"},
        [],
    ),
    (
        "accompaniment in a DIFFERENT block does not rescue the head",
        [
            "| Landed anchor | `bbbbbbbbbb` |",
            "",
            "| Producing commit | `aaaaaaaaaa` on `worker/x` |",
        ],
        {"aaaaaaaaaa": "STRANDED", "bbbbbbbbbb": "LANDED"},
        ["aaaaaaaaaa"],
    ),
    (
        "a landed head alone in its block passes",
        ["| Producing commit | `bbbbbbbbbb`, already on main |"],
        {"bbbbbbbbbb": "LANDED"},
        [],
    ),
    (
        "an unresolvable token is a finding, not a pass",
        ["| Producing commit | `cccccccccc` |"],
        {"cccccccccc": "UNRESOLVABLE"},
        ["cccccccccc"],
    ),
    (
        "two stranded heads in one block are both findings",
        ["Authored as `aaaaaaaaaa`, superseded by `dddddddddd` on `worker/x`."],
        {"aaaaaaaaaa": "STRANDED", "dddddddddd": "STRANDED"},
        ["aaaaaaaaaa", "dddddddddd"],
    ),
    # BARE PROVENANCE, end to end.  The first is the finding the code-span-only
    # reader let through; the second is the noise the repair must still ignore —
    # with no status table behind it, anything extracted here would resolve to
    # UNRESOLVABLE and become a finding, so an empty expectation is a real
    # assertion that nothing was extracted at all.
    (
        "a bare authored head, no backticks, is still a finding",
        ["Authored at aaaaaaaaaa on worker/x, before the rebase mints its landed id."],
        {"aaaaaaaaaa": "STRANDED"},
        ["aaaaaaaaaa"],
    ),
    (
        "a bare digest and a bare number raise no finding",
        ["The instrument blob 0304f489bb held at 0.0999999 ms across ten turns."],
        {},
        [],
    ),
    (
        "a bare landed id accompanies the stranded head beside it",
        ["Authored at aaaaaaaaaa on worker/x; it landed on main as bbbbbbbbbb."],
        {"aaaaaaaaaa": "STRANDED", "bbbbbbbbbb": "LANDED"},
        [],
    ),
    # THE ROW SCOPE (rf2-xlsh).  The first case is the fail-open itself: a
    # record table cited the operative pin in one row and a landed hash in
    # another, and per-paragraph accompaniment let the neighbour answer for it,
    # so a wrong hash in the field whose wrongness costs most went unreported.
    (
        "a landed hash in a SIBLING ROW does not rescue the row beside it",
        [
            "| Original freeze | `bbbbbbbbbb`, registering all seven criteria |",
            "| Pre-registration commit | `aaaaaaaaaa` — this is the hash to cite |",
        ],
        {"aaaaaaaaaa": "STRANDED", "bbbbbbbbbb": "LANDED"},
        ["aaaaaaaaaa"],
    ),
    # The counterweight, and the reason a row is not simply one line: this
    # corpus wraps a long cell across source lines, and the anchor that rescues
    # the head is routinely on the continuation.  `aaaa` is accompanied from the
    # second line of its OWN row; `cccc`, a row down, is not.
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
    # THE SIBLING DIGEST, end to end.  The row scope alone left this at exit 0:
    # the `Blob hash` row above repainted `aaaaaaaaaa` as a digest during
    # extraction, so there was no citation for the row scope to adjudicate and
    # the operative pin failed open.  An empty expectation here would be a pass
    # for the wrong reason, so the finding is the assertion.
    (
        "a digest row above neither repaints nor rescues the row beside it",
        [
            "| Blob hash | `bbbbbbbbbb` |",
            "| Original freeze | `aaaaaaaaaa` |",
        ],
        {"aaaaaaaaaa": "STRANDED", "bbbbbbbbbb": "LANDED"},
        ["aaaaaaaaaa"],
    ),
    # Prose is untouched by the row scope — the shape the census's repairs put
    # the anchor in, which must keep passing.
    (
        "a paragraph is still one scope after the table split",
        [
            "Authored at `aaaaaaaaaa` on `worker/x`, before the rebase; the",
            "same patch landed on main as `bbbbbbbbbb`.",
        ],
        {"aaaaaaaaaa": "STRANDED", "bbbbbbbbbb": "LANDED"},
        [],
    ),
]


# --------------------------------------------------------------------------
# The foreign-citation witnesses
# --------------------------------------------------------------------------

# The real citation this mechanism was built for, and the real origin it must
# hold itself apart from.  Full forty hex throughout: an abbreviation is not a
# claim about another object database, so the mechanism refuses one.
_UPSTREAM = "krausest/js-framework-benchmark"
_HERE = "day8/re-frame2"
_FOREIGN_SHA = "247fafa22c1f2caeb4cad179aa64cf444398cbc7"
_LOCAL_SHA = "19a3710bc9604684ddbc7b2b72ec901dcc0f0ea7"
_PERMALINK = "https://github.com/%s/commit/%s"


class _ForeignCase(NamedTuple):
    """One witness, asserted at all three layers at once.

    Extraction, declaration and adjudication have to be read together here:
    "reported as foreign" and "passes without a local anchor" are different
    claims, and a case that checked only the second would pass just as well if
    the token had been dropped from the population altogether — which is the
    fail-open shape this whole mechanism was ruled against.
    """

    label: str
    lines: List[str]
    local: Optional[str]  # this repository's own origin identity
    status: Dict[str, str]  # what git says about each token
    pins: List[str]  # tokens extracted as citations, in order
    foreign: Dict[str, str]  # of those, the ones carrying a repository identity
    findings: List[str]  # what the accompaniment rule then reports


_FOREIGN_CASES: List[_ForeignCase] = [
    # POSITIVE.  A canonical permalink, matching full SHA, and NO local anchor
    # anywhere in the row — which is the point: before this, the only way to
    # pass was to name a commit of this repository, and no commit of this
    # repository belongs in a row citing somebody else's benchmark.
    _ForeignCase(
        "a canonical permalink declares its token a foreign commit, and it passes",
        [
            "| Benchmark revision | `%s` at commit **`%s`**, canonically at "
            "[the commit page](%s). That SHA belongs to the benchmark's "
            "repository, not to this one |"
            % (_UPSTREAM, _FOREIGN_SHA, _PERMALINK % (_UPSTREAM, _FOREIGN_SHA)),
        ],
        _HERE,
        {},
        [_FOREIGN_SHA],
        {_FOREIGN_SHA: _UPSTREAM},
        [],
    ),
    # SABOTAGE, and the case that says what kind of mechanism this is.  The row
    # carries the words a prose-sniffing gate would have honoured — "foreign
    # repository", "belongs to", "not to this one" — and nothing typed.  It is
    # still a finding.  If this ever passes, the gate has learned a magic
    # phrase and the ruling has been undone.
    _ForeignCase(
        "prose calling a token foreign exempts nothing",
        [
            "| Benchmark revision | at commit **`%s`** — that SHA belongs to a "
            "foreign repository, an upstream one, not to this one, so it "
            "resolves there and nowhere else |" % _FOREIGN_SHA,
        ],
        _HERE,
        {},
        [_FOREIGN_SHA],
        {},
        [_FOREIGN_SHA],
    ),
    # EDGE: the label and the URL name different commits.  Only the URL's SHA
    # is declared, and the displayed token is not it, so the token the reader
    # actually sees keeps the local path.  A permalink vouches for one object,
    # not for its neighbourhood.
    _ForeignCase(
        "a permalink to a DIFFERENT sha declares nothing about the token beside it",
        [
            "| Benchmark revision | at commit **`%s`**, see %s |"
            % (_FOREIGN_SHA, _PERMALINK % (_UPSTREAM, "b" * 40)),
        ],
        _HERE,
        {},
        [_FOREIGN_SHA],
        {},
        [_FOREIGN_SHA],
    ),
    # EDGE: an abbreviated displayed token.  Ten hex characters are an index
    # into ONE object database, so they cannot carry a claim about another; the
    # abbreviation is not the SHA the permalink binds, and takes the local path.
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
    # EDGE: malformed URLs.  Each of these is a link a reader would follow
    # happily and none is the canonical commit page, so none declares anything:
    # plain HTTP, the plural `/commits/`, a trailing query, and a `.git`
    # repository — that last one the sharpest, because `day8/re-frame2.git`
    # compares unequal to `day8/re-frame2` and would otherwise launder a local
    # head through the boundary below.
    _ForeignCase(
        "a malformed commit URL declares nothing",
        [
            "| A | at commit **`%s`**, at http://github.com/%s/commit/%s |"
            % (_FOREIGN_SHA, _UPSTREAM, _FOREIGN_SHA),
            "| B | at commit **`%s`**, at https://github.com/%s/commits/%s |"
            % (_FOREIGN_SHA, _UPSTREAM, _FOREIGN_SHA),
            "| C | at commit **`%s`**, at %s?diff=split |"
            % (_FOREIGN_SHA, _PERMALINK % (_UPSTREAM, _FOREIGN_SHA)),
            "| D | at commit **`%s`**, at https://github.com/%s.git/commit/%s |"
            % (_LOCAL_SHA, _HERE, _LOCAL_SHA),
        ],
        _HERE,
        {_LOCAL_SHA: "STRANDED"},
        [_FOREIGN_SHA, _FOREIGN_SHA, _FOREIGN_SHA, _LOCAL_SHA],
        {},
        [_FOREIGN_SHA, _FOREIGN_SHA, _FOREIGN_SHA, _LOCAL_SHA],
    ),
    # EDGE, and the boundary that keeps this from being an exemption mechanism:
    # a permalink naming THIS repository declares nothing.  Otherwise every
    # stranded local head could be laundered by linking to it on github.com,
    # where a stranded head resolves for nobody.  Case-insensitively, because
    # GitHub is.
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
    # A foreign citation is EXEMPT, not an ANCHOR.  The stranded local head one
    # row down has no accompaniment, and a commit in somebody else's object
    # database is no anchor for this tree — a reader who checks it out is not
    # looking at the measured tree.  Both verdicts in one fixture: the foreign
    # row passes, the local row still reds.
    _ForeignCase(
        "a foreign citation cannot stand in as the landed anchor",
        [
            "| Benchmark revision | at commit **`%s`**, canonically at %s |"
            % (_FOREIGN_SHA, _PERMALINK % (_UPSTREAM, _FOREIGN_SHA)),
            "| Authoring anchor | `%s` on `worker/x` |" % _LOCAL_SHA,
        ],
        _HERE,
        {_LOCAL_SHA: "STRANDED"},
        [_FOREIGN_SHA, _LOCAL_SHA],
        {_FOREIGN_SHA: _UPSTREAM},
        [_LOCAL_SHA],
    ),
    # A declaration reaches exactly as far as accompaniment does — its own row.
    # A reader must find the declaration where the claim is made; a permalink
    # two rows up is not beside the token it would exempt.
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
    # A permalink inside a reproduction command declares nothing, for the same
    # reason its SHAs cite nothing: a fenced block is an example, not the page's
    # own provenance.
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
    # Without an identity for THIS repository there is nothing to compare a
    # permalink against, so none is honoured.  The refusal direction, one last
    # time: a checkout that cannot tell somebody else's repository from its own
    # reds a page it might have passed, rather than passing one it should have
    # red.
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


def self_test(verbose: bool, stream) -> int:
    failures = 0

    for label, lines, expected in _EXTRACTION_CASES:
        cites, _ = scan_file("fixture.md", "\n".join(lines))
        got = [c.token for c in cites]
        if got == expected:
            if verbose:
                stream.write("self-test PASS: extraction [%s]\n" % label)
        else:
            stream.write(
                "self-test FAIL: extraction [%s] expected %r, got %r\n"
                % (label, expected, got)
            )
            failures += 1

    for label, lines, table, expected in _RULE_CASES:
        cites, _ = scan_file("fixture.md", "\n".join(lines))
        got = sorted(f.token for f in evaluate(cites, _FakeGit(table)))
        if got == sorted(expected):
            if verbose:
                stream.write("self-test PASS: rule [%s]\n" % label)
        else:
            stream.write(
                "self-test FAIL: rule [%s] expected %r, got %r\n"
                % (label, sorted(expected), got)
            )
            failures += 1

    for case in _FOREIGN_CASES:
        cites, _ = scan_file(
            "fixture.md", "\n".join(case.lines), DEFAULT_MAX_ID_LEN, case.local
        )
        got_pins = [c.token for c in cites]
        got_foreign = {c.token: c.foreign for c in cites if c.foreign}
        got_findings = sorted(f.token for f in evaluate(cites, _FakeGit(case.status)))
        problems = []
        if got_pins != case.pins:
            problems.append("extracted %r, expected %r" % (got_pins, case.pins))
        if got_foreign != case.foreign:
            problems.append("foreign %r, expected %r" % (got_foreign, case.foreign))
        if got_findings != sorted(case.findings):
            problems.append(
                "findings %r, expected %r" % (got_findings, sorted(case.findings))
            )
        if problems:
            stream.write(
                "self-test FAIL: foreign [%s] %s\n" % (case.label, "; ".join(problems))
            )
            failures += 1
        elif verbose:
            stream.write("self-test PASS: foreign [%s]\n" % case.label)

    # The identity the boundary above compares against, in the three forms a
    # clone can carry `origin` in — plus a non-GitHub remote, which yields no
    # identity and so honours no permalink at all.
    for url, expected in (
        ("https://github.com/day8/re-frame2", _HERE),
        ("https://github.com/day8/re-frame2.git", _HERE),
        ("git@github.com:day8/re-frame2.git", _HERE),
        ("ssh://git@github.com/day8/re-frame2.git", _HERE),
        ("/srv/mirrors/re-frame2.git", None),
    ):
        got = github_identity(url)
        if got != expected:
            stream.write(
                "self-test FAIL: origin identity of %r was %r, expected %r\n"
                % (url, got, expected)
            )
            failures += 1
        elif verbose:
            stream.write("self-test PASS: origin identity of %r is %r\n" % (url, got))

    # The unfilled-anchor tooth.
    _, anchors = scan_file(
        "fixture.md",
        "| Landed anchor | *(filled on merge — a rebase mints a new SHA)* |",
    )
    if len(anchors) == 1 and anchors[0].status == "UNFILLED":
        if verbose:
            stream.write("self-test PASS: unfilled anchor is a finding\n")
    else:
        stream.write(
            "self-test FAIL: unfilled anchor expected 1 finding, got %r\n" % (anchors,)
        )
        failures += 1

    # POSITIVE CONTROL for that tooth: a FILLED anchor must not be one.
    _, filled = scan_file("fixture.md", "| Landed anchor | **`a878d71ab9`** — on main |")
    if not filled:
        if verbose:
            stream.write("self-test PASS: a filled anchor is not a finding\n")
    else:
        stream.write("self-test FAIL: filled anchor produced %r\n" % (filled,))
        failures += 1

    # THE REFUSAL PATHS.  A gate whose can't-run path exits 0 reports success
    # for work it never did, so every way this script can fail to do its job is
    # asserted to leave rc=2.
    repo = _repo_root()
    for label, kwargs, root in (
        ("absent corpus root", {}, "no/such/corpus/root"),
        (
            "unresolvable --changed-since ref",
            {"changed_since": "refs/heads/no-such-ref-rf2-kqac1"},
            DEFAULT_ROOT,
        ),
    ):
        rc = check(repo, root, False, _DevNull(), **kwargs)
        if rc == 2:
            if verbose:
                stream.write("self-test PASS: %s refuses (rc=2)\n" % label)
        else:
            stream.write(
                "self-test FAIL: %s returned %d, expected 2\n" % (label, rc)
            )
            failures += 1

    total = (
        len(_EXTRACTION_CASES) + len(_RULE_CASES) + len(_FOREIGN_CASES) + 5 + 4
    )
    if failures:
        stream.write("\n%d self-test failure(s).\n" % failures)
        return 1
    if verbose:
        stream.write("all %d self-tests passed.\n" % total)
    return 0


class _DevNull:
    def write(self, *_args, **_kwargs) -> int:
        return 0

    def flush(self) -> None:
        return None


def _repo_root() -> str:
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True
    )
    if result.returncode == 0 and result.stdout.strip():
        return result.stdout.strip()
    return os.getcwd()


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

    if args.self_test:
        return self_test(args.verbose, stream)
    return check(_repo_root(), args.root, args.verbose, stream, args.changed_since)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
