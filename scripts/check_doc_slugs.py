#!/usr/bin/env python3
"""Validate in-repo markdown links — both target files and anchor slugs.

Walks the docs corpus, builds a per-file heading-slug index for every
heading (H1-H6) using the exact same slugifier the MkDocs build uses
(pymdownx.slugs.slugify with case=lower), then scans every
[text](file.md) and [text](file.md#anchor) link and reports:

    * BROKEN TARGET — the .md file the link points at does not exist.
    * BROKEN ANCHOR — the file exists but the #anchor isn't a real slug.

Target-file validation was added under rf2-unge8 after the cross-link
audit on 2026-05-12 surfaced a stale `[text](file.md)` (no anchor) ref
the anchor-only validator could not see (docs/core/17a → 19-where-next
after #483 renamed it to 20-where-next).

Hook this into CI and the build fails before such drift ships.

Exit code:
    0  no broken links
    1  at least one broken link (results printed in file:line form)
    2  invocation / setup error

Notes on what is and isn't checked:
    * REPO-ROOT MARKDOWN IS NOT THIS GATE'S (rf2-znup0). The roster is
      DEFAULT_ROOTS plus tools/*/spec (see `_iter_markdown`), so `TESTING.md`,
      `README.md`, `CHANGELOG.md`, `CLAUDE.md`, `AGENTS.md` and
      `SKILL-REDIRECT.md` are never opened here — this gate exits 0 on a broken
      link in any of them. Several dispatch briefs have nominated it as the gate
      for a root-markdown edit and got a green exit code that verified nothing.
      Root markdown appears nowhere in `mkdocs.yml`, so GitHub renders it and
      `scripts/check_readme_links.py` owns it: that gate models GitHub's `-N`
      duplicate-heading suffix where this one models MkDocs' `_N`, and it runs
      on every PR. Widening THIS roster to the repo root was considered and
      rejected — it would double-cover the root `README.md` under two
      conflicting duplicate-suffix rules.
    * Only intra-repo links are validated. External http(s) URLs are skipped.
    * Only links to .md files are validated. Code, image, and asset links
      are skipped (their existence is mkdocs' concern, not the slug index's).
    * Same-file anchors (no path, just #foo) are validated against the
      current file's index. No target-file check is needed.
    * Cross-tree links resolve relative to the linking file (..  segments
      are honoured). Absolute-style paths (`/docs/foo.md`) resolve
      repo-root-relative.
    * Anchors are decoded before comparison — links written as #foo%20bar
      are compared as #foo bar (rare in this corpus but defensive).
    * Pure section-anchor permalinks (e.g. #fragment-only-anchor) and link
      definitions inside fenced code blocks are skipped.

The script is intentionally dependency-light. Beyond pymdown-extensions
(already pinned in requirements.txt for the MkDocs build) it relies only
on the Python stdlib.
"""

from __future__ import annotations

import argparse
import bisect
import re
import subprocess
import sys
import urllib.parse
from pathlib import Path
from typing import Iterable

try:
    from pymdownx.slugs import slugify as _slugify_factory
except ImportError as exc:  # pragma: no cover - exercised only in dev envs
    sys.stderr.write(
        "error: pymdown-extensions is required.  Install requirements.txt "
        "(`pip install -r requirements.txt`) before running this script.\n"
        f"underlying ImportError: {exc}\n"
    )
    sys.exit(2)

# Match the mkdocs.yml `toc.slugify` configuration exactly:
#   slugify: !!python/object/apply:pymdownx.slugs.slugify {kwds: {case: lower}}
SLUGIFY = _slugify_factory(case="lower")
SLUG_SEP = "-"


# Roots to scan. Order does not matter — files are deduplicated by absolute path.
DEFAULT_ROOTS = (
    "docs",
    "spec",
    "skills",
    "migration",
)

# Tools live one tier deeper: tools/<tool-name>/spec/**/*.md.
TOOLS_ROOT = "tools"

# Paths whose markdown should never be scanned.
EXCLUDE_DIR_NAMES = frozenset({
    "findings",      # excludes exploratory work
    "node_modules",
    "site",          # mkdocs build output
    ".git",
    ".beads",
    "ai",            # excludes AI working artefacts
    "__pycache__",
})

# Auto-generated copies of spec/ + migration/ that mkdocs build stages
# under docs/spec/ + docs/migration/. .gitignored, but defensive in case
# a stale copy survives locally.
EXCLUDE_DIR_REL = frozenset({Path("docs/spec"), Path("docs/migration")})

# ATX heading regex — captures level (count of #) and trimmed title text.
# Fenced code blocks are stripped before this is applied.  We capture every
# heading level H1-H6 because the markdown `toc` extension assigns `id="..."`
# to every heading regardless of `toc_depth` (which only controls which
# headings appear in the rendered TOC).  Anchor links to H4-H6 therefore
# resolve on the published site and must be validated here too.
#
# An optional blockquote prefix is accepted: python-markdown (and therefore
# the MkDocs build) renders a heading *inside* a blockquote — `> #### Foo` —
# as a real `<h4 id="...">` and mints the same slug anchor it would for a
# top-level heading.  Authors use blockquoted headings for "callout" teaching
# boxes (e.g. docs/core/10-http.md has ~7), and committed links target those
# anchors.  Without this prefix the indexer never saw them and every such link
# false-positived as a BROKEN ANCHOR (rf2-869k9m).  The prefix mirrors the
# block-quote tokeniser: leading whitespace, then one or more `>` markers each
# with an optional following space (nested quotes `> > #### Foo` included).
# The title is captured *after* the prefix, so slugification is identical to
# the non-quoted case — no loosening of the slug contract.  A *bare* heading
# still must start at column 0 (the prefix group is only entered when a `>`
# is present); we don't begin tolerating indented `#` lines, which markdown
# treats as code, not headings.
_HEADING_RE = re.compile(r"^(?:[ \t]*>[ \t]?)*(#{1,6})[ \t]+(.+?)[ \t]*#*[ \t]*$")

# Inline HTML anchor — authors use `<a name="foo"></a>` / `<a id="foo"></a>`
# to mint a stable target slug that is independent of (and often shorter than)
# the heading's auto-derived slug.  Browsers and the rendered MkDocs site
# resolve both forms; the script must too.  Examples in this corpus:
# Tool-Pair.md `<a name="time-travel">`, Spec-Schemas.md `<a id="rfstate-node">`.
_HTML_ANCHOR_RE = re.compile(
    r"""<a\s+(?:name|id)\s*=\s*["']([A-Za-z0-9_\-:.]+)["']\s*(?:/\s*)?>""",
    re.IGNORECASE,
)

# Markdown inline link.  Captures destination only.  Reference-style links
# ([text][ref]) are ignored — none in this corpus per spot-check, and a full
# parser is out of scope for a CI guard.
#
# The link TEXT may contain newlines: markdown wraps a paragraph freely, so
# `[§Compiled\nviews](Doc.md#anchor)` is one rendered link.  The negated
# character classes match `\n` already — what mattered was that `_extract_links`
# fed this regex one line at a time, so a wrapped link never matched and was
# never validated (rf2-vpc4c).  It is now run over one joined INLINE BLOCK (see
# `_iter_inline_links` / `_inline_blocks`), which is the largest span the
# renderer parses as a single run of inline text — and therefore the largest
# span over which a link may legitimately wrap.  The DESTINATION still forbids whitespace
# (`[^)\s]+`): CommonMark does not permit a bare destination to wrap, so a
# newline there is not a link the renderer would produce either.
_LINK_RE = re.compile(r"\[(?:[^\]\\]|\\.)*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")

# Fenced code block delimiter (``` or ~~~ optionally followed by language).
_FENCE_RE = re.compile(r"^(```|~~~)")

# Inline-code span (CommonMark §6.1).  A span opens with a run of N
# backticks and closes with the next run of EXACTLY N backticks on the same
# line.  This regex implements that with a back-reference for the closing
# run length and a `(?!`)` look-ahead that forbids a longer closing run
# (which would belong to a different span, not this one).
#
# Used by `_extract_links` to mask out spans like
# `` `[NNN-DocName](NNN-DocName.md)` `` — backticked link-syntax PLACEHOLDERS
# authors use to denote a literal link template, not a real link.  Without
# this mask `_LINK_RE` would treat the placeholder as a real link and
# (often) flag it as BROKEN TARGET.
#
# Scope:
# * Single-line only — this is the ANCHOR-recognition variant, applied by
#   `_strip_inline_code` to one line at a time (see `_scan_rendered_ids`).
#   Link extraction uses `_INLINE_CODE_SPAN_RE` below, which spans the whole
#   joined scan unit because a code span may legally cross a line ending.
# * Backslash escaping of backticks (`\``) is NOT honoured — CommonMark
#   itself does not honour it; backticks are always literal markup.
# * `_strip_fences` has already blanked fenced-block lines, so this regex
#   never sees the language tag of a fence as a stray backtick run.
_INLINE_CODE_RE = re.compile(r"(`+)(?:.+?)\1(?!`)")

# The same span rule, applied over a JOINED scan unit (rf2-8wcbe).  Identical
# to `_INLINE_CODE_RE` but for `re.DOTALL`: CommonMark §6.1 explicitly permits
# a code span to contain a line ending, so
#
#     `[literal
#     link](missing.md)`
#
# is ONE `<code>` element and contains no link at all.  Masking per line could
# not see that — neither backtick has a partner on its own line, so neither was
# masked, the lines were joined, and `_LINK_RE` invented a link the renderer
# never produces.  Masking over the same unit the link regex scans is what makes
# the two agree.  A backtick run with no matching partner anywhere in the unit
# still opens no span (the regex simply does not match), which is also what
# CommonMark does — an unpaired backtick is literal text.
_INLINE_CODE_SPAN_RE = re.compile(r"(`+)(?:.+?)\1(?!`)", re.DOTALL)

