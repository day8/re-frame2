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
import re
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

# Tracked, authoritative pre-publication re-frame.ui material. This is an
# explicit opt-in scope: it must not widen the default docs/MkDocs corpus or
# sweep unrelated ai/ scratch. S6 owns publication; this gate owns only links
# and anchors while the source remains here.
#
# The scope is ALSO the retired-operation guard's inventory (see
# _scan_retired_synthesis_ops): every active runnable/dispatch authority a
# worker executes directly must be covered, so a stale copy-from-mayor /
# local-only directive cannot hide in a live authority. rf2-d9v3n expanded the
# inventory from guide/ + drafts/ (which only covered publication-track pages)
# to the operational authorities as well:
#
#   * prep/ — the per-workstream prep tables (w1/w2/w3/w9) workers run against.
#   * the two top-level operational authorities named in SYNTHESIS_FILES.
#
# The inventory stays narrow and reviewable on purpose: the sibling 01-10
# narrative chapters, README, and the reviews/ + spikes/ subtrees are
# historical review/spike prose, NOT active operations, and stay out.
SYNTHESIS_ROOTS = (
    "ai/findings/new-substrate-synthesis/guide",
    "ai/findings/new-substrate-synthesis/drafts",
    "ai/findings/new-substrate-synthesis/prep",
)

# Individual top-level operational authorities. These two files live directly in
# ai/findings/new-substrate-synthesis/ alongside the 01-10 narrative chapters and
# README, so the whole directory is NOT a root — only these named files are
# active authorities a worker dispatches/executes and must be guarded. Naming
# them explicitly keeps the inventory an auditable list, not a directory sweep.
SYNTHESIS_FILES = (
    "ai/findings/new-substrate-synthesis/11-adoption-workstreams.md",
    "ai/findings/new-substrate-synthesis/12-implementation-plan.md",
)

# Diff-ready spec drafts author their links for the file's eventual `spec/`
# location, not for the temporary drafts/ directory. Validate that intended
# link context explicitly. The target path also supplies existing headings for
# same-file anchors in amendment fragments; a not-yet-created target (004A)
# still contributes its `spec/` parent as the relative-link base.
DRAFT_LANDING_TARGETS = {
    "ai/findings/new-substrate-synthesis/drafts/spec-004-rewrite-draft.md":
        "spec/004-Views.md",
    "ai/findings/new-substrate-synthesis/drafts/spec-004A-reagent-compat-appendix.md":
        "spec/004A-Reagent-Compat.md",
    "ai/findings/new-substrate-synthesis/drafts/spec-006-observation-port-amendment.md":
        "spec/006-ReactiveSubstrate.md",
    "ai/findings/new-substrate-synthesis/drafts/spec-009-ui-catalogue-rows.md":
        "spec/009-Instrumentation.md",
    "ai/findings/new-substrate-synthesis/drafts/spec-011-ui-tree-amendment.md":
        "spec/011-SSR.md",
}

# Tools live one tier deeper: tools/<tool-name>/spec/**/*.md.
TOOLS_ROOT = "tools"

