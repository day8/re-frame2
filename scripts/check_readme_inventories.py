#!/usr/bin/env python3
"""Ratchet README inventory claims against their source of truth.

The README cross-link sweep (rf2-27ptt) and its precursor count-fix beads
(rf2-0iaxe / rf2-9tn8t / rf2-cjh9v) kept finding the same defect *by hand*:
a README's layout-map / directory-tree block had silently fallen out of
sync with the dirs actually on disk, or a "N <noun>" prose claim no longer
matched the enumerated list it introduces.  Examples caught manually:

  * testbeds/README.md + the top-level layout map omitted the three SSR
    testbeds (ssr_basic / ssr_hydration_mismatch / ssr_multi_frame).
  * implementation/README.md layout omitted ssr-ring / test-quiet / security.
  * tools/re-frame2-pair-mcp/README.md prose said 25/23 tools; the
    Tool-surface table had 24 rows for a 28-tool registry.
  * tools/story-mcp/README.md per-category "at a glance" said Docs (9)
    (omitting explain-variant); the four category counts summed to 19,
    not the claimed 20.

This script generalises those into three automated checks, driven by small
registries (`LAYOUT_CHECKS` / `COUNT_CHECKS` / `NAME_SET_CHECKS` below) so it
stays maintainable rather than a pile of special-cases:

  1. LAYOUT-MAP <-> DISK BIJECTION.  For a README carrying a fenced
     layout/directory-tree block, parse the *top-level* (shallowest-indent)
     directory entries listed in the block and assert a bijection with the
     directories actually on disk under a base path.  An explicit per-entry
     ``ignore`` set covers legitimately-undocumented dirs (build tooling,
     private shared libs) so the bijection stays exact, not approximate.

  2. COUNT-NUMERAL CHECK.  Assert that a "N <noun>" claim immediately
     preceding an enumerated list equals the list length.  N may be a digit
     run ("20 tools") or an English number word ("twenty-eight ... ops").
     The enumerated list is either a bullet list of backticked code-spans or
     a markdown table whose body rows are counted.

  3. NAME-SET CHECK.  Assert that the first column of a README table equals
     a name list held OUTSIDE that README — for pair-mcp, the `tools/list`
     contract snapshot at `test/fixtures/tool-names.json`.

     Check 2 alone cannot see the defect this file was written for.  Its
     source of truth is the README's OWN table, so a table that has fallen
     behind its registry while the prose agrees with the table is invisible
     to it: both numbers match, and both are wrong.  That is exactly how the
     pair-mcp table drifted twice (24 rows for a 28-tool registry, then 30
     for 33; rf2-664t7 measured the second).  Check 3 closes it by naming an
     external source of truth, and it reports WHICH names are missing rather
     than only that a count differs.

Exit code:
    0  no violations
    1  at least one violation (printed in file:line form)
    2  invocation / setup error

The script is stdlib-only (no third-party deps) and cross-platform — it
reads files via pathlib and never shells out, so it runs identically on the
Windows dev box and the Linux CI runners.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

# ---------------------------------------------------------------------------
# Fenced-block extraction
# ---------------------------------------------------------------------------

_FENCE_RE = re.compile(r"^(\s*)(```|~~~)(.*)$")


@dataclass
class FencedBlock:
    """A fenced code block: its body lines and 1-based opening-fence line no."""

    open_line: int  # 1-based line number of the opening fence
    lines: list[str]  # body lines (between the fences), fences excluded


def _iter_fenced_blocks(text: str) -> list[FencedBlock]:
    """Return every fenced (``` / ~~~) block in *text*, in document order."""
    blocks: list[FencedBlock] = []
    in_fence = False
    marker = ""
    open_line = 0
    body: list[str] = []
    for i, raw in enumerate(text.splitlines(), start=1):
        m = _FENCE_RE.match(raw)
        if m and not in_fence:
            in_fence = True
            marker = m.group(2)
            open_line = i
            body = []
            continue
        if in_fence and m and m.group(2) == marker:
            in_fence = False
            blocks.append(FencedBlock(open_line=open_line, lines=body))
            continue
        if in_fence:
            body.append(raw)
    return blocks


def _section_body(text: str, heading: str) -> tuple[int, str] | None:
    """Return (1-based start-line, body-text) of the markdown section whose
    heading text equals *heading* (any H-level), up to the next heading of
    the same-or-shallower level.  None if no such heading exists.
    """
    lines = text.splitlines()
    heading_re = re.compile(r"^(#{1,6})[ \t]+(.+?)[ \t]*#*[ \t]*$")
    start = None
    start_level = 0
    for i, line in enumerate(lines):
        m = heading_re.match(line)
        if not m:
            continue
        level = len(m.group(1))
        title = m.group(2).strip()
        if start is None:
            if title == heading:
                start = i
                start_level = level
            continue
        # We are inside the target section; stop at next same/shallower heading.
        if level <= start_level:
            return (start + 1, "\n".join(lines[start:i]))
    if start is not None:
        return (start + 1, "\n".join(lines[start:]))
    return None


# ---------------------------------------------------------------------------
# Check 1 — layout-map <-> disk bijection
# ---------------------------------------------------------------------------

# A top-level dir entry inside a layout/tree fenced block.  We match the
# shallowest-indent lines whose first token ends in "/".  Tree-drawing
# glyphs (├── └── │) are tolerated as leading decoration.
_TREE_GLYPHS = "│├└─│├└─"


def _layout_top_level_dirs(block: FencedBlock) -> set[str]:
    """Extract the top-level *directory* entries from a layout/tree block.

    "Top-level" = the shallowest tier at which the layout enumerates the base
    directory's immediate children.  This deliberately ignores deeper
    file/sub-dir listing so the bijection is robust against inner tree detail
    churning — the historical drift was always at the artefact-dir tier,
    never the leaf files.

    Two stylistic forms appear in this corpus, handled uniformly via a
    computed *tier*:

      * Indent-tree (``testbeds/`` / ``implementation/`` / ``examples/``)::

            implementation/
              core/            <-- tier 1 (the children we want)
                deps.edn       <-- tier 2 (ignored)

      * Glyph-tree (``tools/story-mcp/``)::

            tools/story-mcp/   <-- root label
            ├── deps.edn       <-- glyph prefix ⇒ tier 1
            ├── spec/          <-- the children we want
            └── src/

    A leading root label (a single dir line carrying a path separator, e.g.
    ``tools/story-mcp/``, or any sole shallowest line that has deeper
    siblings) is the tier-0 root and never an entry.  Glyph-prefixed lines
    are promoted one tier deeper than their raw indent so the glyph-tree and
    indent-tree forms collapse to the same model.
    """
    entries: list[tuple[int, str, bool]] = []  # (raw_indent, name, has_glyph)
    for raw in block.lines:
        # Strip a trailing inline comment (``  <-- ...`` or ``  ; ...``).
        line = re.split(r"\s{2,}(?:<--|;)", raw, maxsplit=1)[0]
        stripped = line.rstrip()
        if not stripped.strip(_TREE_GLYPHS + " \t"):
            continue
        raw_indent = len(stripped) - len(stripped.lstrip(" \t"))
        rest = stripped.lstrip(" \t")
        has_glyph = bool(rest) and rest[0] in _TREE_GLYPHS
        token = rest.lstrip(_TREE_GLYPHS + " ")
        token = token.split()[0] if token.split() else ""
        if token.endswith("/") and len(token) > 1:
            name = token[:-1]
            # A path-prefixed name (``tools/story-mcp``) is always a root
            # label, never a child entry.
            entries.append((raw_indent, name, has_glyph))

    if not entries:
        return set()

    # Tier = raw_indent, with glyph-prefixed lines bumped past any bare
    # tier-0 root label so ``├── spec/`` outranks ``tools/story-mcp/``.
    def tier(e: tuple[int, str, bool]) -> int:
        raw_indent, _name, has_glyph = e
        return raw_indent + (1 if has_glyph else 0)

    tiers = sorted({tier(e) for e in entries})
    root_tier = tiers[0]
    root_names = [e for e in entries if tier(e) == root_tier]
    deeper = [t for t in tiers if t > root_tier]

    # A lone shallowest entry that has deeper siblings is the root label;
    # likewise any shallowest entry carrying a path separator.
    root_is_label = (
        (len(root_names) == 1 and bool(deeper))
        or all("/" in name for _i, name, _g in root_names)
    )
    target_tier = deeper[0] if (root_is_label and deeper) else root_tier
    return {
        e[1] for e in entries
        if tier(e) == target_tier and "/" not in e[1]
    }


# Gitignored build-artifact directory names that may appear on disk after a
# local `npm install` + CLJS build but are NOT part of the documented layout
# map (rf2-wv7d7z).  The bijection must skip these or every post-build local
# fast-pr run fails the README-inventory check ("node_modules missing from
# README") — a false failure CI dodged only by running the check before
# `npm install`, which is fragile job-ordering luck.
#
# These mirror the gitignored entries in the repo-root and per-artefact
# `.gitignore` files (`/node_modules/`, `/out/`, `/target/`, `/classes/`).
# Dotdir build state (`.cpcache/`, `.shadow-cljs/`) is already excluded by the
# leading-dot filter in `_disk_dirs`, so only the non-dot names need listing.
#
# An explicit, deterministic skip-set is used rather than shelling out to
# `git check-ignore`: the latter reads the *working-tree* `.gitignore` files,
# which on Windows checkouts carry CRLF line terminators that defeat git's
# pattern match (the trailing `\r` becomes part of the pattern) — exactly the
# environment-dependent fragility this fix exists to remove.  The set is tiny,
# toolchain-bound, and keeps the script stdlib-only / no-shell-out (its
# original portability promise).
_GITIGNORED_BUILD_DIRS = frozenset({
    "node_modules",  # npm install
    "out",           # shadow-cljs / cljs build output
    "target",        # lein / tools.build output
    "classes",       # AOT-compiled class output
})


def _disk_dirs(base: Path) -> set[str]:
    """Directory names directly under *base* (excluding dotfiles + gitignored
    build artefacts).

    Dotfiles (``.cpcache`` / ``.shadow-cljs`` / …) are skipped by the
    leading-dot filter; gitignored build-output dirs that have no leading dot
    (``node_modules`` / ``out`` / …) are skipped via ``_GITIGNORED_BUILD_DIRS``
    so the layout-map bijection holds regardless of local build state
    (rf2-wv7d7z).
    """
    if not base.is_dir():
        return set()
    return {
        p.name
        for p in base.iterdir()
        if p.is_dir()
        and not p.name.startswith(".")
        and p.name not in _GITIGNORED_BUILD_DIRS
    }


# ---------------------------------------------------------------------------
# Check 2 — count-numeral vs enumerated-list length
# ---------------------------------------------------------------------------

_NUMBER_WORDS = {
    "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5,
    "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11,
    "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15,
    "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19,
}
_TENS_WORDS = {
    "twenty": 20, "thirty": 30, "forty": 40, "fifty": 50, "sixty": 60,
    "seventy": 70, "eighty": 80, "ninety": 90,
}


def parse_number_word(word: str) -> int | None:
    """Parse an English number word (0..99, incl. hyphenated forms) to int.

    Returns None if *word* is not a recognised number word.  Handles
    "twenty-eight", "twenty eight", and the simple words.
    """
    w = word.strip().lower()
    if w in _NUMBER_WORDS:
        return _NUMBER_WORDS[w]
    if w in _TENS_WORDS:
        return _TENS_WORDS[w]
    parts = re.split(r"[-\s]+", w)
    if len(parts) == 2 and parts[0] in _TENS_WORDS and parts[1] in _NUMBER_WORDS:
        ones = _NUMBER_WORDS[parts[1]]
        if 1 <= ones <= 9:
            return _TENS_WORDS[parts[0]] + ones
    return None


def parse_numeral(token: str) -> int | None:
    """Parse a digit run or English number word into an int."""
    token = token.strip()
    if token.isdigit():
        return int(token)
    return parse_number_word(token)


def count_bullet_code_spans(body: str) -> int:
    """Count backtick code-spans inside a bullet/`at a glance`-style list.

    For the "category breakdown" idiom each list *item* enumerates one or
    more comma-separated `code-span` names; the count claim is the number of
    names.  We count every distinct backtick span across the list's items.
    """
    return len(re.findall(r"`[^`]+`", body))


def table_body_rows(lines: list[str]) -> list[str]:
    """Return the body rows of the first markdown pipe-table in *lines*.

    A table is: a header row (``| ... |``), a delimiter row (``|---|---|``),
    then N body rows.  Returns the body rows only (stripped), in order.
    """
    rows: list[str] = []
    in_table = False
    seen_delim = False
    for raw in lines:
        line = raw.strip()
        is_row = line.startswith("|") and line.count("|") >= 2
        if not in_table:
            if is_row:
                in_table = True
                seen_delim = False
            continue
        if not is_row:
            break  # table ended
        if not seen_delim:
            # The row right after the header should be the delimiter.
            if re.fullmatch(r"\|[\s:|\-]+\|", line):
                seen_delim = True
            continue
        rows.append(line)
    return rows


def count_table_body_rows(lines: list[str]) -> int:
    """Count body rows of the first markdown pipe-table in *lines*."""
    return len(table_body_rows(lines))


def table_first_column_names(lines: list[str]) -> list[str]:
    """Extract the first column of each body row as a bare name.

    The idiom these tables use is a leading backticked code-span —
    ``| `discover-app` | ... | ... |``.  Returns the span's text with the
    backticks stripped; a row whose first cell carries no code-span yields
    its stripped text, so a malformed row surfaces as a mismatch rather
    than vanishing.
    """
    names: list[str] = []
    for row in table_body_rows(lines):
        # ``| a | b |`` splits to ['', ' a ', ' b ', ''] — cell 1 is first.
        cells = row.split("|")
        first = cells[1].strip() if len(cells) > 1 else ""
        m = re.search(r"`([^`]+)`", first)
        names.append(m.group(1) if m else first)
    return names


# ---------------------------------------------------------------------------
# Registry — which READMEs + which blocks to check
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class LayoutCheck:
    """A layout-map <-> disk bijection check on one README section."""

    readme: str           # repo-relative README path
    section: str          # heading text whose fenced block is the layout map
    base_dir: str         # repo-relative dir the entries enumerate
    ignore: frozenset[str] = frozenset()  # on-disk dirs legitimately absent


@dataclass(frozen=True)
class CountCheck:
    """A single 'N <noun>' count-numeral assertion."""

    readme: str
    label: str                       # human label for diagnostics
    # A regex with a single capture group around the numeral token, matched
    # against the whole README text.  The match's *end* anchors where the
    # enumerated list begins (for bullet-style) — see ``counter``.
    claim_re: str
    counter: Callable[[str, re.Match], int]  # (full_text, claim_match) -> count


# Counter strategies ---------------------------------------------------------

def _count_bullet_after(full_text: str, m: re.Match) -> int:
    """Count backtick spans on the single logical bullet the claim heads.

    Used for the per-category 'at a glance' idiom:
        ``- **Docs** (10) — `a`, `b`, ... `j`.``
    The names may wrap across continuation lines until the next bullet (a
    line whose first non-space char is ``-``/``*``) or a blank line.
    """
    after = full_text[m.end():]
    out_lines: list[str] = []
    for line in after.splitlines():
        s = line.strip()
        if not out_lines:
            out_lines.append(line)
            continue
        if not s:
            break
        if s[0] in "-*" or s.startswith("#"):
            break
        out_lines.append(line)
    return count_bullet_code_spans("\n".join(out_lines))


def _count_named_table(section_heading: str) -> Callable[[str, re.Match], int]:
    """Counter that counts body rows of the table under *section_heading*."""

    def counter(full_text: str, _m: re.Match) -> int:
        sec = _section_body(full_text, section_heading)
        if sec is None:
            return -1
        return count_table_body_rows(sec[1].splitlines())

    return counter


LAYOUT_CHECKS: tuple[LayoutCheck, ...] = (
    LayoutCheck(
        readme="testbeds/README.md",
        section="Layout",
        base_dir="testbeds",
    ),
    LayoutCheck(
        readme="implementation/README.md",
        section="Layout",
        base_dir="implementation",
        # scripts/ is build/test tooling, intentionally not an artefact entry.
        ignore=frozenset({"scripts"}),
    ),
    LayoutCheck(
        readme="examples/README.md",
        section="Layout",
        base_dir="examples",
        # _shared/ is a private cross-example helper lib, not a bucket.
        ignore=frozenset({"_shared"}),
    ),
)


COUNT_CHECKS: tuple[CountCheck, ...] = (
    # story-mcp — per-category 'at a glance' breakdown.
    CountCheck(
        readme="tools/story-mcp/README.md",
        label="Dev category count",
        claim_re=r"\*\*Dev\*\*\s*\((\d+)\)",
        counter=_count_bullet_after,
    ),
    CountCheck(
        readme="tools/story-mcp/README.md",
        label="Docs category count",
        claim_re=r"\*\*Docs\*\*\s*\((\d+)\)",
        counter=_count_bullet_after,
    ),
    CountCheck(
        readme="tools/story-mcp/README.md",
        label="Testing category count",
        claim_re=r"\*\*Testing\*\*\s*\((\d+)\)",
        counter=_count_bullet_after,
    ),
    CountCheck(
        readme="tools/story-mcp/README.md",
        label="Write category count",
        claim_re=r"\*\*Write\*\*\s*\((\d+)(?:,[^)]*)?\)",
        counter=_count_bullet_after,
    ),
    # pair-mcp — prose '33 ... ops' vs Tool-surface table rows.  The numeral
    # group admits BOTH forms this module's docstring promises: a digit run
    # and an English number word.  It was word-only (``[a-z\-]+``) until
    # rf2-664t7, which is narrower than ``parse_numeral`` accepts and than
    # the contract advertises — so when the GDS restyle rewrote 'thirty' to a
    # digit (rf2-aw1ez) the count did not become wrong, it became UNREADABLE,
    # and the check reported 'claim pattern not found' rather than a
    # mismatch.  Widening here is not loosening the gate: the count is still
    # compared, and ``parse_numeral`` still rejects a non-numeral.
    CountCheck(
        readme="tools/re-frame2-pair-mcp/README.md",
        label="prose tool count vs Tool-surface table",
        claim_re=r"exposes the (\d+|[a-z\-]+)\s+re-frame2-pair",
        counter=_count_named_table("Tool surface"),
    ),
)


@dataclass(frozen=True)
class NameSetCheck:
    """A README table's first column <-> an external JSON name list.

    The COUNT check above compares a prose claim against the README's own
    table, so a table that has fallen behind its registry is INVISIBLE to
    it — prose and table agree with each other while both under-list the
    source of truth.  That is exactly how the pair-mcp table drifted twice
    (24 rows for a 28-tool registry, then 30 for 33; rf2-664t7), and this
    check is what closes it: the table is compared against a name list
    OUTSIDE the README.
    """

    readme: str           # repo-relative README path
    label: str            # human label for diagnostics
    section: str          # heading whose table carries the names
    source_json: str      # repo-relative JSON file holding the truth
    json_key: str         # key in that file whose value is the name list
    source_note: str = ""  # why that file is the source of truth


NAME_SET_CHECKS: tuple[NameSetCheck, ...] = (
    # pair-mcp Tool-surface table <-> the tools/list contract snapshot.
    #
    # WHY THIS FIXTURE AND NOT registry.cljs.  `tool-names.json` is the
    # canonical `tools/list` snapshot shared by the stdio round-trip test
    # and the cross-server MCP conformance harness (rf2-drke0), and
    # `test/stdio-roundtrip.js` asserts it equals the names a LIVE
    # `tools/list` returns.  So it already tracks `registry/tools` under
    # test, and gating on it keeps this script stdlib-only and free of any
    # coupling to ClojureScript source layout — no regex over `.cljs`.
    NameSetCheck(
        readme="tools/re-frame2-pair-mcp/README.md",
        label="Tool-surface table vs the tools/list contract snapshot",
        section="Tool surface",
        source_json="tools/re-frame2-pair-mcp/test/fixtures/tool-names.json",
        json_key="names",
        source_note=(
            "the tools/list snapshot pinned against a live server by "
            "tools/re-frame2-pair-mcp/test/stdio-roundtrip.js"
        ),
    ),
)


# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------

@dataclass
class Violation:
    readme: str
    line: int
    message: str


def _line_of_offset(text: str, offset: int) -> int:
    """1-based line number containing character *offset*."""
    return text.count("\n", 0, offset) + 1


def _run_layout_check(repo_root: Path, chk: LayoutCheck) -> list[Violation]:
    path = repo_root / chk.readme
    if not path.is_file():
        return [Violation(chk.readme, 0, f"README not found: {path}")]
    text = path.read_text(encoding="utf-8", errors="replace")

    sec = _section_body(text, chk.section)
    if sec is not None:
        sec_start, sec_text = sec
        blocks = _iter_fenced_blocks(sec_text)
        # Offset block line numbers back into the whole-file numbering.
        line_base = sec_start - 1
    else:
        blocks = _iter_fenced_blocks(text)
        line_base = 0
    if not blocks:
        return [Violation(chk.readme, 0,
                          f"no fenced layout block found "
                          f"(section {chk.section!r})")]

    # The layout map is the first fenced block in the (sub)section.
    block = blocks[0]
    listed = _layout_top_level_dirs(block)
    on_disk = _disk_dirs(repo_root / chk.base_dir) - chk.ignore
    listed -= chk.ignore

    block_line = line_base + block.open_line
    violations: list[Violation] = []
    missing = sorted(on_disk - listed)   # on disk but not in the map
    extra = sorted(listed - on_disk)     # in the map but not on disk
    if missing:
        violations.append(Violation(
            chk.readme, block_line,
            f"layout map under {chk.base_dir}/ omits on-disk dir(s): "
            f"{', '.join(missing)}"))
    if extra:
        violations.append(Violation(
            chk.readme, block_line,
            f"layout map under {chk.base_dir}/ lists non-existent dir(s): "
            f"{', '.join(extra)}"))
    return violations


def _run_count_check(repo_root: Path, chk: CountCheck) -> list[Violation]:
    path = repo_root / chk.readme
    if not path.is_file():
        return [Violation(chk.readme, 0, f"README not found: {path}")]
    text = path.read_text(encoding="utf-8", errors="replace")

    m = re.search(chk.claim_re, text)
    if m is None:
        return [Violation(chk.readme, 0,
                          f"{chk.label}: claim pattern not found "
                          f"(/{chk.claim_re}/)")]
    claimed = parse_numeral(m.group(1))
    if claimed is None:
        return [Violation(chk.readme, _line_of_offset(text, m.start()),
                          f"{chk.label}: claim {m.group(1)!r} is not a numeral")]
    actual = chk.counter(text, m)
    if actual < 0:
        return [Violation(chk.readme, _line_of_offset(text, m.start()),
                          f"{chk.label}: could not locate the enumerated list "
                          f"to count")]
    if claimed != actual:
        return [Violation(chk.readme, _line_of_offset(text, m.start()),
                          f"{chk.label}: prose claims {claimed} "
                          f"({m.group(1)!r}) but enumerated list has {actual}")]
    return []


def _run_name_set_check(repo_root: Path, chk: NameSetCheck) -> list[Violation]:
    path = repo_root / chk.readme
    if not path.is_file():
        return [Violation(chk.readme, 0, f"README not found: {path}")]
    src = repo_root / chk.source_json
    if not src.is_file():
        return [Violation(chk.readme, 0,
                          f"{chk.label}: source of truth not found: {src}")]
    try:
        payload = json.loads(src.read_text(encoding="utf-8"))
    except (ValueError, OSError) as exc:
        return [Violation(chk.readme, 0,
                          f"{chk.label}: could not read {chk.source_json}: {exc}")]
    expected_list = payload.get(chk.json_key) if isinstance(payload, dict) else None
    if not isinstance(expected_list, list) or not expected_list:
        return [Violation(chk.readme, 0,
                          f"{chk.label}: {chk.source_json} has no non-empty "
                          f"{chk.json_key!r} list")]

    text = path.read_text(encoding="utf-8", errors="replace")
    sec = _section_body(text, chk.section)
    if sec is None:
        return [Violation(chk.readme, 0,
                          f"{chk.label}: section {chk.section!r} not found")]
    start_line, body = sec
    listed = table_first_column_names(body.splitlines())
    if not listed:
        return [Violation(chk.readme, start_line,
                          f"{chk.label}: no table body rows under "
                          f"{chk.section!r}")]

    expected = set(expected_list)
    actual = set(listed)
    violations: list[Violation] = []
    missing = sorted(expected - actual)
    extra = sorted(actual - expected)
    if missing:
        violations.append(Violation(
            chk.readme, start_line,
            f"{chk.label}: table is MISSING {len(missing)} name(s) present in "
            f"{chk.source_note or chk.source_json}: {', '.join(missing)}"))
    if extra:
        violations.append(Violation(
            chk.readme, start_line,
            f"{chk.label}: table lists {len(extra)} name(s) absent from "
            f"{chk.source_note or chk.source_json}: {', '.join(extra)}"))
    dupes = sorted({n for n in listed if listed.count(n) > 1})
    if dupes:
        violations.append(Violation(
            chk.readme, start_line,
            f"{chk.label}: table repeats {len(dupes)} name(s): "
            f"{', '.join(dupes)}"))
    return violations


def check(repo_root: Path, verbose: bool = False) -> list[Violation]:
    """Run every registered check; return the flat list of violations."""
    violations: list[Violation] = []
    for chk in LAYOUT_CHECKS:
        vs = _run_layout_check(repo_root, chk)
        if verbose and not vs:
            sys.stderr.write(
                f"  OK  layout: {chk.readme} § {chk.section} "
                f"<-> {chk.base_dir}/\n")
        violations.extend(vs)
    for chk in COUNT_CHECKS:
        vs = _run_count_check(repo_root, chk)
        if verbose and not vs:
            sys.stderr.write(
                f"  OK  count:  {chk.readme} — {chk.label}\n")
        violations.extend(vs)
    for chk in NAME_SET_CHECKS:
        vs = _run_name_set_check(repo_root, chk)
        if verbose and not vs:
            sys.stderr.write(
                f"  OK  names:  {chk.readme} — {chk.label}\n")
        violations.extend(vs)
    return violations


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Ratchet README layout-map <-> disk bijection and 'N <noun>' "
            "count-numeral claims (rf2-198k3)."
        ),
    )
    parser.add_argument(
        "--repo-root", default=None,
        help="Path to the repo root.  Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true",
        help="Print each passing check to stderr.",
    )
    parser.add_argument(
        "--self-test", action="store_true",
        help="Run the bundled unit self-tests and exit.",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _run_self_tests(verbose=args.verbose)

    if args.repo_root:
        repo_root = Path(args.repo_root).resolve()
    else:
        repo_root = Path(__file__).resolve().parent.parent

    if not (repo_root / "mkdocs.yml").is_file():
        sys.stderr.write(
            f"error: {repo_root} does not look like the re-frame2 repo root "
            "(no mkdocs.yml).  Pass --repo-root explicitly.\n"
        )
        return 2

    violations = check(repo_root, verbose=args.verbose)
    if violations:
        sys.stderr.write(
            f"\n{len(violations)} README inventory violation(s) found:\n\n"
        )
        for v in violations:
            loc = f"{v.readme}:{v.line}" if v.line else v.readme
            sys.stderr.write(f"  {loc}\n      {v.message}\n")
        sys.stderr.write(
            "\nFix: update the README's layout map / count claim to match the "
            "source of truth (the dirs on disk, or the enumerated list it "
            "introduces).  This guard exists so that drift turns CI red "
            "instead of being found by hand (rf2-198k3).\n"
        )
        return 1

    if args.verbose:
        sys.stderr.write("no README inventory violations.\n")
    return 0


# ---------------------------------------------------------------------------
# Self-tests (rf2-198k3) — pure-function unit checks, no external fixtures.
#
# The full-tree run (`check`) is itself the integration test (it must pass on
# the hand-fixed tree and go red on a deliberate drift — see the bead's
# teeth-proof).  These unit self-tests pin the parsing primitives so a future
# refactor can't silently weaken them.
# ---------------------------------------------------------------------------

def _run_self_tests(verbose: bool = False) -> int:
    failures = 0

    def expect(name: str, got, want) -> None:
        nonlocal failures
        if got == want:
            if verbose:
                sys.stderr.write(f"self-test PASS: {name}\n")
        else:
            sys.stderr.write(
                f"self-test FAIL: {name}: got {got!r}, want {want!r}\n")
            failures += 1

    # parse_numeral / parse_number_word
    expect("digit", parse_numeral("28"), 28)
    expect("word-eleven", parse_numeral("eleven"), 11)
    expect("word-twenty-eight", parse_numeral("twenty-eight"), 28)
    expect("word-twenty eight", parse_numeral("twenty eight"), 28)
    expect("word-ninety-nine", parse_numeral("ninety-nine"), 99)
    expect("not-a-number", parse_numeral("frame"), None)
    expect("twenty", parse_numeral("twenty"), 20)

    # The pair-mcp claim pattern must admit BOTH numeral forms the module
    # docstring promises (rf2-664t7).  It was word-only, so the GDS restyle's
    # digit rewrite made the count unreadable rather than wrong (rf2-aw1ez) —
    # the check reported 'claim pattern not found' and stopped comparing.
    _pair_claim = next(c.claim_re for c in COUNT_CHECKS
                       if c.readme.endswith("re-frame2-pair-mcp/README.md"))

    def _claim(text: str):
        m = re.search(_pair_claim, text)
        return m.group(1) if m else None

    expect("pair-claim-word",
           _claim("exposes the thirty-three re-frame2-pair ops"), "thirty-three")
    expect("pair-claim-digit",
           _claim("exposes the 33 re-frame2-pair ops"), "33")
    expect("pair-claim-wraps-newline",
           _claim("exposes the 33\nre-frame2-pair ops"), "33")
    # Widened, not loosened: a non-numeral still reaches parse_numeral and is
    # still rejected there, so the gate keeps its teeth.
    expect("pair-claim-non-numeral-rejected",
           parse_numeral(_claim("exposes the many re-frame2-pair ops") or ""),
           None)

    # first-column name extraction backing the NameSetCheck (rf2-664t7)
    named = [
        "| MCP tool | What |",
        "|---|---|",
        "| `discover-app` | x |",
        "| `read-ui`      | y |",
        "| plain-text     | z |",
    ]
    expect("table-first-col", table_first_column_names(named),
           ["discover-app", "read-ui", "plain-text"])

    # layout top-level dir extraction — root label + indented entries
    blk = FencedBlock(open_line=1, lines=[
        "testbeds/",
        "  alpha/        <-- a comment",
        "  beta/         <-- b comment",
        "  gamma/        <-- c comment",
    ])
    expect("layout-dirs", _layout_top_level_dirs(blk), {"alpha", "beta", "gamma"})

    # layout extraction with deeper file/sub-dir detail — only depth-1 dirs.
    blk2 = FencedBlock(open_line=1, lines=[
        "impl/",
        "  core/",
        "    deps.edn        a file, not a dir",
        "    src/",
        "  adapters/",
        "    reagent/",
        "  schemas/",
    ])
    expect("layout-dirs-deep", _layout_top_level_dirs(blk2),
           {"core", "adapters", "schemas"})

    # tree-glyph style block
    blk3 = FencedBlock(open_line=1, lines=[
        "tools/story-mcp/",
        "├── deps.edn",
        "├── spec/",
        "└── src/",
    ])
    expect("layout-dirs-glyph", _layout_top_level_dirs(blk3), {"spec", "src"})

    # table body-row counting (header + delimiter + N rows)
    table = [
        "| MCP tool | What |",
        "|---|---|",
        "| `a` | x |",
        "| `b` | y |",
        "| `c` | z |",
    ]
    expect("table-rows", count_table_body_rows(table), 3)

    # bullet code-span counting
    bullet = "- **Docs** (3) — `x`, `y`, `z`."
    m = re.search(r"\*\*Docs\*\*\s*\((\d+)\)", bullet)
    expect("bullet-spans", _count_bullet_after(bullet, m), 3)

    # bullet code-span counting across a wrapped continuation line
    bullet2 = "- **Docs** (4) — `a`, `b`,\n  `c`, `d`.\n- **Next** (1) — `e`."
    m2 = re.search(r"\*\*Docs\*\*\s*\((\d+)\)", bullet2)
    expect("bullet-spans-wrapped", _count_bullet_after(bullet2, m2), 4)

    # _section_body picks the right slice
    doc = "# Top\n\n## Layout\n\n```\nfoo/\n  bar/\n```\n\n## Next\nx\n"
    sec = _section_body(doc, "Layout")
    expect("section-found", sec is not None, True)
    if sec is not None:
        blocks = _iter_fenced_blocks(sec[1])
        expect("section-block-count", len(blocks), 1)

    # _disk_dirs skips gitignored build artefacts + dotdirs but keeps real
    # source dirs (rf2-wv7d7z): a post-build local tree carries node_modules/
    # out/ .shadow-cljs/ .cpcache/ alongside the documented source dirs, and
    # only the latter must show up in the bijection.
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        for d in ("core", "adapters",            # documented source dirs
                  "node_modules", "out",          # gitignored build artefacts
                  "target", "classes",            # gitignored build artefacts
                  ".shadow-cljs", ".cpcache"):     # gitignored dotdirs
            (base / d).mkdir()
        expect("disk-dirs-skips-build-artefacts",
               _disk_dirs(base), {"core", "adapters"})

    # NameSetCheck end-to-end (rf2-664t7) — the regression this check exists
    # for is a table that under-lists its registry while the PROSE agrees
    # with the table, which the count check cannot see by construction.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "tools").mkdir()
        chk = NameSetCheck(
            readme="tools/R.md", label="T", section="Tool surface",
            source_json="tools/names.json", json_key="names")

        def _write(rows: list[str]) -> None:
            (root / "tools" / "R.md").write_text(
                "# T\n\n## Tool surface\n\n| A | B |\n|---|---|\n"
                + "".join(f"| `{r}` | x |\n" for r in rows),
                encoding="utf-8")

        (root / "tools" / "names.json").write_text(
            '{"names": ["alpha", "beta", "gamma"]}', encoding="utf-8")

        _write(["alpha", "beta", "gamma"])
        expect("nameset-in-sync", _run_name_set_check(root, chk), [])

        _write(["alpha", "beta"])
        vs = _run_name_set_check(root, chk)
        expect("nameset-missing-count", len(vs), 1)
        expect("nameset-missing-names", "gamma" in vs[0].message, True)

        _write(["alpha", "beta", "gamma", "delta"])
        vs = _run_name_set_check(root, chk)
        expect("nameset-extra-count", len(vs), 1)
        expect("nameset-extra-names", "delta" in vs[0].message, True)

        _write(["alpha", "beta", "gamma", "gamma"])
        vs = _run_name_set_check(root, chk)
        expect("nameset-duplicate", any("repeats" in v.message for v in vs), True)

        # A missing / unreadable source of truth must be a VIOLATION, never a
        # silent pass — an absent fixture is no verdict, not a green one.
        _write(["alpha", "beta", "gamma"])
        missing_src = NameSetCheck(
            readme="tools/R.md", label="T", section="Tool surface",
            source_json="tools/nope.json", json_key="names")
        expect("nameset-absent-source-is-violation",
               len(_run_name_set_check(root, missing_src)), 1)

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write("all self-tests passed.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