# Non-blank block boundaries (rf2-8wcbe).  A blank line is a block boundary in
# every markdown flavour, but it is NOT the only one: these leaf blocks all
# INTERRUPT an open paragraph, so the text either side of one is never a single
# run of inline content and no inline construct — link, emphasis, code span —
# can bridge them.  Recognising them is what stops the join from stitching
#
#     A stray [opening
#     # Separate heading
#     ](missing.md)
#
# (a paragraph, an H1 and a second paragraph — three rendered blocks) into a
# link to `missing.md` that python-markdown never produces.
#
# Deliberately bounded: this is boundary recognition for a CI guard, not a
# markdown parser.  Only the interrupting starters this corpus actually uses are
# listed, each matched at the ≤3-space indent CommonMark allows (4+ spaces is an
# indented code block, which cannot interrupt a paragraph and so is a
# continuation line here, exactly as it is for the renderer).  They split in two
# by whether the block can be CONTINUED by the following line:
#
# `_LEAF_LINE_BLOCK_RE` — blocks that are complete on their own line, so they
# bound the unit on BOTH sides (nothing after them continues them either):
#   * ATX heading — `#` .. `######`.
#   * Thematic break / setext underline — `---`, `***`, `___`, `===`.  A setext
#     underline ENDS the paragraph above it (turning it into a heading), so it
#     bounds the unit either way and the two readings need not be told apart.
#   * Table row — a leading `|`.  A table's rows render as separate cells; no
#     inline construct spans two of them.
#
# `_LIST_ITEM_START_RE` — a container that DOES continue, so it opens a unit but
# does not close one:
#   * `-`/`+`/`*` or `1.`/`1)`.  Each item is its own container, so the corpus's
#     very common one-link-per-bullet lists cannot bleed a stray bracket into the
#     next bullet — while a continuation line of an item carries no marker, so a
#     link wrapping inside one item still joins.
#
# Blockquote entry is handled separately in `_inline_blocks` because it is
# relative, not absolute: entering (or nesting deeper into) a quote interrupts
# the paragraph above, while a following unprefixed line is CommonMark lazy
# continuation of the quoted paragraph and must NOT bound the unit.
_LEAF_LINE_BLOCK_RE = re.compile(
    r"""^[ ]{0,3}(?:
          \#{1,6}(?:[ \t]|$)              # ATX heading
        | (?:\*[ \t]*){3,}$               # thematic break ***
        | (?:-[ \t]*){3,}$                # thematic break --- / setext H2
        | (?:_[ \t]*){3,}$                # thematic break ___
        | =+[ \t]*$                       # setext H1 underline
        | \|                              # table row
    )""",
    re.VERBOSE,
)

_LIST_ITEM_START_RE = re.compile(
    r"""^[ ]{0,3}(?:
          [-+*](?:[ \t]|$)                # bullet list item
        | \d{1,9}[.)](?:[ \t]|$)          # ordered list item
    )""",
    re.VERBOSE,
)

# Leading blockquote markers, e.g. `> ` or `> > `.  The count of `>` is the
# quote depth; the remainder is the quoted line's own content, which is what
# `_BLOCK_START_RE` must be tested against (a `> # Heading` is still a heading).
_QUOTE_PREFIX_RE = re.compile(r"^(?:[ ]{0,3}>[ \t]?)+")


# rf2-t0ituo / rf2-57k74 — cross-handbook compatibility-anchor manifest +
# source-comment link gate.
#
# Progressive-learning reorgs across the handbooks (PR #5916 for Machines, plus
# the Async/API/Routing restructures) renamed or dropped generated heading IDs.
# External bookmarks and in-repo source comments (`.clj` / `.cljs` walkthroughs)
# still target the old slugs, but neither is a markdown link, so the corpus scan
# above never sees the break. Two additions close the hole:
#
#   1. HANDBOOK_COMPAT_ANCHORS — the manifest of stable anchors that MUST resolve
#      on their page. A reorg that drops one fails here even when nothing in the
#      markdown corpus links to it (an external bookmark has no in-repo linker to
#      catch the break). A listed page that is deleted or renamed also fails, so
#      the bookmarks it carries cannot silently vanish (rf2-57k74).
#   2. The tracked-Clojure-source scan (_iter_source_files) — non-markdown source
#      files whose comments point readers at handbook anchors. Every
#      `docs/<handbook>/<page>.md#anchor` substring is resolved and validated
#      against the target page's slug index, so a stale comment link (or a reorg
#      removing its target) fails here too. rf2-zq5i6 widened this from an
#      examples-only glob roster to the whole tracked tree so moving a covered
#      source file cannot silently drop it from validation; rf2-k30r7 then made
#      "tracked" literally true (`git ls-files`, not a filesystem walk), so an
#      untracked scratch file in a worktree cannot fail the scan.
#
# rf2-57k74 generalized the mechanism from Machines-only to the bounded set of
# covered handbooks (Machines, Async, API, Routing); rf2-zq5i6 made that set
# DERIVED from this manifest's page keys (see COMPAT_HANDBOOKS) rather than a
# separate hand-maintained tuple.
HANDBOOK_COMPAT_ANCHORS = {
    "docs/machines/concepts.md": (
        "state-machines",
        "a-machine-at-a-glance",
        "a-guard-is-a-yesno-gate",
        "an-action-returns-effects",
        "name-them-or-inline-them",
        "strict-encapsulation--a-machine-sees-only-its-own-data",
        "the-snapshot--state-data-tags",
        "tags-and-timers",
        "validating-a-machines-completion-output",
        "testing-transitions-are-pure-function-calls",
        "when-to-reach-for-a-machine--and-when-not",
        # Also referenced by the nine_states / websocket example comments.
        "guards-actions-tags-and-after--the-recognition-kit",
    ),
    "docs/machines/tutorial.md": (
        "step-1--your-first-machine",
        "step-2--a-guard-refuse-an-invalid-submit",
        "step-3--an-action-and-the-data-fx-it-returns",
        "step-6--test-it-a-transition-is-a-pure-function",
    ),
    "docs/machines/tags.md": (
        "querying-with-machine-has-tag",
    ),
    "docs/machines/examples.md": (
        "machines-examples",
    ),
    "docs/machines/index.md": (
        "theyre-everywhere",
        "first-class-support",
        "deeply-integrated",
    ),
    # Async handbook — restructured for the progressive learning arc.
    "docs/async/http.md": (
        "managed-http-reference",
        "setup",
        # Also referenced by the realworld_http example comments.
        "one-handler",
        "the-search-box-race-cured",
    ),
    "docs/async/examples.md": (
        "async-http-examples",
    ),
    "docs/async/index.md": (
        "in-this-section",
    ),
    # API reference.
    "docs/api/re-frame.core.md": (
        "with-frame--with-new-frame",
    ),
    # Routing handbook — the progressive guide rewrite.
    "docs/routing/concepts.md": (
        "routing-the-url-is-a-sub",
        "carrying-global-state-through-the-url",
        "converting-routes--urls-by-hand",
        "navigate-in-place-change-the-query-stay-on-the-route",
        "navigating-to-a-raw-url-string",
        "what-happens-in-order",
        "when-a-loader-fails",
    ),
    "docs/routing/examples.md": (
        "routing-examples",
    ),
    "docs/routing/index.md": (
        "in-this-section",
    ),
}

# The bounded set of handbooks whose source-comment links are inventoried, DERIVED
# from the manifest itself (rf2-zq5i6): the handbook is the second path segment of
# each `docs/<handbook>/<page>.md` manifest key. There is no independently
# maintained authority to drift out of lock-step with HANDBOOK_COMPAT_ANCHORS —
# adding a page under a new handbook to the manifest automatically extends the
# source-comment scan's alternation to that handbook, and dropping the last page of
# a handbook stops scanning it. The source-comment scan validates
# `docs/<handbook>/<page>.md#anchor` references only for these; other doc trees are
# the corpus scan's concern, not this gate's.
COMPAT_HANDBOOKS = tuple(sorted({
    page_rel.split("/")[1] for page_rel in HANDBOOK_COMPAT_ANCHORS
}))

# Tracked Clojure source carries `docs/<handbook>/<page>.md#anchor` references in
# its comments. rf2-zq5i6 replaced the examples-only glob roster with a walk of the
# whole tracked tree (generated/vendor dirs pruned below) so that MOVING a source
# file that carries a covered link — e.g. promoting a walkthrough out of examples/
# into implementation/ — cannot silently drop it from validation. The link pattern
# is specific enough that scanning every source tree only matches files that
# actually reference a covered handbook page.
SOURCE_LINK_EXTS = (".clj", ".cljs", ".cljc")

# Generated / vendored / gitignored trees are pruned from the source-comment walk.
# These never carry authored covered links; scanning them would be slow and could
# match vendored copies. Kept an explicit, auditable list per the rf2-zq5i6 design
# ("generated/vendor trees may remain explicitly excluded").
SOURCE_EXCLUDE_DIR_NAMES = frozenset({
    ".git",
    ".beads",
    "node_modules",
    ".shadow-cljs",
    ".cpcache",
    "target",
    "out",
    "dist",
    "site",
    "__pycache__",
    "ai",  # gitignored local-only working tree
    # This script's own self-test fixtures carry DELIBERATELY broken covered
    # links (they are validated via explicit source_files inputs in the
    # self-tests, never via the production walk). Pruning them keeps the
    # production corpus run from resolving a fixture's broken link against the
    # real repo root.
    "_test_fixtures",
})

# A `docs/<handbook>/<page>.md#anchor` substring, however it is embedded (bare in
# a `;;` comment, inside a markdown `[text](../../docs/...)` link, or in parens).
# Any leading path segments (`../../../`) are ignored — the captured `docs/...`
# tail is resolved repo-root-relative. The handbook alternation is built from
# COMPAT_HANDBOOKS (itself derived from the manifest) so the manifest and the link
# scan stay in lock-step by construction.
_HANDBOOK_DOC_LINK_RE = re.compile(
    r"(docs/(?:" + "|".join(COMPAT_HANDBOOKS) + r")/[A-Za-z0-9_-]+\.md)#([A-Za-z0-9_-]+)"
)


# rf2-zq5i6 — render-faithful placement guard for compatibility anchors whose id
# names a specific passage rather than the heading they sit under. Most compat
# anchors sit immediately under (or immediately before) the heading they name, so a
# deep-link lands at the top of that section. A few name a passage that lives
# BETWEEN two headings; for those the anchor must precede the passage, or a
# deep-link scrolls past the named content and onto the next section. Keyed by the
# (page, anchor) manifest entry; the value is a regex the anchor must appear before.
# The set is deliberately tiny and each key MUST be a real manifest anchor (a
# self-test enforces that), so placement and the anchor manifest stay in lock-step.
COMPAT_ANCHOR_PLACEMENT = {
    # The loader-failure bookmark names the explanation of what a failed page
    # read does to the route. EP-0037 R1 retired route `:on-error`, so the
    # passage it names is now the resource-derived readiness projection and its
    # failure rows, not the old "On loader failure ..." lines. The anchor must
    # still precede that passage so `#when-a-loader-fails` lands ON it rather
    # than scrolling past onto the next section (the rf2-zq5i6 bug).
    ("docs/routing/concepts.md", "when-a-loader-fails"):
        re.compile(r"A blocking first load failed"),
}


def _is_excluded(path: Path, repo_root: Path) -> bool:
    """Return True if path lies under a directory we should skip."""
    rel = path.relative_to(repo_root)
    parts = set(rel.parts)
    if parts & EXCLUDE_DIR_NAMES:
        return True
    for ex in EXCLUDE_DIR_REL:
        try:
            rel.relative_to(ex)
            return True
        except ValueError:
            pass
    return False


