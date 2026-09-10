#!/usr/bin/env python3
"""Report a markdown CONTINUATION BLOCK that renders OUTSIDE its list item.

Python-Markdown wants four columns per nesting level for CONTINUATION
content just as it does for child items.  A paragraph indented two columns
under `- parent` has not merely lost a level — it has left the list item
altogether: the `<ul>` CLOSES, the paragraph renders after it, and a new
list starts below.  The bullet list VISIBLY SPLITS with body prose stranded
between the halves, which makes this the most reader-facing markdown fault
measured in this corpus (rf2-luch, rf2-6ml6, rf2-jzv2).

    - parent                     <ul>
                          -->    <li>parent</li>
      continuation               </ul>
                                 <p>continuation</p>
    - sibling                    <ul>
                                 <li>sibling</li>
                                 </ul>

THIS IS AN INSTRUMENT, NOT A GATE, AND THAT IS DELIBERATE.  It is wired
into no CI job and into no local spine.  Whether this class becomes a
permanent gate obligation is rf2-jzv2's open half and an unmade decision;
see WIRING THIS AS A GATE at the bottom of this docstring for what the
decision costs and what the count would have to reach first.  Run it on
demand:

    python scripts/check_escaped_continuations.py            # whole corpus
    python scripts/check_escaped_continuations.py <paths>    # named files
    python scripts/check_escaped_continuations.py --self-test

    Exit code:
        0  no escaped continuations
        1  at least one
        2  invocation / setup error

WHY `check_flattened_lists.py` CANNOT ANSWER THIS, which is the finding the
bead was filed on.  That gate detects a nested list ITEM that renders flat.
Its `collect_items` matches only list markers, and `_pair_defect` grades
consecutive ITEM pairs, requiring `cur_at.root == prev_at.root`.  An
escaped continuation SPLITS the root list in two, so the pair is never
compared: this class MASKS flattened nesting from that gate rather than
being reported by it.  It reads 0 on this class before any repair and 0
after.  The two instruments are complementary, and the repair needs both —
moving a body to its content column moves the sub-lists inside it, which
can leave a child short of a four-column step, and that IS the other
gate's class.

=== THE DETECTION RULE IS A UNION, AND THE SECOND HALF IS THE ONE THAT GETS
    LOST ===

For each candidate continuation line we know its SOURCE OWNER (the list
item it is indented under) and, from the render, where both of them landed:

    ESCAPED  <=>  cand.depth < owner.depth        (it left an inner list)
              OR  cand is outside every `li`      (it left the list)

FLAGGING ONLY THE DEPTH COMPARISON READS 57 WHERE THE TRUE ANSWER IS 84
(measured by `methodcont-a` on the pre-#9620 `docs/api` surface; re-derived
here — see the `docs/api` control in the self-test notes).  The missing 27
are cascades: an EARLIER escape has already closed the list, so the marker
below it becomes a lazy continuation of the escaped paragraph and renders
as literal text.  Owner and candidate then BOTH read depth 0 and the
comparison is between two zeros — it cannot fire.  A detector built on the
comparison alone ships reading 68% of its class and reports a clean tree
while a third of it stands: green by a path the fault walks straight past.

BOTH HALVES ARE LOAD-BEARING IN THE OTHER DIRECTION TOO.  The second half
alone would miss a continuation that escapes a CHILD item while remaining
inside its GRANDPARENT's `li` — it is inside an `li`, just not the right
one — which is exactly what a bare `in_li` test gets wrong.

=== THREE PROBE TRAPS, ALL PAID FOR BEFORE THIS FILE EXISTED ===

1.  THE OBVIOUS MARKER PROBE IS WRONG IN BOTH DIRECTIONS.  Asking "does
    the text land in an `<li>` or a `<p>`?" reports healthy LOOSE items as
    broken: a continuation block makes its list loose, and python-markdown
    then wraps EVERY item's text in a `<p>`.  Asking "does it have any
    `<li>` ANCESTOR?" reports genuinely escaped blocks as fine, because an
    ancestor's `li` answers for it.  What works is a sentinel per line and
    the `<li>` INSTANCE it landed in, which is what `Placement.li` records.

2.  RENDER WITH THE ELEVEN EXTENSIONS MKDOCS' OWN CONFIG LOADER PRODUCES.
    `_load_renderer` is imported from `check_flattened_lists` rather than
    rebuilt: mkdocs injects `toc`, `tables` and `fenced_code` as builtins,
    so the real list is eleven where the yaml declares eight.  A different
    extension set is a different Markdown language and its count is not
    comparable to anyone else's.

3.  THE CORPUS ENUMERATOR IS `git ls-files`, so an UNTRACKED probe file is
    invisible to it.  A control planted in a scratch file reads exit 0 and
    looks like a working negative — it is the enumerator, not the detector.
    Take controls against TRACKED files, or through `--self-test`, which
    renders strings and never touches the corpus.

=== REPAIRING A SITE WHOSE OWNER IS NOT A LIST ITEM — THE ONE PLACE THIS
    TOOL'S ADVICE CAN DAMAGE PROSE ===

Every verdict here is render-side, and there is a class NO render-side
reading can catch, because in it the render is CORRECT and the error is
upstream: A `+` THAT IS A PLUS SIGN IN HARD-WRAPPED PROSE MATCHES THE LIST
MARKER REGEX.  `docs/EP/EP-0033-re-frame-ui-view-evidence.md:235` reads
`+ generation, released/remounted on ambiguity.`, continuing a sentence
that ends `...source anchor + structural path`.  Nothing about the render
is wrong; the line is prose and renders as prose.  But a probe enumerates
it AS a marker and then truthfully reports that it did not land in an
`<li>`, and THE REPAIR CREATES THE DEFECT — inserting the blank line yields
a manufactured `<ul>` and a truncated sentence.

This detector never REPORTS a marker — markers are owners, not sites — so
it cannot emit that advice directly.  It is exposed one step further out: a
false marker can become a false OWNER, and the indented line below it is
then flagged with repair advice that would indent prose under prose.

HOW THIS FILE HANDLES IT, and what was rejected.  A source-side screen on
the structural tell (a wrapped-prose false positive is a SINGLETON: no
colon lead-in above, no sibling markers around, where a genuine swallowed
marker travels in company) works well on a hand-checked slice but does not
survive contact with this corpus: built here and run over all 291 files it
flagged 175 owners, and every sampled one was an ordinary
`- **Description**:` bullet whose siblings merely sat further away than the
window, because items here are hard-wrapped over many lines.  A screen with
that false-positive rate is worse than none, so THERE IS NO SCREEN.

What there is instead is exact and render-derived rather than guessed:
every site whose OWNER also renders outside every `li` is marked in the
report and counted in the summary.  That set contains the whole false-marker
class by construction — a `+` in prose always renders as prose — alongside
the genuine cascade sites, and it is precisely the set where a repair
changes what the owner IS rather than merely where the continuation sits.
Read those before repairing them; reindent the rest.

=== WHAT THIS DETECTOR DOES NOT SEE AT ALL: AN ESCAPED FENCE ===

`spec/012-Routing.md`'s "Mechanism" renders as TWO `<ol>`s, its steps 3 and
4 restarting at 1, because step 2's fence sits at THREE columns where
python-markdown wants four while step 3's is correctly at four.  That is
this class's mechanism exactly — a continuation block short of its item's
content column, closing the list — but with a FENCE as the block.

IT IS INVISIBLE HERE, AND STRUCTURALLY SO.  Candidates are enumerated from
`_strip_fences`, which blanks a fenced block INCLUDING ITS OPENING AND
CLOSING LINES, so there is no line left to mark.  Inherited deliberately:
that scanner is the corpus's much-corrected notion of "code, not prose",
and unblanking fences here to reach their openers would make every
list-shaped line inside a code sample a candidate.  Measured — this file
reads 0 escaped continuations on `spec/012-Routing.md` while that split
`<ol>` stands.  A fence probe wants its own instrument (mark the opener
only, ask which `li` the resulting `<pre>` lands in); it is not a widening
of this one.

=== WHAT THE PROBE CANNOT MARK, STATED RATHER THAN HIDDEN ===

A sentinel needs somewhere inert to sit, and two line shapes have nowhere:
a table SEPARATOR row (`| --- | --- |`), where any content destroys the
table, and a BARE BLOCKQUOTE line (`>`), which carries no content and whose
marking turns an empty blockquote line into a row of the table below it.
Both are skipped and COUNTED, and the count is printed on every run, so the
hole is visible rather than silent.  Every other line shape is measurable,
tables included: a sentinel appended after a row's trailing `|` becomes a
surplus cell and is SILENTLY DROPPED by the tables extension, so a pipe row
is marked INSIDE ITS FIRST CELL instead, which leaves the cell count — and
therefore the table — untouched.  Escaped table rows are counted and
reported SEPARATELY from prose, because the prose figure is the one every
number on this corpus has been quoted in.

`toc` slugifies a heading's text into its `id` AND its permalink href, so a
sentinel on a line python-markdown reads as a heading survives a textual
desentinel inside those attributes and reads as a structural change when it
is nothing of the kind.  One corpus line does exactly this — a blockquote
line beginning `#8029` renders as an `<h1>`.  `desentinel` therefore strips
the lowercased slug form and its joining hyphen as well.

=== UNITS.  READ THIS BEFORE QUOTING A NUMBER FROM THIS TOOL ===

THIS CORPUS HAS HAD THREE SEPARATE UNIT CONFUSIONS RECORDED AGAINST IT —
16 LINES CALLED A FLOOR AGAINST 450 BLOCKS, "450 blocks" that was a line
count, and 19 indented table blocks carried forward as a repair backlog.
So this tool prints LINES and BLOCKS and TABLES as three separate figures
and never a single headline number.  A hard-wrapped paragraph is one block
and many lines; the two are an order of magnitude apart on this corpus.
Quote the unit with the number, and the tip sha with both.

=== WIRING THIS AS A GATE — THE INPUT TO THE OPEN DECISION, NOT THE
    DECISION ===

What it would cost, measured rather than estimated (figures in the PR that
introduced this file):

  * A renderer round trip per file, two renders each, the same shape as
    `check_flattened_lists.py` — which is why that gate needs
    `requirements.txt` and cannot sit with the pure-stdlib checkers.  The
    two together roughly double that job's wall time.
  * Somewhere to run: `test.yml`'s `verify-readme-links` job already runs
    the sibling gate as two steps and carries no changed-files guard, so
    the natural wiring is two more steps there and no classifier surface.
  * A count of ZERO first.  A gate cannot land red, and the residue is not
    all repairable by reindentation: where a `- item` sits directly under a
    bold paragraph with no blank line between, the MARKER is itself a lazy
    continuation and renders as plain text, so there is no `li` for the
    continuation to be inside and no reindent changes that.  The fix is a
    blank line, which is prose rather than whitespace.  Those sites need a
    prose decision before any gate can be green.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

_HERE = str(Path(__file__).resolve().parent)
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

# Imported, never reimplemented.  The renderer must be the one the sibling
# gate uses or the two counts describe different documents; the fence
# scanner carries a long history of corrections this file would otherwise
# repeat; and the corpus enumerator already knows that `git ls-files A -- B`
# unions its pathspecs rather than intersecting them.
from check_flattened_lists import (  # noqa: E402
    DEFAULT_ROOTS,
    REPO_ROOT,
    UnmeasurableFile,
    _VOID,
    _expand,
    _is_thematic_break,
    _LIST_ITEM_RE,
    _load_renderer,
    _render,
    _strip_fences,
    corpus,
)

import html.parser  # noqa: E402

_SENTINEL = "ZqxCONTPROBE{n:05d}xqZ"
_SENTINEL_STEM = "ZqxCONTPROBE"

# Indent plus any run of blockquote markers.  Stripped before the table
# tests below, because a table inside a blockquote is still a table and its
# rows still lose a surplus cell — `spec/012-Routing.md:1090` is exactly
# that, and treating it as prose made the whole file unmeasurable.
_QUOTE_PREFIX_RE = re.compile(r"^[ \t]*(?:>[ \t]*)*")

# A row of a pipe table, written with the leading and trailing bars this
# corpus uses.  A borderless row (`a | b`) needs no special handling: a
# sentinel appended at the end lands inside its last cell and the cell count
# is unchanged.
_PIPE_ROW_RE = re.compile(r"^\|.*\|[ \t]*$")
_PIPE_FIRST_CELL_RE = re.compile(r"^(\|[^|]*)(\|.*)$")

# `| --- | :-: |` and friends: nothing but bars, dashes, colons and space.
_TABLE_SEP_RE = re.compile(r"^\|?[ \t:|-]*\|[ \t:|-]*$")

# A blockquote line with no content to mark: `>`, `> >`, `>>`.
_BARE_QUOTE_RE = re.compile(r"^[ \t]*>[ \t>]*$")


def _unquoted(line: str) -> tuple[str, str]:
    """Split a line into its indent-and-blockquote prefix and the rest."""
    prefix = _QUOTE_PREFIX_RE.match(line).group(0)
    return prefix, line[len(prefix):]


class Candidate:
    """One continuation line in the source, and the item it sits under."""

    __slots__ = ("line_no", "indent", "owner", "sentinel", "is_table_row")

    def __init__(self, line_no, indent, owner, sentinel, is_table_row):
        self.line_no = line_no
        self.indent = indent
        self.owner = owner            # the Owner it is indented under
        self.sentinel = sentinel
        self.is_table_row = is_table_row


class Owner:
    """One list-item line in the source."""

    __slots__ = ("line_no", "indent", "sentinel")

    def __init__(self, line_no, indent, sentinel):
        self.line_no = line_no
        self.indent = indent
        self.sentinel = sentinel


# --------------------------------------------------------------------------
# source enumeration
# --------------------------------------------------------------------------


def collect(text: str):
    """Every list item, and every continuation line indented under one.

    The source model is deliberately small, because the RENDERER decides
    every verdict: all this has to get right is which item a line is
    indented under.  A list item at indent I closes every open item at
    indent >= I; so does a non-blank line at indent J, for every item at
    indent >= J — which is what ends the list at a column-0 paragraph or
    heading.  Anything left open owns the line.
    """
    lines = text.splitlines()
    owners: list[Owner] = []
    candidates: list[Candidate] = []
    skipped: list[tuple[int, str]] = []
    stack: list[Owner] = []

    for line_no, content in _strip_fences(lines):
        if not content.strip():
            continue
        m = None if _is_thematic_break(content) else _LIST_ITEM_RE.match(content)
        if m is not None:
            indent = _expand(m.group("indent"))
            while stack and stack[-1].indent >= indent:
                stack.pop()
            owner = Owner(line_no, indent, _SENTINEL.format(n=len(owners) + len(candidates)))
            owners.append(owner)
            stack.append(owner)
            continue

        indent = _expand(content[: len(content) - len(content.lstrip(" \t"))])
        while stack and stack[-1].indent >= indent:
            stack.pop()
        if not stack or indent == 0:
            continue

        _prefix, rest = _unquoted(content)
        if _TABLE_SEP_RE.match(rest):
            skipped.append((line_no, "table separator row"))
            continue
        if _BARE_QUOTE_RE.match(content):
            skipped.append((line_no, "bare blockquote line"))
            continue

        candidates.append(
            Candidate(
                line_no=line_no,
                indent=indent,
                owner=stack[-1],
                sentinel=_SENTINEL.format(n=len(owners) + len(candidates)),
                is_table_row=bool(_PIPE_ROW_RE.match(rest)),
            )
        )
    return owners, candidates, skipped


def inject(text: str, owners, candidates) -> str:
    """Mark every owner and every candidate.

    Appending — rather than inserting before the content — keeps the
    sentinel clear of every construct that has to start a line's content to
    fire.  A pipe row is the one exception and is marked inside its FIRST
    CELL: appended after the trailing bar it becomes a surplus cell, which
    the tables extension drops silently, and a sentinel that never reaches
    the render is indistinguishable from one the walker could not place.
    """
    marks = {}
    for item in list(owners) + list(candidates):
        marks[item.line_no] = item
    out = []
    for idx, raw in enumerate(text.splitlines(keepends=True), start=1):
        item = marks.get(idx)
        if item is None:
            out.append(raw)
            continue
        body = raw.rstrip("\r\n")
        eol = raw[len(body):]
        if getattr(item, "is_table_row", False):
            prefix, rest = _unquoted(body)
            cell = _PIPE_FIRST_CELL_RE.match(rest)
            if cell is not None:
                out.append(
                    prefix + cell.group(1).rstrip() + " " + item.sentinel + " "
                    + cell.group(2) + eol
                )
                continue
        stripped = body.rstrip()
        out.append(stripped + " " + item.sentinel + body[len(stripped):] + eol)
    return "".join(out)


def desentinel(rendered: str) -> str:
    """Remove every sentinel, its injected whitespace, and its slug form.

    The slug arm is not decoration: `toc` lowercases a heading's text into
    its `id` and its permalink href, joining words with `-`, so a sentinel
    on a line python-markdown reads as a heading survives a purely textual
    removal and reads as a structural change when it is nothing of the kind.
    """
    body = _SENTINEL_STEM + r"\d{5}xqZ"
    rendered = re.sub(r"[ \t]*" + body, "", rendered)
    return re.sub(r"-?" + body.lower(), "", rendered)


# --------------------------------------------------------------------------
# HTML walk
# --------------------------------------------------------------------------


class Placement:
    __slots__ = ("depth", "li", "in_code", "in_comment")

    def __init__(self, depth, li, in_code, in_comment):
        self.depth = depth
        self.li = li                  # serial of the INNERMOST enclosing li
        self.in_code = in_code
        self.in_comment = in_comment


class _Walker(html.parser.HTMLParser):
    """Locate each sentinel, and record which `li` INSTANCE holds it.

    Every non-void tag opens a scope keyed by a serial, so two sibling
    `<li>`s are distinguishable even though their tag names are not.  That
    identity is the whole point: a continuation that escapes a child item
    while staying inside its grandparent's `li` is inside AN `li`, and only
    the instance says it is the wrong one.
    """

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack: list[tuple[str, int]] = []
        self._serial = 0
        self.placements: dict[str, Placement] = {}

    def handle_starttag(self, tag, attrs):
        if tag in _VOID:
            return
        self._serial += 1
        self.stack.append((tag, self._serial))

    def handle_startendtag(self, tag, attrs):
        return

    def handle_endtag(self, tag):
        if tag in _VOID:
            return
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                del self.stack[i:]
                return

    def _record(self, data, in_comment):
        if _SENTINEL_STEM not in data:
            return
        lis = [ser for tag, ser in self.stack if tag == "li"]
        placement = Placement(
            depth=sum(1 for tag, _ in self.stack if tag in ("ul", "ol")),
            li=lis[-1] if lis else None,
            in_code=any(tag in ("pre", "code") for tag, _ in self.stack),
            in_comment=in_comment,
        )
        for found in re.findall(_SENTINEL_STEM + r"\d{5}xqZ", data):
            self.placements[found] = placement

    def handle_data(self, data):
        self._record(data, False)

    def handle_comment(self, data):
        self._record(data, True)


def place(rendered, marked):
    walker = _Walker()
    walker.feed(rendered)
    walker.close()
    if walker.stack:
        unclosed = ", ".join(sorted({tag for tag, _ in walker.stack}))
        raise UnmeasurableFile(
            f"rendered HTML leaves <{unclosed}> unclosed, so the tag stack "
            "cannot be trusted"
        )
    missing = [m for m in marked if m.sentinel not in walker.placements]
    if missing:
        raise UnmeasurableFile(
            f"{len(missing)} of {len(marked)} sentinels did not survive the "
            f"render (first at line {missing[0].line_no})"
        )
    return walker.placements


# --------------------------------------------------------------------------
# the rule
# --------------------------------------------------------------------------


class Escape:
    __slots__ = ("line_no", "parent_line", "reason", "is_table_row", "owner_is_text")

    def __init__(self, line_no, parent_line, reason, is_table_row, owner_is_text):
        self.line_no = line_no
        self.parent_line = parent_line
        self.reason = reason
        self.is_table_row = is_table_row
        # The owner does not currently render as a list item at all.  See
        # REPAIRING A SITE WHOSE OWNER IS NOT A LIST ITEM in the docstring:
        # these are the sites where a repair changes what the owner IS, and
        # the ones a human must read before touching.
        self.owner_is_text = owner_is_text


def _escaped(cand, at, owner_at):
    """The UNION.  Either disjunct alone reads a fraction of the class."""
    if at.in_comment or owner_at.in_comment:
        return None
    owner_is_text = owner_at.li is None
    if at.li is None:
        return Escape(
            cand.line_no,
            cand.owner.line_no,
            f"indented {cand.indent} columns under a list item at "
            f"{cand.owner.indent}, and renders OUTSIDE EVERY list item",
            cand.is_table_row,
            owner_is_text,
        )
    if at.depth < owner_at.depth:
        return Escape(
            cand.line_no,
            cand.owner.line_no,
            f"indented {cand.indent} columns under a list item at "
            f"{cand.owner.indent}, but renders at list depth {at.depth} "
            f"where its item is at {owner_at.depth} — it has left that item",
            cand.is_table_row,
            owner_is_text,
        )
    return None


def analyse(text, md):
    """Escapes in one document.  Raises UnmeasurableFile if it cannot say."""
    owners, candidates, skipped = collect(text)
    if not candidates:
        return [], skipped
    if _SENTINEL_STEM in text:
        raise UnmeasurableFile("source already contains the probe sentinel")

    marked = list(owners) + list(candidates)
    baseline = _render(md, text)
    probed = _render(md, inject(text, owners, candidates))
    if desentinel(probed) != baseline:
        raise UnmeasurableFile(
            "sentinel injection is not structurally inert here, so placement "
            "read off the probed render would not describe the real page"
        )

    at = place(probed, marked)
    escapes = []
    for cand in candidates:
        found = _escaped(cand, at[cand.sentinel], at[cand.owner.sentinel])
        if found is not None:
            escapes.append(found)
    return escapes, skipped


def blocks(escapes):
    """Contiguous escaped lines under one owner, as (first, last, rows)."""
    out = []
    for esc in escapes:
        if out and esc.parent_line == out[-1][3] and esc.line_no == out[-1][1] + 1:
            out[-1][1] = esc.line_no
            out[-1][2] += 1
        else:
            out.append([esc.line_no, esc.line_no, 1, esc.parent_line])
    return out


# --------------------------------------------------------------------------
# self-test
# --------------------------------------------------------------------------

# (name, markdown, expected escaped-line count)
_CASES = [
    # --- the class itself, both directions -------------------------------
    ("a four-column continuation stays in its item", "- parent\n\n    continuation\n\n- sibling\n", 0),
    ("a two-column continuation escapes", "- parent\n\n  continuation\n\n- sibling\n", 1),
    ("a three-column continuation escapes", "- parent\n\n   continuation\n\n- sibling\n", 1),
    ("a one-column continuation escapes", "- parent\n\n continuation\n", 1),
    (
        "a hard-wrapped escaped paragraph counts every line",
        "- parent\n\n  one\n  two\n  three\n\n- sibling\n",
        3,
    ),
    (
        "the repair of that paragraph is clean",
        "- parent\n\n    one\n    two\n    three\n\n- sibling\n",
        0,
    ),
    # --- the trap in (1): a LOOSE list is not a defect --------------------
    (
        "a healthy loose list, whose items all gain a <p>, is clean",
        "- alpha\n\n    body of alpha\n\n- beta\n\n    body of beta\n",
        0,
    ),
    # --- the depth disjunct: inside an li, but the WRONG one --------------
    (
        "a continuation that leaves a CHILD item but stays in its grandparent",
        "- gp\n    - parent\n\n      continuation\n\n    - psib\n",
        1,
    ),
    (
        "the same continuation at the child's real content column is clean",
        "- gp\n    - parent\n\n        continuation\n\n    - psib\n",
        0,
    ),
    # --- the "outside every li" disjunct: the cascade the depth test misses
    #
    # Reduced from the real shape at `docs/api/re-frame.core.md:765` before
    # #9620 — the `- **On Fresco**` item.  An earlier escape closes the list,
    # so the marker below it cannot interrupt that paragraph and is swallowed
    # into it as literal text; its own continuation is then at depth 0 under
    # an owner at depth 0, and the comparison is between two zeros.
    (
        "a cascade, where an earlier escape swallows the marker below it",
        "- alpha\n\n  escaped paragraph\n- beta\n  lazy line of beta\n",
        2,
    ),
    (
        "the repair of that cascade is clean in both halves",
        "- alpha\n\n    escaped paragraph\n\n- beta\n  lazy line of beta\n",
        0,
    ),
    # --- lazy continuations render INSIDE the li and are NOT defects ------
    ("a lazy two-column continuation is correct", "- parent\n  lazy continuation\n- sibling\n", 0),
    (
        "a lazy continuation under a nested item is correct",
        "- gp\n    - parent\n      lazy\n",
        0,
    ),
    # --- scope -----------------------------------------------------------
    ("prose after a column-0 paragraph is not a continuation", "- parent\n\nProse.\n\n  indented\n", 0),
    ("a continuation-shaped line inside a fence is skipped", "- parent\n\n```text\n  continuation\n```\n", 0),
    (
        "an escaped continuation inside an HTML comment is not a defect",
        "<!--\n- parent\n\n  continuation\n-->\n\nProse.\n",
        0,
    ),
    ("a document with no list has no candidates", "Just prose.\n\n  indented prose.\n", 0),
    # --- tables ----------------------------------------------------------
    (
        "a two-column table escapes its item and its rows are counted",
        "- parent\n\n  | h | k |\n  | - | - |\n  | 1 | 2 |\n",
        2,
    ),
    (
        "the same table at four columns is clean",
        "- parent\n\n    | h | k |\n    | - | - |\n    | 1 | 2 |\n",
        0,
    ),
    # --- code ------------------------------------------------------------
    (
        "an indented code block inside its item is clean",
        "- parent\n\n        code sample\n",
        0,
    ),
]


def self_test() -> int:
    md, _cfg = _load_renderer()
    failures = 0
    checks = 0

    for name, source, expected in _CASES:
        checks += 1
        try:
            got = len(analyse(source, md)[0])
        except UnmeasurableFile as exc:
            print(f"FAIL  {name}: unmeasurable ({exc})")
            failures += 1
            continue
        if got != expected:
            print(f"FAIL  {name}: expected {expected} escape(s), got {got}")
            failures += 1
        else:
            print(f"ok    {name} ({got} escape(s))")

    # THE UNION IS THE RULE, AND EACH DISJUNCT ALONE IS A HOLLOW GATE.
    # Re-derived here rather than asserted, on the two shapes that separate
    # them: neither half detects both, so a regression to either one reds
    # this check instead of quietly halving the corpus count.
    checks += 1
    cascade = "- alpha\n\n  escaped paragraph\n- beta\n  lazy line of beta\n"
    grandparent = "- gp\n    - parent\n\n      continuation\n\n    - psib\n"
    depth_only = []
    li_only = []
    for source in (cascade, grandparent):
        owners, candidates, _ = collect(source)
        probed = _render(md, inject(source, owners, candidates))
        at = place(probed, list(owners) + list(candidates))
        depth_only.append(
            sum(1 for c in candidates if at[c.sentinel].depth < at[c.owner.sentinel].depth)
        )
        li_only.append(sum(1 for c in candidates if at[c.sentinel].li is None))
    union = [len(analyse(cascade, md)[0]), len(analyse(grandparent, md)[0])]
    if depth_only == [1, 1] and li_only == [2, 0] and union == [2, 1]:
        print(
            "ok    each disjunct alone MISSES a case the other catches: on "
            f"(cascade, grandparent) depth-only reads {depth_only}, "
            f"outside-li-only reads {li_only}, the union reads {union}"
        )
    else:
        print(
            f"FAIL  union check: depth-only {depth_only}, outside-li-only "
            f"{li_only}, union {union}"
        )
        failures += 1

    # Injection must be provably inert, and the probe must SAY SO when it is
    # not rather than reporting a clean file.
    checks += 1
    try:
        analyse("- item\n\n  <div>\n", md)
    except UnmeasurableFile:
        print("ok    an unbalanced document is reported UNMEASURABLE")
    else:
        print("FAIL  an unbalanced document was measured anyway")
        failures += 1

    # A sentinel appended after a table row's trailing bar is silently
    # dropped, which is why pipe rows are marked inside their first cell.
    checks += 1
    row = "- parent\n\n  | h | k |\n  | - | - |\n  | 1 | 2 |\n"
    owners, candidates, _ = collect(row)
    if all(c.is_table_row for c in candidates) and _SENTINEL_STEM in _render(
        md, inject(row, owners, candidates)
    ):
        print("ok    a table row is marked inside its first cell and survives the render")
    else:
        print("FAIL  a table row's sentinel did not survive the render")
        failures += 1

    # The slug arm of desentinel: a marked line python-markdown reads as a
    # heading carries its sentinel into `id` and the permalink href.
    checks += 1
    try:
        analyse("- parent\n\n  > #8029 an issue reference\n", md)
    except UnmeasurableFile as exc:
        print(f"FAIL  a heading-shaped continuation was refused: {exc}")
        failures += 1
    else:
        print("ok    a continuation python-markdown reads as a heading is measurable")

    # THE READ-THIS-FIRST QUALIFIER.  On the cascade, `- beta` is swallowed
    # and renders as text, so its continuation is marked; on the plain
    # two-column escape the owner is a healthy `<li>` and it is not.  That
    # partition is what stands between this tool and the wrapped-prose
    # false-marker class, which no render-side reading can catch.
    checks += 1
    flagged = [e.owner_is_text for e in analyse(cascade, md)[0]]
    plain = [e.owner_is_text for e in analyse("- parent\n\n  continuation\n", md)[0]]
    if flagged == [False, True] and plain == [False]:
        print("ok    a site whose OWNER is not a list item is marked, and only that one")
    else:
        print(f"FAIL  owner-is-text qualifier reads {flagged} / {plain}")
        failures += 1

    # A DOCUMENTED NON-DETECTION, pinned so it cannot become an accident.
    # A fence short of its item's content column closes the list exactly as a
    # paragraph does, but `_strip_fences` blanks the fence INCLUDING its
    # opener, so no line survives to mark.  spec/012-Routing.md carries the
    # real instance.  See WHAT THIS DETECTOR DOES NOT SEE AT ALL.
    checks += 1
    escaped_fence = "1. one\n\n   ```text\n   sample\n   ```\n\n2. two\n"
    if len(analyse(escaped_fence, md)[0]) == 0 and "<ol>" in _render(md, escaped_fence):
        print("ok    an escaped FENCE is not seen — documented, not accidental")
    else:
        print("FAIL  the escaped-fence non-detection changed")
        failures += 1

    # The skip list is reported rather than silent.
    checks += 1
    _esc, skipped = analyse("- parent\n\n  >\n  > quoted\n", md)
    if [reason for _line, reason in skipped] == ["bare blockquote line"]:
        print("ok    an unmarkable line is skipped BY NAME and counted")
    else:
        print(f"FAIL  skip list reads {skipped}")
        failures += 1

    print(f"\n{checks - failures}/{checks} checks passed")
    return 1 if failures else 0


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Report markdown continuation blocks that render outside their list item."
    )
    parser.add_argument("paths", nargs="*", help="files to check (default: the whole corpus)")
    parser.add_argument("--self-test", action="store_true", help="run the built-in checks")
    parser.add_argument("--quiet", action="store_true", help="per-file totals only, no sites")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    md, cfg = _load_renderer()
    files = [Path(p).resolve() for p in args.paths] if args.paths else corpus(cfg, DEFAULT_ROOTS)

    per_file = Counter()
    lines = tables = block_count = unmeasurable = skipped_lines = owner_text = 0
    for path in files:
        rel = path.relative_to(REPO_ROOT).as_posix() if path.is_relative_to(REPO_ROOT) else str(path)
        try:
            escapes, skipped = analyse(path.read_text(encoding="utf-8"), md)
        except UnmeasurableFile as exc:
            print(f"{rel}: UNMEASURABLE — {exc}")
            unmeasurable += 1
            continue
        skipped_lines += len(skipped)
        if not escapes:
            continue
        per_file[rel] = len(escapes)
        lines += len(escapes)
        tables += sum(1 for e in escapes if e.is_table_row)
        owner_text += sum(1 for e in escapes if e.owner_is_text)
        block_count += len(blocks(escapes))
        if not args.quiet:
            for esc in escapes:
                print(f"{rel}:{esc.line_no}: ESCAPED CONTINUATION — {esc.reason}")
                print(f"    the list item it is indented under is at {rel}:{esc.parent_line}")
                if esc.owner_is_text:
                    print(
                        "    READ THE SOURCE FIRST: that owner does not render as a "
                        "list item either, so a repair changes what it IS. Confirm it "
                        "is a marker and not a `+`/`-`/`*` in wrapped prose."
                    )

    if per_file:
        print("\nper file, worst first:")
        for rel, n in sorted(per_file.items(), key=lambda kv: (-kv[1], kv[0])):
            print(f"{n:6d}  {rel}")

    print(
        f"\n{len(files)} file(s) scanned, {lines} escaped continuation line(s) "
        f"in {block_count} block(s) across {len(per_file)} file(s); "
        f"{tables} of those lines are table rows and {owner_text} sit under an "
        f"owner that is not itself a list item. "
        f"{unmeasurable} unmeasurable, {skipped_lines} line(s) not markable."
    )
    if lines:
        print(
            "Python-Markdown wants four columns per nesting level for "
            "CONTINUATION content too; short of that the list item CLOSES and "
            "the block renders after it."
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