# rf2-vxgfnd.136 — retired copy-from-mayor / local-only language guard.
#
# The synthesis subtree is force-tracked (see .gitignore's temporary exception):
# a worker reads it directly in their own worktree at the exact revision being
# implemented or reviewed. Operational instructions that still tell a worker the
# tree is gitignored/absent and must be copied from the mayor checkout are stale
# and dangerous — following them replaces the exact-head input with a divergent
# newer copy, or stalls a clean-clone worker on an impossible prerequisite.
#
# This guard runs only under --synthesis-only (it must never widen the default
# docs corpus) and rejects the retired IMPERATIVE directives. The patterns are
# deliberately narrow: they match the copy-from-mayor commands, not the word
# "local-only" on its own — the correct prose "the surrounding ai/ tree stays
# local-only, so the pages are already in-repo" and "a local-only script"
# (a CI-wiring status) are legitimate and must pass. Clearly historical prose is
# also allowed: a match inside a blockquote whose opener is marked HISTORICAL /
# RETIRED / non-operative is exempt (a dated audit may quote the old model).
RETIRED_SYNTHESIS_OP_PATTERNS = (
    ("MAYOR_CHECKOUT_ONLY", re.compile(r"mayor checkout only", re.IGNORECASE)),
    ("DISPATCHER_COPIES", re.compile(r"dispatcher copies", re.IGNORECASE)),
    ("ABSENT_FROM_WORKTREES",
     re.compile(r"absent from worker worktrees", re.IGNORECASE)),
    ("COPY_FROM_MAYOR",
     re.compile(r"cop(?:y|ies)\b[^.\n]*\b(?:into the worktree|from the mayor)",
                re.IGNORECASE)),
    ("LOCAL_ONLY_NEVER_ADD",
     re.compile(r"local-only working artefact\s*\(never\s*`?git add`?", re.IGNORECASE)),
)

# A blockquote line whose opener carries one of these tokens marks a historical,
# non-operative block; retired-language matches inside it are quoted history.
_HISTORICAL_MARKER_RE = re.compile(
    r"\bHISTORICAL\b|\bRETIRED\b|non-operative", re.IGNORECASE
)

