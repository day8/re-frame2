#!/usr/bin/env python3
"""Fail on a nested markdown list that renders FLAT.

Python-Markdown wants exactly four columns per nesting level, regardless of
how wide the parent's list marker is.  A `- parent` with a two- or
three-column child does not render a shallower nest — it renders a SIBLING,
so a checklist silently tells the reader something different from what its
author indented.  A `1. ` parent is not a three-column parent either, and
eight columns under a column-0 parent is not a grandchild: it is an indented
CODE BLOCK, backticks and all.

NOTHING ELSE IN THIS REPOSITORY SEES THIS (rf2-gyq4).  A flattened list is
valid markup, so there is no warning for `mkdocs build --strict` to promote
and no broken target for `check_doc_slugs.py` to resolve; measured on a tree
carrying one deliberately re-flattened item, both exited 0.  That is how 205
sites accumulated before rf2-3bq6 and rf2-qsjr swept them.

    Exit code:
        0  no flattened lists
        1  at least one flattened list
        2  invocation / setup error

AN UNMEASURABLE FILE IS REPORTED, NAMED AND COUNTED, BUT DOES NOT FAIL THE
BUILD, and that is a deliberate contract rather than an oversight.  "I cannot
grade this file" is not the same statement as "this file has a flattened
list": failing on it would demand a repair from an author whose document has
no defect and who has no repair available — the same shape as reporting
`spec/009-Instrumentation.md:295`, and the same reason it is refused there.
The verdict is loud instead: its own line, its own reason, and a count in the
summary.  The hole this leaves is bounded and was measured — 1 file of 291
corpus-wide, `docs/EP/EP-template.md`, named on every run.

MEASURED BY RENDERING, NEVER BY COUNTING SPACES.  The predecessor
indentation heuristic read 209 sites across 45 files with 23 false
positives; the rendering detector read 205 across 49 with none.  The
difference is entirely that one reasons about columns and the other about
what the reader is shown.  The method:

  1. Load the extension list through mkdocs' OWN config loader rather than
     transcribing `markdown_extensions` from mkdocs.yml.  MkDocs adds `toc`,
     `tables` and `fenced_code` as builtins, so the real list is ELEVEN
     extensions where the yaml declares eight (plus toc).  Transcribing it
     renders a different document from the one that ships.
  2. Enumerate list-item lines in the source, with fenced blocks blanked by
     `check_doc_slugs._strip_fences` — the corpus's existing, much-corrected
     notion of "code, not prose", inherited rather than rebuilt.
  3. Inject a unique sentinel at the end of each item's first line.
  4. VALIDATE THE INJECTION.  The sentinel render, with sentinels textually
     removed, must equal the baseline render, and the walker's tag stack must
     balance.  Otherwise the file is UNMEASURABLE and says so — a gate needs
     an explicit third verdict, not a silent pass.
  5. Walk the rendered HTML.  Per sentinel record the enclosing `ul`/`ol`
     count, whether it is inside an `li`, and which root list element it
     belongs to.  Text inside an HTML comment is never rendered, so a flat
     list there is not a defect.
  6. DEFECT: two document-consecutive items sharing a root list, where the
     source indents the second deeper and the render does not nest it —
     either because the depth did not increase (FLATTENED) or because the
     second item stopped being a list item at all (ESCAPED, which is what an
     over-indented child becomes: a code block).

SCOPE IS WHAT THE STRICT BUILD RENDERS, derived rather than listed.  The
roots are `docs/`, `spec/` and `migration/`: `mkdocs_hooks.py`'s
`on_pre_build` stages the latter two into `docs_dir`, so they are part of the
built site on any invocation, local or CI.  Everything `exclude_docs` keeps
out is skipped, and that set is read FROM `mkdocs.yml` through the loaded
config, never transcribed — a prose list of those trees has already drifted
once in this repo, naming two of the six.  A flattened list in an excluded
tree reaches no reader.

ONE KNOWN SITE IS NOT A DEFECT AND MUST NOT RED THIS GATE, and it is handled
by the RULE rather than by an allowlist — see `_pair_defect` and the
`SAME_INDENT_PARENT` note there.  `spec/009-Instrumentation.md:295` already
sits at base + 4*depth: there is no reindent to make, so no repair exists for
a gate to demand.  Reporting it would make this gate red on arrival and teach
the next reader to ignore it.
"""