def _iter_markdown(repo_root: Path) -> Iterable[Path]:
    """Yield absolute paths to every in-scope .md file."""
    roots: list[Path] = []
    for d in DEFAULT_ROOTS:
        p = repo_root / d
        if p.is_dir():
            roots.append(p)
    tools = repo_root / TOOLS_ROOT
    if tools.is_dir():
        for tool in sorted(tools.iterdir()):
            spec = tool / "spec"
            if spec.is_dir():
                roots.append(spec)

    seen: set[Path] = set()
    for root in roots:
        for path in sorted(root.rglob("*.md")):
            if _is_excluded(path, repo_root):
                continue
            ap = path.resolve()
            if ap in seen:
                continue
            seen.add(ap)
            yield path


def _strip_fences(lines: list[str]) -> list[tuple[int, str]]:
    """Return (1-based line-number, content) pairs with fenced code stripped.

    Lines inside a fenced block are replaced with empty strings (preserving
    line numbering) so heading-pattern lines inside code samples don't get
    indexed as real headings.
    """
    out: list[tuple[int, str]] = []
    in_fence = False
    fence_marker = ""
    for i, raw in enumerate(lines, start=1):
        m = _FENCE_RE.match(raw)
        if m:
            marker = m.group(1)
            if not in_fence:
                in_fence = True
                fence_marker = marker
            elif marker == fence_marker:
                in_fence = False
                fence_marker = ""
            out.append((i, ""))
            continue
        out.append((i, "" if in_fence else raw))
    return out


def _mask_html_comments(
    pairs: list[tuple[int, str]],
) -> list[tuple[int, str]]:
    """Blank `<!-- ... -->` regions (which may span lines) with spaces.

    Length and line count are preserved (each masked character becomes a single
    space) so column offsets stay honest and downstream regexes cannot bridge
    across a masked region. Operates on the fence-stripped (line_no, content)
    pairs; comment state carries across lines so a multi-line comment is fully
    masked. An MkDocs/CommonMark HTML comment produces no rendered fragment
    target, so an `<a id="...">` written inside one must not mint a slug
    (rf2-zq5i6).
    """
    out: list[tuple[int, str]] = []
    in_comment = False
    for line_no, content in pairs:
        buf: list[str] = []
        i = 0
        n = len(content)
        while i < n:
            if not in_comment:
                if content.startswith("<!--", i):
                    in_comment = True
                    buf.append("    ")  # blank the 4 chars of "<!--"
                    i += 4
                else:
                    buf.append(content[i])
                    i += 1
            else:
                if content.startswith("-->", i):
                    in_comment = False
                    buf.append("   ")  # blank the 3 chars of "-->"
                    i += 3
                else:
                    buf.append(" ")
                    i += 1
        out.append((line_no, "".join(buf)))
    return out


def _scan_rendered_ids(path: Path) -> dict[str, int]:
    """Map every rendered fragment id on the page to its occurrence count.

    Two anchor mechanisms mint a fragment target, and both are counted so a
    duplicate/colliding id is visible (rf2-zq5i6 — the predecessor collapsed
    everything into a set, hiding collisions):

    1. ATX headings (`# Title`, `## Title`, ...) — slugified with the same
       pymdownx slugifier MkDocs uses, with duplicate-suffix disambiguation
       (`slug`, `slug_1`, `slug_2`, ...) matching pymdownx.toc.
    2. Inline HTML anchors — `<a name="...">` / `<a id="...">` — added by
       authors to mint stable cross-link targets that survive heading renames.
       Both attribute names are recognised (`name` is the legacy HTML form;
       `id` is the modern form; browsers resolve both as fragment targets).

    Recognition is render-faithful (rf2-zq5i6): fenced code is already blanked
    by `_strip_fences`, HTML comments are masked by `_mask_html_comments`, and
    inline-code spans are masked by `_strip_inline_code` before the anchor regex
    runs — anchor-shaped text that the browser never turns into a fragment target
    therefore contributes nothing. Heading slugs are derived from the SAME
    comment-masked line (rf2-ehxs8 — the predecessor matched headings on the raw
    line, so a heading buried in a multiline HTML comment still minted a slug),
    but inline code is deliberately NOT masked away there: inline code inside a
    heading title is part of the rendered slug.
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    counts: dict[str, int] = {}
    seen_counts: dict[str, int] = {}
    fence_stripped = _strip_fences(text.splitlines())
    comment_masked = _mask_html_comments(fence_stripped)
    for _, masked_line in comment_masked:
        # HTML anchor elements can appear on any line (heading or not). Only a
        # RENDERED anchor counts, so recognise them on the comment- and
        # inline-code-masked line.
        anchor_line = _strip_inline_code(masked_line)
        for am in _HTML_ANCHOR_RE.finditer(anchor_line):
            aid = am.group(1)
            counts[aid] = counts.get(aid, 0) + 1

        # Headings are recognised on the comment-masked line (rf2-ehxs8): a
        # heading that lives entirely inside a multiline HTML comment renders no
        # fragment target and must not be indexed. Inline code is NOT masked
        # here — a heading title's inline code IS part of the rendered slug.
        m = _HEADING_RE.match(masked_line)
        if not m:
            continue
        title = m.group(2).strip()
        # attr_list is NOT enabled in mkdocs.yml, so a trailing `{#id}` is not an
        # attribute list — it is literal heading text and the rendered fragment id
        # is the slugified FULL visible title (rf2-ru0wg).  Slugify the title
        # exactly as authored; no explicit-id special case.
        slug = SLUGIFY(title, SLUG_SEP)
        if not slug:
            continue
        # pymdownx.toc disambiguates duplicate HEADING slugs by appending _N
        # starting at the second occurrence.  We mirror that so links to
        # disambiguated anchors validate — and so each disambiguated heading id
        # counts as its own distinct rendered target.
        n = seen_counts.get(slug, 0)
        rid = slug if n == 0 else f"{slug}_{n}"
        counts[rid] = counts.get(rid, 0) + 1
        seen_counts[slug] = n + 1
    return counts


def _slug_index(path: Path) -> set[str]:
    """Return the set of rendered fragment ids on the page (see _scan_rendered_ids)."""
    return set(_scan_rendered_ids(path))


_ANCHOR_ID_RE_CACHE: dict[str, re.Pattern[str]] = {}


def _anchor_id_re(anchor_id: str) -> re.Pattern[str]:
    """Compiled regex matching an explicit `<a id|name="anchor_id">` element."""
    rx = _ANCHOR_ID_RE_CACHE.get(anchor_id)
    if rx is None:
        rx = re.compile(
            r"""<a\s+(?:name|id)\s*=\s*["']"""
            + re.escape(anchor_id)
            + r"""["']""",
            re.IGNORECASE,
        )
        _ANCHOR_ID_RE_CACHE[anchor_id] = rx
    return rx


def _anchor_precedes_passage(
    lines: list[str],
    anchor_id: str,
    passage_re: re.Pattern[str],
) -> bool:
    """True iff an explicit `<a id=anchor_id>` appears before the named passage.

    `lines` are content lines the CALLER has already reduced to rendered source —
    fence-stripped, and (from `_check_compat_anchor_placement`) HTML-comment- and
    inline-code-masked, so only a real fragment target matches. Returns False if the anchor is
    absent, the passage is absent, or the anchor appears at/after the passage —
    each is a placement defect the caller reports (rf2-zq5i6). Kept a pure
    function so the placement teeth can drive it with a correct and a mutated
    (drifted) line list directly.
    """
    anchor_re = _anchor_id_re(anchor_id)
    anchor_idx: int | None = None
    passage_idx: int | None = None
    for i, line in enumerate(lines):
        if anchor_idx is None and anchor_re.search(line):
            anchor_idx = i
        if passage_idx is None and passage_re.search(line):
            passage_idx = i
    if anchor_idx is None or passage_idx is None:
        return False
    return anchor_idx < passage_idx


def _iter_source_files(
    repo_root: Path,
    *,
    exts: tuple[str, ...] = SOURCE_LINK_EXTS,
    exclude_dir_names: frozenset[str] = SOURCE_EXCLUDE_DIR_NAMES,
) -> Iterable[Path]:
    """Yield every GIT-TRACKED Clojure source file under repo_root, pruning
    vendor/generated trees (rf2-zq5i6, rf2-k30r7).

    The roster is Git tracking, not a filesystem walk. An untracked or ignored
    scratch file in a programmer's worktree is not repository content, so it
    MUST NOT be able to fail this scan — a walk-based roster let any stray
    `.clj` anywhere outside a fixed excluded directory raise a false BROKEN
    SOURCE-COMMENT LINK. CI runs on clean clones with no such files, so the
    breakage landed only on the authoring machine. `git ls-files` is the
    authoritative tracked set (the same corpus-scan discipline the residue
    checks follow: generated/untracked copies are not the corpus).

    Exclusions still apply ON TOP of tracking, because a path can be tracked
    AND excluded — the self-test's `node_modules/vendored.cljc` fixture is
    exactly that. Pruning therefore stays a filter in its own right rather
    than a side effect of the tracked roster.

    `git ls-files` is scoped to (and reports relative to) `repo_root`, so the
    self-tests can point this at a fixture subtree unchanged.
    """
    result = subprocess.run(
        ["git", "ls-files", "-z", "--", *(f"*{ext}" for ext in exts)],
        cwd=repo_root,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"git ls-files failed in {repo_root}: {result.stderr.strip()}"
        )
    for rel in sorted(entry for entry in result.stdout.split("\0") if entry):
        if any(part in exclude_dir_names for part in Path(rel).parts[:-1]):
            continue
        path = repo_root / rel
        # A tracked path can be absent from the working tree mid-rename; the
        # index still lists it. Skip rather than crash the whole scan.
        if path.is_file():
            yield path


def _strip_inline_code(line: str) -> str:
    """Mask inline-code spans with spaces so `_LINK_RE` skips them.

    Spaces (not empty replacement) preserve column offsets, which keeps
    column-sensitive diagnostics honest if added later.  Length-preserving
    masking also means `_LINK_RE` cannot bridge across a stripped span.

    Backticked link-syntax PLACEHOLDERS such as
    `` `[NNN-DocName](NNN-DocName.md)` `` are a documentation idiom in this
    corpus — they're literal markup the author wants to TALK about, not a
    real link to resolve.  Without this masking the validator flags every
    such placeholder as BROKEN TARGET (rf2-mqv8s).
    """
    return _INLINE_CODE_RE.sub(lambda m: " " * (m.end() - m.start()), line)


def _quote_depth_and_body(content: str) -> tuple[int, str]:
    """Split a line into its blockquote depth and the quoted line's own content."""
    m = _QUOTE_PREFIX_RE.match(content)
    if not m:
        return 0, content
    return m.group(0).count(">"), content[m.end():]