# Paths whose markdown should never be scanned.
EXCLUDE_DIR_NAMES = frozenset({
    "findings",      # default excludes exploratory work; synthesis opt-in is exact
    "node_modules",
    "site",          # mkdocs build output
    ".git",
    ".beads",
    "ai",            # default excludes AI work; tracked synthesis opt-in is exact
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

# Custom heading id syntax: "## Title {#explicit-id}" — pymdownx.toc honours
# this when the attr_list extension is enabled.  We're conservative and accept
# any {#id} suffix on H1-H3 headings.
_EXPLICIT_ID_RE = re.compile(r"\{#([A-Za-z0-9_\-:.]+)\}\s*$")

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
# * Single-line only.  Multi-line inline code is rare in this corpus and
#   the validator already processes line-by-line after `_strip_fences`.
# * Backslash escaping of backticks (`\``) is NOT honoured — CommonMark
#   itself does not honour it; backticks are always literal markup.
# * `_strip_fences` has already blanked fenced-block lines, so this regex
#   never sees the language tag of a fence as a stray backtick run.
_INLINE_CODE_RE = re.compile(r"(`+)(?:.+?)\1(?!`)")


# rf2-t0ituo — Machines handbook compatibility-anchor manifest + source-comment
# link gate.
#
# PR #5916 reorganized the Machines handbook and dropped ~20 generated heading
# IDs. External bookmarks and in-repo source comments (`.clj` / `.cljs`
# walkthroughs) still target them, but neither is a markdown link, so the corpus
# scan above never sees the break. Two additions close the hole:
#
#   1. MACHINES_COMPAT_ANCHORS — the manifest of stable anchors that MUST resolve
#      on their page. A reorg that drops one fails here even when nothing in the
#      markdown corpus links to it (an external bookmark has no in-repo linker to
#      catch the break).
#   2. SOURCE_COMMENT_LINK_GLOBS — non-markdown source files whose comments point
#      readers at handbook anchors. Every `docs/machines/<page>.md#anchor`
#      substring is resolved and validated against the target page's slug index,
#      so a stale comment link (or a reorg removing its target) fails here too.
#
# Both run only in the default (docs-corpus) scope, never under --synthesis-only.
MACHINES_COMPAT_ANCHORS = {
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
}

# Source trees whose comments carry `docs/machines/<page>.md#anchor` references.
SOURCE_COMMENT_LINK_GLOBS = (
    "examples/**/*.clj",
    "examples/**/*.cljs",
    "examples/**/*.cljc",
)

# A `docs/machines/<page>.md#anchor` substring, however it is embedded (bare in a
# `;;` comment, inside a markdown `[text](../../docs/...)` link, or in parens).
# Any leading path segments (`../../../`) are ignored — the captured `docs/...`
# tail is resolved repo-root-relative.
_MACHINES_DOC_LINK_RE = re.compile(
    r"(docs/machines/[A-Za-z0-9_-]+\.md)#([A-Za-z0-9_-]+)"
)


def _is_excluded(
    path: Path,
    repo_root: Path,
    *,
    allow_ai_findings: bool = False,
) -> bool:
    """Return True if path lies under a directory we should skip."""
    rel = path.relative_to(repo_root)
    parts = set(rel.parts)
    excluded_names = EXCLUDE_DIR_NAMES
    if allow_ai_findings:
        # Safe only because `_iter_markdown` applies this exception beneath
        # the two exact SYNTHESIS_ROOTS, never to an arbitrary ai/ path.
        excluded_names = excluded_names - {"ai", "findings"}
    if parts & excluded_names:
        return True
    for ex in EXCLUDE_DIR_REL:
        try:
            rel.relative_to(ex)
            return True
        except ValueError:
            pass
    return False


def _iter_markdown(
    repo_root: Path,
    *,
    synthesis_only: bool = False,
) -> Iterable[Path]:
    """Yield absolute paths to every in-scope .md file."""
    roots: list[tuple[Path, bool]] = []
    explicit_files: list[Path] = []
    if synthesis_only:
        for d in SYNTHESIS_ROOTS:
            p = repo_root / d
            if p.is_dir():
                roots.append((p, True))
        for f in SYNTHESIS_FILES:
            p = repo_root / f
            if p.is_file():
                explicit_files.append(p)
    else:
        for d in DEFAULT_ROOTS:
            p = repo_root / d
            if p.is_dir():
                roots.append((p, False))
        tools = repo_root / TOOLS_ROOT
        if tools.is_dir():
            for tool in sorted(tools.iterdir()):
                spec = tool / "spec"
                if spec.is_dir():
                    roots.append((spec, False))

    seen: set[Path] = set()
    for root, allow_ai_findings in roots:
        for path in sorted(root.rglob("*.md")):
            if _is_excluded(
                path,
                repo_root,
                allow_ai_findings=allow_ai_findings,
            ):
                continue
            ap = path.resolve()
            if ap in seen:
                continue
            seen.add(ap)
            yield path
    # Individually-named authority files (SYNTHESIS_FILES). Run through the same
    # exclusion + dedup path; allow_ai_findings is True because these are the
    # exact tracked synthesis authorities, never an arbitrary ai/ path.
    for path in explicit_files:
        if _is_excluded(path, repo_root, allow_ai_findings=True):
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


def _slug_index(path: Path) -> set[str]:
    """Compute the slug set for every heading and inline HTML anchor in path.

    Two anchor mechanisms contribute to the slug set:

    1. ATX headings (`# Title`, `## Title`, ...) — slugified with the same
       pymdownx slugifier MkDocs uses, with duplicate-suffix disambiguation
       (`slug`, `slug_1`, `slug_2`, ...) matching pymdownx.toc.
    2. Inline HTML anchors — `<a name="...">` / `<a id="...">` — added by
       authors to mint stable cross-link targets that survive heading renames.
       Both attribute names are recognised (`name` is the legacy HTML form;
       `id` is the modern form; browsers resolve both as fragment targets).
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    slugs: set[str] = set()
    seen_counts: dict[str, int] = {}
    for _, line in _strip_fences(text.splitlines()):
        # HTML anchor elements can appear on any line (heading or not).
        for am in _HTML_ANCHOR_RE.finditer(line):
            slugs.add(am.group(1))

        m = _HEADING_RE.match(line)
        if not m:
            continue
        title = m.group(2).strip()
        explicit = _EXPLICIT_ID_RE.search(title)
        if explicit:
            slug = explicit.group(1)
        else:
            slug = SLUGIFY(title, SLUG_SEP)
        if not slug:
            continue
        # pymdownx.toc disambiguates duplicate slugs by appending _N starting
        # at the second occurrence.  We mirror that so links to disambiguated
        # anchors validate.
        n = seen_counts.get(slug, 0)
        if n == 0:
            slugs.add(slug)
        else:
            slugs.add(f"{slug}_{n}")
        seen_counts[slug] = n + 1
    return slugs


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


def _extract_links(path: Path) -> Iterable[tuple[int, str]]:
    """Yield (line-number, destination) for every inline markdown link.

    Links inside fenced code blocks AND inside inline-code spans are
    skipped — both are "code", not real cross-references (rf2-mqv8s).
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    for line_no, content in _strip_fences(text.splitlines()):
        if not content:
            continue
        for m in _LINK_RE.finditer(_strip_inline_code(content)):
            yield line_no, m.group(1)


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


def _scan_retired_synthesis_ops(
    files: Iterable[Path],
    repo_root: Path,
) -> list[tuple[Path, int, str, str]]:
    """Flag retired copy-from-mayor / local-only directives (rf2-vxgfnd.136).

    Returns (path, line_no, classification, line-text) for each retired
    imperative. Fenced code is stripped first so a sample never trips the
    guard, and a match inside a HISTORICAL/RETIRED-marked blockquote is
    treated as quoted history and skipped.
    """
    hits: list[tuple[Path, int, str, str]] = []
    for path in files:
        text = path.read_text(encoding="utf-8", errors="replace")
        in_historical_quote = False
        for line_no, line in _strip_fences(text.splitlines()):
            stripped = line.lstrip()
            if stripped.startswith(">"):
                # Track a historical blockquote: it opens with a marker token
                # and runs until the blockquote ends (a non-`>` line).
                if _HISTORICAL_MARKER_RE.search(line):
                    in_historical_quote = True
            elif stripped == "":
                # Blank lines continue a blockquote in CommonMark lazy form
                # only when followed by more `>`; treat blank as a soft break
                # but keep the marker until a real non-quote content line.
                pass
            else:
                in_historical_quote = False
            if in_historical_quote:
                continue
            for classification, pattern in RETIRED_SYNTHESIS_OP_PATTERNS:
                if pattern.search(line):
                    hits.append((path, line_no, classification, line.strip()))
    return hits


def _check_machines_compat_anchors(
    repo_root: Path,
    slugs_for,
) -> list[tuple[str, str]]:
    """Validate every manifest compat anchor resolves on its page (rf2-t0ituo).

    Returns (page-rel, anchor) for each manifest anchor absent from its page's
    slug index. Pages missing from the working tree are skipped, so the
    fixture-based self-tests — whose mini-repos carry no docs/machines tree —
    stay unaffected.
    """
    missing: list[tuple[str, str]] = []
    for page_rel, anchors in MACHINES_COMPAT_ANCHORS.items():
        page = repo_root / page_rel
        if not page.is_file():
            continue
        page_slugs = slugs_for(page)
        for anchor in anchors:
            if anchor not in page_slugs:
                missing.append((page_rel, anchor))
    return missing


def _check_source_comment_links(
    repo_root: Path,
    slugs_for,
) -> list[tuple[Path, int, str, str]]:
    """Validate `docs/machines/*.md#anchor` links in source comments (rf2-t0ituo).

    Scans SOURCE_COMMENT_LINK_GLOBS line by line for the handbook-anchor
    substring and resolves each against the target page's slug index. Returns
    (source-file, line-no, `page#anchor`, reason) for every broken reference.
    A repo with no matching source files (the self-test fixtures) yields none.
    """
    broken: list[tuple[Path, int, str, str]] = []
    for pattern in SOURCE_COMMENT_LINK_GLOBS:
        for src in sorted(repo_root.glob(pattern)):
            text = src.read_text(encoding="utf-8", errors="replace")
            for line_no, line in enumerate(text.splitlines(), start=1):
                for m in _MACHINES_DOC_LINK_RE.finditer(line):
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
    synthesis_only: bool = False,
) -> int:
    """Validate every in-repo markdown link.  Return the total defect count.

    Flags these distinct defects:
        * BROKEN TARGET     — link points at an .md file that doesn't exist.
        * BROKEN ANCHOR     — file exists but the #anchor doesn't resolve.
        * AI_FINDINGS_LINK  — link points into the gitignored ai/findings/ tree
                              (rf2-l7yj8).  Committed files must not reference
                              gitignored working artefacts; inline a sentence
                              summary instead.
        * RETIRED_SYNTHESIS_OP — (synthesis scope only, rf2-vxgfnd.136) an
                              active synthesis instruction still asserts the
                              retired local-only / copy-from-mayor model for the
                              force-tracked synthesis subtree.
        * MISSING COMPAT ANCHOR — (default scope, rf2-t0ituo) a manifest anchor
                              in MACHINES_COMPAT_ANCHORS no longer resolves on
                              its page (external bookmarks / source comments
                              depend on it).
        * BROKEN SOURCE-COMMENT LINK — (default scope, rf2-t0ituo) a
                              `docs/machines/*.md#anchor` reference embedded in a
                              non-markdown source comment does not resolve.
    """
    files = list(_iter_markdown(repo_root, synthesis_only=synthesis_only))
    if synthesis_only:
        if not files:
            sys.stderr.write(
                "error: synthesis link gate matched zero markdown files under "
                f"{', '.join(SYNTHESIS_ROOTS)}; refusing a vacuous pass.\n"
            )
            return 1
        sys.stderr.write(
            f"synthesis link gate files ({len(files)}):\n"
        )
        for path in files:
            sys.stderr.write(
                f"  {path.relative_to(repo_root.resolve()).as_posix()}\n"
            )
    if verbose:
        sys.stderr.write(f"scanning {len(files)} markdown files...\n")

    # Build slug index lazily — many files are never linked to with an anchor.
    slug_cache: dict[Path, set[str]] = {}

    def slugs_for(path: Path) -> set[str]:
        ap = path.resolve()
        if ap not in slug_cache:
            slug_cache[ap] = _slug_index(path)
        return slug_cache[ap]

    broken_anchor: list[tuple[Path, int, str, str]] = []
    broken_target: list[tuple[Path, int, str, str]] = []
    ai_findings: list[tuple[Path, int, str]] = []
    for path in files:
        path_rel = path.relative_to(repo_root.resolve()).as_posix()
        landing_rel = DRAFT_LANDING_TARGETS.get(path_rel)
        landing_target = (repo_root / landing_rel).resolve() if landing_rel else None
        relative_base = landing_target.parent if landing_target else None
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
                if landing_target and landing_target.is_file():
                    same_file_slugs.update(slugs_for(landing_target))
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
                relative_base=relative_base,
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

    # rf2-vxgfnd.136 — the retired copy-from-mayor guard runs only in the
    # synthesis scope. It never touches the default docs corpus.
    retired_ops: list[tuple[Path, int, str, str]] = []
    if synthesis_only:
        retired_ops = _scan_retired_synthesis_ops(files, repo_root)

    # rf2-t0ituo — the Machines compat-anchor manifest + source-comment link gate
    # run only in the default docs scope (never under --synthesis-only).
    compat_missing: list[tuple[str, str]] = []
    source_comment_broken: list[tuple[Path, int, str, str]] = []
    if not synthesis_only:
        compat_missing = _check_machines_compat_anchors(repo_root, slugs_for)
        source_comment_broken = _check_source_comment_links(repo_root, slugs_for)

    total = (
        len(broken_anchor)
        + len(broken_target)
        + len(ai_findings)
        + len(retired_ops)
        + len(compat_missing)
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

    if retired_ops:
        sys.stderr.write(
            f"\n{len(retired_ops)} retired synthesis operation instruction(s) "
            "found (rf2-vxgfnd.136):\n\n"
        )
        for src, line_no, classification, snippet in retired_ops:
            rel = src.relative_to(repo_root.resolve())
            sys.stderr.write(
                f"  RETIRED_SYNTHESIS_OP [{classification}]: {rel}:{line_no}\n"
                f"      {snippet}\n"
            )
        sys.stderr.write(
            "\nFix: the new-substrate-synthesis subtree is force-tracked. Tell "
            "workers to read the tracked path in their own worktree at the exact "
            "revision being implemented/reviewed -- not to copy it from the mayor "
            "checkout. If the line is genuinely historical, move it into a "
            "blockquote whose opener is marked HISTORICAL / non-operative.\n"
        )

    if compat_missing:
        sys.stderr.write(
            f"\n{len(compat_missing)} missing Machines compat anchor(s) "
            "found (rf2-t0ituo):\n\n"
        )
        for page_rel, anchor in compat_missing:
            sys.stderr.write(
                f"  MISSING COMPAT ANCHOR: {page_rel}#{anchor}\n"
            )
        sys.stderr.write(
            "\nFix: these anchors back external bookmarks and in-repo source "
            "comments. Restore an `<a id=\"...\"></a>` for each on its page "
            "(without duplicating a visible heading), or update "
            "MACHINES_COMPAT_ANCHORS if the anchor is intentionally retired.\n"
        )

    if source_comment_broken:
        sys.stderr.write(
            f"\n{len(source_comment_broken)} broken source-comment link(s) into "
            "the Machines handbook found (rf2-t0ituo):\n\n"
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
            "MACHINES_COMPAT_ANCHORS).\n"
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
    parser.add_argument(
        "--synthesis-only",
        action="store_true",
        help=(
            "Scan only the tracked re-frame.ui synthesis authority inventory: "
            "the guide + active drafts, the prep/ workstream tables, and the "
            "top-level adoption/implementation plans. Enumerates every matched "
            "file and fails if the scope is empty."
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
        synthesis_only=args.synthesis_only,
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
        ("inline_code_negative_control",     1),  # rf2-mqv8s
        ("ai_findings_link_flagged",         1),  # rf2-l7yj8
        ("ai_findings_dir_link_flagged",     1),  # rf2-l7yj8
        ("blockquoted_heading_ok",           0),  # rf2-869k9m
        ("indented_heading_not_indexed",     1),  # rf2-869k9m (negative control)
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
            got = check(root, verbose=False)
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

    # rf2-vxgfnd.135 — prove the new scope is explicit and has real teeth:
    # both defects remain invisible to the unchanged default corpus, then fail
    # when the narrow synthesis scope is selected. The valid fixture also
    # proves both roots are enumerated while unrelated ai/ scratch stays out.
    synthesis_cases: list[tuple[str, int]] = [
        ("synthesis_broken_target", 1),
        ("synthesis_broken_anchor", 1),
        ("synthesis_valid_narrow_scope", 0),
        # rf2-vxgfnd.136 — the retired copy-from-mayor guard has teeth in the
        # synthesis scope, stays invisible to the default corpus, and exempts
        # correct "surrounding ai/ stays local-only" prose + HISTORICAL-marked
        # quotes of the old model.
        ("synthesis_retired_op", 1),
        ("synthesis_retired_op_allowed", 0),
        # rf2-d9v3n — the inventory now covers the operational authorities too,
        # so a retired copy-from-mayor / mayor-checkout-only directive can no
        # longer hide in a prep/ workstream table (COPY_FROM_MAYOR) or a
        # top-level plan named in SYNTHESIS_FILES (MAYOR_CHECKOUT_ONLY). Both
        # trip under the synthesis scope and stay invisible to the default corpus.
        ("synthesis_retired_op_prep", 1),
        ("synthesis_retired_op_toplevel", 1),
    ]
    for fixture, expected in synthesis_cases:
        root = _SELF_TEST_FIXTURE_ROOT / fixture
        if not (root / "mkdocs.yml").is_file():
            sys.stderr.write(
                f"self-test FAIL: fixture {fixture!r} missing mkdocs.yml at {root}\n"
            )
            failures += 1
            continue

        saved_stderr = sys.stderr
        sys.stderr = _DevNull()
        try:
            default_got = check(root, verbose=False)
            synthesis_got = check(root, verbose=False, synthesis_only=True)
        finally:
            sys.stderr = saved_stderr

        if default_got != 0:
            sys.stderr.write(
                f"self-test FAIL: {fixture} leaked into the default corpus "
                f"(broken={default_got}, expected 0)\n"
            )
            failures += 1
        elif synthesis_got == expected:
            if verbose:
                sys.stderr.write(
                    f"self-test PASS: {fixture} "
                    f"(default=0, synthesis={synthesis_got})\n"
                )
        else:
            sys.stderr.write(
                f"self-test FAIL: {fixture} expected synthesis broken={expected}, "
                f"got {synthesis_got}\n"
            )
            failures += 1

    # rf2-d9v3n — prove the expanded inventory enumerates exactly the active
    # authorities (guide + drafts + prep/ + the two top-level SYNTHESIS_FILES)
    # and NOTHING else: the fixture also carries a top-level narrative chapter, a
    # README, a reviews/ page, a spikes/ page (each with a retired directive), and
    # unrelated ai/ scratch — none of which may enter the inventory. The parallel
    # synthesis_valid_narrow_scope run above (expected synthesis broken=0) is the
    # belt-and-suspenders proof that those retired-directive landmines are never
    # scanned; this set-equality is the direct proof.
    narrow_root = _SELF_TEST_FIXTURE_ROOT / "synthesis_valid_narrow_scope"
    narrow_files = {
        path.relative_to(narrow_root).as_posix()
        for path in _iter_markdown(narrow_root, synthesis_only=True)
    }
    expected_narrow_files = {
        "ai/findings/new-substrate-synthesis/guide/index.md",
        "ai/findings/new-substrate-synthesis/drafts/design.md",
        "ai/findings/new-substrate-synthesis/prep/w1-migrator-rule-table.md",
        "ai/findings/new-substrate-synthesis/11-adoption-workstreams.md",
        "ai/findings/new-substrate-synthesis/12-implementation-plan.md",
    }
    if narrow_files != expected_narrow_files:
        sys.stderr.write(
            "self-test FAIL: synthesis scope inventory mismatch: "
            f"expected {sorted(expected_narrow_files)}, got {sorted(narrow_files)}\n"
        )
        failures += 1
    elif verbose:
        sys.stderr.write(
            "self-test PASS: synthesis scope enumerates guide + drafts + prep + "
            "top-level authorities only (narrative/README/reviews/spikes excluded)\n"
        )

    empty_root = _SELF_TEST_FIXTURE_ROOT / "valid_link"
    saved_stderr = sys.stderr
    sys.stderr = _DevNull()
    try:
        empty_got = check(empty_root, verbose=False, synthesis_only=True)
    finally:
        sys.stderr = saved_stderr
    if empty_got != 1:
        sys.stderr.write(
            "self-test FAIL: empty synthesis scope did not fail closed "
            f"(got broken={empty_got}, expected 1)\n"
        )
        failures += 1
    elif verbose:
        sys.stderr.write(
            "self-test PASS: empty synthesis scope refuses a vacuous pass\n"
        )

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(
            f"all {len(cases) + len(synthesis_cases) + 2} self-tests passed.\n"
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