from __future__ import annotations

import argparse
import contextlib
import html.parser
import io
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# The trees the strict build renders.  spec/ and migration/ are here because
# mkdocs_hooks.py stages them into docs_dir; see the module docstring.
DEFAULT_ROOTS = ("docs", "spec", "migration")

# Alphanumeric only, so markdown has nothing to interpret in it, and long
# enough that a collision with corpus prose is not a live worry.  A collision
# is checked for anyway.
_SENTINEL = "ZqxFLATLISTPROBE{n:05d}xqZ"
_SENTINEL_STEM = "ZqxFLATLISTPROBE"

# A list item whose marker is followed by whitespace and then content.  `---`
# cannot match (the marker must be followed by whitespace); a thematic break
# written `* * *` can, and is screened out separately by `_is_thematic_break`.
_LIST_ITEM_RE = re.compile(r"^(?P<indent>[ \t]*)(?P<marker>[-*+]|\d{1,9}[.)])(?P<gap>[ \t]+)(?P<body>\S.*)$")

_THEMATIC_RE = re.compile(r"^[ \t]*(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$")

# HTML elements that never open a scope.  Anything not listed is treated as a
# container, which is what makes an unclosed `<Title>` placeholder show up as
# a stack imbalance instead of silently reparenting every sentinel below it.
_VOID = frozenset(
    "area base br col embed hr img input link meta param source track wbr".split()
)


class UnmeasurableFile(Exception):
    """The probe cannot answer for this file, and declines to guess."""


# --------------------------------------------------------------------------
# renderer
# --------------------------------------------------------------------------


def _load_renderer():
    """Return a `markdown.Markdown` configured exactly as the site build is.

    Loading through `mkdocs.config.load_config` rather than reading the yaml
    is the point: the builtins it injects are half the difference between
    this render and the published one.
    """
    try:
        import markdown  # noqa: WPS433  (optional dependency, reported below)
        import mkdocs.config
    except ImportError as exc:  # pragma: no cover - environment problem
        raise SystemExit(
            f"check_flattened_lists: missing dependency ({exc}). "
            "Install the docs requirements: pip install -r requirements.txt"
        )

    stderr_noise = io.StringIO()
    with contextlib.redirect_stderr(stderr_noise):
        cfg = mkdocs.config.load_config(str(REPO_ROOT / "mkdocs.yml"))
    md = markdown.Markdown(
        extensions=cfg["markdown_extensions"],
        extension_configs=cfg["mdx_configs"],
    )
    return md, cfg


def _render(md, text: str) -> str:
    md.reset()
    return md.convert(text)


# --------------------------------------------------------------------------
# source enumeration
# --------------------------------------------------------------------------


def _strip_fences(lines: list[str]) -> list[tuple[int, str]]:
    """Blank fenced-code lines, borrowing check_doc_slugs' scanner.

    Imported rather than reimplemented so the two gates can never disagree
    about where a fence is — that function carries a long history of
    corrections (indented fences inside list items, fences inside
    blockquotes, unbalanced openers) that this gate would otherwise repeat.
    """
    scripts_dir = str(Path(__file__).resolve().parent)
    if scripts_dir not in sys.path:
        sys.path.insert(0, scripts_dir)
    from check_doc_slugs import _strip_fences as _impl  # noqa: E402

    return _impl(lines)


def _is_thematic_break(line: str) -> bool:
    return bool(_THEMATIC_RE.match(line))