def _inline_blocks(
    pairs: Iterable[tuple[int, str]],
) -> Iterable[list[tuple[int, str]]]:
    """Group (line_no, content) pairs into single INLINE blocks.

    A scan unit is a maximal run of lines the renderer parses as ONE run of
    inline content, so it is exactly the span over which a markdown inline
    construct may wrap.  Scanning per unit rather than per line is what lets
    `_iter_inline_links` see a wrapped link (rf2-vpc4c); bounding the unit at
    every real block boundary is what stops it from stitching unrelated blocks
    into a link no renderer would produce (rf2-8wcbe).

    Four kinds of boundary end a unit:

    1. A blank line — the universal block boundary.  Fence lines and
       fenced-block bodies arrive already blanked by `_strip_fences`, so a code
       block bounds a unit here too, for free.
    2. A paragraph-interrupting block start — `_LEAF_LINE_BLOCK_RE` (ATX
       heading, thematic break / setext underline, table row) or
       `_LIST_ITEM_START_RE`.  The line STARTS the next unit rather than being
       dropped, so a link that lives on it is still extracted.
    3. The END of a single-line leaf block: nothing continues an ATX heading, a
       thematic break or a table row, so the following line opens a fresh unit
       and a stray `[` in a heading cannot reach into the paragraph under it.
       A list item is a container that DOES continue, so it only opens a unit.
    4. Entering a blockquote (or nesting one level deeper).  A quote interrupts
       the paragraph above it.  The converse is not a boundary: an unprefixed
       line after a quoted one is CommonMark lazy continuation of the SAME
       quoted paragraph, and a `>`-prefixed continuation line inside one quote
       keeps the depth unchanged — which is why the corpus's blockquoted
       wrapped links still join.

    The block tests run against the line's content with any blockquote markers
    removed, so `> ## Heading` bounds a unit just as `## Heading` does.
    """
    block: list[tuple[int, str]] = []
    prev_depth = 0
    ended = False
    for line_no, content in pairs:
        if not content.strip():
            if block:
                yield block
                block = []
            prev_depth = 0
            ended = False
            continue
        depth, body = _quote_depth_and_body(content)
        leaf = bool(_LEAF_LINE_BLOCK_RE.match(body))
        if block and (
            ended or leaf or depth > prev_depth or _LIST_ITEM_START_RE.match(body)
        ):
            yield block
            block = []
        block.append((line_no, content))
        prev_depth = depth
        ended = leaf
    if block:
        yield block


def _mask_code_spans(text: str) -> str:
    """Mask inline-code spans across a joined scan unit (rf2-8wcbe).

    Length- and newline-preserving, so a match position in the masked text still
    maps back to its source line.  Unlike the per-line `_strip_inline_code`, this
    honours CommonMark §6.1's allowance for a code span to contain a line ending
    — the reason a backticked, line-wrapped link PLACEHOLDER must not be
    extracted as a link.
    """
    def _blank(m: re.Match[str]) -> str:
        return "".join("\n" if ch == "\n" else " " for ch in m.group(0))

    return _INLINE_CODE_SPAN_RE.sub(_blank, text)


def _iter_inline_links(
    pairs: Iterable[tuple[int, str]],
) -> Iterable[tuple[int, str]]:
    """Yield (line-number, destination) for every inline link in the given lines.

    `pairs` are (line_no, content) pairs the CALLER has already reduced to
    scannable source — fence-stripped.  Inline code is masked HERE, over the
    joined unit, not by the caller (rf2-8wcbe).

    Each inline block (`_inline_blocks`) is joined with `\\n` and scanned as ONE
    string, so a link whose text wraps across a newline is seen.  The
    predecessor scanned line by line, so `](target#anchor)` landing on the
    following line never matched `_LINK_RE` and the link was therefore never
    validated — silently unguarded, since `mkdocs build --strict` reports a
    broken anchor as INFO and still exits 0 (rf2-vpc4c).

    Masking runs over the same joined unit `_LINK_RE` scans, so the two agree on
    what the text is: a code span that crosses a line ending is masked whole and
    yields no link, where per-line masking saw two unpaired backticks, masked
    neither, and invented one (rf2-8wcbe).

    The reported line is the one carrying the DESTINATION (`m.start(1)`), not
    the one carrying the opening `[`.  For an unwrapped link the two are the
    same line, so single-line diagnostics are unchanged; for a wrapped one the
    destination's line is the line an author edits to fix the anchor, and it is
    the more robust of the two — an unclosed stray `[` earlier in the block can
    drag the match start backwards, but never the destination.
    """
    for block in _inline_blocks(pairs):
        joined = _mask_code_spans("\n".join(content for _, content in block))
        # Offset of each line's first character within `joined`, for mapping a
        # match position back to a line number.  `_mask_code_spans` masks
        # length-preservingly, so these offsets are exact.
        line_starts: list[int] = []
        offset = 0
        for _, content in block:
            line_starts.append(offset)
            offset += len(content) + 1  # +1 for the joining newline
        for m in _LINK_RE.finditer(joined):
            i = bisect.bisect_right(line_starts, m.start(1)) - 1
            yield block[i][0], m.group(1)


