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
    one describing it.  A FILE PATH in a code span counts as a digest word: "|
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
    0  every cited pin is landed or shares its block with a landed one
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
# they are read only from the token's own line, so a neighbouring sentence
# cannot repaint it.
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

# A provenance anchor whose author promised to fill it after the merge and did
# not.  Unambiguous, so it is reported on sight.
_UNFILLED_ANCHOR = re.compile(r"\(\s*filled on merge", re.I)

_FENCE = re.compile(r"^\s*(?:```|~~~)")


class Citation(NamedTuple):
    path: str
    line: int
    block: int
    token: str
    reason: str  # why it was read as a pin rather than a digest


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
    to two whole lines above.

    It has to cross lines: this corpus hard-wraps at about eighty columns, so
    the word describing a token routinely sits on the line above it, and a run
    of "`file.cljs`\\n`hash`, `file.cljs`\\n`hash`" puts it two lines up.  It
    stops at a blank line, because that is a different block and a different
    claim.

    WHOLE lines, never a character slice.  Cutting the context mid-line can cut
    a code span in half, after which the surviving backtick re-pairs with the
    wrong partner and the file path that would have identified the token as a
    blob stops being visible — which is how the first cut of this function
    reported a column of blob hashes as unresolvable pins.
    """
    parts = [_strip_quote(lines[index][:span_start])]
    i = index - 1
    taken = 0
    while taken < _LEFT_LINES and i >= 0 and lines[i].strip():
        parts.append(_strip_quote(lines[i]))
        taken += 1
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


def scan_file(
    path: str, text: str, max_id_len: int = DEFAULT_MAX_ID_LEN
) -> Tuple[List[Citation], List[Finding]]:
    lines = text.splitlines()
    citations: List[Citation] = []
    anchors: List[Finding] = []
    in_fence = False
    block = 0
    for i, line in enumerate(lines):
        if not line.strip():
            block += 1
            continue
        if _FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            # Fenced blocks are reproduction commands.  Their SHAs are
            # arguments to an example, not the page's own provenance.
            continue
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
                    citations.append(Citation(path, i + 1, block, token, verdict.reason))
        # Then the same line with its code spans blanked out, so a token cannot
        # be read twice, and with the default reading switched off.
        for match in _BARE_PROSE_HEX.finditer(_mask_code_spans(line)):
            token = match.group(1)
            if len(token) > max_id_len:
                continue
            verdict = classify(lines, i, match.start(), match.end())
            if verdict.is_pin and verdict.spoken:
                citations.append(
                    Citation(path, i + 1, block, token, verdict.reason + ", uncoded")
                )
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
    """Apply the accompaniment rule, one block at a time.

    A block is a maximal run of consecutive non-blank lines, which makes a
    markdown table one block and a prose paragraph one block — the two shapes
    this corpus writes provenance in.  That is the scope in which a reader
    actually finds the fallback: the census's repairs all put the landed SHA in
    the same table cell or the same sentence as the head it rescues.
    """
    per_block: Dict[Tuple[str, int], List[Citation]] = {}
    for c in citations:
        per_block.setdefault((c.path, c.block), []).append(c)

    findings: List[Finding] = []
    for (_path, _block), group in sorted(per_block.items()):
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
    citations: List[Citation] = []
    findings: List[Finding] = []
    for path in files:
        with io.open(path, encoding="utf-8") as handle:
            text = handle.read()
        rel = os.path.relpath(path, repo).replace(os.sep, "/")
        cites, anchors = scan_file(rel, text, max_id_len)
        citations.extend(cites)
        findings.extend(anchors)

    findings.extend(evaluate(citations, git))
    findings.sort(key=lambda f: (f.path, f.line, f.token))

    if verbose:
        counts: Dict[str, int] = {}
        for c in citations:
            counts[git.status(c.token)] = counts.get(git.status(c.token), 0) + 1
        stream.write(
            "check_provenance_pins: %d files, %d cited pins "
            "(%d landed, %d stranded, %d unresolvable)\n"
            % (
                len(files),
                len(citations),
                counts.get("LANDED", 0),
                counts.get("STRANDED", 0),
                counts.get("UNRESOLVABLE", 0),
            )
        )

    if not findings:
        if verbose:
            stream.write(
                "check_provenance_pins: every cited pin is an ancestor of %s or "
                "shares its block with one.\n" % BASELINE_REF
            )
        return 0

    stream.write(
        "\ncheck_provenance_pins: %d finding(s). Every cited authored head must "
        "be accompanied, in its own block, by a SHA that is an ancestor of %s.\n"
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
    # unexamined (rf2-kqac1).  It reads the other way now, and the three cases
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

    total = len(_EXTRACTION_CASES) + len(_RULE_CASES) + 4
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