def _expand(indent: str) -> int:
    """Indent width in columns, tabs counting to the next four-column stop."""
    col = 0
    for ch in indent:
        col = (col // 4 + 1) * 4 if ch == "\t" else col + 1
    return col


class Item:
    """One list-item line in the source."""

    __slots__ = ("line_no", "indent", "marker", "sentinel")

    def __init__(self, line_no: int, indent: int, marker: str, sentinel: str):
        self.line_no = line_no
        self.indent = indent
        self.marker = marker
        self.sentinel = sentinel


def collect_items(text: str) -> list[Item]:
    """Every list-item line outside a fence, in document order."""
    lines = text.splitlines()
    items: list[Item] = []
    for line_no, content in _strip_fences(lines):
        if not content or _is_thematic_break(content):
            continue
        m = _LIST_ITEM_RE.match(content)
        if m is None:
            continue
        items.append(
            Item(
                line_no=line_no,
                indent=_expand(m.group("indent")),
                marker=m.group("marker"),
                sentinel=_SENTINEL.format(n=len(items)),
            )
        )
    return items


def inject(text: str, items: list[Item]) -> str:
    """Append ` <sentinel>` to each item's first line, before trailing space.

    Appending — rather than inserting before the item's content — keeps the
    sentinel clear of every construct that has to start a line's content to
    fire: emphasis runs, code spans, link openers, task-list checkboxes.  The
    leading space keeps it clear of whatever the line ENDS with, an unclosed
    HTML tag included.  Both halves are why the injection is inert in all but
    a handful of files, and the inertness check is what proves it per file
    rather than per argument.
    """
    lines = text.splitlines(keepends=True)
    by_line = {item.line_no: item for item in items}
    out = []
    for idx, raw in enumerate(lines, start=1):
        item = by_line.get(idx)
        if item is None:
            out.append(raw)
            continue
        body = raw.rstrip("\r\n")
        eol = raw[len(body):]
        stripped = body.rstrip()
        trailing = body[len(stripped):]
        out.append(stripped + " " + item.sentinel + trailing + eol)
    return "".join(out)


def desentinel(rendered: str) -> str:
    """Remove every sentinel and the whitespace injected in front of it."""
    return re.sub(r"[ \t]*" + _SENTINEL_STEM + r"\d{5}xqZ", "", rendered)


# --------------------------------------------------------------------------
# HTML walk
# --------------------------------------------------------------------------


class Placement:
    __slots__ = ("depth", "root", "in_li", "in_code", "in_comment")

    def __init__(self, depth: int, root, in_li: bool, in_code: bool, in_comment: bool):
        self.depth = depth
        self.root = root
        self.in_li = in_li
        self.in_code = in_code
        self.in_comment = in_comment


class _Walker(html.parser.HTMLParser):
    """Locate each sentinel in the rendered tree.

    Every non-void tag opens a scope keyed by a serial number, so two
    sibling `<ul>`s are distinguishable even though their tag names are not —
    which is what lets the rule ask whether two items share a ROOT list
    rather than merely both being in one.
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

    def _record(self, data: str, in_comment: bool) -> None:
        if _SENTINEL_STEM not in data:
            return
        lists = [(tag, ser) for tag, ser in self.stack if tag in ("ul", "ol")]
        placement = Placement(
            depth=len(lists),
            root=lists[0][1] if lists else None,
            in_li=any(tag == "li" for tag, _ in self.stack),
            in_code=any(tag in ("pre", "code") for tag, _ in self.stack),
            in_comment=in_comment,
        )
        for found in re.findall(_SENTINEL_STEM + r"\d{5}xqZ", data):
            self.placements[found] = placement

    def handle_data(self, data):
        self._record(data, False)

    def handle_comment(self, data):
        self._record(data, True)


def place(rendered: str, items: list[Item]) -> dict[str, Placement]:
    walker = _Walker()
    walker.feed(rendered)
    walker.close()
    if walker.stack:
        unclosed = ", ".join(sorted({tag for tag, _ in walker.stack}))
        raise UnmeasurableFile(
            f"rendered HTML leaves <{unclosed}> unclosed, so the tag stack "
            "cannot be trusted"
        )
    # WHY A FILE LANDS HERE, recorded so the next reader does not re-diagnose
    # it.  `docs/EP/EP-template.md` opens `# EP-NNNN: <Title>`, which
    # python-markdown passes through as a raw `<title>` element in the page
    # BODY.  Python 3.14's html.parser treats `title` as an RCDATA element, so
    # everything after it — `</h1>`, every list, every HTML comment, the rest
    # of the document — arrives as one run of TEXT inside that element.  Every
    # sentinel would then read depth 0 and "not inside an li", which is not a
    # benign misreading: it is the shape this gate reports as ESCAPED.  The
    # balance check is what stands between that and a page of manufactured
    # defects, so it stays.
    missing = [item for item in items if item.sentinel not in walker.placements]
    if missing:
        raise UnmeasurableFile(
            f"{len(missing)} of {len(items)} sentinels did not survive the "
            f"render (first at line {missing[0].line_no})"
        )
    return walker.placements


# --------------------------------------------------------------------------
# the rule
# --------------------------------------------------------------------------


class Defect:
    __slots__ = ("line_no", "parent_line", "kind", "detail")

    def __init__(self, line_no: int, parent_line: int, kind: str, detail: str):
        self.line_no = line_no
        self.parent_line = parent_line
        self.kind = kind
        self.detail = detail


def _pair_defect(prev: Item, cur: Item, prev_at: Placement, cur_at: Placement):
    """Grade one document-consecutive pair, or return None.

    SAME_INDENT_PARENT.  The rule asks whether the SOURCE indents `cur`
    deeper than `prev` and the RENDER declines to nest it.  Both halves are
    load-bearing, and the second one is what keeps
    `spec/009-Instrumentation.md:295` out of this report without an
    allowlist: that item already sits at base + 4*depth relative to its
    parent, so there is no reindent that would change the render, and a gate
    that demands a repair which does not exist is a gate nobody can make
    green.  What defeats the nest there is the 43-item LOOSE list around it,
    whose four-column continuation blocks hold the content column open —
    measured, the depth does not move at ANY indent from 2 through 8.  That
    is rf2-luch's class (an escaped continuation block), not this one, and it
    is detected by asking whether a repair EXISTS rather than by naming the
    file: the pair is only a defect when `cur` is indented deeper than a
    FOUR-COLUMN step would need, i.e. when moving it to `prev.indent + 4`
    would be a real move.

    An allowlist naming the file and line was the alternative, and it is
    worse in both directions: it would go stale the moment the surrounding
    list is edited, and any allowance broad enough to survive that edit is
    broad enough to hide the next real site.
    """
    if prev_at.in_comment or cur_at.in_comment:
        return None
    if cur.indent <= prev.indent:
        return None

    # The repair this gate would demand: put `cur` one four-column step in
    # from its parent.  Where `cur` is ALREADY there, no reindent exists, so
    # there is nothing to report — see SAME_INDENT_PARENT above.
    if cur.indent == prev.indent + 4:
        return None

    if not cur_at.in_li:
        where = "an indented code block" if cur_at.in_code else "outside any list item"
        return Defect(
            cur.line_no,
            prev.line_no,
            "ESCAPED",
            f"indented {cur.indent} columns under a parent at {prev.indent}; "
            f"renders as {where}. Python-Markdown wants exactly "
            f"{prev.indent + 4}.",
        )
    if not prev_at.in_li:
        return None
    if cur_at.root != prev_at.root:
        return None
    if cur_at.depth > prev_at.depth:
        return None
    return Defect(
        cur.line_no,
        prev.line_no,
        "FLATTENED",
        f"indented {cur.indent} columns under a parent at {prev.indent}, but "
        f"renders at the same depth ({cur_at.depth}) — a SIBLING, not a "
        f"child. Python-Markdown wants exactly {prev.indent + 4}.",
    )


def analyse(text: str, md) -> list[Defect]:
    """Defects in one document.  Raises UnmeasurableFile if it cannot say."""
    items = collect_items(text)
    if not items:
        return []
    if _SENTINEL_STEM in text:
        raise UnmeasurableFile("source already contains the probe sentinel")

    baseline = _render(md, text)
    probed = _render(md, inject(text, items))
    if desentinel(probed) != baseline:
        raise UnmeasurableFile(
            "sentinel injection is not structurally inert here, so nesting "
            "read off the probed render would not describe the real page"
        )

    placements = place(probed, items)
    defects: list[Defect] = []
    for prev, cur in zip(items, items[1:]):
        defect = _pair_defect(prev, cur, placements[prev.sentinel], placements[cur.sentinel])
        if defect is not None:
            defects.append(defect)
    return defects


# --------------------------------------------------------------------------
# corpus
# --------------------------------------------------------------------------


def _tracked_markdown(roots) -> list[Path]:
    """Git-tracked .md files under the roots.

    One pathspec per root and the extension filtered in Python: `git ls-files
    '*.md' -- docs` UNIONS its pathspecs rather than intersecting them
    (CLAUDE.md instrument item (m)), and returns a plausible, much larger
    number while looking scoped.
    """
    out = subprocess.run(
        ["git", "ls-files", "--", *roots],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    return [REPO_ROOT / line for line in out.splitlines() if line.endswith(".md")]


def _docs_relative(path: Path) -> str:
    """Where this source file lands inside docs_dir once staged."""
    rel = path.relative_to(REPO_ROOT).as_posix()
    if rel.startswith("docs/"):
        return rel[len("docs/"):]
    return rel  # spec/… and migration/… stage under those same names


def corpus(cfg, roots) -> list[Path]:
    spec = cfg["exclude_docs"]
    keep = []
    for path in _tracked_markdown(roots):
        if spec is not None and spec.match_file(_docs_relative(path)):
            continue
        keep.append(path)
    return sorted(keep)


# --------------------------------------------------------------------------
# self-test
# --------------------------------------------------------------------------

_CASES = [
    # (name, markdown, expected defect count)
    ("four-column child nests", "- parent\n    - child\n", 0),
    ("two-column child is flat", "- parent\n  - child\n", 1),
    ("three-column child is flat", "- parent\n   - child\n", 1),
    (
        "six-column grandchild of a four-column child is flat",
        "- parent\n    - child\n      - grandchild\n",
        1,
    ),
    (
        "eight columns under a column-0 parent is a code block",
        "- parent\n        - swallowed\n",
        1,
    ),
    (
        "ordered parent is not a three-column parent",
        "1. parent\n   1. child\n",
        1,
    ),
    (
        "ordered parent with four columns nests",
        "1. parent\n    1. child\n",
        0,
    ),
    (
        "the repair of the code-block case is clean",
        "- parent\n    - child\n        - grandchild\n",
        0,
    ),
    (
        "list-shaped lines inside a fence are not items",
        "Prose.\n\n```text\n- parent\n  - child\n```\n",
        0,
    ),
    (
        "a flat list inside an HTML comment is not a defect",
        "<!--\n- parent\n  - child\n-->\n\nProse.\n",
        0,
    ),
    (
        "a dedent to a sibling is not a defect",
        "- parent\n    - child\n- sibling\n",
        0,
    ),
    (
        "two separate top-level lists are not a pair",
        "- alpha\n\n## Heading\n\n  - beta\n",
        0,
    ),
    (
        "a deeper item that already sits at parent+4 is not reported",
        "- parent\n    - child\n",
        0,
    ),
    (
        "emphasis at the end of an item does not defeat the probe",
        "- parent **bold**\n  - child `code`\n",
        1,
    ),
    (
        "a link at the end of an item does not defeat the probe",
        "- parent [text](x.md)\n  - child\n",
        1,
    ),
    (
        "a task-list-shaped item is still measured",
        "- [ ] parent\n  - [ ] child\n",
        1,
    ),
    (
        "a hard line break at the end of an item survives",
        "- parent  \n  continued\n  - child\n",
        1,
    ),
    ("a thematic break is not a list item", "para\n\n* * *\n\npara\n", 0),
    ("a single-level list is clean", "- one\n- two\n- three\n", 0),
    (
        "a correctly nested three-level list is clean",
        "- one\n    - two\n        - three\n- four\n",
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
            got = len(analyse(source, md))
        except UnmeasurableFile as exc:
            print(f"FAIL  {name}: unmeasurable ({exc})")
            failures += 1
            continue
        if got != expected:
            print(f"FAIL  {name}: expected {expected} defect(s), got {got}")
            failures += 1
        else:
            print(f"ok    {name} ({got} defect(s))")

    # Both directions on one document: the same list, flattened and repaired.
    flat = "Intro.\n\n- alpha\n  - beta\n    - gamma\n\nOutro.\n"
    good = "Intro.\n\n- alpha\n    - beta\n        - gamma\n\nOutro.\n"
    checks += 2
    if len(analyse(flat, md)) == 0:
        print("FAIL  negative control: the flattened document reported clean")
        failures += 1
    else:
        print("ok    negative control: the flattened document is reported")
    if len(analyse(good, md)) != 0:
        print("FAIL  positive control: the repaired document reported a defect")
        failures += 1
    else:
        print("ok    positive control: the repaired document is clean")

    # The injection must be provably inert, and the checker must SAY SO when
    # it is not rather than reporting a clean file.
    checks += 1
    try:
        analyse("- item\n\n<div>\n", md)
    except UnmeasurableFile:
        print("ok    an unbalanced document is reported UNMEASURABLE")
    else:
        print("FAIL  an unbalanced document was measured anyway")
        failures += 1

    checks += 1
    if desentinel(_render(md, inject("- a\n  - b\n", collect_items("- a\n  - b\n")))) == _render(
        md, "- a\n  - b\n"
    ):
        print("ok    desentinel(probe render) == baseline render")
    else:
        print("FAIL  desentinel(probe render) != baseline render")
        failures += 1

    print(f"\n{checks - failures}/{checks} checks passed")
    return 1 if failures else 0


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Fail on a nested markdown list that renders flat."
    )
    parser.add_argument("paths", nargs="*", help="files to check (default: the whole corpus)")
    parser.add_argument("--self-test", action="store_true", help="run the built-in checks")
    parser.add_argument("--verbose", action="store_true", help="name every file scanned")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    md, cfg = _load_renderer()
    if args.paths:
        files = [Path(p).resolve() for p in args.paths]
    else:
        files = corpus(cfg, DEFAULT_ROOTS)

    total = 0
    unmeasurable = 0
    for path in files:
        rel = path.relative_to(REPO_ROOT).as_posix() if path.is_relative_to(REPO_ROOT) else str(path)
        try:
            defects = analyse(path.read_text(encoding="utf-8"), md)
        except UnmeasurableFile as exc:
            print(f"{rel}: UNMEASURABLE — {exc}")
            unmeasurable += 1
            continue
        for defect in defects:
            print(f"{rel}:{defect.line_no}: {defect.kind} — {defect.detail}")
            print(f"    parent list item is at {rel}:{defect.parent_line}")
            total += 1
        if args.verbose and not defects:
            print(f"{rel}: ok")

    print(
        f"\n{len(files)} file(s) scanned, {total} flattened list(s), "
        f"{unmeasurable} unmeasurable."
    )
    if total:
        print(
            "Python-Markdown wants exactly four columns per nesting level, "
            "whatever the parent's marker width."
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