def _extract_links(path: Path) -> Iterable[tuple[int, str]]:
    """Yield (line-number, destination) for every inline markdown link.

    Links inside fenced code blocks AND inside inline-code spans are
    skipped — both are "code", not real cross-references (rf2-mqv8s).
    Links that wrap across a newline ARE seen (rf2-vpc4c) — see
    `_iter_inline_links`, which is also where inline code is masked and where
    the join is bounded to one rendered inline block (rf2-8wcbe).

    This function's only job is reducing the file to fence-stripped
    (line_no, content) pairs.  `check_readme_links.py` imports it so both gates
    share one extractor and cannot drift apart (rf2-vpc4c).
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    yield from _iter_inline_links(_strip_fences(text.splitlines()))


def _resolve_target(
    linker: Path,
    dest_path: str,
    repo_root: Path,
    *,
    relative_base: Path | None = None,
) -> Path | None:
    """Resolve a (possibly relative) link path against the linker's directory.

    Returns the absolute Path to the target file, or None if resolution would
    escape the repo (which can't be validated locally — those are treated as
    external references and skipped by the caller).

    Absolute-style paths (starting with `/`) resolve repo-root-relative —
    this matches mkdocs' link-rendering convention.
    """
    if not dest_path:
        return linker  # same-file anchor
    bases = [repo_root] if dest_path.startswith("/") else [linker.parent]
    if relative_base and relative_base != linker.parent:
        bases.append(relative_base)

    candidates: list[Path] = []
    for base in bases:
        try:
            target = (
                base / (dest_path.lstrip("/") if dest_path.startswith("/") else dest_path)
            ).resolve()
            target.relative_to(repo_root.resolve())
        except (OSError, ValueError):
            continue
        candidates.append(target)
        # Drafts mix links to neighbouring source material with links authored
        # for their landing directory. Prefer the real source-neighbour when
        # present, then fall through to the explicit landing context.
        if target.is_file():
            return target
    return candidates[-1] if candidates else None


def _is_ai_findings_link(path_part: str) -> bool:
    """Return True if a link path resolves under the gitignored ai/findings/ tree.

    The repo's `/ai/` directory is gitignored at the root, so committed markdown
    that links into `ai/findings/<file>.md` (or the bare directory) creates an
    invisible-on-CI broken target — mkdocs strict's link validator catches it,
    blocking unrelated PRs in cascade.  rf2-l7yj8 promotes this from a
    mkdocs-only failure to a fast pre-PR lint.

    The check is path-component-sensitive: it matches `ai/findings/` only as a
    whole pair of path components, so casual prose links to (say)
    `vai/findings.md` or `ai-findings.md` are not mis-flagged.  Both root-anchored
    (`/ai/findings/...`) and relative (`../../ai/findings/...`) forms are caught.
    """
    # Normalise to forward slashes so the path-component test is OS-agnostic.
    normalised = path_part.replace("\\", "/")
    # Split into segments and search for the consecutive pair ("ai", "findings").
    segments = [s for s in normalised.split("/") if s not in ("", ".")]
    for i in range(len(segments) - 1):
        if segments[i] == "ai" and segments[i + 1] == "findings":
            return True
    return False


def _check_compat_anchors(
    repo_root: Path,
    counts_for,
    manifest: dict[str, tuple[str, ...]],
) -> tuple[list[tuple[str, str]], list[str], list[tuple[str, str, int]]]:
    """Validate every manifest compat anchor resolves to exactly one target (rf2-57k74/zq5i6).

    Returns (missing_anchors, missing_pages, duplicate_anchors):
        * missing_anchors — (page-rel, anchor) for each manifest anchor absent
          from its page's rendered-id index.
        * missing_pages — page-rel for each inventoried page NOT present in the
          working tree. A deleted or renamed page fails the gate, so the external
          bookmarks it carries cannot silently disappear behind an ordinary
          link-rename (rf2-57k74 closed this hole — the Machines-only predecessor
          silently `continue`d past an absent page).
        * duplicate_anchors — (page-rel, anchor, count) for each manifest anchor
          that resolves to MORE THAN ONE rendered target on its page (rf2-zq5i6):
          a duplicate explicit id, or an explicit id colliding with a generated
          heading slug. A fragment id that appears twice is an invalid, ambiguous
          bookmark (the browser resolves only the first), so it fails the gate.

    `counts_for(page)` returns the page's {rendered-id: occurrence-count} map. The
    manifest is an explicit parameter, NOT a module global, so the fixture-based
    self-tests stay isolated by passing their own inputs (a fixture manifest or
    `{}`) rather than relying on production pages being absent.
    """
    missing_anchors: list[tuple[str, str]] = []
    missing_pages: list[str] = []
    duplicate_anchors: list[tuple[str, str, int]] = []
    for page_rel, anchors in manifest.items():
        page = repo_root / page_rel
        if not page.is_file():
            missing_pages.append(page_rel)
            continue
        page_counts = counts_for(page)
        for anchor in anchors:
            n = page_counts.get(anchor, 0)
            if n == 0:
                missing_anchors.append((page_rel, anchor))
            elif n > 1:
                duplicate_anchors.append((page_rel, anchor, n))
    return missing_anchors, missing_pages, duplicate_anchors


def _check_compat_anchor_placement(
    repo_root: Path,
    placement: dict[tuple[str, str], re.Pattern[str]] = COMPAT_ANCHOR_PLACEMENT,
) -> list[tuple[str, str, str]]:
    """Validate render-faithful placement of passage-naming compat anchors (rf2-zq5i6).

    For each `(page, anchor) -> passage_re` rule, the anchor must appear before the
    passage it names so a deep-link lands ON that content, not past it. Returns
    (page-rel, anchor, reason) for each misplaced or missing entry.
    """
    misplaced: list[tuple[str, str, str]] = []
    for (page_rel, anchor), passage_re in placement.items():
        page = repo_root / page_rel
        if not page.is_file():
            misplaced.append((page_rel, anchor, "page missing from working tree"))
            continue
        # Placement must locate the RENDERED anchor (rf2-ehxs8): mask HTML
        # comments and inline code as well as fenced code, so anchor-shaped text
        # the browser never turns into a fragment target cannot stand in for the
        # real anchor. Without this a commented/backticked fake anchor before the
        # passage passes placement while the sole rendered anchor sits after it.
        fence_stripped = _strip_fences(
            page.read_text(encoding="utf-8", errors="replace").splitlines()
        )
        lines = [
            _strip_inline_code(content)
            for _, content in _mask_html_comments(fence_stripped)
        ]
        if not _anchor_precedes_passage(lines, anchor, passage_re):
            misplaced.append(
                (
                    page_rel,
                    anchor,
                    f"must appear before the passage matching /{passage_re.pattern}/",
                )
            )
    return misplaced


def _check_source_comment_links(
    repo_root: Path,
    slugs_for,
    *,
    source_files: Iterable[Path],
    link_re: re.Pattern[str] = _HANDBOOK_DOC_LINK_RE,
) -> list[tuple[Path, int, str, str]]:
    """Validate `docs/<handbook>/*.md#anchor` links in source comments (rf2-57k74).

    Scans each file in `source_files` line by line for the handbook-anchor
    substring matched by `link_re` and resolves each against the target page's
    slug index. Returns (source-file, line-no, `page#anchor`, reason) for every
    broken reference. An empty `source_files` (some self-test fixtures) yields none.

    `source_files` and `link_re` are parameters so the self-tests can drive the
    same mechanism against explicit fixture source files and a fixture-scoped
    pattern. Production passes the whole tracked Clojure tree (see
    `_iter_source_files`) so a covered comment stays validated wherever its file
    lives (rf2-zq5i6).
    """
    broken: list[tuple[Path, int, str, str]] = []
    for src in source_files:
        text = src.read_text(encoding="utf-8", errors="replace")
        for line_no, line in enumerate(text.splitlines(), start=1):
            for m in link_re.finditer(line):
                doc_rel, anchor = m.group(1), m.group(2)
                target = (repo_root / doc_rel).resolve()
                if not target.is_file():
                    broken.append(
                        (src, line_no, f"{doc_rel}#{anchor}", "missing target file")
                    )
                    continue
                if anchor not in slugs_for(target):
                    broken.append(
                        (src, line_no, f"{doc_rel}#{anchor}", "missing anchor")
                    )
    return broken


def check(
    repo_root: Path,
    verbose: bool = False,
    *,
    compat_anchors: dict[str, tuple[str, ...]] | None = None,
    source_files: Iterable[Path] | None = None,
    source_comment_re: re.Pattern[str] | None = None,
    placement: dict[tuple[str, str], re.Pattern[str]] | None = None,
) -> int:
    """Validate every in-repo markdown link.  Return the total defect count.

    Flags these distinct defects:
        * BROKEN TARGET     — link points at an .md file that doesn't exist.
        * BROKEN ANCHOR     — file exists but the #anchor doesn't resolve.
        * AI_FINDINGS_LINK  — link points into the gitignored ai/findings/ tree
                              (rf2-l7yj8).  Committed files must not reference
                              gitignored working artefacts; inline a sentence
                              summary instead.
        * MISSING COMPAT ANCHOR — (default scope, rf2-57k74) a manifest anchor
                              in HANDBOOK_COMPAT_ANCHORS no longer resolves on
                              its page (external bookmarks / source comments
                              depend on it).
        * DUPLICATE COMPAT ANCHOR — (default scope, rf2-zq5i6) a manifest anchor
                              resolves to MORE THAN ONE rendered target on its
                              page (duplicate explicit id, or explicit id vs
                              generated heading slug) — an ambiguous bookmark.
        * MISPLACED COMPAT ANCHOR — (default scope, rf2-zq5i6) a passage-naming
                              compat anchor (COMPAT_ANCHOR_PLACEMENT) no longer
                              precedes the passage it names, so a deep-link
                              scrolls past the content onto the next section.
        * MISSING COMPAT PAGE — (default scope, rf2-57k74) an inventoried page in
                              HANDBOOK_COMPAT_ANCHORS is missing from the working
                              tree — a delete/rename would otherwise take every
                              bookmark it carries with it, silently.
        * BROKEN SOURCE-COMMENT LINK — (default scope, rf2-57k74) a
                              `docs/<handbook>/*.md#anchor` reference embedded in
                              a non-markdown source comment does not resolve.

    The compat-anchor manifest, source-comment scan, and placement rules default
    to the production inventory (HANDBOOK_COMPAT_ANCHORS / the tracked Clojure tree
    via _iter_source_files / _HANDBOOK_DOC_LINK_RE / COMPAT_ANCHOR_PLACEMENT); the
    self-tests pass explicit fixture inputs so an absent production page never has
    to be silently ignored to keep them green.
    """
    if compat_anchors is None:
        compat_anchors = HANDBOOK_COMPAT_ANCHORS
    if source_files is None:
        source_files = list(_iter_source_files(repo_root))
    if source_comment_re is None:
        source_comment_re = _HANDBOOK_DOC_LINK_RE
    if placement is None:
        placement = COMPAT_ANCHOR_PLACEMENT
    files = list(_iter_markdown(repo_root))
    if verbose:
        sys.stderr.write(f"scanning {len(files)} markdown files...\n")

    # Build the rendered-id index lazily — many files are never linked to with an
    # anchor. `counts_for` yields the {id: occurrence-count} map (used by the
    # uniqueness check); `slugs_for` derives the membership set from it.
    counts_cache: dict[Path, dict[str, int]] = {}
    slug_cache: dict[Path, set[str]] = {}

    def counts_for(path: Path) -> dict[str, int]:
        ap = path.resolve()
        if ap not in counts_cache:
            counts_cache[ap] = _scan_rendered_ids(path)
        return counts_cache[ap]

    def slugs_for(path: Path) -> set[str]:
        ap = path.resolve()
        if ap not in slug_cache:
            slug_cache[ap] = set(counts_for(path))
        return slug_cache[ap]

    broken_anchor: list[tuple[Path, int, str, str]] = []
    broken_target: list[tuple[Path, int, str, str]] = []
    ai_findings: list[tuple[Path, int, str]] = []
    for path in files:
        for line_no, dest in _extract_links(path):
            # External / non-file references — out of scope.
            if dest.startswith(("http://", "https://", "mailto:", "tel:", "//")):
                continue

            path_part, _, anchor = dest.partition("#")
            anchor = urllib.parse.unquote(anchor).strip()

            # Strip any query string from path-part (rare in markdown but safe).
            path_part = path_part.split("?", 1)[0]

            # rf2-l7yj8: any link into the gitignored ai/findings/ tree is a
            # policy violation regardless of whether the target happens to
            # exist locally.  Flag and continue so further checks still run.
            if _is_ai_findings_link(path_part):
                ai_findings.append((path, line_no, dest))
                continue

            # Same-file anchor (`[text](#foo)`).  No target-file check; anchor
            # only.  Empty anchor (just `#` with no fragment) is meaningless
            # so we skip it.
            if path_part == "":
                if not anchor:
                    continue
                same_file_slugs = set(slugs_for(path))
                if anchor not in same_file_slugs:
                    broken_anchor.append(
                        (path, line_no, dest, str(path.relative_to(repo_root.resolve())))
                    )
                continue

            # Only validate links to .md files — anchors and target-existence
            # on other file types (images, source files, asset links) aren't
            # part of the slug-index contract.  mkdocs' own link check is the
            # right gate for those.
            if not path_part.endswith(".md"):
                continue

            target = _resolve_target(
                path,
                path_part,
                repo_root,
            )
            if target is None:
                # Path escapes the repo — treat as external reference, skip.
                continue
            if not target.is_file():
                broken_target.append(
                    (path, line_no, dest, _display_target(target, repo_root))
                )
                continue

            # Target exists.  Validate anchor if one was specified.
            if anchor and anchor not in slugs_for(target):
                broken_anchor.append(
                    (path, line_no, dest, str(target.relative_to(repo_root.resolve())))
                )

    # rf2-57k74 / rf2-zq5i6 — the cross-handbook compat-anchor manifest, uniqueness,
    # placement, and source-comment link gates.
    compat_missing, compat_missing_pages, compat_duplicate = _check_compat_anchors(
        repo_root, counts_for, compat_anchors
    )
    compat_misplaced = _check_compat_anchor_placement(repo_root, placement)
    source_comment_broken = _check_source_comment_links(
        repo_root,
        slugs_for,
        source_files=source_files,
        link_re=source_comment_re,
    )

    total = (
        len(broken_anchor)
        + len(broken_target)
        + len(ai_findings)
        + len(compat_missing)
        + len(compat_missing_pages)
        + len(compat_duplicate)
        + len(compat_misplaced)
        + len(source_comment_broken)
    )

    if broken_target:
        sys.stderr.write(
            f"\n{len(broken_target)} broken target file(s) found:\n\n"
        )
        for src, line_no, dest, target_rel in broken_target:
            rel = src.relative_to(repo_root)
            sys.stderr.write(
                f"  BROKEN TARGET: {rel}:{line_no} -> {dest}\n"
                f"      (missing: {target_rel})\n"
            )
        sys.stderr.write(
            "\nFix: rename the link to point at the file's current path, "
            "or restore the missing file.\n"
        )

    if broken_anchor:
        sys.stderr.write(
            f"\n{len(broken_anchor)} broken anchor link(s) found:\n\n"
        )
        for src, line_no, dest, target_rel in broken_anchor:
            rel = src.relative_to(repo_root)
            sys.stderr.write(
                f"  BROKEN ANCHOR: {rel}:{line_no} -> {dest}\n"
                f"      (target: {target_rel})\n"
            )
        sys.stderr.write(
            "\nFix: confirm the heading still exists in the target file and "
            "update the link, or rename the heading and re-link.\n"
        )

    if ai_findings:
        sys.stderr.write(
            f"\n{len(ai_findings)} link(s) into gitignored ai/findings/ tree "
            "found (rf2-l7yj8):\n\n"
        )
        for src, line_no, dest in ai_findings:
            rel = src.relative_to(repo_root)
            sys.stderr.write(
                f"  AI_FINDINGS_LINK: {rel}:{line_no} -> {dest}\n"
            )
        sys.stderr.write(
            "\nFix: the /ai/ tree is gitignored — committed files must not "
            "link into it.  Replace the markdown link with a 1-sentence inline "
            "summary of the finding (and a date) so the committed prose is "
            "self-contained and mkdocs strict's link validator doesn't trip "
            "on a missing target in CI.\n"
        )

    if compat_missing_pages:
        sys.stderr.write(
            f"\n{len(compat_missing_pages)} inventoried compat page(s) missing "
            "from the working tree (rf2-57k74):\n\n"
        )
        for page_rel in compat_missing_pages:
            sys.stderr.write(
                f"  MISSING COMPAT PAGE: {page_rel}\n"
            )
        sys.stderr.write(
            "\nFix: this page carries external bookmark anchors listed in "
            "HANDBOOK_COMPAT_ANCHORS. Restore it at the inventoried path, or — if "
            "it was deliberately renamed/removed — move its compat anchors onto "
            "the page that now owns the content and update the manifest key.\n"
        )

    if compat_missing:
        sys.stderr.write(
            f"\n{len(compat_missing)} missing handbook compat anchor(s) "
            "found (rf2-57k74):\n\n"
        )
        for page_rel, anchor in compat_missing:
            sys.stderr.write(
                f"  MISSING COMPAT ANCHOR: {page_rel}#{anchor}\n"
            )
        sys.stderr.write(
            "\nFix: these anchors back external bookmarks and in-repo source "
            "comments. Restore an `<a id=\"...\"></a>` for each on its page "
            "(without duplicating a visible heading), or update "
            "HANDBOOK_COMPAT_ANCHORS if the anchor is intentionally retired.\n"
        )

    if compat_duplicate:
        sys.stderr.write(
            f"\n{len(compat_duplicate)} colliding handbook compat anchor(s) "
            "found (rf2-zq5i6):\n\n"
        )
        for page_rel, anchor, count in compat_duplicate:
            sys.stderr.write(
                f"  DUPLICATE COMPAT ANCHOR: {page_rel}#{anchor} "
                f"(resolves to {count} rendered targets)\n"
            )
        sys.stderr.write(
            "\nFix: a compatibility fragment must resolve to exactly one rendered "
            "target. Remove the duplicate `<a id>` element, or rename it so it no "
            "longer collides with the other explicit anchor or the generated "
            "heading slug of the same name.\n"
        )

    if compat_misplaced:
        sys.stderr.write(
            f"\n{len(compat_misplaced)} misplaced handbook compat anchor(s) "
            "found (rf2-zq5i6):\n\n"
        )
        for page_rel, anchor, reason in compat_misplaced:
            sys.stderr.write(
                f"  MISPLACED COMPAT ANCHOR: {page_rel}#{anchor}\n"
                f"      ({reason})\n"
            )
        sys.stderr.write(
            "\nFix: move the `<a id>` so it precedes the passage it names — a "
            "deep-link must land ON that content, not scroll past it onto the "
            "following section.\n"
        )

    if source_comment_broken:
        sys.stderr.write(
            f"\n{len(source_comment_broken)} broken source-comment link(s) into "
            "the covered handbooks found (rf2-57k74):\n\n"
        )
        for src, line_no, dest, reason in source_comment_broken:
            rel = src.relative_to(repo_root.resolve())
            sys.stderr.write(
                f"  BROKEN SOURCE-COMMENT LINK: {rel}:{line_no} -> {dest}\n"
                f"      ({reason})\n"
            )
        sys.stderr.write(
            "\nFix: point the comment at a live canonical heading, or restore a "
            "compatibility anchor on the target page (and list it in "
            "HANDBOOK_COMPAT_ANCHORS).\n"
        )

    if total == 0 and verbose:
        sys.stderr.write("no broken links.\n")

    return total


def _display_target(target: Path, repo_root: Path) -> str:
    """Best-effort repo-relative string for a (possibly non-existent) target."""
    try:
        return str(target.relative_to(repo_root.resolve()))
    except ValueError:
        return str(target)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Validate intra-repo markdown links — target files (rf2-unge8) "
            "and anchor slugs (rf2-sefq)."
        ),
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Path to the repo root.  Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print progress to stderr."
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help=(
            "Run the bundled fixture-based self-tests in "
            "scripts/_test_fixtures/check_doc_slugs/ and exit."
        ),
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

    broken = check(
        repo_root,
        verbose=args.verbose,
    )
    return 0 if broken == 0 else 1


# --------------------------------------------------------------------------
# Self-tests (rf2-unge8) — small fixture-driven sanity checks.
#
# Each fixture is a self-contained mini-repo (just enough to exercise the
# validator: a single .md file plus a sibling mkdocs.yml so the repo-root
# guard accepts it).  We invoke `check(repo_root)` against each fixture
# and assert the expected broken-link count.  These run in CI alongside
# the full-corpus scan.
# --------------------------------------------------------------------------

_SELF_TEST_FIXTURE_ROOT = Path(__file__).resolve().parent / "_test_fixtures" / "check_doc_slugs"


def _run_self_tests(verbose: bool = False) -> int:
    """Run fixture-based self-tests.  Return 0 on success, 1 on any failure."""
    cases: list[tuple[str, int]] = [
        # (fixture-dir, expected-broken-link-count)
        ("valid_link",                       0),
        ("broken_target",                    1),
        ("broken_anchor",                    1),
        ("same_file_anchor_ok",              0),
        ("same_file_anchor_broken",          1),
        ("absolute_path_ok",                 0),
        ("relative_dotdot_ok",               0),
        ("inline_code_placeholder_ignored",  0),  # rf2-mqv8s
        # POSITIVE CONTROL for the wrapped-link fix (rf2-vpc4c): the
        # single-line broken link this gate always caught must keep reding.
        # `broken_anchor` / `broken_target` above pin the unwrapped cases;
        # this one pins a broken link sharing a line with a masked placeholder.
        ("inline_code_negative_control",     1),  # rf2-mqv8s
        ("ai_findings_link_flagged",         1),  # rf2-l7yj8
        ("ai_findings_dir_link_flagged",     1),  # rf2-l7yj8
        ("blockquoted_heading_ok",           0),  # rf2-869k9m
        ("indented_heading_not_indexed",     1),  # rf2-869k9m (negative control)
        # rf2-ru0wg — renderer parity for `## Title {#id}` headings. attr_list is
        # NOT enabled in mkdocs.yml, so the brace suffix is literal heading text
        # and the fragment id is the slugified FULL visible title. The parity
        # tooth, its negative control, and the duplicate case:
        ("explicit_id_full_title_ok",        0),  # `{#dup}` -> id `one-dup`
        ("explicit_id_brace_not_a_target",   1),  # `#dup` is NOT a target
        ("explicit_id_duplicate",            0),  # -> `one-dup`, `one-dup_1`
        # rf2-vpc4c — line-wrap blindness. A link whose text wraps across a
        # newline is a real rendered link and must be validated (the gate was
        # blind to it, and mkdocs strict does not cover the gap); a correct
        # wrapped link must NOT be flagged; and the join that makes wrapped
        # links visible must stop at a block boundary.
        ("wrapped_link_broken_anchor",       2),  # negative control
        ("wrapped_link_ok",                  0),  # no false positives
        ("wrapped_link_block_bound",         0),  # join stops at a blank line
        # rf2-8wcbe — the join must not bridge a NON-blank block boundary, and
        # inline code must be masked over the same unit the link regex scans.
        # Each expects 1, not 0: the single finding is a REAL broken wrapped
        # link, so the count fails in BOTH directions — upward if a phantom is
        # invented, downward if bounding the join discards real links.
        ("multiline_code_span_not_a_link",   1),
        ("non_blank_block_bound",            1),
        # rf2-mmyc — a fence indented by its container is still a fence, so the
        # sample inside it is code and carries no links to resolve.  Each
        # fixture's samples contain a blank line, which splits the inline block
        # so the code-span mask cannot pair the fence's own backtick runs and
        # hide the links by accident; without it these would pass for the wrong
        # reason.  The negative control fails in BOTH directions — its single
        # finding is a REAL broken link in prose AFTER an indented fence, so it
        # counts up if the fences are still scanned as prose and down if
        # widening the matcher swallows the document.
        ("indented_fence_link_ignored",      0),
        ("indented_fence_negative_control",  1),
        # rf2-mmyc — the fenced-doc-link assertion, in the rf2-re0m shape: the
        # link inside the fence RESOLVES, so link validation is satisfied and
        # only the assertion can see it.  Scoped, so the identical sample under
        # a sibling tree stays silent.
        ("fenced_doc_link_in_scope",         1),
        ("fenced_doc_link_out_of_scope",     0),
    ]

    failures = 0
    for fixture, expected in cases:
        root = _SELF_TEST_FIXTURE_ROOT / fixture
        if not (root / "mkdocs.yml").is_file():
            sys.stderr.write(
                f"self-test FAIL: fixture {fixture!r} missing mkdocs.yml at {root}\n"
            )
            failures += 1
            continue

        # Silence the validator's own diagnostic output during self-tests so
        # the success path stays terse.  Failures still surface via the
        # PASS/FAIL summary below.
        saved_stderr = sys.stderr
        sys.stderr = _DevNull()
        try:
            # Isolate through explicit fixture inputs: the compat manifest,
            # source-comment scan, and placement rules are empty here, so these
            # fixtures never depend on production handbook pages being absent
            # (rf2-57k74 / rf2-zq5i6).
            got = check(
                root,
                verbose=False,
                compat_anchors={},
                source_files=(),
                placement={},
            )
        finally:
            sys.stderr = saved_stderr

        if got == expected:
            if verbose:
                sys.stderr.write(f"self-test PASS: {fixture} (broken={got})\n")
        else:
            sys.stderr.write(
                f"self-test FAIL: {fixture} expected broken={expected}, got {got}\n"
            )
            failures += 1

    # rf2-57k74 / rf2-zq5i6 — compat-anchor + source-comment TEETH. Drive the
    # generalized mechanism against fixture handbook pages through EXPLICIT
    # manifest/source-file/placement inputs, proving:
    #   * a present anchor + present page passes (docs/machines/page.md);
    #   * DELETING an individual anchor fails (MISSING COMPAT ANCHOR);
    #   * a MISSING inventoried page fails (MISSING COMPAT PAGE) — the hole the
    #     Machines-only predecessor left by silently skipping absent pages;
    #   * render-faithfulness (rf2-zq5i6): an anchor that exists ONLY inside an
    #     HTML comment or an inline-code span mints no rendered target, so it does
    #     not satisfy the manifest;
    #   * uniqueness (rf2-zq5i6): a manifest anchor that resolves to two rendered
    #     targets — two explicit ids, or an explicit id colliding with a generated
    #     heading slug — fails (DUPLICATE COMPAT ANCHOR);
    #   * placement (rf2-zq5i6): the anchor must precede the passage it names — a
    #     correct fixture passes, a drifted one fails (MISPLACED COMPAT ANCHOR);
    #   * the source-comment mechanism validates links for Machines AND a real
    #     non-Machines handbook (Routing valid, Async broken), exercising the
    #     manifest-derived handbook vocabulary causally.
    teeth_root = _SELF_TEST_FIXTURE_ROOT / "compat_anchor_teeth"
    teeth_cases: list[tuple[str, dict, int]] = [
        ("compat anchor present",
         dict(compat_anchors={"docs/machines/page.md": ("keep-me",)},
              source_files=(), placement={}), 0),
        ("compat anchor deleted",
         dict(compat_anchors={"docs/machines/page.md": ("gone-anchor",)},
              source_files=(), placement={}), 1),
        ("inventoried page missing",
         dict(compat_anchors={"docs/machines/absent.md": ("keep-me",)},
              source_files=(), placement={}), 1),
        # rf2-zq5i6 render-faithfulness teeth.
        ("commented anchor does not resolve",
         dict(compat_anchors={"docs/machines/commented.md": ("keep-me",)},
              source_files=(), placement={}), 1),
        ("backticked anchor does not resolve",
         dict(compat_anchors={"docs/machines/backticked.md": ("keep-me",)},
              source_files=(), placement={}), 1),
        # rf2-zq5i6 uniqueness teeth.
        ("duplicate explicit ids collide",
         dict(compat_anchors={"docs/machines/duplicate.md": ("dup-me",)},
              source_files=(), placement={}), 1),
        ("explicit id vs generated heading slug collide",
         dict(compat_anchors={"docs/machines/collision.md": ("dup-me",)},
              source_files=(), placement={}), 1),
        # rf2-zq5i6 placement teeth.
        ("placement ok — anchor precedes passage",
         dict(compat_anchors={}, source_files=(),
              placement={("docs/routing/loader_ok.md", "when-a-loader-fails"):
                         re.compile(r"On loader failure")}), 0),
        ("placement drifted — anchor after passage",
         dict(compat_anchors={}, source_files=(),
              placement={("docs/routing/loader_drifted.md", "when-a-loader-fails"):
                         re.compile(r"On loader failure")}), 1),
        # rf2-ehxs8 — both remaining raw/fence-only source paths made
        # render-faithful. (1) A heading that exists ONLY inside a multiline HTML
        # comment mints no fragment target, so it must not satisfy the manifest
        # (the predecessor matched headings on the raw line). (2) Placement must
        # locate the RENDERED anchor: commented and backticked anchor-shaped text
        # before the passage must not stand in for the sole real anchor, which
        # sits after it.
        ("commented-only heading mints no rendered slug",
         dict(compat_anchors={"docs/machines/commented_heading.md": ("keep-me",)},
              source_files=(), placement={}), 1),
        ("placement ignores commented/backticked fake anchor before passage",
         dict(compat_anchors={}, source_files=(),
              placement={("docs/routing/loader_fake_before.md", "when-a-loader-fails"):
                         re.compile(r"On loader failure")}), 1),
        # rf2-57k74 source-comment mechanism (Machines).
        ("source-comment link valid",
         dict(compat_anchors={}, placement={},
              source_files=(teeth_root / "src" / "valid.clj",)), 0),
        ("source-comment link broken",
         dict(compat_anchors={}, placement={},
              source_files=(teeth_root / "src" / "broken.clj",)), 1),
        # rf2-zq5i6 — real non-Machines source comments, exercised causally.
        ("routing source-comment link valid",
         dict(compat_anchors={}, placement={},
              source_files=(teeth_root / "src" / "routing_ref.cljc",)), 0),
        ("async source-comment link broken",
         dict(compat_anchors={}, placement={},
              source_files=(teeth_root / "src" / "async_ref.cljc",)), 1),
    ]
    if not (teeth_root / "mkdocs.yml").is_file():
        sys.stderr.write(
            f"self-test FAIL: fixture 'compat_anchor_teeth' missing mkdocs.yml "
            f"at {teeth_root}\n"
        )
        failures += 1
    else:
        for label, kwargs, expected in teeth_cases:
            saved_stderr = sys.stderr
            sys.stderr = _DevNull()
            try:
                got = check(teeth_root, verbose=False, **kwargs)
            finally:
                sys.stderr = saved_stderr
            if got == expected:
                if verbose:
                    sys.stderr.write(
                        f"self-test PASS: compat teeth [{label}] (broken={got})\n"
                    )
            else:
                sys.stderr.write(
                    f"self-test FAIL: compat teeth [{label}] "
                    f"expected broken={expected}, got {got}\n"
                )
                failures += 1

    # rf2-zq5i6 — the source-comment handbook vocabulary is DERIVED from the
    # manifest, with no independent COMPAT_HANDBOOKS authority. Assert the derived
    # set matches the manifest keys and the link regex matches every covered
    # handbook (the causal guarantee the broken-async tooth above depends on).
    expected_handbooks = {page.split("/")[1] for page in HANDBOOK_COMPAT_ANCHORS}
    if set(COMPAT_HANDBOOKS) != expected_handbooks:
        sys.stderr.write(
            "self-test FAIL: COMPAT_HANDBOOKS drifted from the manifest keys: "
            f"derived {sorted(COMPAT_HANDBOOKS)}, expected {sorted(expected_handbooks)}\n"
        )
        failures += 1
    elif not all(
        _HANDBOOK_DOC_LINK_RE.search(f"docs/{hb}/page.md#anchor")
        for hb in ("machines", "async", "api", "routing")
    ):
        sys.stderr.write(
            "self-test FAIL: manifest-derived handbook link regex does not match "
            "every covered handbook (machines/async/api/routing)\n"
        )
        failures += 1
    elif verbose:
        sys.stderr.write(
            "self-test PASS: source-comment handbook vocabulary derived from the "
            "manifest (no independent COMPAT_HANDBOOKS authority)\n"
        )

    # rf2-zq5i6 — placement rules stay in lock-step with the anchor manifest: every
    # COMPAT_ANCHOR_PLACEMENT key must name a real (page, anchor) in the manifest.
    placement_orphans = [
        (page_rel, anchor)
        for (page_rel, anchor) in COMPAT_ANCHOR_PLACEMENT
        if anchor not in HANDBOOK_COMPAT_ANCHORS.get(page_rel, ())
    ]
    if placement_orphans:
        sys.stderr.write(
            "self-test FAIL: COMPAT_ANCHOR_PLACEMENT references non-manifest "
            f"anchors: {placement_orphans}\n"
        )
        failures += 1
    elif verbose:
        sys.stderr.write(
            "self-test PASS: every placement rule keys a real manifest anchor\n"
        )

    # rf2-zq5i6 — the source-comment scan covers the whole tracked tree, so MOVING a
    # covered comment to a deep non-examples path keeps it validated, while vendored
    # trees stay pruned. `node_modules/vendored.cljc` is itself TRACKED, so this also
    # proves the exclusions apply on top of tracking rather than falling out of it.
    source_walk = {
        p.relative_to(teeth_root).as_posix() for p in _iter_source_files(teeth_root)
    }
    if "lib/deep/moved_ref.cljc" not in source_walk:
        sys.stderr.write(
            "self-test FAIL: source scan dropped a covered comment at a "
            f"non-examples path (got {sorted(source_walk)})\n"
        )
        failures += 1
    elif "node_modules/vendored.cljc" in source_walk:
        sys.stderr.write(
            "self-test FAIL: source scan included a pruned vendor tree "
            f"(got {sorted(source_walk)})\n"
        )
        failures += 1
    elif verbose:
        sys.stderr.write(
            "self-test PASS: source scan covers tracked non-examples paths and "
            "prunes vendored trees\n"
        )

    # rf2-k30r7 — the roster is Git tracking, not the filesystem. An UNTRACKED
    # scratch source carrying a genuinely broken covered link must not reach the
    # production scan. The tooth is causal in both directions: the same file is
    # first proven poisonous via an explicit `source_files` input (it DOES report
    # a break when handed over directly), then proven invisible to the tracked
    # roster. A walk-based roster fails the second half.
    scratch = teeth_root / "src" / "k30r7_untracked_scratch.cljc"
    try:
        scratch.write_text(
            "(ns k30r7-untracked-scratch)\n"
            ";; ../../docs/machines/concepts.md#rf2-k30r7-anchor-that-does-not-exist\n",
            encoding="utf-8",
        )
        saved_stderr = sys.stderr
        sys.stderr = _DevNull()
        try:
            explicit_broken = check(
                teeth_root,
                verbose=False,
                compat_anchors={},
                placement={},
                source_files=(scratch,),
            )
        finally:
            sys.stderr = saved_stderr
        tracked_roster = {
            p.relative_to(teeth_root).as_posix()
            for p in _iter_source_files(teeth_root)
        }
    finally:
        scratch.unlink(missing_ok=True)

    if explicit_broken != 1:
        sys.stderr.write(
            "self-test FAIL: the untracked-scratch fixture is not actually broken, "
            f"so the tracking tooth proves nothing (got broken={explicit_broken})\n"
        )
        failures += 1
    elif scratch.relative_to(teeth_root).as_posix() in tracked_roster:
        sys.stderr.write(
            "self-test FAIL: an untracked scratch source reached the production "
            f"scan (got {sorted(tracked_roster)})\n"
        )
        failures += 1
    elif "src/valid.clj" not in tracked_roster:
        sys.stderr.write(
            "self-test FAIL: the tracked roster lost a tracked source while "
            f"excluding the untracked one (got {sorted(tracked_roster)})\n"
        )
        failures += 1
    elif verbose:
        sys.stderr.write(
            "self-test PASS: untracked scratch sources cannot fail the scan while "
            "tracked sources stay covered\n"
        )

    # rf2-zq5i6 — placement ordering logic, driven directly with a correct and a
    # drifted (mutated) line list. This is the focused tooth that fails if the
    # routing loader-failure bookmark ever drifts behind its explanation again.
    _placement_passage = re.compile(r"On loader failure")
    _correct_lines = [
        '<a id="when-a-loader-fails"></a>',
        "",
        "Runs client- and server-side. On loader failure, transition to error.",
        "",
        "### Declaring resources instead",
    ]
    _drifted_lines = [
        "Runs client- and server-side. On loader failure, transition to error.",
        "",
        '<a id="when-a-loader-fails"></a>',
        "",
        "### Declaring resources instead",
    ]
    if not _anchor_precedes_passage(
        _correct_lines, "when-a-loader-fails", _placement_passage
    ):
        sys.stderr.write(
            "self-test FAIL: placement check rejected a correctly-placed anchor\n"
        )
        failures += 1
    elif _anchor_precedes_passage(
        _drifted_lines, "when-a-loader-fails", _placement_passage
    ):
        sys.stderr.write(
            "self-test FAIL: placement check passed an anchor that drifted behind "
            "its explanation\n"
        )
        failures += 1
    elif verbose:
        sys.stderr.write(
            "self-test PASS: placement check accepts anchor-before-passage and "
            "rejects the drifted mutation\n"
        )

    # rf2-vpc4c / rf2-8wcbe — link extraction driven directly with explicit line
    # lists, so the wrap contract is pinned at the mechanism rather than only
    # through a fixture's aggregate count. Each case states the (line_no,
    # destination) pairs `_iter_inline_links` must yield from FENCE-STRIPPED
    # input — `_extract_links` reduces a file to exactly this shape, and inline
    # code is masked inside `_iter_inline_links` over the joined unit, so these
    # inputs are raw source lines. The cases run in both directions: the join
    # must reach across a wrap (rf2-vpc4c) and must stop at every real block
    # boundary and inside a multiline code span (rf2-8wcbe).
    extraction_cases: list[tuple[str, list[tuple[int, str]], list[tuple[int, str]]]] = [
        # POSITIVE CONTROL — the unwrapped case that always worked. The reported
        # line is the link's own line, exactly as before the fix.
        ("single-line link still extracted",
         [(1, "See [the doc](target.md#anchor) for detail.")],
         [(1, "target.md#anchor")]),
        # THE FIX — text wraps, so `](dest)` lands on the next line. The
        # predecessor yielded nothing here.
        ("wrapped link extracted, reported at the destination's line",
         [(1, "See [the"), (2, "doc](target.md#anchor) for detail.")],
         [(2, "target.md#anchor")]),
        # Blockquote continuation markers ride along in the link TEXT; only the
        # destination is captured, so the `>` prefix is harmless.
        ("wrapped link inside a blockquote",
         [(7, "> Per the note, [§Compiled"),
          (8, "> views](API.md#compiled-views) is live.")],
         [(8, "API.md#compiled-views")]),
        # BLOCK BOUND — a blank line ends the block, so these are never one link.
        ("blank line is not bridged",
         [(1, "A stray bracket [here"), (2, ""), (3, "](missing.md) after.")],
         []),
        # A fenced block arrives already blanked by `_strip_fences`, so it acts
        # as a block boundary for free.
        ("blanked fence lines are not bridged",
         [(1, "A stray bracket [here"), (2, ""), (3, ""), (4, ""),
          (5, "](missing.md) after.")],
         []),
        # A destination may not itself wrap: CommonMark forbids whitespace in a
        # bare destination, so this is not a link the renderer would produce.
        ("wrapped DESTINATION is not a link",
         [(1, "See [the doc](target.md#an"), (2, "chor) for detail.")],
         []),
        # An unclosed stray `[` earlier in the same block drags the match START
        # backwards, which is why the DESTINATION's line is what gets reported.
        # The destination itself is still correct and still found.
        ("stray bracket does not lose the destination",
         [(1, "An unclosed [ bracket opens here,"),
          (2, "and [the real link](target.md#anchor) follows.")],
         [(2, "target.md#anchor")]),
        # rf2-8wcbe — NON-BLANK block boundaries. A blank line is not the only
        # boundary; each pair below spans a real one, so no renderer produces a
        # link from it. THE BEAD'S SECOND COUNTEREXAMPLE leads.
        ("ATX heading is not bridged",
         [(1, "A stray [opening"), (2, "# Separate heading"),
          (3, "](missing.md)")],
         []),
        ("a heading does not reach the paragraph below it",
         [(1, "# A heading holding a stray ["),
          (2, "bracket](missing.md) opens the next paragraph.")],
         []),
        ("thematic break / setext underline is not bridged",
         [(1, "A stray bracket [here"), (2, "---"), (3, "](missing.md) after.")],
         []),
        ("list-item start is not bridged",
         [(1, "* A bullet holding a stray ["),
          (2, "* the next bullet](missing.md) closes the list.")],
         []),
        ("table row is not bridged",
         [(1, "| A cell holding a stray [ | one |"),
          (2, "| a later row](missing.md) | two |")],
         []),
        ("blockquote entry is not bridged",
         [(1, "A paragraph holding a stray ["),
          (2, "> and a quote](missing.md) interrupts it.")],
         []),
        # POSITIVE CONTROLS for those bounds — the mirror-image failure is
        # bounding so aggressively that real wrapped links are discarded.
        # A list item's CONTINUATION line carries no marker, and an unprefixed
        # line after a quoted one is CommonMark lazy continuation: both are one
        # inline run, so a link wrapping inside them is still extracted.
        ("wrapped link inside one list item still extracted",
         [(1, "* See [the compiled"),
          (2, "  views](API.md#compiled-views) doc.")],
         [(2, "API.md#compiled-views")]),
        ("blockquote lazy continuation still joins",
         [(1, "> Per the note, [§Compiled"),
          (2, "views](API.md#compiled-views) is live.")],
         [(2, "API.md#compiled-views")]),
        # rf2-8wcbe — code spans are masked over the JOINED unit, because a
        # CommonMark code span may contain a line ending. THE BEAD'S FIRST
        # COUNTEREXAMPLE: per-line masking saw two unpaired backticks, masked
        # neither, and invented a link the renderer never produces.
        ("multiline code span yields no link",
         [(1, "`[literal"), (2, "link](missing.md)`")],
         []),
        ("multiline code span masks only itself",
         [(1, "See [the doc](target.md#anchor) and a `[fake"),
          (2, "link](nowhere.md)` placeholder.")],
         [(1, "target.md#anchor")]),
        # POSITIVE CONTROL for the mask: an UNPAIRED backtick opens no span in
        # CommonMark, so it must not swallow the rest of the unit — the risk
        # that kept masking per-line in the first place.
        ("unpaired backtick masks nothing",
         [(1, "A stray ` backtick opens no span,"),
          (2, "and [the real link](target.md#anchor) follows.")],
         [(2, "target.md#anchor")]),
    ]
    for label, lines, expected_links in extraction_cases:
        got_links = list(_iter_inline_links(lines))
        if got_links == expected_links:
            if verbose:
                sys.stderr.write(
                    f"self-test PASS: extraction [{label}]\n"
                )
        else:
            sys.stderr.write(
                f"self-test FAIL: extraction [{label}] expected "
                f"{expected_links}, got {got_links}\n"
            )
            failures += 1

    # rf2-mmyc — FENCE RECOGNITION, driven directly at `_strip_fences`.  That
    # function is the scanner's only notion of "this is code, not prose", so
    # every other check inherits whatever it gets wrong: the rf2-re0m bulk link
    # pass rewrote six lines inside three Clojure samples and the gate stayed
    # green because a column-0-anchored matcher could not see the indented
    # fences those samples lived in.
    #
    # Each case states the 1-based line numbers that survive as PROSE.  Driving
    # the primitive rather than a fixture matters here: the inline-code-span
    # mask pairs a balanced fence's own backtick runs by accident, so a
    # fixture-level count can come out right while the scanner is still blind.
    #
    # The expectations are RENDERER-DERIVED, not read off CommonMark: each was
    # confirmed against python-markdown + pymdownx.superfences, the pair MkDocs
    # actually runs, which is stricter than CommonMark about closing fences.
    fence_cases: list[tuple[str, list[str], list[int]]] = [
        # POSITIVE CONTROL — the column-0 case that always worked.  It cannot
        # red before the fix; its job is to stay green after it.
        ("column-0 fence still blanks its body",
         ["Prose before.",
          "",
          "```clojure",
          "[not a link](missing.md)",
          "```",
          "Prose after."],
         [1, 6]),
        # THE DEFECT — a fence carrying its container's indent.
        ("fence indented inside a list item is a fence",
         ["- A bullet:",
          "",
          "  ```clojure",
          "  [not a link](missing.md)",
          "  ```",
          "",
          "Prose after."],
         [1, 7]),
        ("fence indented inside an admonition is a fence",
         ["!!! note",
          "",
          "    ```clojure",
          "    [not a link](missing.md)",
          "    ```",
          "",
          "Prose after."],
         [1, 7]),
        ("indented tilde fence is a fence",
         ["- A bullet:",
          "",
          "  ~~~clojure",
          "  [not a link](missing.md)",
          "  ~~~",
          "",
          "Prose after."],
         [1, 7]),
        # CommonMark's three-space allowance, which the renderer honours: a
        # fence may be indented up to three spaces in ordinary context.  The
        # corpus has 100 such lines.
        ("fence indented three spaces in ordinary context is a fence",
         ["Prose.",
          "",
          "   ```clojure",
          "   [not a link](missing.md)",
          "   ```",
          "",
          "Prose after."],
         [1, 7]),
        # THE CONTROL THAT REJECTS THE ONE-CHARACTER FIX.  Four or more spaces
        # in ordinary context is an INDENTED CODE BLOCK, a different construct
        # with no closing delimiter.  A matcher relaxed to `^\s*` opens a fence
        # here and, finding no closer, blanks the rest of the file.  Lines 3-4
        # stay visible because this scanner recognises where a FENCE is, not
        # where an indented code block is — see `_strip_fences`.
        ("four-space indented code block in ordinary context is not a fence",
         ["Prose.",
          "",
          "    ```clojure",
          "    (a literal fence, inside an indented code block)",
          "",
          "Prose after."],
         [1, 3, 4, 6]),
        # THE CONTROL THAT REJECTS CommonMark'S CLOSING-FENCE SLACK.  Taken
        # from skills/re-frame2-implementor/references/output-format.md, which
        # displays a nested fence inside a ```markdown sample.  CommonMark lets
        # a closing fence be indented up to three spaces regardless of its
        # opener, which would end the outer block at line 3; superfences does
        # not, and neither do we — the closer must be the opener's exact marker
        # at the opener's exact indentation.
        ("indented bare fence inside a column-0 fence does not close it",
         ["```markdown",
          "- **Claimed tags:**",
          "  ```",
          "  :core/*",
          "  ```",
          "- **Score:** 10",
          "```",
          "",
          "Prose after."],
         [9]),
        # RUNAWAY GUARD.  rf2-re0m shipped three unbalanced fences, so this is
        # not hypothetical.  A fence ends with the container that holds it, so
        # an unclosed one cannot blank the rest of the document — which is the
        # mirror-image failure of the defect: a gate that goes quiet.
        ("unclosed indented fence ends with its container",
         ["- A bullet:",
          "",
          "  ```clojure",
          "  (unclosed",
          "",
          "Prose after, back at column zero."],
         [1, 6]),
    ]
    for label, lines, expected_visible in fence_cases:
        got_visible = [n for n, content in _strip_fences(lines) if content.strip()]
        if got_visible == expected_visible:
            if verbose:
                sys.stderr.write(f"self-test PASS: fence [{label}]\n")
        else:
            sys.stderr.write(
                f"self-test FAIL: fence [{label}] expected visible lines "
                f"{expected_visible}, got {got_visible}\n"
            )
            failures += 1

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(
            f"all {len(cases) + len(teeth_cases) + len(extraction_cases) + len(fence_cases) + 4} "
            "self-tests passed.\n"
        )
    return 0


class _DevNull:
    """Minimal stderr stand-in that silently swallows writes during self-tests."""

    def write(self, *_args, **_kwargs) -> int:  # noqa: D401
        return 0

    def flush(self) -> None:  # pragma: no cover
        return None


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
